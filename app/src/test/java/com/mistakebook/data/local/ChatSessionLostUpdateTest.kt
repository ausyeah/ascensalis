package com.mistakebook.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「生成过的对话丢了」修复的契约（v0.0.130）。
 *
 * 用户报告：生成过的对话从两个列表里都消失，归档里也找不到。
 * 排查出四条根因，每条都钉在这里：
 *
 * 1. **丢更新**：rename/换模型/刷统计走「读整行 → copy → 整行回写」，
 *    与 graduateTemporary（摘 temporary、打 archivedAt）并发时把刚写的
 *    状态列回写成旧值——转正归档后的会话重新从两个列表里消失。
 *    修复：ChatDao 不再提供整行 @Update，热点路径全部改定向 UPDATE。
 * 2. **归档清理线太狠**：只看 archivedAt，一条归档后还在用的对话
 *    满 3 个月照样物理删除。修复：加 updatedAt 双闸（3 个月没动过才删）。
 * 3. **往归档会话里发消息不解归档**：会话留在主列表看不见的地方。
 *    修复：insertUserMessage 调 unarchiveOnNewMessage。
 * 4. **空会话复用漏了 archivedAt IS NULL**：新对话落到已归档会话里，
 *    看不见正文。修复：findEmptyFreeSession 补条件。
 */
class ChatSessionLostUpdateTest {

    private val dao = readSource("src/main/java/com/mistakebook/data/local/ChatDao.kt")
    private val repo = readSource("src/main/java/com/mistakebook/data/repos/ChatRepository.kt")

    @Test
    fun `会话表不再提供整行 Update——丢更新的源头必须不存在`() {
        val sessionSection = dao.substringAfter("interface ChatDao")
            .substringBefore("suspend fun insertMessage")
        assertFalse(
            "chat_sessions 不许再有 @Update 整行更新（读整行 copy 回写就是丢更新）",
            Regex("""@Update\s*\n\s*suspend fun updateSession\(""").containsMatchIn(sessionSection)
        )
    }

    @Test
    fun `定向更新各只写自己该写的列`() {
        // renameSessionById：title + titleLocked + updatedAt，不碰状态列
        val rename = queryBeforeFun("renameSessionById")
        assertTrue(rename.contains("title = :title"))
        assertTrue(rename.contains("titleLocked = 1"))
        assertTrue(rename.contains("updatedAt = :at"))
        assertFalse(rename.contains("temporary"))
        assertFalse(rename.contains("archivedAt"))

        // updateSessionModel：model + updatedAt
        val model = queryBeforeFun("updateSessionModel")
        assertTrue(model.contains("model = :model"))
        assertTrue(model.contains("updatedAt = :at"))
        assertFalse(model.contains("temporary"))
        assertFalse(model.contains("archivedAt"))

        // setTitleIfUnlocked：titleLocked 的判断在 WHERE 里（原子，不留两步窗口）
        val title = queryBeforeFun("setTitleIfUnlocked")
        assertTrue(title.contains("titleLocked = 0"))
        assertTrue(title.contains("SET title = :title"))
    }

    @Test
    fun `往归档会话发消息就解除归档`() {
        val unarchive = queryBeforeFun("unarchiveOnNewMessage")
        assertTrue(
            "解除归档的 UPDATE 只该对已归档会话生效",
            unarchive.contains("archivedAt IS NOT NULL")
        )
        val insertUser = repo.substringAfter("suspend fun insertUserMessage")
            .substringBefore("touchSessionWith(sessionId, text, now)")
        assertTrue(
            "insertUserMessage 必须先调 unarchiveOnNewMessage",
            insertUser.contains("chatDao.unarchiveOnNewMessage(sessionId, now)")
        )
    }

    @Test
    fun `热点路径全部定向更新不再整行 copy`() {
        listOf(
            "suspend fun renameSession",
            "suspend fun applyGeneratedTitle",
            "suspend fun insertStreamingAssistantMessage"
        ).forEach { marker ->
            val block = repo.substringAfter(marker).take(1000)
            assertFalse(
                "$marker 里不许再出现整行 copy 回写",
                block.contains("updateSession(session.copy(")
            )
        }
        // touchSessionWith：标题走条件更新，locked 判断不再先读整行
        val touch = repo.substringAfter("private suspend fun touchSessionWith")
            .substringBefore("private suspend fun")
        assertTrue(touch.contains("setTitleIfUnlocked"))
        assertFalse(touch.contains("chatDao.updateSession("))
    }

    @Test
    fun `归档清理双闸——归档满三个月且三个月没动过`() {
        listOf("listArchivedBefore", "hardDeleteArchivedBefore").forEach { funName ->
            val query = queryBeforeFun(funName)
            assertTrue("$funName 缺归档时刻线", query.contains("archivedAt < :before"))
            assertTrue(
                "$funName 缺最后活跃线（还在用的归档会话不许销毁）",
                query.contains("updatedAt < :before")
            )
        }
    }

    @Test
    fun `空会话复用不许落到已归档会话里`() {
        val find = queryBeforeFun("findEmptyFreeSession")
        assertTrue(
            "findEmptyFreeSession 必须排除已归档会话",
            find.contains("archivedAt IS NULL")
        )
    }

    @Test
    fun `临时对话转正是幂等的定向更新`() {
        val graduate = queryBeforeFun("graduateTemporary")
        assertTrue(graduate.contains("temporary = 0"))
        assertTrue(graduate.contains("archivedAt = :at"))
        assertTrue(
            "graduateTemporary 必须带 temporary = 1 守卫（重复跑不产生副作用）",
            graduate.contains("AND temporary = 1")
        )
    }

    /** 取 fun 声明之前最近的一段 @Query。 */
    private fun queryBeforeFun(funName: String): String {
        val at = dao.indexOf("suspend fun $funName")
        require(at >= 0) { "fun not found: $funName" }
        return dao.substring(0, at).substring(dao.lastIndexOf("@Query(", at))
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
