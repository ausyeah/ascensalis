package com.mistakebook.data.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 记忆与标题的接线（v0.0.108）。
 *
 * 纯函数都测过了，这里钉的是「有没有被真正接上」——记忆写进库却忘了拼进
 * 系统提示词，表现就是「我明明记了，它还是不知道」；标题生成器写好了却
 * 没人调用，表现就是历史列表永远还是第一句话。
 */
class MemoryWiringTest {

    @Test
    fun `记忆必须拼进系统提示词`() {
        val repo = readSource("src/main/java/com/mistakebook/data/repos/ChatRepository.kt")
        assertTrue(
            "buildContext 要把记忆块拼进 system 上下文（v0.0.112 单文档注入）",
            repo.contains("MemoryPrompts.injectDocument(memoryText())")
        )
        // 放在题目上下文之前：越一般的背景越靠前，模型读起来层次才对
        val memoryAt = repo.indexOf("MemoryPrompts.injectDocument")
        val questionAt = repo.indexOf("append(buildQuestionContext(questionId).orEmpty())")
        assertTrue(memoryAt in 1 until questionAt)
    }

    @Test
    fun `记忆改写走单文档模型`() {
        val repo = readSource("src/main/java/com/mistakebook/data/repos/MemoryRepository.kt")
        assertTrue("依赖收窄到 SettingsStore", repo.contains("settingsStore: SettingsStore"))
        assertFalse("旧的记忆条目表已退役", repo.contains("MemoryDao"))
        // 两条入口共用一次模型改写：当前全文 + 请求 → 全新全文
        assertTrue("用户请求要走改写", repo.contains("MemoryPrompts.rewriteUserPrompt(documentNow(), request)"))
        assertTrue("整理也要走改写", repo.contains("MemoryPrompts.ORGANIZE_REQUEST"))
        assertTrue("改写要走 completeOnce", repo.contains("stream.completeOnce"))
        // 模型返回「（空）」是清空记忆的约定
        assertTrue(repo.contains("（空）"))
    }

    @Test
    fun `记忆页输入框发的是改写请求`() {
        val screen = readSource("src/main/java/com/mistakebook/ui/settings/MemoryScreen.kt")
        assertTrue("输入框要调 applyRequest", screen.contains("container.memoryRepository.applyRequest(request, profile)"))
        assertTrue("整理按钮要走 organize", screen.contains("container.memoryRepository.organize(profile)"))
        assertTrue("全文要展示出来", screen.contains("container.memoryRepository.document"))
    }

    @Test
    fun `记忆库页面必须接在设置和路由上`() {
        val settings = readSource("src/main/java/com/mistakebook/ui/settings/SettingsScreen.kt")
        assertTrue("设置里要有入口", settings.contains("onOpenMemory"))
        assertTrue(settings.contains("R.string.settings_memory_entry"))

        val nav = readSource("src/main/java/com/mistakebook/MistakeBookNavHost.kt")
        assertTrue("路由要存在", nav.contains("const val MEMORY = \"memory\""))
        assertTrue("路由要指向页面", nav.contains("com.mistakebook.ui.settings.MemoryScreen("))
        assertTrue("设置页回调要接上", nav.contains("onOpenMemory = { navController.navigate(Routes.MEMORY) }"))
    }

    @Test
    fun `整理记忆用默认模型`() {
        val screen = readSource("src/main/java/com/mistakebook/ui/settings/MemoryScreen.kt")
        assertTrue(
            "整理要走当前生效的默认模型",
            screen.contains("container.settingsStore.snapshotNow().activeProfile")
        )
        assertTrue(screen.contains("container.memoryRepository.organize(profile)"))
    }

    @Test
    fun `首条消息才生成标题`() {
        val vm = readSource("src/main/java/com/mistakebook/ui/chat/ChatViewModel.kt")
        assertTrue(
            "只有首条用户消息才起标题，后面每轮都改会让标题一直跳",
            vm.contains("_state.value.messages.none { it.role == ChatRole.USER }")
        )
        assertTrue("要真的调用生成器", vm.contains("titleGenerator.generate(effectiveProfile, text)"))
        assertTrue(
            "生成失败不能影响发送",
            vm.contains("runCatching") && vm.contains("repository.applyGeneratedTitle(sessionId, generated)")
        )
        val repo = readSource("src/main/java/com/mistakebook/data/repos/ChatRepository.kt")
        assertTrue("手动改名过的会话不许被覆盖", repo.contains("if (session.titleLocked || title.isBlank()) return"))
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