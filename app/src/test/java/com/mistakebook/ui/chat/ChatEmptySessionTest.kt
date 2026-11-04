package com.mistakebook.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 没发过消息的会话不该留下记录（v0.0.107）。
 *
 * 用户的原话：「每次新开的一个对话如果没发消息，没必要记录的啊」。
 * 之前是「点开就建行」——新建对话立刻往库里插一条，返回时什么都不做，
 * 于是历史里攒下一串没有标题、没有内容的「新对话」，看着像记录，其实全是噪音。
 *
 * 三道闸门一起钉住，缺一条都会漏：
 * 1. 列表查询过滤掉零消息会话（旧数据、并发写入都不会露头）；
 * 2. 退出时把空会话整条删掉（删条件写在 SQL 里，避免「查完又发了一条」被误删）；
 * 3. 启动兜底清理老版本攒下的空行。
 */
class ChatEmptySessionTest {

    @Test
    fun `会话列表只显示说过话的`() {
        val dao = readSource("src/main/java/com/mistakebook/data/local/ChatDao.kt")
        val start = dao.indexOf("fun observeSessions()")
        assertTrue("会话列表查询必须存在", start > 0)
        val query = dao.substring(dao.lastIndexOf("@Query", start), start)
        assertTrue(
            "列表必须要求「至少有一条消息」",
            query.contains("EXISTS (SELECT 1 FROM chat_messages")
        )
    }

    @Test
    fun `退出空会话把它整条删掉`() {
        val dao = readSource("src/main/java/com/mistakebook/data/local/ChatDao.kt")
        assertTrue("DAO 要有 deleteIfEmpty", dao.contains("suspend fun deleteIfEmpty(id: Long): Int"))
        assertTrue(
            "删的条件必须在 SQL 里——先查再删会把刚发的第一条消息一起删掉",
            dao.contains("WHERE m.sessionId = :id AND m.role <> 'SYSTEM'")
        )

        val repo = readSource("src/main/java/com/mistakebook/data/repos/ChatRepository.kt")
        assertTrue(
            "仓库要暴露「空就删」",
            repo.contains("suspend fun discardIfEmpty(id: Long): Boolean")
        )

        val nav = readSource("src/main/java/com/mistakebook/MistakeBookNavHost.kt")
        assertTrue("退出对话时必须调它", nav.contains("container.chatRepository.discardIfEmpty(leaving)"))
        assertTrue(
            "删了会话就顺手清草稿——草稿绑在不存在的会话上只是垃圾",
            nav.contains("container.settingsStore.setChatDraft(leaving, \"\")")
        )
    }

    @Test
    fun `只注入系统指令不算说过话`() {
        // 拍题/翻译这类入口会往会话里落一条**用户看不见的** SYSTEM 指令。
        // 只按「有没有消息行」判断的话，点开入口又退出会留下一条「未命名对话」——
        // 在用户看来，这就是一段没有任何消息的空记录。
        val dao = readSource("src/main/java/com/mistakebook/data/local/ChatDao.kt")
        assertTrue(
            "列表、退出即删、启动清理都必须把 SYSTEM 排除在「说过话」之外",
            dao.split("role <> 'SYSTEM'").size - 1 >= 4
        )
        assertTrue(
            "「新对话」复用槽位也不能挑一条只有系统指令的会话",
            dao.contains("WHERE m.sessionId = s.id AND m.role <> 'SYSTEM'")
        )
    }

    @Test
    fun `启动时清理老版本留下的空会话`() {
        val app = readSource("src/main/java/com/mistakebook/MistakeBookApp.kt")
        assertTrue(
            "老数据的空行要清掉，不能只靠列表过滤",
            app.contains("container.chatRepository.purgeEmptySessions()")
        )
        val dao = readSource("src/main/java/com/mistakebook/data/local/ChatDao.kt")
        assertTrue(
            "批量清理也必须排除临时对话与归档",
            dao.contains("suspend fun deleteEmptySessions(): Int") &&
                dao.contains("AND temporary = 0 AND archivedAt IS NULL")
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