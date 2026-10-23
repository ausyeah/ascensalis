package com.mistakebook.pomodoro

/**
 * 极简 XLS（Excel 97-2003：OLE2 复合文档 + BIFF8 记录流）表格读取器。
 *
 * ## 为什么不用 Apache POI
 *
 * POI 的 HSSF 当然能读，但为一个「导入专注记录」的需求拖进
 * poi-core + commons-* 四五个包、几 MB 体积和上万方法数，代价与
 * 收益完全不成比例。而导入只需要第一张工作表里的**字符与数字**——
 * 不碰格式、不碰公式、不碰样式。把这些裁掉之后，剩下的解析器
 * 只有两百来行，零依赖，还能整个跑在 JVM 单测里。
 *
 * ## 两层结构
 *
 * 1. **OLE2（复合文档）**：从目录里找到 `Workbook`（旧版叫 `Book`）
 *    流。小于 4096 字节的流住在 mini stream 里，要顺 mini FAT 链读；
 *    大的流直接走 FAT 链。两层都实现了——真实导出文件两种都见过。
 * 2. **BIFF8 记录流**：顺序走记录，只认第一个工作片子流
 *    （BOF 的 dt=0x0020 起、EOF 止）。认格的记录：
 *    - `SST`(0x00FC) + `CONTINUE`：共享字符串表，支持跨记录续写
 *      （续写块开头那字节是新的编码标志，UTF-16 可中途切换）；
 *    - `LABELSST`(0x00FD)：格子里存的是 SST 下标；
 *    - `LABEL`(0x0204)：格子里直接存字符串（BIFF5-7 的老形式）；
 *    - `NUMBER`(0x0203) / `RK`(0x027E) / `MULRK`(0x00BD)：数字三兄弟，
 *      RK 是压缩的 30 位表示，整数/浮点/除百三种形态都解；
 *    - `FORMULA`(0x0006)：公式格。结果是数字就取数字；是字符串就找
 *      紧跟的 `STRING`(0x0207) 记录——只导入数据的话够用了。
 *
 * ## 失败策略
 *
 * 任何一步不认、越界、断链，一律返回 null，由调用方提示用户
 * 「换一个文件或另存为 CSV」。绝不抛异常——导入的是用户手里
 * 不知道什么来路的文件，容错是义务不是加分项。
 */
object XlsTableReader {

    /** OLE2 复合文档魔数。 */
    private val OLE2_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
    )

    private const val ENDOFCHAIN = -0x2L // 0xFFFFFFFE
    private const val FREESECT = -0x1L   // 0xFFFFFFFF

    fun looksLikeXls(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return OLE2_MAGIC.indices.all { bytes[it] == OLE2_MAGIC[it] }
    }

    /**
     * 读出第一张工作表的二维表格（行 → 列 → 单元格文本）。
     * 解析失败返回 null；读出空表返回空 list。
     */
    fun readTable(bytes: ByteArray): List<List<String>>? {
        val stream = readWorkbookStream(bytes) ?: return null
        return biffToTable(stream)
    }

    // =====================================================================
    // 第一层：OLE2 复合文档
    // =====================================================================

    private fun readWorkbookStream(bytes: ByteArray): ByteArray? {
        if (bytes.size < 512 || !looksLikeXls(bytes)) return null
        val sectorShift = u16(bytes, 0x1E)
        val miniSectorShift = u16(bytes, 0x20)
        if (sectorShift !in 9..12 || miniSectorShift != 6) return null
        val sectorSize = 1 shl sectorShift
        val miniSectorSize = 1 shl miniSectorShift
        val headerSize = if (sectorShift == 12) 4096 else 512
        if (bytes.size < headerSize) return null

        fun sectorOffset(sid: Long): Long = headerSize + sid * sectorSize

        val numFatSectors = u32(bytes, 0x2C).toInt()
        val firstDirSector = u32(bytes, 0x30)
        val miniCutoff = u32(bytes, 0x38)
        val firstMiniFatSector = u32(bytes, 0x3C)
        val firstDifatSector = u32(bytes, 0x44)

        val maxSector = (bytes.size - headerSize) / sectorSize
        fun validSector(sid: Long) = sid in 0 until maxSector

        // ---- DIFAT：FAT 扇区号清单（头内 109 条 + 溢出链）----
        val difat = ArrayList<Long>()
        for (i in 0 until 109) {
            val v = u32(bytes, 0x4C + i * 4)
            if (v == FREESECT) break
            difat.add(v)
        }
        // DIFAT 溢出链：每个 DIFAT 扇区末尾 4 字节指向下一个 DIFAT 扇区
        var difatSid = firstDifatSector
        var difatGuard = 0
        while (difatSid != ENDOFCHAIN && validSector(difatSid) && difatGuard++ < 128) {
            val base = sectorOffset(difatSid).toInt()
            if (base + sectorSize > bytes.size) break
            val per = sectorSize / 4
            for (i in 0 until per - 1) {
                val v = u32(bytes, base + i * 4)
                if (v == FREESECT) break
                difat.add(v)
            }
            difatSid = u32(bytes, base + (per - 1) * 4)
        }
        if (difat.isEmpty() || numFatSectors > 1_000_000) return null

        // ---- FAT：扇区链映射 ----
        val fat = LongArray(difat.size * (sectorSize / 4))
        var fi = 0
        for (sid in difat) {
            if (!validSector(sid)) return null
            val base = sectorOffset(sid).toInt()
            if (base + sectorSize > bytes.size) return null
            for (i in 0 until sectorSize / 4) fat[fi++] = u32(bytes, base + i * 4)
        }

        fun chain(start: Long): List<Long> {
            val out = ArrayList<Long>()
            var sid = start
            var n = 0
            while (sid != ENDOFCHAIN && validSector(sid) && n++ < 200_000) {
                out.add(sid)
                val idx = sid.toInt()
                if (idx >= fat.size) break
                sid = fat[idx]
            }
            return out
        }

        // ---- 目录：找 Workbook / Book 流与 Root（mini stream 容器）----
        val dirSectors = chain(firstDirSector)
        if (dirSectors.isEmpty()) return null
        var wbStart = 0L
        var wbSize = 0L
        var found = false
        var rootStart = ENDOFCHAIN
        var rootSize = 0L
        for (sid in dirSectors) {
            val base = sectorOffset(sid).toInt()
            if (base + sectorSize > bytes.size) return null
            var off = base
            while (off + 128 <= base + sectorSize) {
                // 目录项 128 字节：name(64) | nameLen(2)@0x40 | type(1)@0x42 |
                // left(4)@0x44 | right(4)@0x48 | child(4)@0x4C | start(4)@0x74 | size(8)@0x78
                val nameLen = u16(bytes, off + 0x40)
                // 名字是 UTF-16LE、以 null 结尾，nameLen 含结尾 null（2 字节）
                val charBytes = (nameLen - 2).coerceIn(0, 64)
                val name = buildString {
                    var i = 0
                    while (i < charBytes) {
                        append(u16(bytes, off + i).toChar())
                        i += 2
                    }
                }.lowercase()
                val type = bytes[off + 0x42].toInt() and 0xFF
                val start = u32(bytes, off + 0x74)
                val size = u32(bytes, off + 0x78)
                if (type == 5) {
                    rootStart = start
                    rootSize = size
                } else if (type == 2 && (name == "workbook" || name == "book")) {
                    wbStart = start
                    wbSize = size
                    found = true
                }
                off += 128
            }
        }
        if (!found) return null

        // ---- 取流内容：>= cutoff 走 FAT 链，否则住 mini stream ----
        fun readFatStream(start: Long, size: Long): ByteArray? {
            if (size < 0 || size > bytes.size) return null
            val out = ByteArray(size.toInt())
            var written = 0
            for (sid in chain(start)) {
                val base = sectorOffset(sid).toInt()
                if (base + sectorSize > bytes.size) return null
                val n = minOf(sectorSize, out.size - written)
                if (n <= 0) break
                bytes.copyInto(out, written, base, base + n)
                written += n
            }
            return if (written == out.size) out else null
        }

        if (wbSize >= miniCutoff) return readFatStream(wbStart, wbSize)

        // mini stream：Root 的流按 64 字节小扇区切分，链在 mini FAT 里
        if (wbSize <= 0 || rootSize <= 0 || firstMiniFatSector == ENDOFCHAIN) return null
        val miniStream = readFatStream(rootStart, rootSize) ?: return null
        val miniFatSectors = chain(firstMiniFatSector)
        if (miniFatSectors.isEmpty()) return null
        val miniFat = LongArray(miniFatSectors.size * (sectorSize / 4))
        var mi = 0
        for (sid in miniFatSectors) {
            val base = sectorOffset(sid).toInt()
            if (base + sectorSize > bytes.size) return null
            for (i in 0 until sectorSize / 4) miniFat[mi++] = u32(bytes, base + i * 4)
        }
        val out = ByteArray(wbSize.toInt())
        var written = 0
        var sid = wbStart
        var guard = 0
        while (sid != ENDOFCHAIN && guard++ < 200_000) {
            val idx = sid.toInt()
            if (idx < 0 || idx >= miniFat.size) return null
            val base = idx * miniSectorSize
            if (base + miniSectorSize > miniStream.size) return null
            val n = minOf(miniSectorSize, out.size - written)
            if (n <= 0) break
            miniStream.copyInto(out, written, base, base + n)
            written += n
            sid = miniFat[idx]
        }
        return if (written == out.size) out else null
    }

    // =====================================================================
    // 第二层：BIFF8 记录流
    // =====================================================================

    private fun biffToTable(stream: ByteArray): List<List<String>>? {
        // 稀疏存格：key = row * 1000 + col（行 ≤ 65536、列 ≤ 256，不撞）
        val cells = HashMap<Long, String>()
        var sst: List<String> = emptyList()
        var maxRow = -1
        var maxCol = -1

        fun put(row: Int, col: Int, text: String) {
            if (row in 0..65535 && col in 0..255) {
                cells[(row.toLong() * 1000 + col)] = text
                if (row > maxRow) maxRow = row
                if (col > maxCol) maxCol = col
            }
        }

        var pos = 0
        var collecting = false // 正处于第一个工作片子流里
        var done = false
        var sheetSeen = false // 是否已经进过第一个工作表
        var guard = 0
        while (pos + 4 <= stream.size && guard++ < 5_000_000) {
            val id = u16(stream, pos)
            val len = u16(stream, pos + 2)
            val dataStart = pos + 4
            val dataEnd = dataStart + len
            if (dataEnd > stream.size) break // 记录越界：停在这里，已读到的格仍然有效
            var next = dataEnd

            when (id) {
                0x0809 -> { // BOF
                    val dt = if (len >= 4) u16(stream, dataStart + 2) else 0
                    when (dt) {
                        // 工作簿全局段：SST 共享字符串表在这里，但**格子不在**
                        0x0005 -> collecting = false
                        // 工作表段 / 宏表段：格数据在这里。
                        // 0x0010 = 工作表，0x0040 = 宏表。
                        0x0010, 0x0040 -> {
                            if (sheetSeen) {
                                // 第二张表：不要，只取第一张
                                collecting = false
                                done = true
                            } else {
                                collecting = true
                                sheetSeen = true
                            }
                        }
                        // 其它段（0x0020 = 图表、0x0006 = VB 模块…）：跳过
                        else -> {
                            if (collecting) {
                                collecting = false
                                done = true
                            }
                        }
                    }
                }
                0x000A -> { // EOF
                    if (collecting) { collecting = false; done = true }
                }
                0x00FC -> { // SST，后接若干 CONTINUE
                    val chunks = ArrayList<ByteArray>()
                    chunks.add(stream.copyOfRange(dataStart, dataEnd))
                    var p = dataEnd
                    while (p + 4 <= stream.size && u16(stream, p) == 0x003C) {
                        val cEnd = p + 4 + u16(stream, p + 2)
                        if (cEnd > stream.size) break
                        chunks.add(stream.copyOfRange(p + 4, cEnd))
                        p = cEnd
                    }
                    next = p
                    sst = parseSst(chunks)
                }
                0x00FD -> if (collecting && len >= 10) { // LABELSST：row,col,ixfe,isst
                    val row = u16(stream, dataStart)
                    val col = u16(stream, dataStart + 2)
                    val isst = u32(stream, dataStart + 6).toInt()
                    put(row, col, sst.getOrNull(isst) ?: "")
                }
                0x0204 -> if (collecting && len >= 9) { // LABEL：row,col,ixfe,cch(2),grbit(1),rgb
                    val row = u16(stream, dataStart)
                    val col = u16(stream, dataStart + 2)
                    val cch = u16(stream, dataStart + 6)
                    val grbit = stream[dataStart + 8].toInt() and 0xFF
                    put(row, col, readChars(stream, dataStart + 9, dataEnd, cch, (grbit and 0x01) != 0))
                }
                0x0203 -> if (collecting && len >= 14) { // NUMBER
                    val row = u16(stream, dataStart)
                    val col = u16(stream, dataStart + 2)
                    put(row, col, formatNumber(readDouble(stream, dataStart + 6)))
                }
                0x027E -> if (collecting && len >= 10) { // RK
                    val row = u16(stream, dataStart)
                    val col = u16(stream, dataStart + 2)
                    put(row, col, formatNumber(rkToDouble(u32(stream, dataStart + 6).toInt())))
                }
                0x00BD -> if (collecting && len >= 6) { // MULRK：row,colFirst,[xf,rk]*,colLast
                    val row = u16(stream, dataStart)
                    val colFirst = u16(stream, dataStart + 2)
                    val n = (len - 6) / 6
                    for (i in 0 until n) {
                        val off = dataStart + 4 + i * 6 + 2 // 跳过 2 字节 xf
                        if (off + 4 > dataEnd) break
                        val rk = u32(stream, off).toInt()
                        put(row, colFirst + i, formatNumber(rkToDouble(rk)))
                    }
                }
                0x0006 -> if (collecting && len >= 14) { // FORMULA
                    val row = u16(stream, dataStart)
                    val col = u16(stream, dataStart + 2)
                    val special = (0 until 4).all { stream[dataStart + 6 + it] == 0xFF.toByte() }
                    if (!special) {
                        put(row, col, formatNumber(readDouble(stream, dataStart + 6)))
                    } else if (dataEnd + 4 <= stream.size && u16(stream, dataEnd) == 0x0207) {
                        // 字符串结果：紧跟一条 STRING 记录（cch + grbit + 字符）
                        val sLen = u16(stream, dataEnd + 2)
                        val sDataEnd = dataEnd + 4 + sLen
                        if (sLen >= 3 && sDataEnd <= stream.size) {
                            val cch = u16(stream, dataEnd + 4)
                            val grbit = stream[dataEnd + 6].toInt() and 0xFF
                            put(row, col, readChars(stream, dataEnd + 7, sDataEnd, cch, (grbit and 0x01) != 0))
                            next = sDataEnd
                        }
                    }
                }
            }
            pos = next
            if (done) break
        }

        if (maxRow < 0) return emptyList()
        // 物化成稠密表格。中间的空行保留（分段表格靠它隔开），尾部全空行裁掉
        // ——Excel 的 DIMENSIONS 常说自己是 65536 行，不裁的话导入全是空行。
        val rows = ArrayList<List<String>>()
        for (r in 0..maxRow) {
            val line = ArrayList<String>()
            for (c in 0..maxCol) line.add(cells[(r.toLong() * 1000 + c)].orEmpty())
            rows.add(line)
        }
        while (rows.isNotEmpty() && rows.last().all { it.isEmpty() }) rows.removeAt(rows.size - 1)
        return rows
    }

    // ---------------------------------------------------------------- SST

    /**
     * 解析共享字符串表（含跨 CONTINUE 续写）。
     *
     * ## CONTINUE 块的编码标志字节（这里最容易错）
     *
     * MS-XLS 规定：字符串的字符数据可以断在 CONTINUE 边界上，而
     * **只有字符数据被切断时**，新 CONTINUE 块的第一字节才是编码
     * 标志（0 = 单字节压缩，1 = UTF-16）。如果上一块恰好结束在
     * **字符串边界**上（上一个字符串读完了、下一个字符串还没开始），
     * CONTINUE 块**没有**标志字节——它直接从下一个字符串的
     * header（cch 2 字节 + flags 1 字节）开始。
     *
     * 无条件吞掉首字节的后果：一旦遇到「边界对齐」的 CONTINUE，
     * 后面全部错位。实测番茄TODO 导出的 .xls 就是这种：1459 个
     * 字符串只能解出 507 个，后半张表全成了空单元格。
     *
     * 所以规则是：**header 字段跨块不消费标志字节，字符数据跨块才消费。**
     */
    private fun parseSst(chunks: List<ByteArray>): List<String> {
        val first = chunks.firstOrNull() ?: return emptyList()
        if (first.size < 8) return emptyList()
        val unique = u32(first, 4).toInt()
        if (unique < 0 || unique > 1_000_000) return emptyList()

        val cur = ChunkCursor(chunks)
        cur.pos = 8
        val out = ArrayList<String>(unique)
        repeat(unique) {
            val cch = cur.rawU16()
            if (cch < 0) return@repeat
            val flags = cur.rawU8()
            if (flags < 0) return@repeat
            val fRich = (flags and 0x08) != 0
            val fExt = (flags and 0x04) != 0
            val cRun = if (fRich) cur.rawU16() else 0
            val cbExt = if (fExt) cur.rawU32() else 0L
            val text = cur.readChars(cch, (flags and 0x01) != 0)
            if (fRich) cur.skipRaw(cRun * 4)
            if (fExt) cur.skipRaw(cbExt.toInt())
            out.add(text)
        }
        return out
    }

    /**
     * SST/字符串读取游标。
     *
     * 两类读取的跨块语义不同，这是全部难点所在：
     * - [rawU8] / [rawU16] / [rawU32] / [skipRaw]：header 与富文本
     *   附加数据。跨块时**直接续读**，不碰编码标志字节；
     * - [readChars]：字符数据。跨块时**必须先读掉**新块的编码标志
     *   字节，再按新宽度继续。
     */
    private class ChunkCursor(private val chunks: List<ByteArray>) {
        var ci = 0
        var pos = 0
        private fun chunk() = chunks.getOrNull(ci)
        private fun available() = chunk()?.let { pos < it.size } == true

        /** 换到下一块（不消费任何字节）。 */
        private fun advance(): Boolean {
            ci++
            if (ci >= chunks.size) return false
            pos = 0
            return true
        }

        /**
         * 读一个字节；块用尽时**直接续读下一块**，不消费编码标志。
         * 用于 header 字段（cch / flags / cRun / cbExt）。
         */
        fun rawU8(): Int {
            if (!available() && !advance()) return -1
            val b = chunks[ci][pos].toInt() and 0xFF
            pos++
            return b
        }

        fun rawU16(): Int {
            val b0 = rawU8()
            if (b0 < 0) return -1
            val b1 = rawU8()
            if (b1 < 0) return -1
            return b0 or (b1 shl 8)
        }

        fun rawU32(): Long {
            var v = 0L
            for (i in 0 until 4) {
                val b = rawU8()
                if (b < 0) return -1L
                v = v or (b.toLong() shl (8 * i))
            }
            return v
        }

        /**
         * 读 n 个字符。
         *
         * 跨块时先吃掉新块的编码标志字节（0=单字节 / 1=UTF-16），
         * 后续字符按新宽度读——同一个字符串的前后半段字符宽度可能不同。
         */
        fun readChars(n: Int, fHigh: Boolean): String {
            val sb = StringBuilder()
            var high = fHigh
            var left = n
            while (left > 0) {
                if (!available()) {
                    if (!advance()) break
                    high = readOptionFlag()
                    continue
                }
                if (high) {
                    if (chunks[ci].size - pos < 2) {
                        // 差 1 字节凑不齐一个 UTF-16 字符：换块按新宽度续读
                        if (!advance()) break
                        high = readOptionFlag()
                        continue
                    }
                    sb.append(rawU16().toChar())
                } else {
                    sb.append(rawU8().toChar())
                }
                left--
            }
            return sb.toString()
        }

        /**
         * 跳过原始字节（rich text runs / ext rst）。
         *
         * 这些是二进制结构，跨块时**没有**编码标志字节——直接续读。
         */
        fun skipRaw(n: Int) {
            var left = n
            while (left > 0) {
                if (!available() && !advance()) return
                val step = minOf(left, chunks[ci].size - pos)
                pos += step
                left -= step
            }
        }

        /** 读掉新块开头的编码标志字节，返回是否 UTF-16。 */
        private fun readOptionFlag(): Boolean {
            if (!available()) return false
            val flag = chunks[ci][pos].toInt() and 0xFF
            pos++
            return (flag and 0x01) != 0
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 从字节区间 [off, end) 里读 cch 个字符（LABEL / STRING 记录用）。 */
    private fun readChars(b: ByteArray, off: Int, end: Int, cch: Int, fHigh: Boolean): String {
        val sb = StringBuilder()
        var i = off
        repeat(cch) {
            if (fHigh) {
                if (i + 1 >= end) return@repeat
                sb.append(((b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)).toChar())
                i += 2
            } else {
                if (i >= end) return@repeat
                sb.append((b[i].toInt() and 0xFF).toChar())
                i++
            }
        }
        return sb.toString()
    }

    private fun readDouble(b: ByteArray, off: Int): Double =
        java.lang.Double.longBitsToDouble(u32(b, off + 4) shl 32 or (u32(b, off) and 0xFFFFFFFFL))

    /** RK 压缩数字解码：30 位载荷，整数/浮点两种形态，最低位是「除百」标志。 */
    internal fun rkToDouble(rk: Int): Double {
        val div100 = (rk and 0x01) != 0
        val isInt = (rk and 0x02) != 0
        val v = if (isInt) {
            (rk shr 2).toDouble() // 算术右移保符号
        } else {
            java.lang.Double.longBitsToDouble((rk.toLong() and 0xFFFFFFFCL) shl 32)
        }
        return if (div100) v / 100.0 else v
    }

    /**
     * 数字格转字符串：整数不带小数点，小数用 plain 记法（不出科学计数法）。
     * 时长列进来是 25 就得到 "25"，1.5 得到 "1.5"，交给上层按分钟/小时解析。
     */
    internal fun formatNumber(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return ""
        val rounded = Math.rint(d)
        if (rounded == d && kotlin.math.abs(d) < 9.2e18) return rounded.toLong().toString()
        return java.math.BigDecimal(d).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    /**
     * 小端读 32 位，**按有符号解释**再转 Long。
     *
     * CFB 的 FAT/特值（FREESECT=0xFFFFFFFF、ENDOCHAIN=0xFFFFFFFE…）
     * 高位都是 1，只有符号扩展后才能和 -1L/-2L 常量对上；普通扇区号
     * 都小于 2^31，有无符号解释结果相同。
     */
    private fun u32(b: ByteArray, off: Int): Long {
        var v = 0
        for (i in 0 until 4) v = v or ((b[off + i].toInt() and 0xFF) shl (8 * i))
        return v.toLong()
    }
}
