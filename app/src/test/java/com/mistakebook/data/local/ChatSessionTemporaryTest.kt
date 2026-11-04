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
 * - v0.0.112 起「退出即删」改为「退出即归档」：archiveTemporaryOnExit
 *   不判 temporary → 退出任何普通会话都会被误打归档标记。
 *
 * 前两条 JVM 里跑不了 Room，第三条依赖 Android；所以统一钉源码结构。
 */
class ChatSessionTemporaryTest {

    @Test
    fun `迁移 7 到 8 加两列且默认零`() {
        val db = readSource("src/main/java/com/mistakebook/data/local/MistakeBookDatabase.kt")

        assertTrue("版本号必须至少到 13（12→13 资料库向量索引）", db.contains("MISTAKE_BOOK_DB_VERSION = 13"))
        assertTrue("必须有 MIGRATION_7_8", db.contains("val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8)"))
        assertTrue(
            "pinned 必须 NOT NULL DEFAULT 0——老会话默认不置顶",
            db.contains("ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
        )
        assertTrue(
            "temporary 必须 NOT NULL DEFAULT 0——老会话默认全是正式对话",
            db.contains("ADD COLUMN `temporary` INTEGER NOT NULL DEFAULT 0")
        )
        assertTrue("最新迁移必须注册进 addMigrations", db.contains("MIGRATION_11_12,\n                    MIGRATION_12_13\n                )"))
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
            "退出路径仍保留物理删除（空临时对话用）",
            dao.contains("DELETE FROM chat_sessions WHERE id = :id")
        )
        assertTrue(
            "转正归档要摘掉 temporary 并打归档时间",
            dao.contains("SET temporary = 0, archivedAt = :at")
        )
    }

    @Test
    fun `临时对话页面要挂归档提示横幅`() {
        val screen = readSource("src/main/java/com/mistakebook/ui/chat/ChatScreen.kt")
        assertTrue("横幅要有挂载点", screen.contains("if (state.temporary) {"))
        assertTrue("横幅要说明退出后去哪", screen.contains("R.string.chat_temporary_banner"))
        val vm = readSource("src/main/java/com/mistakebook/ui/chat/ChatViewModel.kt")
        assertTrue("状态要带 temporary 标记", vm.contains("val temporary: Boolean = false"))
        assertTrue("装载会话时要把标记写进状态", vm.contains("temporary = session.temporary"))
    }

    @Test
    fun `退出临时对话是转正加归档`() {
        val repo = readSource("src/main/java/com/mistakebook/data/repos/ChatRepository.kt")
        val start = repo.indexOf("suspend fun archiveTemporaryOnExit(")
        assertTrue("archiveTemporaryOnExit 必须存在（v0.0.112 退出即归档）", start >= 0)
        val body = repo.substring(start, repo.indexOf("\n    }", start))

        assertTrue("必须先读出会话再判断", body.contains("chatDao.findSession(id)"))
        assertTrue("必须判断 temporary，否则历史对话会被误归档", body.contains("if (!session.temporary) return"))
        // 有内容的：转正 + 归档（摘掉 temporary 才会出现在「已归档会话」里）
        assertTrue("要走 graduateTemporary", body.contains("chatDao.graduateTemporary(id,"))
        // 空的：没东西可归档，与「空会话不留记录」同口径直接删
        assertTrue("空临时对话直接删", body.contains("chatDao.countMessages(id) == 0"))
        assertTrue("删除前要清附件目录", body.contains("chatDir(id)"))
    }

    @Test
    fun `启动兜底把残留临时对话补归档而不是删除`() {
        val app = readSource("src/main/java/com/mistakebook/MistakeBookApp.kt")
        assertTrue(
            "进程被杀时退出回调不会跑，启动必须兜底归档",
            app.contains("archiveAbandonedTemporarySessions()")
        )
        val repo = readSource("src/main/java/com/mistakebook/data/repos/ChatRepository.kt")
        assertFalse("旧的全删兜底不许残留", repo.contains("deleteTemporarySessions()"))
        assertTrue(
            "兜底也要走 graduateTemporary",
            repo.contains("leftovers.forEach { chatDao.graduateTemporary(it.id, now) }")
        )
    }

    @Test
    fun `对话页退出走 NavHost 以便归档临时对话`() {
        val nav = readSource("src/main/java/com/mistakebook/MistakeBookNavHost.kt")
        assertTrue(
            "onBack 必须归档临时对话",
            nav.contains("container.chatRepository.archiveTemporaryOnExit(leaving)")
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