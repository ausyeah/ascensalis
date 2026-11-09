package com.mistakebook.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探活判定（v0.0.144）的单测。
 *
 * 背景：用户换了个 reasoning 型模型（deepseek-v4 / glm-5 / kimi-k2…），
 * 点「测试大模型」一律显示「无响应」，同一个 Key 手动发请求却完全正常。
 * 真因是探活只给了 1 个 token——模型把预算全花在思考上，正文为空，
 * 于是被报成连接失败。判定必须认下「只有思考」也是通的。
 */
class LlmProbeVerdictTest {

    @Test
    fun `有正文就是通`() {
        assertEquals("pong", probeVerdict("pong", ""))
    }

    @Test
    fun `只有思考也算通`() {
        val verdict = probeVerdict("", "The user said ping; I should answer pong.")
        assertTrue("只有思考过程也要算连上了：$verdict", verdict != null)
        assertTrue(verdict!!.contains("思考"))
    }

    @Test
    fun `正文优先于思考`() {
        assertEquals("OK", probeVerdict("OK", "先想一下…"))
    }

    @Test
    fun `既没正文也没思考才是无响应`() {
        assertNull(probeVerdict("", ""))
        assertNull(probeVerdict("   ", "  "))
    }
}
