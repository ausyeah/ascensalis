package com.mistakebook.net

import com.mistakebook.data.prefs.NetworkMode
import java.io.IOException
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 全局网络路由：决定每个请求走系统代理还是直连。
 *
 * ## 为什么用自定义 ProxySelector，而不是换 OkHttpClient
 *
 * 客户端（Retrofit / ChatCompletionStream）在 AppContainer 里一次性建好，
 * 设置页改了代理模式不可能把整套客户端重建一遍。而 OkHttp 的
 * `proxySelector` 是**每次建连前**回调的——把决策放进 [select]，
 * 改一个 @Volatile 字段，下一个请求就走新规则，无需重建任何东西。
 *
 * ## 三种模式
 *
 * - [NetworkMode.SYSTEM]：委托系统默认选择器（等价于不设置）；
 * - [NetworkMode.DIRECT]：一律 `NO_PROXY`；
 * - [NetworkMode.AUTO]：[DIRECT_HOSTS] 里的国内服务直连，其余走系统。
 */
object NetworkRouter {

    @Volatile
    var mode: NetworkMode = NetworkMode.AUTO

    /**
     * 自动模式下强制直连的域名（MinerU 云服务）。
     *
     * MinerU（mineru.net / openxlab）部署在国内，用户挂了代理后
     * 这些请求被塞进代理出口，常见结果是「能刷微博但识别一直转圈」。
     */
    val DIRECT_HOSTS: Set<String> = setOf(
        "mineru.net",
        "mineru.openxlab.org.cn",
        "cdn-mineru.openxlab.org.cn"
    )

    /** 装到 OkHttpClient 上的选择器。 */
    val proxySelector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<java.net.Proxy> {
            val host = uri?.host?.lowercase().orEmpty()
            if (host.isEmpty()) return listOf(java.net.Proxy.NO_PROXY)
            val direct = when (mode) {
                NetworkMode.DIRECT -> true
                NetworkMode.AUTO -> isDirectHost(host)
                NetworkMode.SYSTEM -> false
            }
            if (direct) return listOf(java.net.Proxy.NO_PROXY)
            val system = runCatching { ProxySelector.getDefault() }.getOrNull()
                ?: return listOf(java.net.Proxy.NO_PROXY)
            return runCatching { system.select(uri) }.getOrNull()
                ?: listOf(java.net.Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            runCatching { ProxySelector.getDefault()?.connectFailed(uri, sa, ioe) }
        }
    }

    private fun isDirectHost(host: String): Boolean =
        DIRECT_HOSTS.any { host == it || host.endsWith(".$it") }
}
