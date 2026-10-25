package com.mistakebook.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mistakebook.data.AppFiles
import com.mistakebook.util.isWithinDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 本地备份：zip = mistake_book.db + files/ 全量。
 *
 * - Android Q+ 用 MediaStore.Downloads 写入 Download/错题本/，不需要存储权限；
 * - Q 以下写入 app 私有目录后由分享按钮导出（避免申请 WRITE_EXTERNAL_STORAGE）。
 * 恢复是覆盖式：替换 db 与 files 之后需要重启进程才会生效（设置页会提示重启）。
 */
class BackupManager(
    private val context: Context,
    private val files: AppFiles,
    /**
     * 备份/恢复时先关库、事后重建。
     *
     * ## 为什么必须有这个开关
     *
     * Room 默认开 **WAL**（预写日志）。真正的数据在 `-wal` 里，
     * `.db` 文件只包含已 checkpoint 的部分。直接拷 `.db`：
     * - 备份：丢掉最近写入的数据（用户刚加的题不在备份里）
     * - 恢复：把新库盖在旧库上，而旧的 `-wal`/`-shm` 还在 →
     *   下次启动 SQLite 会拿**旧 WAL 重放到新库**上，轻则数据错乱，重则库损坏
     *
     * 关库让 SQLite 把 WAL 落进 `.db` 并删掉 `-wal`/`-shm`，
     * 这才是「一个自洽的库」。
     */
    private val closeDatabase: suspend () -> Unit = {},
    private val reopenDatabase: () -> Unit = {}
) {

    suspend fun export(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // 备份前对两个数据库均执行安全检查点（checkpoint / flush）
            val wordbookDb = context.getDatabasePath("wordbook_local.db")
            listOf(files.databaseFile, wordbookDb).forEach { dbFile ->
                if (dbFile.exists()) {
                    runCatching {
                        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                        }
                    }
                }
            }

            // 先关库：把 WAL checkpoint 进 .db 并清掉 -wal/-shm，
            // 否则备份里那份 .db 缺最近的数据。详见 closeDatabase 的注释。
            closeDatabase()
            // 用局部变量而不是直接把 try/finally 当表达式传进 runCatching：
            // `try` 没有 else 分支时，Kotlin 推不出它的类型，
            // 会报「Missing return statement」——这个报错完全指不到真正的原因。
            val path: String
            try {
                path = doExport("backup_${timestamp()}.zip")
            } finally {
                reopenDatabase()
            }
            path
        }
    }

    private suspend fun doExport(name: String): String {
        val entries = collectEntries()
        // if/else 当函数体要显式 return。写成表达式体
        // （`= if (...) a else b`）也可以，但两个分支都得是 String 才推得出来。
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/错题本"
                )
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法写入下载目录")
            context.contentResolver.openOutputStream(uri)!!.use { stream ->
                writeZip(stream, entries)
            }
            "Download/错题本/$name"
        } else {
            val dir = files.exportDir.apply { mkdirs() }
            val target = File(dir, name)
            target.outputStream().use { stream -> writeZip(stream, entries) }
            target.absolutePath
        }
    }

    /** 解压并覆盖数据库与文件目录，返回恢复后的题目数量。 */
    suspend fun restore(input: InputStream): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val temp = File(context.cacheDir, "restore_${System.currentTimeMillis()}")
            temp.mkdirs()
            // 关库必须在**解压之前**：Room 还开着的时候覆盖 .db，
            // SQLite 句柄仍指向旧文件，行为不可预期。
            closeDatabase()
            // 同 export：try 无 else 分支时不能当表达式用，会报「Missing return statement」
            val restored: Int
            try {
                ZipInputStream(input.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val target = File(temp, entry.name)
                            // zip-slip 守卫：校验解压目标路径在 temp 规范化目录边界内
                            if (isWithinDirectory(temp, target)) {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { zip.copyTo(it) }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                val restoredDb = File(temp, "mistake_book.db")
                if (!restoredDb.exists()) error("备份中缺少数据库文件")

                // **必须连 -wal/-shm 一起删掉**。
                // 只覆盖 .db 而留着旧的 WAL，下次启动 SQLite 会拿旧 WAL
                // 重放到新库上——那是**另一份数据库的历史**，
                // 轻则数据错乱（恢复后又冒出已删的题），重则库损坏。
                val db = files.databaseFile
                db.parentFile?.mkdirs()
                db.delete()
                File(db.path + "-wal").delete()
                File(db.path + "-shm").delete()

                restoredDb.copyTo(db, overwrite = false)

                // 完整解压并还原 wordbook_local.db 及其关联文件（兼容旧版本备份）
                val restoredWordbookDb = File(temp, "wordbook_local.db")
                if (restoredWordbookDb.exists()) {
                    val wordbookDb = context.getDatabasePath("wordbook_local.db")
                    wordbookDb.parentFile?.mkdirs()
                    wordbookDb.delete()
                    File(wordbookDb.path + "-wal").delete()
                    File(wordbookDb.path + "-shm").delete()

                    restoredWordbookDb.copyTo(wordbookDb, overwrite = false)

                    val restoredWordbookWal = File(temp, "wordbook_local.db-wal")
                    if (restoredWordbookWal.exists()) {
                        restoredWordbookWal.copyTo(File(wordbookDb.path + "-wal"), overwrite = false)
                    }
                    val restoredWordbookShm = File(temp, "wordbook_local.db-shm")
                    if (restoredWordbookShm.exists()) {
                        restoredWordbookShm.copyTo(File(wordbookDb.path + "-shm"), overwrite = false)
                    }
                }

                val restoredFiles = File(temp, "files")
                if (restoredFiles.exists()) {
                    restoredFiles.copyRecursively(context.filesDir, overwrite = true)
                }
                restored = countQuestions()
            } finally {
                // 失败时也要清理，否则每次恢复失败都在 cache 里攒一个目录，
                // 而 cache 又不会被清理——用户重试十次就是十份完整文档的体积。
                temp.deleteRecursively()
                reopenDatabase()
            }
            restored
        }
    }

    private fun collectEntries(): List<Pair<File, String>> {
        val entries = mutableListOf<Pair<File, String>>()
        val filesRoot = context.filesDir
        // `chats` 也在列：聊天附件存在 chatDir(sessionId) 下，
        // 漏了它备份就不完整——而数据库里那几条消息还在，
        // 恢复后附件全成死链，界面上显示不出来的文件。
        //
        // `mineru` 不在列（T5）：它是识别的临时产物，且识别终态会被
        // RecognitionEngine 分层回收；DB 里已有 task.markdown 缓存，
        // 把它打进备份只会让 zip 体积随识别次数线性膨胀。恢复旧备份时
        // zip 里若含 mineru 文件也无害（整目录解压覆盖），无需兼容代码。
        listOf("crops", "questions", "imports", "chats").forEach { dirName ->
            File(filesRoot, dirName).walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    entries += file to "files/${file.relativeTo(filesRoot).path}"
                }
        }
        return entries
    }

    private fun writeZip(stream: OutputStream, entries: List<Pair<File, String>>) {
        ZipOutputStream(stream).use { zip ->
            zip.putNextEntry(ZipEntry(files.databaseFile.name))
            files.databaseFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            
            // 将 wordbook_local.db 及其 WAL/SHM 文件纳入 ZIP 归档
            val wordbookDb = context.getDatabasePath("wordbook_local.db")
            listOf(wordbookDb, File(wordbookDb.path + "-wal"), File(wordbookDb.path + "-shm")).forEach { file ->
                if (file.exists()) {
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            
            entries.forEach { (file, entryName) ->
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())

    private fun countQuestions(): Int = runCatching {
        val db = SQLiteDatabase.openDatabase(
            files.databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        db.rawQuery("SELECT COUNT(*) FROM questions WHERE deletedAt IS NULL", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }.getOrDefault(0)
}
