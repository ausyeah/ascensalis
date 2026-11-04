package com.mistakebook.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话的「置顶 / 临时」两列（v0.0.96）的源码契约。
 *
 * 这两件事都属于「静默出错」型：
 * - 老库升级时迁移写错一个字符 → 启动即闪退（本仓库踩过一次）；
 * - 列表查询忘了滤掉 temporary → 一条本该消失的临时对话永远挂在历史里；
 * - discardIfTemporary 不判 temporary → 退出任何一个会话都会把历史对话删掉。
 *
 * 前两条 JVM 里跑不了 Room，第三条依赖 Android；所以统一钉源码结构。
 */
class ChatSessionTemporaryTest {

    @Test
    fun `迁移 7 到 8 加两列且默认零`() {
        val db = readSource("src/main/java/com/mistakebook/data/local/MistakeBookDatabase.kt")

        assertTrue("版本号必须至少到 10（9→10 给题目与会话加归档时间）", db.contains("MISTAKE_BOOK_DB_VERSION = 10"))
        assertTrue("必须有 MIGRATION_7_8", db.contains("val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8)"))
        assertTrue(
            "pinned 必须 NOT NULL DEFAULT 0——老会话默认不置顶",
            db.contains("ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
        )
        assertTrue(
            "temporary 必须 NOT NULL DEFAULT 0——老会话默认全是正式对话",
            db.contains("ADD COLUMN `temporary` INTEGER NOT NULL DEFAULT 0")
        )
        assertTrue("最新迁移必须注册进 addMigrations", db.contains("MIGRATION_8_9,\n                    MIGRATION_9_10\n                )"))
    }

    @Test
    fun `实体两个新字段默认假`() {
        val entity = readSource("src/main/java/com/mistakebook/data/local/entities/ChatSession.kt")
        assertTrue(entity.contains("val pinned: Boolean = false"))
        assertTrue(entity.contains("val temporary: Boolean = false"))
    }

    @Test
    fun `列表滤掉临时对话且置顶在前`() {
        val dao = readSource("src/main/java/com/mistakebook/data/local/ChatDao.kt")

        assertTrue(
            "列表必须滤掉临时对话，否则「退出即删」看起来失败了",
            dao.contains("WHERE deletedAt IS NULL AND temporary = 0")
        )
        assertTrue(
            "排序必须置顶优先",
            dao.contains("ORDER BY pinned DESC, updatedAt DESC")
        )
        assertTrue(
            "空的自由会话不许复用临时对话——复用后再退出会把用户刚写的都删掉",
            dao.contains("s.deletedAt IS NULL AND s.temporary = 0")
        )
        assertTrue(
            "临时对话要物理删除，不是软删",
            dao.contains("DELETE FROM chat_sessions WHERE id = :id")
        )
    }

    @Test
    fun `退出时只删临时对话`() {
        val repo = readSource("src/main/java/com/mistakebook/data/repos/ChatRepository.kt")
        val start = repo.indexOf("suspend fun discardIfTemporary(")
        assertTrue("discardIfTemporary 必须存在", start >= 0)
        val body = repo.substring(start, repo.indexOf("\n    }", start))

        assertTrue("必须先读出会话再判断", body.contains("chatDao.findSession(id)"))
        assertTrue("必须判断 temporary，否则会把历史对话一起删掉", body.contains("if (!session.temporary) return"))
        assertTrue("删除前要清附件目录，否则图片永远留在缓存里", body.contains("chatDir(id)"))
        assertTrue("最后才删行", body.contains("chatDao.deleteSession(id)"))
    }

    @Test
    fun `启动时清理被杀进程留下的临时对话`() {
        val app = readSource("src/main/java/com/mistakebook/MistakeBookApp.kt")
        assertTrue(
            "进程被杀时退出回调不会跑，启动必须兜底清理",
            app.contains("purgeTemporarySessions()")
        )
    }

    @Test
    fun `对话页退出走 NavHost 以便删临时对话`() {
        val nav = readSource("src/main/java/com/mistakebook/MistakeBookNavHost.kt")
        assertTrue(
            "onBack 必须尝试删临时对话",
            nav.contains("container.chatRepository.discardIfTemporary(leaving)")
        )
        val chat = readSource("src/main/java/com/mistakebook/ui/chat/ChatScreen.kt")
        assertTrue(
            "系统返回键必须交给 onBack——系统自己 pop 的话删除那一步会被跳过"
                + "（抽屉开着时例外：那一下先关抽屉）",
            chat.contains("BackHandler(enabled = !drawerState.isOpen) { onBack() }")
        )
    }

    @Test
    fun `临时对话是全新的一条不复用`() {
        val repo = readSource("src/main/java/com/mistakebook/data/repos/ChatRepository.kt")
        val start = repo.indexOf("suspend fun openTemporarySession(")
        assertTrue(start >= 0)
        val body = repo.substring(start, repo.indexOf("\n    }", start))
        assertFalse(
            "绝不能复用空会话——那条可能是用户留着的历史对话",
            body.contains("findEmptyFreeSession")
        )
        assertTrue("必须标记 temporary", body.contains("temporary = true"))
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