package com.mistakebook.data.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 对话自动归档的契约（v0.0.121，issue #35）。
 *
 * 用户要求：「对话时间戳七天前的都丢进归档，归档三个月前的删掉」。
 * Room 在 JVM 上跑不了，按仓库惯例钉源码：DAO 的 WHERE 条件、
 * 仓库层的文件清理、启动时的挂载点，三处少一处这条规则就名存实亡。
 */
class ChatSessionAutoArchiveTest {

    private val dao = readSource("src/main/java/com/mistakebook/data/local/ChatDao.kt")
    private val repo = readSource("src/main/java/com/mistakebook/data/repos/ChatRepository.kt")
    private val app = readSource("src/main/java/com/mistakebook/MistakeBookApp.kt")

    @Test
    fun `自动归档只动七天内没动过又没归档没软删的会话`() {
        // @Query 在 fun 声明之前：先取 fun 之前的全部文本，再取最后一段 @Query
        val before = dao.indexOf("suspend fun archiveStaleSessions")
        val query = dao.substring(0, before).substring(dao.lastIndexOf("@Query(", before))
        listOf(
            "archivedAt IS NULL",
            "deletedAt IS NULL",
            "temporary = 0",
            "updatedAt < :olderThan"
        ).forEach { cond ->
            assertTrue("自动归档 SQL 缺条件：$cond", query.contains(cond))
        }
    }

    @Test
    fun `归档清理以归档时刻为准且不碰软删会话`() {
        val before = dao.indexOf("suspend fun hardDeleteArchivedBefore")
        val query = dao.substring(0, before).substring(dao.lastIndexOf("@Query(", before))
        assertTrue(
            "删除线按 archivedAt 算（归档满三个月），不是 updatedAt",
            query.contains("archivedAt < :before")
        )
        assertTrue(
            "软删会话留给回收站的 30 天保留期，这里不许抢",
            query.contains("deletedAt IS NULL")
        )
    }

    @Test
    fun `仓库层删除前先清附件文件`() {
        val block = repo.substringAfter("purgeExpiredArchivedSessions")
        val order = listOf(
            block.indexOf("appFiles.deleteRecursively(appFiles.chatDir(it.id))"),
            block.indexOf("chatDao.hardDeleteArchivedBefore(before)")
        )
        assertTrue(
            "必须先删附件目录再删库行（库行一删 CASCADE 连行带路径信息一起没了）",
            order[0] in 0 until order[1]
        )
        assertTrue(
            "删除线必须来自 ChatRetention.deleteCutoff，不许在仓库里重算",
            block.contains("ChatRetention.deleteCutoff(now)")
        )
        assertTrue(
            "归档线必须来自 ChatRetention.archiveCutoff",
            repo.contains("ChatRetention.archiveCutoff(now)")
        )
    }

    @Test
    fun `启动时挂上两条保留期任务`() {
        assertTrue(
            "MistakeBookApp 启动必须跑 autoArchiveStaleSessions",
            app.contains("container.chatRepository.autoArchiveStaleSessions()")
        )
        assertTrue(
            "MistakeBookApp 启动必须跑 purgeExpiredArchivedSessions",
            app.contains("container.chatRepository.purgeExpiredArchivedSessions()")
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
