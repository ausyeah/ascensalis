package com.mistakebook.data

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * App 专属文件目录规划。
 *
 * 所有图片与中间产物都放在 app-specific files，卸载即清理；备份/恢复走 zip。
 */
class AppFiles(context: Context) {

    private val filesRoot: File = context.filesDir

    val cropDir: File = File(filesRoot, "crops")

    val importDir: File = File(filesRoot, "imports")

    val exportDir: File = File(filesRoot, "exports")

    val shareCacheDir: File = File(context.cacheDir, "share")

    val databaseFile: File = context.getDatabasePath("mistake_book.db")

    fun questionDir(taskId: Long): File = File(filesRoot, "questions/$taskId")

    fun questionImageDir(taskId: Long): File = File(questionDir(taskId), "images")

    fun mineruDir(taskId: Long): File = File(filesRoot, "mineru/$taskId")

    fun newCropFile(): File {
        cropDir.mkdirs()
        return File(cropDir, "${UUID.randomUUID()}.jpg")
    }

    fun newImportFile(extension: String): File {
        importDir.mkdirs()
        return File(importDir, "${UUID.randomUUID()}.$extension")
    }

    /** PDF 每页栅格化后的临时图片。 */
    fun newPdfPageFile(): File {
        val dir = File(importDir, "pdf_pages")
        dir.mkdirs()
        return File(dir, "${UUID.randomUUID()}.jpg")
    }

    /**
     * 对话附件的私有目录。
     *
     * **必须拷进应用私有目录**：用户从相册或文件管理器选的原文件随时可能被删或被移动，
     * 而对话历史是长期存在的——原文件没了，历史里的附件也就渲染不出来了。
     */
    fun chatDir(sessionId: Long): File = File(filesRoot, "chats/$sessionId")

    /** 对话附件落盘。扩展名保留原样，便于后续按类型分派。 */
    fun chatFile(sessionId: Long, name: String, extension: String): File {
        val dir = chatDir(sessionId)
        dir.mkdirs()
        return File(dir, "$name.$extension")
    }

    /**
     * 对话页「图片先过 MinerU」的中间产物目录。
     *
     * 刻意**不放进** `questions/`：那里的图片是题目封面，
     * 混进聊天 OCR 的中间文件会让备份/导出把垃圾当成题目图片带走。
     */
    fun chatOcrDir(sessionId: Long): File = File(chatDir(sessionId), "ocr")

    fun deleteRecursively(file: File?) {
        if (file == null || !file.exists()) return
        file.listFiles()?.forEach { deleteRecursively(it) }
        file.delete()
    }
}
