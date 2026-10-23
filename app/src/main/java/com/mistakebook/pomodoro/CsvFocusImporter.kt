package com.mistakebook.pomodoro

/**
 * CSV 专注数据导入（从其他程序的时间记录导出）。
 *
 * ## 为什么只认「任务分类 / 任务名 / 总时长」三个关键字段
 *
 * 外部导出的 CSV 列名千奇百怪（中文「分类/学科/类别」、英文
 * 「subject/category」还有带序号的），全量对齐不现实。而统计图
 * 只用得到这三个维度：分类决定饼图、任务名决定任务排行、时长决定
 * 一切数值。日期列有就按日期归档，没有就归到今天。
 *
 * ## 容错原则
 *
 * - 列名**包含匹配**（不苛求完全相等），大小写不敏感；
 * - 时长容 many 种写法：`25`、`25分钟`、`1:30`（时:分）、`1:30:00`、
 *   `90m`、`1.5h`；单位优先看单元格后缀，再看表头，最后默认分钟；
 * - 解析不了的行**跳过并计数**，不让一条脏数据毁掉整次导入。
 */
object CsvFocusImporter {

    /** 一条可导入的记录。day 为空 = 归到今天。 */
    data class Row(
        val subject: String?,
        val taskName: String,
        val durationSec: Long,
        val day: String?
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

    /** 单条专注时长上限：超过 24 小时的一律视为脏数据。 */
    private const val MAX_SEC = 24 * 3600L

    fun parse(text: String): ParseResult {
        val lines = text.lineSequence()
            .map { it.trim('\uFEFF', '\r', ' ') }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) {
            return ParseResult(emptyList(), 0, headerUnrecognized = true)
        }

        val header = splitCsvLine(lines.first())
        val subjectCol = header.indexOfFirst { it.matchesAny(SUBJECT_KEYS) }
        val taskCol = header.indexOfFirst { it.matchesAny(TASK_KEYS) }
        val durationCol = header.indexOfFirst { it.matchesAny(DURATION_KEYS) }
        val dateCol = header.indexOfFirst { it.matchesAny(DATE_KEYS) }

        // 任务名 + 时长是最小集：缺一个这文件就没法按任务归档
        if (taskCol < 0 || durationCol < 0) {
            return ParseResult(emptyList(), 0, headerUnrecognized = true)
        }

        val rows = mutableListOf<Row>()
        var skipped = 0
        val durationHeader = header.getOrNull(durationCol).orEmpty()

        lines.drop(1).forEach { line ->
            val cells = splitCsvLine(line)
            val taskName = cells.getOrNull(taskCol)?.trim().orEmpty()
            val rawDuration = cells.getOrNull(durationCol)?.trim().orEmpty()
            val subject = cells.getOrNull(subjectCol)?.trim()?.takeIf { it.isNotEmpty() }
            val day = cells.getOrNull(dateCol)?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeDay(it) }

            if (taskName.isEmpty()) {
                skipped++
                return@forEach
            }
            val sec = parseDuration(rawDuration, durationHeader)
            if (sec <= 0L) {
                skipped++
                return@forEach
            }
            rows.add(
                Row(
                    subject = subject,
                    taskName = taskName,
                    durationSec = sec.coerceAtMost(MAX_SEC),
                    day = day
                )
            )
        }
        return ParseResult(rows, skipped, headerUnrecognized = false)
    }

    // ------------------------------------------------------------ 内部实现

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
     */
    internal fun normalizeDay(raw: String): String? {
        val s = raw.trim().take(10)
        val full = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""").find(s)
        if (full != null) {
            val (y, mo, d) = full.destructured
            return runCatching {
                LocalDateFactory.of(y.toInt(), mo.toInt(), d.toInt())
            }.getOrNull()
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
    }
}
