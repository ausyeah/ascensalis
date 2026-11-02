package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话级模型选择的持久化契约。
 *
 * 用户反馈「对话时不能换模型，也不能设置模型」的修复拆成三层，各有证据：
 * - **落库**：selectModel 必须按会话写 SettingsStore（源码契约——
 *   ViewModel 依赖链过重无法在纯 JVM 实例化，与 GradingContractTest 同理）；
 * - **回填**：进会话时必须读回并写进 selectedModel（源码契约）；
 * - **决策**：存了就用存的、没存落回默认（resolveSessionModel 纯函数，直接执行）。
 *
 * 持久化走 SettingsStore 的 per-session 键而不是 Room：
 * 老库迁移有崩溃史（HANDOFF §2 第 9 条），DataStore 无迁移负担。
 */
class ChatModelPersistenceTest {

    // ---------- 纯函数：回填决策 ----------

    @Test
    fun `存过模型名时回填用存的`() {
        assertEquals(
            "deepseek-r1",
            ChatViewModel.resolveSessionModel("deepseek-r1", "default-model")
        )
    }

    @Test
    fun `没存过时回填落回供应商默认`() {
        assertEquals(
            "default-model",
            ChatViewModel.resolveSessionModel(null, "default-model")
        )
    }

    @Test
    fun `存了空白串视同没存`() {
        assertEquals(
            "default-model",
            ChatViewModel.resolveSessionModel("", "default-model")
        )
        assertEquals(
            "default-model",
            ChatViewModel.resolveSessionModel("   ", "default-model")
        )
    }

    @Test
    fun `回填结果去掉首尾空白`() {
        assertEquals("gpt-4o", ChatViewModel.resolveSessionModel(" gpt-4o ", ""))
    }

    @Test
    fun `两处都没有时结果为空串`() {
        assertEquals("", ChatViewModel.resolveSessionModel(null, ""))
    }

    // ---------- 纯函数：模型过少的提示判定 ----------

    @Test
    fun `列表为空或只有一项时需要提示`() {
        assertTrue(needsModelHint(emptyList()))
        assertTrue(needsModelHint(listOf("gpt-4o")))
    }

    @Test
    fun `两个以上模型不需要提示`() {
        assertFalse(needsModelHint(listOf("gpt-4o", "gpt-4o-mini")))
    }

    // ---------- 源码契约：落库与回填必须存在 ----------

    @Test
    fun `selectModel 必须按会话落库`() {
        val src = readSource("ChatViewModel.kt", "src/main/java/com/mistakebook/ui/chat/ChatViewModel.kt")
        assertTrue(
            "selectModel 里必须调用 settingsStore.setChatModel 按会话持久化",
            Regex("""fun selectModel\(model: String\)""").find(src) != null &&
                Regex("""setChatModel\(sessionId, model\)""").find(src) != null
        )
    }

    @Test
    fun `进会话必须从 SettingsStore 读回模型`() {
        val src = readSource("ChatViewModel.kt", "src/main/java/com/mistakebook/ui/chat/ChatViewModel.kt")
        assertTrue(
            "init 里必须调用 settingsStore.getChatModel 回填",
            Regex("""getChatModel\(session\.id\)""").find(src) != null
        )
    }

    @Test
    fun `切换供应商必须清掉会话模型的落库`() {
        val src = readSource("ChatViewModel.kt", "src/main/java/com/mistakebook/ui/chat/ChatViewModel.kt")
        // selectProvider 清空 selectedModel 的同时要清 per-session 键，
        // 否则下次进会话又把旧供应商的模型名捡回来
        val fn = Regex("""fun selectProvider\(profileId: String\) \{[\s\S]*?\n    \}""").find(src)?.value ?: ""
        assertTrue("selectProvider 里必须清除 setChatModel", fn.contains("setChatModel(sessionId, \"\")"))
    }

    @Test
    fun `SettingsStore 的会话模型键与草稿同构`() {
        val src = readSource("SettingsStore.kt", "src/main/java/com/mistakebook/data/prefs/SettingsStore.kt")
        assertTrue(
            "SettingsStore 必须提供 chat_model_<sessionId> 键（前缀常量）",
            src.contains("""CHAT_MODEL_KEY_PREFIX = "chat_model_"""")
        )
        assertTrue("SettingsStore 必须提供 getChatModel", src.contains("suspend fun getChatModel(sessionId: Long)"))
        assertTrue("SettingsStore 必须提供 setChatModel", src.contains("suspend fun setChatModel(sessionId: Long, model: String)"))
    }

    @Test
    fun `会话模型不落 Room 的 ChatSession 字段`() {
        // ChatSession.model 的语义是「这条是哪个模型答的」历史快照，
        // 每轮生成都会被覆盖；会话当前选用哪个模型是另一个状态。
        // 两个状态塞同一段数据 = 两套真相互相覆盖，这里钉住不许回退。
        val src = readSource("ChatViewModel.kt", "src/main/java/com/mistakebook/ui/chat/ChatViewModel.kt")
        assertFalse(
            "selectModel 不得直接写 session.model（快照字段由生成服务维护）",
            Regex("""fun selectModel[\s\S]*?session\.copy\(model""").find(src) != null
        )
    }

    private fun readSource(name: String, vararg paths: String): String {
        // 测试进程的工作目录随 gradlew 启动位置变化：app/ 与项目根都试。
        val candidates = paths.toList() + paths.map { "app/$it" } + paths.map { "../$it" }
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 $name，已尝试：$candidates")
        return file.readText()
    }
}
