package com.mistakebook.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 公式渲染差分与「渲染循环不随流式文本重启」的契约。
 *
 * 流式「一卡一卡」的根因是渲染协程挂在 LaunchedEffect(mathRenderer, requests)
 * 上：流式每长一段文本 effect 就被取消重启，正在进行的 WebView 渲染批次
 * 整批作废。修复后 requests 变化只更新队列，渲染循环单独长驻。
 * 修复本身是 Compose 结构（纯 JVM 测不到协程编排），能钉住的是：
 * - 差分纯函数 [mathPendingOf] 的语义（直接执行）；
 * - effect 的挂载键不再包含 requests（源码契约——一旦有人把它改回去，
 *   卡顿会原样回归）。
 */
class MathPendingTest {

    private fun req(key: String, latex: String = key, display: Boolean = false) =
        Triple(key, latex, display)

    // ---------- 差分纯函数 ----------

    @Test
    fun `全部未渲染时返回全部请求`() {
        val requests = listOf(req("i:a"), req("i:b"), req("d:c"))
        assertEquals(requests, mathPendingOf(requests, emptyMap<String, Any?>()))
    }

    @Test
    fun `已渲染的条目不再排队`() {
        val requests = listOf(req("i:a"), req("i:b"), req("d:c"))
        val rendered = mapOf<String, Any?>("i:b" to Any())
        assertEquals(listOf(req("i:a"), req("d:c")), mathPendingOf(requests, rendered))
    }

    @Test
    fun `渲染失败的条目也不重排队`() {
        // 失败在 renderedMath 里是 null：差分只看「处理过没有」，
        // 重试是渲染循环 attempt 分支的职责——差分若把失败项捞回来，
        // 流式期间每帧都会重排整批失败公式，WebView 永远轮不到空闲
        val requests = listOf(req("i:a"), req("i:bad"))
        val rendered = mapOf<String, Any?>("i:bad" to null)
        assertEquals(listOf(req("i:a")), mathPendingOf(requests, rendered))
    }

    @Test
    fun `全部已渲染时返回空`() {
        val requests = listOf(req("i:a"), req("d:b"))
        val rendered = mapOf<String, Any?>("i:a" to Any(), "d:b" to null)
        assertEquals(emptyList<Triple<String, String, Boolean>>(), mathPendingOf(requests, rendered))
    }

    @Test
    fun `保持请求顺序`() {
        val requests = listOf(req("d:c"), req("i:a"), req("i:b"))
        val rendered = mapOf<String, Any?>("i:a" to Any())
        assertEquals(listOf(req("d:c"), req("i:b")), mathPendingOf(requests, rendered))
    }

    // ---------- 源码契约：渲染循环不得随 requests 重启 ----------

    @Test
    fun `渲染 effect 只挂在 mathRenderer 上`() {
        val src = readSource("RichText.kt", "src/main/java/com/mistakebook/ui/common/RichText.kt")
        assertTrue(
            "渲染循环必须挂在 LaunchedEffect(mathRenderer) 上（mathQueue 队列模式）",
            src.contains("LaunchedEffect(mathRenderer)")
        )
        assertFalse(
            "渲染 effect 的键不得包含 requests——流式文本一变就取消重启，" +
                "正在进行的 WebView 渲染整批作废（一卡一卡的根因）",
            src.contains("LaunchedEffect(mathRenderer, requests)")
        )
    }

    @Test
    fun `requests 变化经队列合并不直接触发渲染`() {
        val src = readSource("RichText.kt", "src/main/java/com/mistakebook/ui/common/RichText.kt")
        assertTrue(
            "requests 必须经 MutableStateFlow 进渲染循环（StateFlow 天然 conflated：处理完当前批必读最新值，积压中间帧自动被覆盖）",
            src.contains("mathQueue.collect")
        )
        assertTrue(
            "requests 更新必须走 SideEffect（同步赋值、零打断）",
            src.contains("SideEffect { mathQueue.value = requests }")
        )
        // conflate() 在 StateFlow 上是无效调用（kotlinx.coroutines 已弃用并标 error 级），
        // 钉住不许加回来
        assertFalse(
            "StateFlow 上不得调用 conflate()（无效且已被协程库弃用）",
            src.contains("conflate")
        )
    }

    private fun readSource(name: String, vararg paths: String): String {
        val candidates = paths.toList() + paths.map { "app/$it" } + paths.map { "../$it" }
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 $name，已尝试：$candidates")
        return file.readText()
    }
}
