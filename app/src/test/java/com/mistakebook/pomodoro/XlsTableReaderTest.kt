package com.mistakebook.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XLS 读取器测试。
 *
 * 夹具全部**手工按规范搭字节**（CFB 头/目录/FAT + BIFF8 记录），
 * 不借 Apache POI 生成——正是为了把「规范理解」和「解析器实现」
 * 分开钉死：偏移读错一位、链走错一层，这些用例立刻红。
 *
 * 覆盖：
 * - CFB 两层取流：Workbook ≥ 4096 走 FAT 链；< 4096 住 mini stream；
 * - BIFF 记录六兄弟：LABELSST / LABEL / NUMBER / RK / MULRK / FORMULA+STRING；
 * - SST 跨 CONTINUE 且**字符宽度中途切换**（压缩→UTF-16）；
 * - 只取第一个工作表，第二张表的内容不许混进来；
 * - 畸形输入（截断 / 没有 Workbook 流 / 全零流）不抛异常。
 */
class XlsTableReaderTest {

    // ================================================================ BIFF 构件

    private fun rec(id: Int, data: ByteArray): ByteArray {
        val out = ByteArray(4 + data.size)
        out[0] = (id and 0xFF).toByte()
        out[1] = ((id shr 8) and 0xFF).toByte()
        out[2] = (data.size and 0xFF).toByte()
        out[3] = ((data.size shr 8) and 0xFF).toByte()
        data.copyInto(out, 4)
        return out
    }

    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun u32(v: Long): ByteArray =
        ByteArray(4) { ((v shr (8 * it)) and 0xFF).toByte() }

    private fun i32(v: Int): ByteArray = u32(v.toLong() and 0xFFFFFFFFL)

    private fun f64(v: Double): ByteArray {
        val bits = java.lang.Double.doubleToRawLongBits(v)
        return ByteArray(8) { ((bits shr (8 * it)) and 0xFF).toByte() }
    }

    private fun bof(dt: Int) = rec(0x0809, u16(0x0600) + u16(dt) + u16(0) + u16(0) + ByteArray(8))

    private val eof = rec(0x000A, ByteArray(0))

    /** LABELSST(0x00FD)：row, col, ixfe, isst(4)。 */
    private fun labelsst(row: Int, col: Int, isst: Int) =
        rec(0x00FD, u16(row) + u16(col) + u16(0) + u32(isst.toLong()))

    /** LABEL(0x0204)：row, col, ixfe, cch(2), grbit(1), rgb。grbit=1 走 UTF-16。 */
    private fun label(row: Int, col: Int, text: String): ByteArray {
        val chars = text.toByteArray(Charsets.UTF_16LE)
        return rec(0x0204, u16(row) + u16(col) + u16(0) + u16(text.length) + byteArrayOf(0x01) + chars)
    }

    /** NUMBER(0x0203)：row, col, ixfe, double(8)。 */
    private fun number(row: Int, col: Int, v: Double) =
        rec(0x0203, u16(row) + u16(col) + u16(0) + f64(v))

    /** RK(0x027E)：row, col, ixfe, rk(4)。 */
    private fun rk(row: Int, col: Int, rkVal: Int) =
        rec(0x027E, u16(row) + u16(col) + u16(0) + i32(rkVal))

    /** MULRK(0x00BD)：row, colFirst, [xf(2), rk(4)]*, colLast。 */
    private fun mulrk(row: Int, colFirst: Int, rks: List<Int>): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        body.write(u16(row)); body.write(u16(colFirst))
        rks.forEach { body.write(u16(0)); body.write(i32(it)) }
        body.write(u16(colFirst + rks.size - 1))
        return rec(0x00BD, body.toByteArray())
    }

    /** FORMULA(0x0006) 字符串结果 + 紧跟的 STRING(0x0207)。 */
    private fun formulaString(row: Int, col: Int, text: String): ByteArray {
        val result = ByteArray(8) { 0xFF.toByte() } // 全 FF = 结果是字符串
        val body = u16(row) + u16(col) + u16(0) + result + u16(0) + u32(0)
        val chars = text.toByteArray(Charsets.UTF_16LE)
        val str = rec(0x0207, u16(text.length) + byteArrayOf(0x01) + chars)
        return rec(0x0006, body) + str
    }

    // ---------------------------------------------------------------- RK 编码

    private fun rkInt(v: Int, div100: Boolean = false): Int {
        var r = (v shl 2) or 0x02
        if (div100) r = r or 0x01
        return r
    }

    private fun rkDouble(d: Double, div100: Boolean = false): Int {
        var r = (java.lang.Double.doubleToRawLongBits(d) ushr 32).toInt() and 0xFFFFFFFC.toInt()
        if (div100) r = r or 0x01
        return r
    }

    // ================================================================ CFB 构件

    private val ole2Magic = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
    )

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 4) b[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    /** 目录项 128 字节：name(64) nameLen@0x40 type@0x42 start@0x74 size@0x78。 */
    private fun writeDirEntry(dir: ByteArray, off: Int, name: String, type: Int, start: Long, size: Long) {
        val chars = name.toByteArray(Charsets.UTF_16LE)
        chars.copyInto(dir, off)
        putU16(dir, off + 0x40, chars.size + 2) // 含结尾 null
        dir[off + 0x42] = type.toByte()
        putU32(dir, off + 0x74, start)
        putU32(dir, off + 0x78, size)
        putU32(dir, off + 0x7C, 0)
    }

    private fun header(
        numFatSectors: Int, firstDirSector: Long,
        firstMiniFat: Long, numMiniFat: Long, firstDifat: Long
    ): ByteArray {
        val h = ByteArray(512)
        ole2Magic.copyInto(h, 0)
        putU16(h, 0x18, 0x003E)   // minor
        putU16(h, 0x1A, 3)        // major（v3，512 字节扇区）
        putU16(h, 0x1C, 0xFFFE)   // byte order
        putU16(h, 0x1E, 9)        // sector shift = 512
        putU16(h, 0x20, 6)        // mini sector shift = 64
        putU32(h, 0x2C, numFatSectors.toLong())
        putU32(h, 0x30, firstDirSector)
        putU32(h, 0x38, 4096)     // mini stream cutoff
        putU32(h, 0x3C, firstMiniFat)
        putU32(h, 0x40, numMiniFat)
        putU32(h, 0x44, firstDifat)
        putU32(h, 0x48, 0)
        // DIFAT[0] 指向 FAT 扇区 0；其余未用条目填 FREESECT
        putU32(h, 0x4C, 0)
        for (i in 0x50 until 512) h[i] = 0xFF.toByte()
        return h
    }

    private fun fatSector(fat: LongArray): ByteArray {
        val b = ByteArray(512)
        for (i in fat.indices) putU32(b, i * 4, fat[i])
        return b
    }

    /**
     * FAT 路径：Workbook ≥ 4096 字节，直接住普通扇区。
     * 布局：0=FAT，1=目录，2..=Workbook 链。
     */
    private fun buildFatPathCfb(workbook: ByteArray): ByteArray {
        val nWb = (workbook.size + 511) / 512
        val fat = LongArray(128) { -0x1L } // FREESECT
        fat[0] = -0x3L                    // FATSECT
        fat[1] = -0x2L                    // ENDOFCHAIN（目录单扇区）
        for (i in 0 until nWb) {
            fat[2 + i] = if (i == nWb - 1) -0x2L else (2 + i + 1).toLong()
        }
        val dir = ByteArray(512)
        writeDirEntry(dir, 0, "Root Entry", 5, -0x2L, 0)
        writeDirEntry(dir, 128, "Workbook", 2, 2, workbook.size.toLong())
        val wbSectors = ArrayList<ByteArray>()
        var off = 0
        while (off < workbook.size) {
            val n = minOf(512, workbook.size - off)
            val s = ByteArray(512)
            workbook.copyInto(s, 0, off, off + n)
            wbSectors.add(s)
            off += 512
        }
        val out = java.io.ByteArrayOutputStream()
        out.write(header(1, 1, -0x2L, 0, -0x2L))
        out.write(fatSector(fat))
        out.write(dir)
        wbSectors.forEach { out.write(it) }
        return out.toByteArray()
    }

    /**
     * mini stream 路径：Workbook < 4096，住在 Root 流切出的 64 字节小扇区里。
     * 布局：0=FAT，1=目录，2=miniFAT，3..=mini stream。
     */
    private fun buildMiniPathCfb(workbook: ByteArray): ByteArray {
        require(workbook.size < 4096)
        val nMini = (workbook.size + 63) / 64
        val miniStreamSize = nMini * 64
        val nMiniSectors = (miniStreamSize + 511) / 512
        val fat = LongArray(128) { -0x1L }
        fat[0] = -0x3L
        fat[1] = -0x2L
        fat[2] = -0x2L
        for (i in 0 until nMiniSectors) {
            fat[3 + i] = if (i == nMiniSectors - 1) -0x2L else (3 + i + 1).toLong()
        }
        val miniFat = LongArray(128) { -0x1L }
        for (i in 0 until nMini) {
            miniFat[i] = if (i == nMini - 1) -0x2L else (i + 1).toLong()
        }
        val dir = ByteArray(512)
        writeDirEntry(dir, 0, "Root Entry", 5, 3, miniStreamSize.toLong())
        writeDirEntry(dir, 128, "Workbook", 2, 0, workbook.size.toLong()) // 小扇区 0 起
        // mini stream 内容（Root 的流）
        val miniStream = ByteArray(miniStreamSize)
        workbook.copyInto(miniStream, 0)
        val miniSectors = ArrayList<ByteArray>()
        var off = 0
        while (off < miniStream.size) {
            val n = minOf(512, miniStream.size - off)
            val s = ByteArray(512)
            miniStream.copyInto(s, 0, off, off + n)
            miniSectors.add(s)
            off += 512
        }
        val out = java.io.ByteArrayOutputStream()
        out.write(header(1, 1, 2, 1, -0x2L))
        out.write(fatSector(fat))
        out.write(dir)
        out.write(fatSector(miniFat)) // miniFAT 扇区
        miniSectors.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** 没有 Workbook 目录项的 CFB（只有 Root Entry）。 */
    private fun buildNoWorkbookCfb(): ByteArray {
        val fat = LongArray(128) { -0x1L }
        fat[0] = -0x3L
        fat[1] = -0x2L
        val dir = ByteArray(512)
        writeDirEntry(dir, 0, "Root Entry", 5, -0x2L, 0)
        val out = java.io.ByteArrayOutputStream()
        out.write(header(1, 1, -0x2L, 0, -0x2L))
        out.write(fatSector(fat))
        out.write(dir)
        out.write(ByteArray(512))
        return out.toByteArray()
    }

    // ================================================================ 工作表内容

    /**
     *  globals（SST 含一条跨 CONTINUE、宽度中途切换的字符串）
     *  + 第一个工作表（六种格记录全用上）
     *  + 第二个工作表（必须被忽略）
     */
    private fun workbookBytes(): ByteArray {
        // ---- SST：9 条，前 8 条 + 第 9 条头部在 chunk1，尾巴在 chunk2 且换成 UTF-16
        val sstChunk1 = java.io.ByteArrayOutputStream()
        sstChunk1.write(u32(9)); sstChunk1.write(u32(9)) // total, unique
        fun utf16Str(s: String) { sstChunk1.write(u16(s.length)); sstChunk1.write(byteArrayOf(0x01)); sstChunk1.write(s.toByteArray(Charsets.UTF_16LE)) }
        fun latinStr(s: String) { sstChunk1.write(u16(s.length)); sstChunk1.write(byteArrayOf(0x00)); sstChunk1.write(s.toByteArray(Charsets.ISO_8859_1)) }
        utf16Str("分类"); utf16Str("任务名"); utf16Str("时长")
        utf16Str("数学"); utf16Str("刷真题")
        utf16Str("英语"); utf16Str("背单词")
        latinStr("Hello") // 压缩单字节路径
        // 第 9 条 "abc中def"：chunk1 里写 cch=7 + 压缩标志 + "abc"
        sstChunk1.write(u16(7)); sstChunk1.write(byteArrayOf(0x00))
        sstChunk1.write("abc".toByteArray(Charsets.ISO_8859_1))
        val sstChunk2 = java.io.ByteArrayOutputStream()
        sstChunk2.write(byteArrayOf(0x01)) // 新块标志：UTF-16
        sstChunk2.write("中def".toByteArray(Charsets.UTF_16LE))

        val globals = bof(0x0005) +
            rec(0x00FC, sstChunk1.toByteArray()) +
            rec(0x003C, sstChunk2.toByteArray()) +
            eof

        // ---- 第一个工作表
        val sheet1 = bof(0x0010) +
            labelsst(0, 0, 0) + labelsst(0, 1, 1) + labelsst(0, 2, 2) + labelsst(0, 3, 7) +
            labelsst(1, 0, 3) + labelsst(1, 1, 4) + number(1, 2, 25.0) +
            labelsst(2, 0, 5) + labelsst(2, 1, 6) + rk(2, 2, rkInt(30)) +
            label(3, 1, "直存") + mulrk(3, 2, listOf(rkInt(45), rkInt(90))) +
            formulaString(4, 1, "公式结果") + number(4, 2, 60.0) +
            eof

        // ---- 第二个工作表：内容必须被丢弃
        val sheet2 = bof(0x0010) + labelsst(0, 0, 8) + number(0, 1, 999.0) + eof

        return globals + sheet1 + sheet2
    }

    private val expectedTable = listOf(
        listOf("分类", "任务名", "时长", "Hello"),
        listOf("数学", "刷真题", "25", ""),
        listOf("英语", "背单词", "30", ""),
        listOf("", "直存", "45", "90"),
        listOf("", "公式结果", "60", "")
    )

    // ================================================================ 用例

    @Test
    fun `FAT 路径读出第一个工作表`() {
        val padded = workbookBytes() + rec(0x0208, ByteArray(4000)) // 撑过 4096 走 FAT
        val file = buildFatPathCfb(padded)
        assertTrue(XlsTableReader.looksLikeXls(file))
        val table = XlsTableReader.readTable(file)
        assertEquals(expectedTable, table)
    }

    @Test
    fun `mini stream 路径同样读出`() {
        val file = buildMiniPathCfb(workbookBytes())
        assertEquals(expectedTable, XlsTableReader.readTable(file))
    }

    @Test
    fun `Book 旧流名也认`() {
        val wb = workbookBytes() + rec(0x0208, ByteArray(4000))
        val file = buildFatPathCfb(wb)
        // 把目录里 Workbook 改名 Book（同名长 UTF-16）
        val dirOff = 512 + 512 + 128 // 头 + FAT + 目录第二项
        "Book".toByteArray(Charsets.UTF_16LE).copyInto(file, dirOff)
        putU16(file, dirOff + 0x40, "Book".length * 2 + 2)
        assertEquals(expectedTable, XlsTableReader.readTable(file))
    }

    @Test
    fun `畸形输入全部安全返回`() {
        // 不是 OLE2
        assertFalse(XlsTableReader.looksLikeXls("分类,任务,时长\n".toByteArray()))
        assertNull(XlsTableReader.readTable("hello".toByteArray()))
        assertFalse(XlsTableReader.looksLikeXls(ByteArray(0)))
        // 没有 Workbook 流
        assertNull(XlsTableReader.readTable(buildNoWorkbookCfb()))
        // 截断：最后一个 Workbook 扇区不完整
        val file = buildFatPathCfb(workbookBytes() + rec(0x0208, ByteArray(4000)))
        assertNull(XlsTableReader.readTable(file.copyOfRange(0, file.size - 600)))
        // 流内容全零：不抛异常，返回空表
        val zero = buildFatPathCfb(ByteArray(4200))
        assertEquals(emptyList<List<String>>(), XlsTableReader.readTable(zero))
    }

    @Test
    fun `RK 三形态与数字格式化`() {
        assertEquals(25.0, XlsTableReader.rkToDouble(rkInt(25)), 1e-9)
        assertEquals(25.5, XlsTableReader.rkToDouble(rkInt(2550, div100 = true)), 1e-9)
        assertEquals(1.5, XlsTableReader.rkToDouble(rkDouble(1.5)), 1e-9)
        assertEquals(0.015, XlsTableReader.rkToDouble(rkDouble(1.5, div100 = true)), 1e-12)
        assertEquals(-7.0, XlsTableReader.rkToDouble(rkInt(-7)), 1e-9)
        assertEquals("25", XlsTableReader.formatNumber(25.0))
        assertEquals("1.5", XlsTableReader.formatNumber(1.5))
        assertEquals("0.015", XlsTableReader.formatNumber(0.015))
        assertEquals("100000000000000000000", XlsTableReader.formatNumber(1e20))
    }

    @Test
    fun `xls 表格直接喂给专注导入器`() {
        val file = buildMiniPathCfb(workbookBytes())
        val table = XlsTableReader.readTable(file)!!
        val r = CsvFocusImporter.parseTable(table, allowSerialDate = true)
        // 一行表格 = 一条导入记录：时长只认「时长」列（MULRK 的 45 在隔壁列，不参与）
        assertEquals(4, r.rows.size)
        assertEquals(0, r.skipped)
        assertFalse(r.headerUnrecognized)
        assertEquals("数学", r.rows[0].subject)
        assertEquals("刷真题", r.rows[0].taskName)
        assertEquals(1500L, r.rows[0].durationSec)
        assertEquals(1800L, r.rows[1].durationSec)
        assertEquals("直存", r.rows[2].taskName)
        assertEquals(2700L, r.rows[2].durationSec)
        assertNull(r.rows[2].subject)
        assertEquals("公式结果", r.rows[3].taskName)
        assertEquals(3600L, r.rows[3].durationSec)
    }

    @Test
    fun `xls 日期序列值换算归档`() {
        // SST：日期 / 任务 / 时长 / 数学
        val sst = java.io.ByteArrayOutputStream()
        sst.write(u32(4)); sst.write(u32(4))
        listOf("日期", "任务", "时长", "数学").forEach {
            sst.write(u16(it.length)); sst.write(byteArrayOf(0x01)); sst.write(it.toByteArray(Charsets.UTF_16LE))
        }
        val wb = bof(0x0005) + rec(0x00FC, sst.toByteArray()) + eof +
            bof(0x0010) +
            labelsst(0, 0, 0) + labelsst(0, 1, 1) + labelsst(0, 2, 2) +
            number(1, 0, 45581.0) + labelsst(1, 1, 3) + number(1, 2, 25.0) +
            eof
        val file = buildMiniPathCfb(wb)
        val r = CsvFocusImporter.parseTable(XlsTableReader.readTable(file)!!, allowSerialDate = true)
        assertEquals(1, r.rows.size)
        assertEquals("2024-10-16", r.rows[0].day)
        assertEquals(1500L, r.rows[0].durationSec)
    }
}
