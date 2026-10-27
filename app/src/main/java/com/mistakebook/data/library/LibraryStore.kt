package com.mistakebook.data.library

import java.io.File

/**
 * 资料库（v0.0.51 T6）：App 私有目录 `files/library/` 下的文本文件集合。
 *
 * 对话时 AI 能读（降级期）/ 读写（工具框架接入后），管理页能增删改查。
 * 一期只收文本类：`.md` / `.txt` / `.json` / `.csv`；PDF 走 MinerU 的
 * 解析列为二期，本版不落库。
 *
 * ## 路径安全是这里的第一要务
 *
 * 文件名来自模型输出（工具参数）或用户输入，**绝不能**直接拼路径——
 * `..\..\data\..` 这类穿越能读到/写穿沙箱外。所有入口都经
 * [resolveLibraryPath]：先按名去目录分量，再做归一化双保险，
 * 规范化后不在库目录内的一律拒绝。判定是纯函数，单测覆盖。
 */
object LibraryStore {

    /** 一期支持的文本扩展名（小写比较）。 */
    val SUPPORTED_EXTENSIONS = setOf("md", "txt", "json", "csv")

    /** 读文件的内容上限；超过截断并在返回里注明。 */
    const val MAX_READ_BYTES = 50 * 1024

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

    /** 读文件；超过 [MAX_READ_BYTES] 截断并返回「已截断」标记。 */
    fun read(file: File): Pair<String, Boolean>? {
        if (!file.isFile || !file.exists()) return null
        val raw = file.readBytes()
        val truncated = raw.size > MAX_READ_BYTES
        val bytes = if (truncated) raw.copyOf(MAX_READ_BYTES) else raw
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
        return text to truncated
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
}
