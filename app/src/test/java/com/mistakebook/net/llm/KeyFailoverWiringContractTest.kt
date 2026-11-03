package com.mistakebook.net.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 多 key 失败转移的**接线契约**守护。
 *
 * 失败转移的策略在 [KeyFailoverTest] 里测，但策略再对，只要哪条请求路径
 * 又自己按模型名挑 key（`resolveKey`），换 key 就等于没做——每次重发
 * 都拿回同一把废 key，而所有单测照样全绿。这种退化在 JVM 单测里看不见，
 * 只能钉源码。
 *
 * 钉的都是「一退就回到旧样子」的点：
 * 1. 请求构造必须由调用方传 key 进来，不许自己 resolveKey；
 * 2. 失败转移的入口必须真的接在每条请求路径上；
 * 3. 换 key 只发生在「一帧都没吐过」之后，否则用户会看到同一段话两遍；
 * 4. 设置页要把这个行为告诉用户（仓库规则：界面文案全部进 strings.xml）。
 */
class KeyFailoverWiringContractTest {

    @Test
    fun `请求构造必须由调用方给 key`() {
        val stream = readSource("app/src/main/java/com/mistakebook/net/llm/ChatCompletionStream.kt")

        assertTrue(
            "buildRequest 必须收 key 参数：换 key 重发时只换它",
            stream.contains("private fun buildRequest(profile: LlmProfile, key: String, request: ChatRequest, streaming: Boolean): Request")
        )
        assertFalse(
            "buildRequest 不许自行 resolveKey——那会让失败转移静默失效",
            stream.contains("profile.resolveKey(")
        )
        assertTrue(
            "鉴权头必须用传进来的这把 key",
            stream.contains(".header(\"Authorization\", \"Bearer \$key\")")
        )
    }

    @Test
    fun `非流式与流水线也都得走失败转移`() {
        val client = readSource("app/src/main/java/com/mistakebook/pipeline/LlmClient.kt")
        val stream = readSource("app/src/main/java/com/mistakebook/net/llm/ChatCompletionStream.kt")

        assertTrue(
            "LlmClient 必须引入失败转移",
            client.contains("import com.mistakebook.net.llm.withKeyFailover")
        )
        // 阶梯、纯文本、纯文本带图、一次性补全：四条路径各包一层
        assertTrue(
            "refineJson 的阶梯必须整段包进失败转移（一把新 key 值得把阶梯重走一遍）",
            client.contains("withKeyFailover(profile) { key ->")
        )
        assertTrue(
            "sendOnce 必须收 key 参数，否则换 key 传不进去",
            client.contains("private suspend fun sendOnce(\n        profile: LlmProfile,\n        key: String,")
        )
        assertFalse(
            "sendOnce 不许自行 resolveKey，否则换 key 传进去也白发",
            slice(client, "private suspend fun sendOnce(", "private fun recordUsage(")
                .contains("profile.resolveKey(")
        )
        assertTrue(
            "流式侧的一次性补全也要换 key（上下文压缩失败极难猜到是 key 的事）",
            stream.contains("withKeyFailover(profile) { key ->")
        )
    }

    @Test
    fun `只有探活与拉列表允许自己挑 key`() {
        val client = readSource("app/src/main/java/com/mistakebook/pipeline/LlmClient.kt")

        // 探活回答的是「首选那把 key 到底通不通」，换一把成功了反而掩盖
        // 「你日常在用的那把已经废了」；拉列表是逐把 key 主动探测，
        // 本来就该由调用方指定用哪把。除此之外一律不许自己 resolveKey。
        val probing = slice(client, "suspend fun listModels(", "suspend fun probeModel(") +
            slice(client, "suspend fun probeModel(", "suspend fun testConnection(") +
            slice(client, "suspend fun testConnection(", "private fun fail(kind")
        val resolveCalls = Regex("profile\\.resolveKey\\(").findAll(client).count()
        val probingCalls = Regex("profile\\.resolveKey\\(").findAll(probing).count()
        assertEquals(
            "resolveKey 只该出现在探活与拉列表里，别处自己挑 key 会让失败转移失效",
            probingCalls,
            resolveCalls
        )
    }

    @Test
    fun `流式换 key 只发生在还没吐字的时候`() {
        val stream = readSource("app/src/main/java/com/mistakebook/net/llm/ChatCompletionStream.kt")

        assertTrue(
            "换 key 的闸门必须是 totalEmitted == 0：吐过字再换，用户会看到同一段话两遍",
            stream.contains("if (totalEmitted == 0 && isKeyFailure(reason) && nextKey < keys.size)")
        )
        assertTrue(
            "换 key 是沿候选链往后走一格",
            stream.contains("keyIndex = nextKey")
        )
        assertTrue(
            "换 key 后降档开关要恢复：新 key 可能指向另一条渠道，上限与上一把不同",
            stream.contains("allowMaxTokensHalve = true,\n                    keyIndex = nextKey")
        )
    }

    @Test
    fun `候选链是唯一来源`() {
        val profile = readSource("app/src/main/java/com/mistakebook/data/prefs/LlmProfile.kt")

        assertTrue(
            "必须有候选链字段：一个模型只存一把 key 就没法失败转移",
            profile.contains("val modelKeyChain: Map<String, List<String>> = emptyMap()")
        )
        assertTrue(
            "keyCandidates 按模型给出全部可用 key",
            profile.contains("fun keyCandidates(model: String): List<LlmKeyEntry>")
        )
        assertTrue(
            "resolveKey 取链头，不另写一套挑选规则",
            profile.contains("fun resolveKey(model: String): String =\n        keyCandidates(model).firstOrNull()?.key.orEmpty()")
        )
        assertTrue(
            "探测时同一份数据生成两张表，设置页也必须两份一起写",
            readSource("app/src/main/java/com/mistakebook/ui/settings/SettingsProfileSections.kt")
                .let { it.contains("modelKeys = chain.mapValues { it.value.first() }") && it.contains("modelKeyChain = chain") }
        )
    }

    @Test
    fun `设置页要把自动换 key 告诉用户`() {
        val strings = readSource("app/src/main/res/values/strings.xml")

        assertTrue(
            "附加 Key 说明要提到失败后自动换下一把 Key（仓库规则：界面文案全部进 strings.xml）",
            strings.contains("会自动换下一把 Key 重试")
        )
        assertFalse(
            "不允许把这句话硬编码在 Kotlin 里绕开 strings.xml",
            readSource("app/src/main/java/com/mistakebook/ui/settings/SettingsProfileSections.kt")
                .contains("会自动换下一把 Key 重试")
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

    /** 取 [from] 到 [to] 之间的源码（都不含）。找不到边界时抛错，不静默给空串。 */
    private fun slice(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("源码里找不到 $from", start >= 0)
        val end = src.indexOf(to, start)
        assertTrue("源码里找不到 $from 之后的 $to", end > start)
        return src.substring(start, end)
    }
}
