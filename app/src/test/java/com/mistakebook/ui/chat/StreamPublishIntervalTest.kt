package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式发布的节流曲线与「翻上去别动」冻结闸（v0.0.96）。
 *
 * 用户原话：「流式输出查看思考内容的时候仍然会一抽一抽的」，
 * 并点名要学 [NovelForge](https://github.com/ausyeah/NovelForge) 的流式控制。
 *
 * 那边踩过的两个坑，这里直接照抄结论：
 * 1. **固定间隔必然「一抽一抽」**：220ms = 4.5Hz，reverseLayout 下每发布一次
 *    整个视口平移一次，眼睛读到的就是 4~5 次离散跳变；
 * 2. **冻结只对渲染层有效还不够**：列表在 20Hz 长高时，就算单次发布很便宜，
 *    上面所有内容也会被反复顶走。
 */
class StreamPublishIntervalTest {

    @Test
    fun `短回答保持全速`() {
        // 跟手是底线：「模型秒回」却看着像卡了一下，比慢更难受
        assertEquals(50L, streamPublishIntervalMs(0))
        assertEquals(50L, streamPublishIntervalMs(200))
        assertEquals(50L, streamPublishIntervalMs(400))
    }

    @Test
    fun `间隔随长度变慢且单调`() {
        val samples = listOf(0, 500, 1000, 2000, 4000, 8000).map { streamPublishIntervalMs(it) }
        samples.zipWithNext { a, b -> assertTrue("$a -> $b 没有变慢", b > a) }
    }

    @Test
    fun `长输出封顶在可读的速度上`() {
        // 150ms ≈ 6.7Hz。再慢就是肉眼可见的一跳一跳；
        // 8000 字以上的回答正好会顶到这条上限
        assertEquals(150L, streamPublishIntervalMs(8000))
        assertEquals(150L, streamPublishIntervalMs(60_000))
        assertTrue(
            "任何长度都不许慢过 150ms",
            (5000..50_000 step 997).all { streamPublishIntervalMs(it) <= 150L }
        )
    }

    @Test
    fun `曲线必须是思考与正文之和的函数`() {
        val service = readSource("src/main/java/com/mistakebook/ui/chat/ChatGenerationService.kt")
        assertTrue(
            "节流要按「思考+正文」总长度取值——只看正文的话，长思考仍会按最快速率刷",
            service.contains("streamPublishIntervalMs(rounds.answer.length + rounds.thinking.length)")
        )
        assertFalse(
            "固定 220ms 的旧节流必须删掉",
            service.contains("PREVIEW_THROTTLE_MS")
        )
    }

    @Test
    fun `冻结闸必须先判冻结再盖状态`() {
        val vm = readSource("src/main/java/com/mistakebook/ui/chat/ChatViewModel.kt")
        val gate = vm.indexOf("if (streamFrozen) {")
        val apply = vm.indexOf("applyStreamingSnapshot(snap)")
        assertTrue("收集器里必须有冻结闸", gate > 0)
        assertTrue(
            "冻结判断必须先于应用快照——先应用再判断，冻结就是句空话",
            gate < apply
        )
        assertTrue(
            "解冻必须补帧，否则冻结期攒下的内容要等下一个窗口才出现",
            vm.contains("applyStreamingSnapshot(held)")
        )
        assertTrue(
            "终态快照不许被冻结——可重试/被截断那几行状态晚一帧就丢",
            vm.indexOf("if (snap.finished)") < gate
        )
    }

    @Test
    fun `界面必须用触底跟随状态驱动冻结`() {
        val screen = readSource("src/main/java/com/mistakebook/ui/chat/ChatScreen.kt")
        assertTrue(
            "ChatScreen 必须把「是否跟随」接到冻结闸上",
            screen.contains("viewModel.setStreamFrozen(!coordinator.following)")
        )
    }

    private fun readSource(path: String): String {
        val candidates = listOf(
            java.io.File(path),
            java.io.File("../$path"),
            java.io.File(path.removePrefix("app/")),
            java.io.File("../" + path.removePrefix("app/"))
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalArgumentException("Cannot find file: $path among $candidates")
        return file.readText()
    }
}