package com.mistakebook.pomodoro

/**
 * 专注数据导入（从其他程序的时间记录导出：CSV 或 .xls 表格）。
 *
 * ## 为什么只认「任务分类 / 任务名 / 总时长」三个关键字段
 *
 * 外部导出的表格列名千奇百怪（中文「分类/学科/类别」、英文
 * 「subject/category」还有带序号的），全量对齐不现实。而统计图
 * 只用得到这三个维度：分类决定饼图、任务名决定任务排行、时长决定
 * 一切数值。日期列有就按日期归档，没有就归到今天。
 *
 * ## 容错原则
 *
 * - 列名**包含匹配**（不苛求完全相等），大小写不敏感；
 * - 时长容 many 种写法：`25`、`25分钟`、`1:30`（时:分）、`1:30:00`、
 *   `90m`、`1.5h`；单位优先看单元格后缀，再看表头，最后默认分钟；
 * - 解析不了的行**跳过并计数**，不让一条脏数据毁掉整次导入；
 * - 编码容 UTF-8（带/不带 BOM）与 GBK，由 [decodeText] 兜底。
 */
object CsvFocusImporter {

    /** 一条可导入的记录。day 为空 = 归到今天。 */
    data class Row(
        val subject: String?,
        val taskName: String,
        val durationSec: Long,
        val day: String?,
        /**
         * 当天的开始时刻（自 00:00 起的分钟数）。为空 = 不知道，
         * 调用方按中午 12 点摆。番茄TODO 这类带「16:48 至 17:39」
         * 时间段的导出能填上，导入后时间轴才是真的。
         */
        val startMinute: Int? = null,
        /**
         * 该条记录在源文件里是否算「完成」。为空 = 源文件没说，
         * 调用方按未完成处理（导入的是已经发生过的事，不虚报）。
         */
        val completed: Boolean? = null
    )

    data class ParseResult(
        val rows: List<Row>,
        /** 因时长解析失败/关键列为空而跳过的行数。 */
        val skipped: Int,
        /** 未能识别表头时为 true（rows 为空）。 */
        val headerUnrecognized: Boolean
    )

    // ------------------------------------------------------------ 列名识别

    private val SUBJECT_KEYS = listOf(
        "分类", "学科", "类别", "科目", "subject", "category", "type", "group", "project"
    )
    private val TASK_KEYS = listOf(
        "任务", "事项", "内容", "名称", "task", "name", "title", "item", "event"
    )
    private val DURATION_KEYS = listOf(
        "时长", "用时", "持续时间", "duration", "minutes", "minute", "min",
        "seconds", "second", "sec", "elapsed", "spent"
    )
    private val DATE_KEYS = listOf("日期", "时间", "date", "day")

    /**
     * 状态列。番茄TODO 导出带「状态」（已完成 / 已放弃）。
     *
     * 已放弃的记录**不导入**：那段时间用户实际上没在专注，
     * 算进去会虚报专注时长。注意番茄TODO 自己的汇总行也写了
     * 「不包含已放弃的记录」——它自己就不算，我们跟着它。
     */
    private val STATUS_KEYS = listOf("状态", "status", "state")

    /** 状态文本里出现这些词 = 这条记录作废。 */
    private val ABANDONED_MARKERS = listOf("放弃", "取消", "abandon", "cancel", "discard")

    /**
     * 状态文本里出现这些词 = 这条记录算完成。
     *
     * 不含裸的「完成」——「未完成」也含这两个字，会把没做完的当完成。
     */
    private val DONE_MARKERS = listOf("已完成", "done", "complete", "finished")

    /** 单条专注时长上限：超过 24 小时的一律视为脏数据。 */
    private const val MAX_SEC = 24 * 3600L

    /** Excel 日期序列值 → Unix 纪元日的偏移（1900 闰年 bug 修正后）。 */
    private const val EXCEL_EPOCH_OFFSET = 25569L

    /** 序列值合法上界：9999-12-31。 */
    private const val MAX_EXCEL_SERIAL = 2958465L

    /**
     * 完成度列。番茄TODO 导出带「完成度」（100%）。
     *
     * 没有「状态」列时才用它兜底——「状态」更权威（明确说了完成/放弃）。
     */
    private val COMPLETION_KEYS = listOf("完成度", "完成率", "进度", "progress", "completion")

    /**
     * 表头行探测的扫描上限：前多少行里允许找表头。
     *
     * 番茄TODO 的 .xls 导出前面有 5 行元信息（用户、导出范围、时长共计、
     * 记录条数），表头在第 6 行。给 30 行余量足够，再多就当成脏数据。
     */
    private const val HEADER_SCAN_LIMIT = 30

    /**
     * 单元格文本里出现「像日期的东西」→ 这行是数据行，不是表头。
     *
     * 元信息行里写着「导出范围:2026-03-01 至 2026-10-24」，用它把这行
     * 排除在表头候选之外。
     */
    private val RE_DATE_IN_TEXT = Regex("""\d{4}[-/.]\d{1,2}[-/.]\d{1,2}""")

    /** 单元格文本里的「HH:mm」→ 当天第几分钟。 */
    private val RE_CLOCK = Regex("""(\d{1,2}):(\d{2})""")

    fun parse(text: String): ParseResult {
        val table = text.lineSequence()
            .map { it.trim('\uFEFF', '\r', ' ') }
            .filter { it.isNotBlank() }
            .map { splitCsvLine(it) }
            .toList()
        return parseTable(table)
    }

    /**
     * 表格解析（CSV 与 XLS 共用）。
     *
     * **表头不一定在第一行**：番茄TODO 的导出前面塞了 5 行元信息
     * （用户、导出范围、时长共计、记录条数），表头在第 6 行。所以这里
     * 先在前 [HEADER_SCAN_LIMIT] 行里探测真正的表头行——判据是
     * 「同时含任务列与时长列」，且该行不含日期样式的文本（排除元信息行）。
     *
     * @param allowSerialDate XLS 路径传 true——单元格里的纯数字可能是
     *   Excel 日期序列值（如 45581 = 2024-10-23），按序列值换算归档；
     *   CSV 路径保持 false，避免把「1」这种脏日期当真。
     */
    internal fun parseTable(
        table: List<List<String>>,
        allowSerialDate: Boolean = false
    ): ParseResult {
        val rows = table.filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) {
            return ParseResult(emptyList(), 0, headerUnrecognized = true)
        }

        val headerIdx = findHeaderRow(rows)
        if (headerIdx < 0) {
            return ParseResult(emptyList(), 0, headerUnrecognized = true)
        }
        val header = rows[headerIdx]

        val subjectCol = header.indexOfFirst { it.matchesAny(SUBJECT_KEYS) }
        val taskCol = header.indexOfFirst { it.matchesAny(TASK_KEYS) }
        val durationCol = header.indexOfFirst { it.matchesAny(DURATION_KEYS) }
        val dateCol = header.indexOfFirst { it.matchesAny(DATE_KEYS) }
        val statusCol = header.indexOfFirst { it.matchesAny(STATUS_KEYS) }
        val completionCol = header.indexOfFirst { it.matchesAny(COMPLETION_KEYS) }

        val out = mutableListOf<Row>()
        var skipped = 0
        val durationHeader = header.getOrNull(durationCol).orEmpty()

        rows.drop(headerIdx + 1).forEach { cells ->
            val taskName = cells.getOrNull(taskCol)?.trim().orEmpty()
            val rawDuration = cells.getOrNull(durationCol)?.trim().orEmpty()
            val subject = cells.getOrNull(subjectCol)?.trim()?.takeIf { it.isNotEmpty() }
            val rawDate = cells.getOrNull(dateCol)?.trim().orEmpty()
            val day = rawDate.takeIf { it.isNotEmpty() }
                ?.let { normalizeDay(it, allowSerialDate) }

            if (taskName.isEmpty()) {
                skipped++
                return@forEach
            }
            // 状态是「已放弃」→ 那段时间用户没在专注，不算专注时长
            val statusText = cells.getOrNull(statusCol)?.trim().orEmpty()
            if (statusText.containsAnyOf(ABANDONED_MARKERS)) {
                skipped++
                return@forEach
            }
            val sec = parseDuration(rawDuration, durationHeader)
            if (sec <= 0L) {
                skipped++
                return@forEach
            }
            out.add(
                Row(
                    subject = subject,
                    taskName = taskName,
                    durationSec = sec.coerceAtMost(MAX_SEC),
                    day = day,
                    startMinute = parseStartMinute(rawDate),
                    completed = resolveCompleted(statusText, statusCol, cells, completionCol)
                )
            )
        }
        return ParseResult(out, skipped, headerUnrecognized = false)
    }

    // ------------------------------------------------------------ 内部实现

    /**
     * 探测真正的表头行，返回它在 [rows] 里的下标；找不到返回 -1。
     *
     * 判据（三个都要满足）：
     * 1. 同时含**任务列**与**时长列**——缺一个这文件就没法按任务归档；
     * 2. 不含日期样式的文本——番茄TODO 的元信息行写着
     *    「导出范围:2026-03-01 至 2026-10-24」，带日期，不是表头；
     * 3. 在前 [HEADER_SCAN_LIMIT] 行内——再往下多半是数据区，
     *    硬找容易把某条数据当表头（比如任务名里带「时长」二字）。
     *
     * 第 3 条还有个隐含好处：普通 CSV（表头在第一行）走同一条路径，
     * 只是第 0 行就命中，行为与从前完全一致。
     */
    private fun findHeaderRow(rows: List<List<String>>): Int {
        val limit = minOf(rows.size, HEADER_SCAN_LIMIT)
        for (i in 0 until limit) {
            val row = rows[i]
            val hasTask = row.any { it.matchesAny(TASK_KEYS) }
            val hasDuration = row.any { it.matchesAny(DURATION_KEYS) }
            if (!hasTask || !hasDuration) continue
            if (row.any { RE_DATE_IN_TEXT.containsMatchIn(it) }) continue
            return i
        }
        return -1
    }

    /**
     * 从「2026-10-23 16:48 至 2026-10-23 17:39」这类文本里取**开始时刻**。
     *
     * 取的是**前**一个时间点（开始），不是结束——番茄TODO 的记录是一段
     * 连续专注，起止都在同一天（跨夜的极少）。没有 HH:mm 就返回 null，
     * 调用方按中午 12 点摆。
     */
    internal fun parseStartMinute(raw: String): Int? {
        if (raw.isBlank()) return null
        val m = RE_CLOCK.find(raw) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val mi = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || mi !in 0..59) return null
        return h * 60 + mi
    }

    /**
     * 判定一条记录在源文件里算不算完成。
     *
     * 优先看「状态」列（「已完成」/「已放弃」）；没有状态列时退化到
     * 「完成度」列（100% 算完成）。都拿不到就返回 null——调用方按
     * 未完成处理，不替用户虚报番茄数。
     */
    private fun resolveCompleted(
        statusText: String,
        statusCol: Int,
        cells: List<String>,
        completionCol: Int
    ): Boolean? {
        if (statusCol >= 0 && statusText.isNotBlank()) {
            return statusText.containsAnyOf(DONE_MARKERS)
        }
        if (completionCol >= 0) {
            val raw = cells.getOrNull(completionCol)?.trim().orEmpty()
            if (raw.isBlank()) return null
            val pct = raw.removeSuffix("%").trim().toDoubleOrNull() ?: return null
            return pct >= 99.5
        }
        return null
    }

    private fun String.containsAnyOf(keys: List<String>): Boolean {
        val lower = lowercase()
        return keys.any { lower.contains(it) }
    }

    private fun String.matchesAny(keys: List<String>): Boolean {
        val lower = lowercase()
        return keys.any { lower.contains(it) }
    }

    /** 逗号分列 + 去引号。导出文件常见的 "a, b" 形式。 */
    internal fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        cur.append('"'); i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    out.add(cur.toString().trim())
                    cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString().trim())
        return out
    }

    /**
     * 时长解析：后缀单位 > 表头单位 > 默认分钟。
     *
     * 支持：`90`、`90m`、`1.5h`、`45分钟`、`1:30`（时:分）、``1:30:00`。
     * 两段式冒号默认按「时:分」（苹果计时器/大多数导出的习惯），
     * 表头明确是秒时按「分:秒」。
     */
    internal fun parseDuration(raw: String, header: String): Long {
        if (raw.isEmpty()) return 0L
        val s = raw.trim().lowercase().removeSuffix("。").trim()

        // h:mm[:ss]
        val colonParts = s.split(":")
        if (colonParts.size in 2..3 && colonParts.all { it.trim().toIntOrNull() != null }) {
            val nums = colonParts.map { it.trim().toLong() }
            return when (nums.size) {
                3 -> nums[0] * 3600 + nums[1] * 60 + nums[2]
                else -> {
                    val headerIsSec = header.containsAny("sec", "秒")
                    if (headerIsSec) nums[0] * 60 + nums[1] else nums[0] * 3600 + nums[1] * 60
                }
            }
        }

        // 数字 + 单位后缀
        val m = Regex("""^(\d+(?:\.\d+)?)\s*([a-zA-Z\u4e00-\u9fa5]*)$""").find(s) ?: return 0L
        val value = m.groupValues[1].toDouble()
        val unit = m.groupValues[2]
        // 注意两点：
        // 1. 空串 contains("") 为 true，单位为空时必须先放行到表头判断，
        //    否则「25」会误中第一个分支按小时计（25 分钟变 25 小时）；
        // 2. 表头判小时只认「小时 / hour」——「时长」本身就含「时」，
        //    用单字「时」会把最常见的时长表头全判成小时。
        return when {
            unit.isEmpty() -> when {
                header.containsAny("sec", "秒") -> value.toLong()
                header.containsAny("hour", "小时") -> (value * 3600).toLong()
                else -> (value * 60).toLong()
            }
            unit.containsAny("h", "hr", "hour", "小时", "时") -> (value * 3600).toLong()
            unit.containsAny("s", "sec", "second", "秒") -> value.toLong()
            unit.containsAny("m", "min", "minute", "分钟", "分") -> (value * 60).toLong()
            // 无后缀：看表头；再默认分钟（大多数导出按分钟计）
            header.containsAny("sec", "秒") -> value.toLong()
            header.containsAny("hour", "小时") -> (value * 3600).toLong()
            else -> (value * 60).toLong()
        }
    }

    private fun String.containsAny(vararg keys: String): Boolean =
        keys.any { contains(it) }

    /**
     * 日期归一成 'yyyy-MM-dd'；认不出返回 null（调用方归到今天）。
     * 支持 2026-10-23 / 2026/10/23 / 2026.10.23 / 10-23（补今年）。
     * [allowSerialDate] 为 true 时额外认纯数字的 Excel 日期序列值。
     */
    internal fun normalizeDay(raw: String, allowSerialDate: Boolean = false): String? {
        val s = raw.trim().take(10)
        val full = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""").find(s)
        if (full != null) {
            val (y, mo, d) = full.destructured
            return runCatching {
                LocalDateFactory.of(y.toInt(), mo.toInt(), d.toInt())
            }.getOrNull()
        }
        if (allowSerialDate) {
            // Excel 序列值：1 = 1900-01-01，含 1900 闰年 bug。
            // 1900-02-29 是虚构日，序列 > 60 时减 25569，否则减 25568。
            val serial = s.toLongOrNull()
            if (serial != null && serial in 1..MAX_EXCEL_SERIAL) {
                val epochDay = if (serial > 60) serial - EXCEL_EPOCH_OFFSET else serial - 25568L
                return LocalDateFactory.ofEpochDay(epochDay)
            }
        }
        val short = Regex("""^(\d{1,2})[-/.](\d{1,2})""").find(s)
        if (short != null) {
            val (mo, d) = short.destructured
            val year = LocalDateFactory.today().year
            return runCatching { LocalDateFactory.of(year, mo.toInt(), d.toInt()) }.getOrNull()
        }
        return null
    }

    /** 包一层便于测试替身（不直接依赖 java.time 的 today 语义）。 */
    internal object LocalDateFactory {
        fun today(): java.time.LocalDate = java.time.LocalDate.now()
        fun of(y: Int, m: Int, d: Int): String =
            java.time.LocalDate.of(y, m, d).toString()
        fun ofEpochDay(epochDay: Long): String =
            java.time.LocalDate.ofEpochDay(epochDay).toString()
    }

    /**
     * 字节解码：UTF-8 BOM 直剥；严格 UTF-8 解码失败（含控制字符
     * 误判）时兜底 GBK——Windows 中文办公软件导出 CSV 的默认编码。
     */
    internal fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val strict = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            null
        }
        return strict ?: String(bytes, java.nio.charset.Charset.forName("GBK"))
    }
}
