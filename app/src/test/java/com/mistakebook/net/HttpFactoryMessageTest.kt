package com.mistakebook.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException

class HttpFactoryMessageTest {

    @Test
    fun `non primitive top level message does not abort extraction`() {
        val body = """{"msg":{"detail":"nested"},"message":"usable message"}"""
        assertEquals("usable message", HttpFactory.extractServerMessage(body))
    }

    @Test
    fun `nested error message is extracted`() {
        val body = """{"error":{"message":"request rejected"}}"""
        assertEquals("request rejected", HttpFactory.extractServerMessage(body))
    }

    @Test
    fun `non primitive nested error message returns empty`() {
        val body = """{"error":{"message":["unexpected"]}}"""
        assertEquals("", HttpFactory.extractServerMessage(body))
    }

    // ---- v0.0.147：异常类失败不再吞掉真实原因 ----

    @Test
    fun `连不上归类为网络错误且带上异常信息`() {
        val error = HttpFactory.throwableError(ConnectException("Failed to connect to /60.205.247.57:38421"))
        assertEquals(ApiErrorKind.NETWORK, error.kind)
        assertTrue("真实原因必须在 detail 里", error.detail.contains("60.205.247.57"))
    }

    @Test
    fun `DNS 失败归类为网络错误`() {
        assertEquals(ApiErrorKind.NETWORK, HttpFactory.throwableError(UnknownHostException("relay.local")).kind)
    }

    @Test
    fun `message 为空时退回异常类名`() {
        val error = HttpFactory.throwableError(ConnectException())
        assertTrue("不能是空 detail，否则 UI 又会只剩 UNKNOWN", error.detail.isNotBlank())
        assertEquals("ConnectException", error.detail)
    }

    @Test
    fun `反序列化失败归类为响应格式不对而不是未知`() {
        // 典型场景：代理/VPN 拦截后返回 HTML 错误页（HTTP 200 + 非 JSON）
        val error = HttpFactory.throwableError(
            kotlinx.serialization.SerializationException("Expected start of the object '{', but had '<'")
        )
        assertEquals(ApiErrorKind.BAD_RESPONSE, error.kind)
        assertTrue(error.detail.contains("Expected start"))
    }
}
