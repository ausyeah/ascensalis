package com.mistakebook.data.chat

import com.mistakebook.domain.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * xlsx 抽取（v0.0.57 用户反馈「xls 不支持」）。
 *
 * 这条链路以前完全没有测试，xlsx 又恰好落进 OTHER——界面「就绪」、
 * 模型「你没发文件」。这里把 sharedStrings / 内联字符串 / 数字 / 空列占位
 * / zip 解包都钉住，并覆盖分类规则。
 */
class XlsxTextTest {

    @Test
    fun `sharedStrings 按 si 分条 富文本多段 t 拼接`() {
        val xml = """
            <sst>
              <si><t>姓名</t></si>
              <si><r><t>张</t></r><r><t>三</t></r></si>
              <si><t xml:space="preserve"> &amp; </t></si>
            </sst>
        """.trimIndent()
        val list = XlsxText.parseSharedStrings(xml)
        assertEquals(listOf("姓名", "张三", " & "), list)
    }

    @Test
    fun `列索引按字母进位`() {
        assertEquals(0, XlsxText.columnIndex("A1"))
        assertEquals(1, XlsxText.columnIndex("B7"))
        assertEquals(25, XlsxText.columnIndex("Z1"))
        assertEquals(26, XlsxText.columnIndex("AA1"))
        assertEquals(27, XlsxText.columnIndex("AB2"))
        // 没有引用时退回 0，不崩
        assertEquals(0, XlsxText.columnIndex(""))
    }

    @Test
    fun `单元格支持共享字符串 内联字符串 与数字`() {
        val shared = listOf("科目", "数学", "语文")
        val xml = """
            <sheetData>
              <row r="1">
                <c r="A1" t="s"><v>0</v></c>
                <c r="B1" t="inlineStr"><is><t>分数</t></is></c>
              </row>
              <row r="2">
                <c r="A2" t="s"><v>1</v></c>
                <c r="B2"><v>123</v></c>
              </row>
            </sheetData>
        """.trimIndent()
        val text = XlsxText.parseSheet(xml, shared)
        assertEquals("科目\t分数\n数学\t123", text)
    }

    @Test
    fun `空列占位不左移 且去掉行尾空列`() {
        // B 列空、C 列有值：A 与 C 之间必须有一个空 tab，否则 C 会左移到 B。
        val xml = """
            <sheetData>
              <row r="1">
                <c r="A1"><v>1</v></c>
                <c r="C1"><v>3</v></c>
              </row>
            </sheetData>
        """.trimIndent()
        assertEquals("1\t\t3", XlsxText.parseSheet(xml, emptyList()))
    }

    @Test
    fun `共享字符串越界退回原值而不是整行空掉`() {
        val xml = """<row r="1"><c r="A1" t="s"><v>99</v></c></row>"""
        assertEquals("99", XlsxText.parseSheet(xml, listOf("only")))
    }

    @Test
    fun `完整 zip 解包多工作表并排序`() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            z.write("<sst><si><t>成绩</t></si><si><t>88</t></si></sst>".toByteArray())
            z.closeEntry()
            // 故意先写 sheet10 再写 sheet2，验证排序按数字而非字典序
            z.putNextEntry(ZipEntry("xl/worksheets/sheet10.xml"))
            z.write("""<sheetData><row r="1"><c r="A1" t="s"><v>0</v></c></row></sheetData>""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("xl/worksheets/sheet2.xml"))
            z.write("""<sheetData><row r="1"><c r="A1" t="s"><v>1</v></c></row></sheetData>""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("xl/workbook.xml"))
            z.write("<workbook/>".toByteArray())
            z.closeEntry()
        }
        val text = XlsxText.extract(zip.toByteArray())
        // sheet2 在前、sheet10 在后
        assertTrue(text.indexOf("sheet2") < text.indexOf("sheet10"))
        assertTrue(text.contains("成绩"))
        assertTrue(text.contains("88"))
    }

    @Test
    fun `非 zip 输入返回空串不抛异常`() {
        assertEquals("", XlsxText.extract(byteArrayOf(1, 2, 3)))
        assertEquals("", XlsxText.extract(ByteArray(0)))
    }

    @Test
    fun `分类识别 xlsx 与 xls`() {
        assertEquals(AttachmentKind.XLSX, ChatAttachmentPreparer.classify("成绩单.xlsx", null))
        assertEquals(AttachmentKind.XLSX, ChatAttachmentPreparer.classify("成绩单.XLSX", null))
        assertEquals(AttachmentKind.XLSX, ChatAttachmentPreparer.classify("老表.xls", null))
        assertEquals(
            AttachmentKind.XLSX,
            ChatAttachmentPreparer.classify("a", ChatAttachmentPreparer.XLSX_MIME)
        )
        assertEquals(
            AttachmentKind.XLSX,
            ChatAttachmentPreparer.classify("a", ChatAttachmentPreparer.XLS_MIME)
        )
    }

    @Test
    fun `落盘扩展名`() {
        assertEquals("xlsx", ChatAttachmentPreparer.extensionFor(AttachmentKind.XLSX, "a.xls"))
        assertEquals("xlsx", ChatAttachmentPreparer.extensionFor(AttachmentKind.XLSX, "a.xlsx"))
    }
}
