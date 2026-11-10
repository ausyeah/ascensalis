package com.mistakebook.net

import com.mistakebook.data.prefs.NetworkMode
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动模式下「哪些 host 直连」的单测。
 *
 * 钉的是**用户自建中转站的连通性**：裸 IP（如 `http://60.205.247.57:38421`）
 * 以前被 `isDirectHost` 判成「非直连」，在 AUTO 模式下被塞进系统代理——许多
 * 代理规则对裸 IP + 非标端口的 http 服务处理不完善，连接直接失败，用户只看到
 * 一个「UNKNOWN」。自建服务理应直连，这里把这条判据钉死。
 */
class NetworkRouterTest {

    @Test
    fun `自建中转站的裸 IP 直连`() {
        NetworkRouter.mode = NetworkMode.AUTO
        assertTrue("公网裸 IP 应直连", NetworkRouter.isDirectHost("60.205.247.57"))
        assertTrue("非标端口不影响 host 判定", NetworkRouter.isDirectHost("123.45.67.89"))
    }

    @Test
    fun `内网与回环本来就直连`() {
        NetworkRouter.mode = NetworkMode.AUTO
        assertTrue(NetworkRouter.isDirectHost("localhost"))
        assertTrue(NetworkRouter.isDirectHost("127.0.0.1"))
        assertTrue(NetworkRouter.isDirectHost("192.168.1.10"))
        assertTrue(NetworkRouter.isDirectHost("10.0.0.5"))
    }

    @Test
    fun `国内域名与已登记域名直连`() {
        NetworkRouter.mode = NetworkMode.AUTO
        assertTrue("以 .cn 结尾的直连", NetworkRouter.isDirectHost("mineru.net"))
        assertTrue(NetworkRouter.isDirectHost("api.deepseek.com"))
        assertTrue(NetworkRouter.isDirectHost("token.sensenova.cn"))
    }
}
