package com.mistakebook.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 三个新报 issue 的契约（v0.0.126）。
 *
 * #37 番茄钟任务归档后找不到、不能恢复；
 * #38 生成图第一时间不渲染，退出重进才正常；
 * #39 思考时不能收起思考；输出时滚动一抽一抽、手势优先级不够。
 *
 * 三条都属于「不报错、测试也不会红」的体验型退化，按仓库惯例钉源码。
 */
class Issue37to39ContractTest {

    private val archived = readSource("src/main/java/com/mistakebook/ui/settings/ArchivedItemsScreen.kt")
    private val dao = readSource("src/main/java/com/mistakebook/data/local/PomodoroDao.kt")
    private val settings = readSource("src/main/java/com/mistakebook/ui/settings/SettingsScreen.kt")
    private val genService = readSource("src/main/java/com/mistakebook/ui/chat/ChatGenerationService.kt")
    private val viewModel = readSource("src/main/java/com/mistakebook/ui/chat/ChatViewModel.kt")
    private val chatDao = readSource("src/main/java/com/mistakebook/data/local/ChatDao.kt")
    private val bubble = readSource("src/main/java/com/mistakebook/ui/chat/ChatMessageBubble.kt")
    private val list = readSource("src/main/java/com/mistakebook/ui/chat/ChatMessageList.kt")
    private val coordinator = readSource("src/main/java/com/mistakebook/ui/chat/AutoScrollCoordinator.kt")

    // ---------------- #37 ----------------

    @Test
    fun `番茄钟归档任务有找回入口且能恢复`() {
        assertTrue(
            "DAO 必须能列出归档任务",
            dao.contains("WHERE archived = 1")
        )
        assertTrue(
            "DAO 必须有恢复方法",
            dao.contains("SET archived = 0 WHERE id = :id")
        )
        assertTrue(
            "归档页必须有第三类：番茄钟任务",
            archived.contains("POMODORO_TASKS")
        )
        assertTrue(
            "归档任务行必须能恢复",
            archived.contains("container.pomodoroDao.unarchiveTask(task.id)")
        )
        assertTrue(
            "设置「已归档内容」必须有入口行",
            settings.contains("R.string.settings_archived_tasks")
        )
    }

    // ---------------- #38 ----------------

    @Test
    fun `生成图落库即刷新气泡图片`() {
        assertTrue(
            "生图那一轮就要落成附件，不能等 tool loop 结束",
            genService.contains("persistGeneratedImage(container, sessionId, assistantId, path)")
        )
        assertTrue(
            "chat_attachments 必须有会话级 Flow（observeMessages 盯不到这张表）",
            chatDao.contains("fun observeAttachmentsOfSession(")
        )
        assertTrue(
            "ViewModel 必须订阅附件变化并重载气泡图片",
            viewModel.contains("observeSessionAttachments(")
        )
        assertTrue(
            "终态快照覆盖 state 后必须补一次重载，否则会把刚加载的图抹掉",
            viewModel.contains("loadBubbleImages(finalState.messages)")
        )
    }

    // ---------------- #39 ----------------

    @Test
    fun `思考块流式期间也能收起`() {
        assertTrue(
            "折叠按钮不许再按 locked 禁用",
            !bubble.contains("enabled = !locked")
        )
        assertTrue(
            "用户手动动过折叠键后，流式结束不再自动收起",
            bubble.contains("userToggled")
        )
    }

    @Test
    fun `手指按下即交出滚动权`() {
        assertTrue(
            "列表必须在 Initial pass 监听按下（不等触摸 slop）",
            list.contains("PointerEventPass.Initial") && list.contains("onTouchDown()")
        )
        assertTrue(
            "协调器必须暴露按下/抬起钩子",
            coordinator.contains("fun onPointerDown()") && coordinator.contains("fun onPointerUp()")
        )
        assertTrue(
            "按住期间不许判位置（否则冻结/解冻抖动）",
            coordinator.contains("coordinator.pointerDown")
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