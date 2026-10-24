package com.mistakebook.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 网络代理模式的键值映射。
 *
 * 默认必须是 [NetworkMode.AUTO]：用户诉求是「mineru 走代理就会说没网」，
 * 升级上来的老用户没有任何已存值，此时 AUTO（MinerU 直连）正是那个修复。
 */
class NetworkModeTest {

    @Test
    fun `键值映射正确`() {
        assertEquals(NetworkMode.SYSTEM, NetworkMode.fromKey("system"))
        assertEquals(NetworkMode.DIRECT, NetworkMode.fromKey("direct"))
        assertEquals(NetworkMode.AUTO, NetworkMode.fromKey("auto"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(NetworkMode.DIRECT, NetworkMode.fromKey("DIRECT"))
        assertEquals(NetworkMode.SYSTEM, NetworkMode.fromKey("System"))
    }

    @Test
    fun `缺省走自动`() {
        assertEquals(NetworkMode.AUTO, NetworkMode.fromKey(null))
        assertEquals(NetworkMode.AUTO, NetworkMode.fromKey(""))
        assertEquals(NetworkMode.AUTO, NetworkMode.fromKey("不认识的值"))
    }
}
