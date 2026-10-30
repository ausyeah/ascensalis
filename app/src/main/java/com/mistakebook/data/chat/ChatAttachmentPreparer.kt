package com.mistakebook.data.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.mistakebook.data.AppFiles
import com.mistakebook.data.ImageNormalizer
import com.mistakebook.data.local.entities.ChatAttachment
import com.mistakebook.data.pdf.PdfTextExtractor
import com.mistakebook.domain.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal fun decodePlainText(bytes: ByteArray): String {
    val gb18030 = Charset.forName("GB18030")
    return decodeStrict(bytes, Charsets.UTF_8)
        ?: decodeStrict(bytes, gb18030)
        ?: String(bytes, gb18030)
}

private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = runCatching {
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()

internal fun copyLimited(input: InputStream, target: File, maxBytes: Long): Long {
    try {
        require(maxBytes > 0)
        target.parentFile?.mkdirs()
        var total = 0L
        input.use { source ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    require(total <= maxBytes) { "文件太大：$total" }
                    output.write(buffer, 0, read)
                }
            }
        }
        require(total > 0) { "文件为空" }
        return total
    } catch (error: Throwable) {
        runCatching { target.delete() }
        throw error
    }
}

/**
 * 附件处理结果。
 *
 * **刻意不是 [ChatAttachment] 实体**：实体带 `messageId` 外键，
 * 而用户是**先选附件、后发消息**——选的那一刻消息还不存在，插不进去。
 * 所以准备阶段只在内存里，发送时才由 ChatRepository 落库。
 */
data class PreparedAttachment(
    val localPath: String,
    val fileName: String,
    val mimeType: String,
    val kind: AttachmentKind,
    val sizeBytes: Long = 0,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val textExcerpt: String = "",
    val extractedChars: Int = 0,
    val errorMessage: String? = null,
    /**
     * 抽不出文字时栅格化出来的**页面图片**路径（v0.0.58）。
     *
     * PDF 是扫描件 / 特殊字体编码时，客户端自己把页面转成图，
     * 由模型用识图 / OCR 工具读——而不是把乱码发给模型换一句
     * 「请把页面转成图片后发给我」。
     */
    val pageImages: List<String> = emptyList()
) {
    val ok: Boolean get() = errorMessage == null
}

/** PDF 转图后写给模型的说明（v0.0.58）。const 只能落在顶层或 companion，这里选顶层。 */
private const val PDF_PAGE_IMAGE_NOTE =
    "（该 PDF 的文字层无法提取，可能是扫描件或使用了特殊字体编码。" +
        "本消息已附带它的页面图片，请调用 describe_image 或 ocr_image 读取图片内容，" +
        "不要向用户索要转换后的文件。）"

/**
 * 附件准备管线。**纯单机，不上传**。
 *
 * - 图片：按长边压到 [longEdgePx]、JPEG q80 存进私有目录，
 *   之后由 [ChatImageDataUrls] 编成 base64 data URL 随请求发出
 * - PDF：复用 [PdfTextExtractor] 抽文本
 * - TXT / MD：直接读
 * - DOCX：[DocxText] 从 zip 里抽正文
 * - 其它类型：保留文件但不参与构造请求（[AttachmentKind.OTHER]）
 *
 * ## 为什么要拷进私有目录
 *
 * 用户从相册或文件管理器选的原文件随时可能被删或被移动，
 * 而对话历史是长期存在的。原文件没了，历史里的附件就渲染不出来、
 * 下一轮对话也带不上去了。
 */
class ChatAttachmentPreparer(
    private val context: Context,
    private val appFiles: AppFiles
) {

    suspend fun prepare(sessionId: Long, uri: Uri, longEdgePx: Int): PreparedAttachment =
        withContext(Dispatchers.IO) {
            val fileName = displayNameOf(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
            val kind = classify(fileName, context.contentResolver.getType(uri))
            val extension = extensionFor(kind, fileName)

            runCatching {
                val target = appFiles.chatFile(sessionId, UUID.randomUUID().toString(), extension)
                val size = copyTo(uri, target)
                when (kind) {
                    AttachmentKind.IMAGE -> processImage(target, fileName, size, longEdgePx)
                    AttachmentKind.PDF -> processPdf(target, fileName, size, sessionId)
                    AttachmentKind.TEXT -> processPlainText(target, fileName, size)
                    AttachmentKind.DOCX -> processDocx(target, fileName, size)
                    AttachmentKind.XLSX -> processSpreadsheet(target, fileName, size)
                    // LIBRARY 只由「@资料库」内部登记产生（见 ChatViewModel），
                    // 不会经 SAF 选文件进来；留一支让 when 保持穷尽。
                    AttachmentKind.LIBRARY -> PreparedAttachment(
                        localPath = target.absolutePath,
                        fileName = fileName,
                        mimeType = "",
                        kind = kind,
                        sizeBytes = size
                    )
                    AttachmentKind.OTHER -> PreparedAttachment(
                        localPath = target.absolutePath,
                        fileName = fileName,
                        mimeType = "",
                        kind = kind,
                        sizeBytes = size
                    )
                }
            }.getOrElse { throwable ->
                PreparedAttachment(
                    localPath = "",
                    fileName = fileName,
                    mimeType = "",
                    kind = kind,
                    errorMessage = throwable.message ?: "处理失败"
                )
            }
        }

    /**
     * 准备一个**已经在会话目录里**的本地文件（v0.0.58：PDF 栅格化出的页面图）。
     *
     * 与 [prepare] 的区别：不走 SAF、不拷贝——文件本来就在
     * `appFiles.chatDir(sessionId)` 里，再拷一份纯属浪费。
     */
    suspend fun prepareFile(
        sessionId: Long,
        file: File,
        longEdgePx: Int
    ): PreparedAttachment = withContext(Dispatchers.IO) {
        if (!file.isFile) {
            return@withContext PreparedAttachment(
                localPath = "",
                fileName = file.name,
                mimeType = "",
                kind = AttachmentKind.OTHER,
                errorMessage = "文件不存在"
            )
        }
        val kind = classify(file.name, null)
        runCatching {
            when (kind) {
                AttachmentKind.IMAGE ->
                    processImage(file, file.name, file.length(), longEdgePx)

                else -> PreparedAttachment(
                    localPath = file.absolutePath,
                    fileName = file.name,
                    mimeType = "",
                    kind = kind,
                    sizeBytes = file.length()
                )
            }
        }.getOrElse { throwable ->
            PreparedAttachment(
                localPath = "",
                fileName = file.name,
                mimeType = "",
                kind = kind,
                errorMessage = throwable.message ?: "处理失败"
            )
        }
    }

    // ------------------------------------------------------------ 处理
    private fun copyTo(uri: Uri, target: File): Long =
        context.contentResolver.openInputStream(uri)?.let {
            copyLimited(it, target, MAX_FILE_BYTES)
        } ?: throw IllegalStateException("无法读取所选文件")

    private fun processImage(file: File, fileName: String, size: Long, longEdgePx: Int): PreparedAttachment {
        val bitmap = ImageNormalizer.decodeBounded(file, longEdgePx)
            ?: return PreparedAttachment(
                localPath = file.absolutePath,
                fileName = fileName,
                mimeType = "image/jpeg",
                kind = AttachmentKind.IMAGE,
                sizeBytes = size,
                errorMessage = "无法解码这张图片"
            )
        val width = bitmap.width
        val height = bitmap.height
        try {
            compress(bitmap, file)
        } finally {
            bitmap.recycle()
        }
        return PreparedAttachment(
            localPath = file.absolutePath,
            fileName = fileName,
            mimeType = "image/jpeg",
            kind = AttachmentKind.IMAGE,
            sizeBytes = file.length(),
            widthPx = width,
            heightPx = height
        )
    }


    private fun compress(bitmap: Bitmap, target: File) {
        target.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
    }

    /**
     * 把 PDF 页面栅格化成图片（v0.0.77，供资料库的扫描型 PDF 复用）。
     *
     * 与附件路径同一套实现（[PdfPageImages]，最多 4 页、长边 1500px）：
     * 资料库里的扫描件同样交给识图 / OCR 链路，而不是只吐一句「读不出文字」。
     *
     * @return 页面图路径（落在该会话附件目录）；失败返回空列表。
     */
    fun renderPdfPages(file: File, sessionId: Long): List<String> = PdfPageImages.render(
        file,
        targetFactory = { index ->
            appFiles.chatFile(
                sessionId,
                "libpdfpage_${System.currentTimeMillis()}_$index",
                "jpg"
            )
        }
    )

    private fun processPdf(
        file: File,
        fileName: String,
        size: Long,
        sessionId: Long
    ): PreparedAttachment {
        val pages = PdfTextExtractor.extractAll(file.readBytes())
        val text = pages.filter { it.isNotBlank() }.joinToString("\n")
        // v0.0.58：抽出来的东西**先过质量检查**。
        // 非标准内容流的兜底路径会把 PDF 二进制原文当文本返回（用户实测
        // 「已提取 631 字」其实是一串乱码），旧逻辑非空即成功，
        // 于是乱码被发给模型，模型只能让用户自己去转图片。
        if (PdfTextQuality.isUsable(text)) {
            return excerpt(file, fileName, "application/pdf", AttachmentKind.PDF, size, text)
        }
        // 抽不出可读文字 → 栅格化成页面图，让模型用识图/OCR 工具读。
        // 注意必须命名 targetFactory：尾随 lambda 会绑到最后一个参数（longEdgePx）。
        val pageImages = PdfPageImages.render(file, targetFactory = { index ->
            appFiles.chatFile(
                sessionId,
                "pdfpage_${System.currentTimeMillis()}_$index",
                "jpg"
            )
        })
        return PreparedAttachment(
            localPath = file.absolutePath,
            fileName = fileName,
            mimeType = "application/pdf",
            kind = AttachmentKind.PDF,
            sizeBytes = size,
            // 这段「正文」是给模型看的说明：它会随文档摘要一起进上下文，
            // 明确告诉模型「文字没抽出来，页面图已经在了，去调识图/OCR 工具」。
            // 留空的话模型只看到「未能提取到正文」，不知道该干什么。
            textExcerpt = PDF_PAGE_IMAGE_NOTE,
            extractedChars = 0,
            errorMessage = if (pageImages.isEmpty()) {
                "没有从 $fileName 里读到可读文字（可能是扫描件或损坏），且页面转图失败"
            } else {
                null
            },
            pageImages = pageImages
        )
    }

    /** PDF 转图后写给模型的说明（v0.0.58）。 */
    private fun processPlainText(file: File, fileName: String, size: Long): PreparedAttachment {
        val text = decodePlainText(file.readBytes())
        return excerpt(file, fileName, "text/plain", AttachmentKind.TEXT, size, text)
    }

    private fun processDocx(file: File, fileName: String, size: Long): PreparedAttachment {
        val text = DocxText.extract(file.readBytes())
        return excerpt(file, fileName, DOCX_MIME, AttachmentKind.DOCX, size, text)
    }

    /**
     * Excel 表格（v0.0.57）。
     *
     * - `.xlsx`（zip 包）：[XlsxText] 抽成 `\t`/`\n` 的文本表；
     * - 老版 `.xls`（OLE2 二进制，魔数 `D0 CF 11 E0`）：零依赖解不开单元格，
     *   给一条**可操作**的提示而不是「处理失败」。用户看到「请另存为 .xlsx」
     *   就知道下一步怎么做；只说「不支持」只会让人卡住。
     */
    private fun processSpreadsheet(file: File, fileName: String, size: Long): PreparedAttachment {
        val bytes = file.readBytes()
        val excelMime = if (fileName.lowercase().endsWith(".xlsx")) {
            XLSX_MIME
        } else {
            XLS_MIME
        }
        val isOle2 = bytes.size >= 4 &&
            bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()
        if (isOle2) {
            return PreparedAttachment(
                localPath = file.absolutePath,
                fileName = fileName,
                mimeType = excelMime,
                kind = AttachmentKind.XLSX,
                sizeBytes = size,
                errorMessage = "老版 .xls 暂不支持直接读取，请用 Excel/WPS 另存为 .xlsx 或 .csv 后重新发送"
            )
        }
        val text = XlsxText.extract(bytes)
        return excerpt(file, fileName, excelMime, AttachmentKind.XLSX, size, text)
    }

    /**
     * 统一处理「抽出来的文本」：优先保留完整内容，最多限制为 [MAX_EXCERPT_CHARS] 字符。
     * 长文档仍不能独占整段会话上下文，但短于上限的题目、讲义和源码不再被过早截断。
     */
    private fun excerpt(
        file: File,
        fileName: String,
        mimeType: String,
        kind: AttachmentKind,
        size: Long,
        text: String
    ): PreparedAttachment {
        val trimmed = text.trim()
        return PreparedAttachment(
            localPath = file.absolutePath,
            fileName = fileName,
            mimeType = mimeType,
            kind = kind,
            sizeBytes = size,
            textExcerpt = trimmed.take(MAX_EXCERPT_CHARS),
            extractedChars = trimmed.length,
            errorMessage = if (trimmed.isEmpty()) "没有从 $fileName 里读到任何文字" else null
        )
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    companion object {

        // ------------------------------------------------------------ 分派

        /** 按文件名与 MIME 判断类型。**纯逻辑，可单测。** */
        fun classify(fileName: String, mimeType: String?): AttachmentKind {
            val lower = fileName.lowercase()
            val mime = mimeType?.lowercase().orEmpty()
            return when {
                mime.startsWith("image/") -> AttachmentKind.IMAGE
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                    lower.endsWith(".webp") || lower.endsWith(".heic") || lower.endsWith(".bmp") ->
                    AttachmentKind.IMAGE

                mime == "application/pdf" || lower.endsWith(".pdf") -> AttachmentKind.PDF
                lower.endsWith(".docx") || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    AttachmentKind.DOCX

                // Excel（v0.0.57）：xlsx 原生抽取；xls 也归这里，处理时再按
                // OLE2 魔数给「请另存为」的提示（见 processSpreadsheet）。
                lower.endsWith(".xlsx") || lower.endsWith(".xls") ||
                    mime == XLSX_MIME || mime == XLS_MIME -> AttachmentKind.XLSX

                // .doc 是老二进制格式，零依赖解不开——归 OTHER 而不是 DOCX，
                // 因为归错类型会让 UI 显示「就绪」但模型什么也收不到
                mime.startsWith("text/") || lower.endsWith(".txt") || lower.endsWith(".md") ||
                    lower.endsWith(".markdown") || lower.endsWith(".csv") -> AttachmentKind.TEXT

                else -> AttachmentKind.OTHER
            }
        }

        /** 落盘扩展名。图片统一存 jpg——已经是 jpg 的不必再压一遍。 */
        fun extensionFor(kind: AttachmentKind, fileName: String): String = when (kind) {
            AttachmentKind.IMAGE -> "jpg"
            AttachmentKind.PDF -> "pdf"
            AttachmentKind.DOCX -> "docx"
            AttachmentKind.XLSX -> "xlsx"
            // LIBRARY 不经 SAF 进来（只在 @资料库 登记时产生），这支让 when 保持穷尽
            AttachmentKind.LIBRARY -> fileName.substringAfterLast('.', "bin").ifBlank { "bin" }
            AttachmentKind.TEXT -> if (fileName.lowercase().endsWith(".md")) "md" else "txt"
            AttachmentKind.OTHER -> fileName.substringAfterLast('.', "bin").ifBlank { "bin" }
        }
        /** 单文件上限。超过直接拒绝，不做「读一半」。 */
        const val MAX_FILE_BYTES = 20L * 1024 * 1024

        const val JPEG_QUALITY = 80

        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

        /** xlsx 的 MIME（OpenXML 表格）。 */
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        /** 老版 xls 的 MIME（OLE2 二进制表格）。 */
        const val XLS_MIME = "application/vnd.ms-excel"

        /** 单个文本附件最多保留 20,000 字符，避免极大文件占满会话上下文。 */
        const val MAX_EXCERPT_CHARS = 20_000
    }
}
