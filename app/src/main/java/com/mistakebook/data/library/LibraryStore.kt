package com.mistakebook.data.library

import java.io.File

/**
 * 资料库（v0.0.51 T6）：App 私有目录 `files/library/` 下的资料文件集合。
 *
 * 对话时 AI 能读（降级期）/ 读写（工具框架接入后），管理页能增删改查。
 * 一期只收文本类：`.md` / `.txt` / `.json` / `.csv`；v0.0.54 起支持
 * 二进制文档 `.docx` / `.pdf`：原文原样落库，读取时经
 * [DocumentTextExtractor] 提取正文（尽力而为，提取失败返回提示文案）。
 *
 * ## 路径安全是这里的第一要务
 *
 * 文件名来自模型输出（工具参数）或用户输入，**绝不能**直接拼路径——
 * `..\..\data\..` 这类穿越能读到/写穿沙箱外。所有入口都经
 * [resolveLibraryPath]：先按名去目录分量，再做归一化双保险，
 * 规范化后不在库目录内的一律拒绝。判定是纯函数，单测覆盖。
 */
object LibraryStore {

    /** 支持的扩展名（小写比较）。 */
    val SUPPORTED_EXTENSIONS = setOf("md", "txt", "json", "csv", "docx", "pdf", "xlsx", "xls")

    /** 二进制文档扩展名：读取需提取正文，写入/追加一律拒绝（防损坏原文件）。 */
    val BINARY_EXTENSIONS = setOf("docx", "pdf", "xlsx", "xls")

    /**
     * 二进制文档提取不出正文时返回的提示（预览与对话共用）。
     *
     * v0.0.58 改文案：旧文案只说「可能是扫描件」，用户看完还是不知道该干嘛。
     * 现在给出两条可操作的路——对话里直接发这份文件（客户端会自动把页面
     * 转成图片让模型用识图/OCR 工具读），或在详情页重新识别（走 MinerU）。
     */
    const val BINARY_EMPTY_HINT =
        "（未能从该文档提取出文字：可能是扫描件或使用了特殊字体编码。" +
            "可以直接在对话里把这份文件发给 AI——客户端会自动把页面转成图片，" +
            "由模型调用识图/OCR 工具读取；题目类文档也可以在详情页「重新识别」。）"

    /** 读文件的内容上限；超过截断并在返回里注明。 */
    const val MAX_READ_BYTES = 50 * 1024

    /**
     * 扫描型 PDF（v0.0.77）：文本层提不出来（或提出来是乱码）。
     *
     * 与 [BINARY_EMPTY_HINT] 的区别：那个是「放弃」，这个是**交给识图链路**——
     * 调用方据此把页面栅格化成图片附件，让已经配置好的识图模型 / OCR 去读
     * （用户反馈：「不是配置好了识图模型和 OCR，这就是你的手段啊」）。
     */
    const val SCANNED_PDF_HINT =
        "（该 PDF 是扫描件：文字层无法提取。页面图片已随本轮消息附上，" +
            "请调用 describe_image 或 ocr_image 读取内容，不要向用户索要转换后的文件。）"

    /**
     * 读取结果（v0.0.77）：除了正文与截断标记，还要告诉调用方
     * [scannedPdf]——是扫描件时上层要转图片走识图，而不是把提示文案
     * 当正文塞进上下文。
     */
    data class ReadResult(
        val text: String,
        val truncated: Boolean,
        val scannedPdf: Boolean = false
    )

    /** 单文件大小上限（写入侧）：资料库文件不该塞整本书。 */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /**
     * 把用户/模型给的文件名解析成**库目录内**的安全路径。
     *
     * @return 合法返回绝对路径文件对象；非法（穿越、空名、扩展名不支持）
     *   返回 null。纯函数（root 由调用方传入），可单测。
     */
    fun resolveLibraryPath(root: File, name: String): File? {
        val trimmed = name.trim().replace('\\', '/')
        if (trimmed.isEmpty()) return null
        // 严格策略：文件名里出现任何目录分量/穿越语义/绝对路径前缀直接拒绝，
        // 不做「压平取末段」——那会把 `../evil.md` 洗白成 evil.md 落进库里。
        if (trimmed != File(trimmed).name) return null
        if (trimmed.contains('/') || trimmed.contains("..")) return null
        val ext = trimmed.substringAfterLast('.', "").lowercase()
        if (ext !in SUPPORTED_EXTENSIONS) return null
        val resolved = File(root, trimmed).canonicalFile
        // 双保险：归一化后必须仍在库目录内（防符号链接/非常规输入绕过前面的门）
        val rootCanonical = root.canonicalFile
        if (!resolved.path.startsWith(rootCanonical.path + File.separator)) return null
        return resolved
    }

    /** 是否为二进制文档（.docx / .pdf）。 */
    fun isBinaryDoc(file: File): Boolean = file.extension.lowercase() in BINARY_EXTENSIONS

    /** 库目录（不存在则创建）。 */
    fun ensureDir(filesDir: File): File {
        val dir = File(filesDir, "library")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 列出库内全部文件（名称升序），非法文件跳过。 */
    fun list(root: File): List<File> =
        root.listFiles()?.filter { it.isFile && it.exists() }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    /**
     * 读文件；超过 [MAX_READ_BYTES] 截断并返回「已截断」标记。
     * 二进制文档走正文提取，同样受 50KB 截断约束（对话注入的口径不变）。
     *
     * v0.0.77 起内部走 [readDetailed]，PDF 额外区分「文本型 / 扫描型」。
     */
    fun read(file: File): Pair<String, Boolean>? =
        readDetailed(file)?.let { it.text to it.truncated }

    /**
     * 读文件并给出结构化的结果（v0.0.77）。
     *
     * PDF 分两种：文本层能抽出**可用**文字 → 直接当正文；
     * 抽不出来或抽出来是乱码（[PdfTextQuality] 判定）→ 标记 scannedPdf，
     * 由上层把页面转成图片交给识图 / OCR。其余二进制文档维持原行为。
     */
    fun readDetailed(file: File): ReadResult? {
        if (!file.isFile || !file.exists()) return null
        if (isBinaryDoc(file)) {
            val text = DocumentTextExtractor.extract(file)
            if (file.extension.lowercase() == "pdf") {
                // 非标准内容流的兜底路径会把 PDF 二进制原文当文本返回，
                // 因此「非空」不等于「可用」——必须过质量判定。
                if (!com.mistakebook.data.chat.PdfTextQuality.isUsable(text)) {
                    return ReadResult(SCANNED_PDF_HINT, truncated = false, scannedPdf = true)
                }
            } else if (text.isBlank()) {
                return ReadResult(BINARY_EMPTY_HINT, truncated = false)
            }
            val (cut, truncated) = truncateUtf8(text, MAX_READ_BYTES)
            return ReadResult(cut, truncated)
        }
        val raw = file.readBytes()
        val truncated = raw.size > MAX_READ_BYTES
        val bytes = if (truncated) raw.copyOf(MAX_READ_BYTES) else raw
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
        return ReadResult(text, truncated)
    }

    /** 在 UTF-8 字符边界上安全截断，不产生半个字符的乱码。 */
    private fun truncateUtf8(text: String, maxBytes: Int): Pair<String, Boolean> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text to false
        var cut = maxBytes
        // 退到字符起始字节（0b10xxxxxx 是多字节序列的后续字节）
        while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
        return String(bytes, 0, cut, Charsets.UTF_8) to true
    }

    /** 写文件（新建或覆盖）；返回实际字节数。 */
    fun write(file: File, content: String): Long {
        file.parentFile?.mkdirs()
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "内容超过资料库单文件上限" }
        file.writeBytes(bytes)
        return bytes.size.toLong()
    }

    /** 追加内容；返回追加后的总字节数。 */
    fun append(file: File, content: String): Long {
        file.parentFile?.mkdirs()
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(file.length() + bytes.size <= MAX_FILE_BYTES) { "追加后超过资料库单文件上限" }
        file.appendText(content, Charsets.UTF_8)
        return file.length()
    }

    /** 删除文件；返回是否删除成功。 */
    fun delete(file: File): Boolean = file.exists() && file.delete()

    /**
     * 把若干资料库文件拼成一段「资料原文」上下文（v0.0.57）。
     *
     * ## 为什么单独成函数
     *
     * 原先这段拼接写在 `ChatViewModel` 里，并且**直接插进用户消息正文**——
     * 于是资料原文原样出现在气泡里，用户还以为自己发的是一整篇文档。
     * 搬到 [LibraryStore] 后：一是纯逻辑、可单测；二是对话服务与 ViewModel
     * 共用同一份格式，不会两边各拼一套。
     *
     * 输出格式（每个文件一段）：
     * ```
     * 【资料库文件：xxx.docx】
     * （内容超 50KB 已截断）   ← 仅截断时出现
     * 正文……
     * ```
     * 抬头用「【】」包住来源，模型能把它与用户的话区分开。
     *
     * 读不出的文件（不存在 / 空）跳过，不产生空抬头。
     */
    fun buildContextBlock(files: List<File>): String = buildString {
        files.forEach { file ->
            val (content, truncated) = read(file) ?: return@forEach
            if (isNotEmpty()) append("\n\n")
            append("【资料库文件：").append(file.name).append("】\n")
            if (truncated) append("（内容超 50KB 已截断）\n")
            append(content.trim())
        }
    }
}
