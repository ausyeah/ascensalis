package com.mistakebook.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 循环输出检测（v0.0.135，用户：「防止循环输出的小心思，循环输出就会
 * 触发中断」）的纯函数测试。
 *
 * 钉两类行为：① 真打转必须抓到；② 正常文本（含合法的两轮重复、
 * 变长列举、跨段复述）绝不能误杀——误杀会把好端端的回答掐断，
 * 比漏放严重得多。
 */
class ChatLoopGuardTest {

    // ---- 真循环：必须抓到 ----

    @Test
    fun `同一短句连续四轮重复判定为循环`() {
        val unit = "根据题意可得，"
        val text = "解答如下：" + unit.repeat(6)
        assertTrue(ChatLoopGuard.isLooping(text))
    }

    @Test
    fun `长片段重复也能抓到（周期接近上限）`() {
        val unit = "我们不妨设 x 为满足条件的最小正整数，然后代入原式化简得到恒等式成立。"
        val text = unit.repeat(5)
        assertTrue(ChatLoopGuard.isLooping(text))
    }

    @Test
    fun `尾部恰好四轮重复即触发`() {
        val unit = "第一步我们看"
        // 恰好 4 个周期（每个 6 字 = MIN_PERIOD）落在尾部窗口
        val text = "开头正常内容" + unit.repeat(4)
        assertTrue(ChatLoopGuard.isLooping(text))
    }

    // ---- 正常文本：绝不能误杀 ----

    @Test
    fun `两轮重复不算循环`() {
        // 正常文本里「好的好的」「那么那么」很常见
        val text = "这道题的答案是 C。" + "也就是说，".repeat(3) + "最终选 C。"
        // 三轮短重复（不足四轮）不该触发
        assertFalse(ChatLoopGuard.isLooping("答：选B。" + "因此可得".repeat(3) + "故选 B。"))
    }

    @Test
    fun `太短的片段（低于最小周期）不误报`() {
        // 单个标点/字重复：正常排版里也可能出现
        assertFalse(ChatLoopGuard.isLooping("哈哈哈哈哈哈哈哈哈哈哈哈"))
    }

    @Test
    fun `正常数学解答不误报`() {
        val answer = """
            设 f(x) = x^2 + 2x，对称轴 x = -1。
            因为 -1 在区间 [-3, 1] 内，所以最小值在顶点取得。
            f(-1) = 1 - 2 = -1。
            最大值在端点 x = 1 处取得，f(1) = 1 + 2 = 3。
        """.trimIndent()
        assertFalse(ChatLoopGuard.isLooping(answer))
    }

    @Test
    fun `文本过短一律不算循环`() {
        assertFalse(ChatLoopGuard.isLooping(""))
        assertFalse(ChatLoopGuard.isLooping("好的"))
        assertFalse(ChatLoopGuard.isLooping("重复".repeat(3)))
    }

    // ---- 增量检测器：每 CHECK_STEP 才查一次，命中只报一次 ----

    @Test
    fun `增量检测在攒够一步后才判定`() {
        val detector = ChatLoopDetector()
        val unit = "根据题意可得，"
        // 喂到不足 CHECK_STEP(64)：不报（还没到检查点）
        assertFalse(detector.observe("答：" + unit))
        // 喂够一步且已成循环：报（10×7+2=72 ≥ 64）
        assertTrue(detector.observe("答：" + unit.repeat(10)))
    }

    @Test
    fun `命中后同一实例只报一次`() {
        val detector = ChatLoopDetector()
        val unit = "根据题意可得，"
        val looping = "答：" + unit.repeat(10)
        assertTrue(detector.observe(looping))
        // 继续喂更多重复内容，不再重复触发（调用方已中断）
        assertFalse(detector.observe(looping + unit.repeat(5)))
    }

    @Test
    fun `正常增长的回答一路不误报`() {
        val detector = ChatLoopDetector()
        var text = ""
        val sentences = listOf(
            "设函数 f(x) 定义在 R 上。",
            "由题意，f(x) 满足 f(x+2) = f(x)。",
            "因此 f 是周期为 2 的函数。",
            "在区间 [0,2] 上单调递增。",
            "所以 f(3) = f(1)。",
            "结合 f(1) = 2 得 f(3) = 2。"
        )
        for (sentence in sentences) {
            text += sentence
            assertEquals("误报于：$text", false, detector.observe(text))
        }
    }
}
