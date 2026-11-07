package com.mistakebook.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 资料库扫描 PDF 的 OCR 兜底契约（v0.0.131）。
 *
 * 根因：Word/WPS 导出的中文 PDF 常见「PDF 1.5 对象流 + 嵌字字体无
 * ToUnicode」——零依赖抽取器读不出可读文本，此前建索引直接跳过，
 * 资料在库里等于不存在（用户拿 EP 模拟卷实测踩中）。修复：判为
 * scannedPdf 的资料走 [LibraryIndexer] 注入的 OCR 兜底（渲染页图 →
 * 当前识别渠道逐页转写），转写文本按页码拼成正文入库。
 *
 * Room / PdfRenderer 在 JVM 上跑不了，钉三处最容易被改坏的地方：
 * 页文本拼接的页码对齐、索引器与装配处的兜底接线、渲染逻辑只此一份。
 */
class LibraryScannedPdfFallbackTest {

    @Test
    fun `拼页时页码按原 PDF 页序标注，失败页不挤占页码`() {
        val assembled = LibraryIndexer.assembleOcrPages(
            listOf("甲页正文", null, null, "丁页正文")
        )
        // 第 2、3 页识别失败：第 4 页必须还叫「第 4 页」，不能被顶成第 3 页
        assertTrue("开头应有第 1 页标注", assembled.contains("【第 1 页】"))
        assertTrue(assembled.contains("甲页正文"))
        assertTrue("失败页后的页码必须保持原序", assembled.contains("【第 4 页】"))
        assertTrue(assembled.contains("丁页正文"))
        assertFalse("失败页不该出现在正文里", assembled.contains("【第 2 页】"))
        assertFalse(assembled.contains("【第 3 页】"))
    }

    @Test
    fun `空白与纯空白页整页剔除`() {
        val assembled = LibraryIndexer.assembleOcrPages(
            listOf("  \n ", "只有一行的页", "")
        )
        assertEquals("【第 2 页】\n只有一行的页", assembled)
    }

    @Test
    fun `全部失败时返回空串——资料不算失败，只是没有可检索内容`() {
        val assembled = LibraryIndexer.assembleOcrPages(listOf(null, "", "   "))
        assertEquals("", assembled)
    }

    @Test
    fun `单页成功也带页码标注`() {
        val assembled = LibraryIndexer.assembleOcrPages(listOf("正文"))
        assertTrue(assembled.startsWith("【第 1 页】"))
        assertTrue(assembled.contains("正文"))
    }

    @Test
    fun `索引器构造带 OCR 兜底参数且默认无操作`() {
        val source = readSource("src/main/java/com/mistakebook/data/library/LibraryIndexer.kt")
        assertTrue(
            "LibraryIndexer 必须有 pdfOcrFallback 注入点",
            source.contains("pdfOcrFallback")
        )
        assertTrue(
            "兜底默认实现必须是无操作（AppContainer 不装也不崩）",
            source.contains("{ _, _ -> null }")
        )
        assertTrue(
            "scannedPdf 分支必须真的调用兜底，而不是只留参数",
            Regex("""scannedPdf[\s\S]{0,800}pdfOcrFallback\(file""").containsMatchIn(source)
        )
        assertTrue(
            "OCR 阶段必须把 stage 文案透出到 Progress（长流程要可见）",
            source.contains("stage = stage")
        )
    }

    @Test
    fun `装配处真的接上了兜底——不是默认空实现`() {
        val container = readSource("src/main/java/com/mistakebook/di/AppContainer.kt")
        assertTrue(
            "AppContainer 装配 LibraryIndexer 必须传 pdfOcrFallback",
            container.contains("pdfOcrFallback = { pdf, onStage ->")
        )
        assertTrue(
            "兜底必须走共享的页渲染器",
            container.contains("PdfPageRenderer.render(")
        )
        assertTrue(
            "兜底必须经当前识别渠道转写（与拍题同一 OcrEngine 出口）",
            container.contains("OcrEngine.forSettings(settings, ocrEngineDeps)")
        )
        assertTrue(
            "兜底产物必须按页码拼正文",
            container.contains("LibraryIndexer\n                        .assembleOcrPages(texts)")
        )
        assertTrue(
            "页图是临时产物，索引完必须清掉",
            container.contains("tempDir.deleteRecursively()")
        )
    }

    @Test
    fun `渲染逻辑只此一份——PdfImporter 必须委托 PdfPageRenderer`() {
        val shared = readSource("src/main/java/com/mistakebook/data/pdf/PdfPageRenderer.kt")
        val importer = readSource("src/main/java/com/mistakebook/data/pdf/PdfImporter.kt")
        assertTrue(
            "共享渲染器必须带 OOM 降级（RGB_565 再试）",
            shared.contains("Bitmap.Config.RGB_565")
        )
        assertTrue(
            "PdfImporter 必须委托共享渲染器",
            importer.contains("PdfPageRenderer.render(pdfFile, wanted)")
        )
        assertFalse(
            "私有渲染副本必须删干净（两份渲染迟早改岔）",
            importer.contains("private fun renderOnePage(")
        )
    }

    private fun readSource(path: String): String {
        val candidates = listOf(
            File(path),
            File("../$path"),
            File(path.removePrefix("app/")),
            File("../" + path.removePrefix("app/"))
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalArgumentException("Cannot find file: $path among $candidates")
        return file.readText()
    }
}
