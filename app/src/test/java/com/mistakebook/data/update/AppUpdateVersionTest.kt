package com.mistakebook.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 版本号解析与比较（v0.0.99）。
 *
 * 这是更新功能的全部判断依据：解析错一个数字，用户就会被告知
 * 「有新版」（其实没有，白下一次 20MB），或者被告知「已是最新」
 * （其实有新版，怎么都更新不了）。
 *
 * 两条底线：
 * - 解析不出来一律当「相同」——**宁可漏报，不可误报**；
 * - Release 的 tag 带前缀 "v"，应用里的 versionName 不带，比较前要统一。
 */
class AppUpdateVersionTest {

    @Test
    fun `解析带 v 前缀与不带前缀的版本号`() {
        assertEquals(listOf(0, 0, 98), AppUpdate.parseVersion("v0.0.98")?.toList())
        assertEquals(listOf(0, 0, 98), AppUpdate.parseVersion("0.0.98")?.toList())
        assertEquals(listOf(0, 0, 98), AppUpdate.parseVersion("  v0.0.98  ")?.toList())
        assertEquals(listOf(12, 34, 56), AppUpdate.parseVersion("12.34.56")?.toList())
    }

    @Test
    fun `解析不了的版本号返回 null`() {
        // 预发布号、带构建号、段数不对、空串：都不是能安全比较的版本
        assertNull(AppUpdate.parseVersion("v0.1.0-beta1"))
        assertNull(AppUpdate.parseVersion("0.0.98+12"))
        assertNull(AppUpdate.parseVersion("0.1"))
        assertNull(AppUpdate.parseVersion(""))
        assertNull(AppUpdate.parseVersion("最新"))
    }

    @Test
    fun `比较版本号大小`() {
        assertEquals(1, AppUpdate.compareVersions("0.0.99", "0.0.98"))
        assertEquals(-1, AppUpdate.compareVersions("0.0.97", "0.0.98"))
        assertEquals(0, AppUpdate.compareVersions("0.0.98", "0.0.98"))
        // 次版本与主版本也要比出来，否则 0.1.0 会被当成不比 0.0.98 新
        assertEquals(1, AppUpdate.compareVersions("0.1.0", "0.0.98"))
        assertEquals(1, AppUpdate.compareVersions("1.0.0", "0.9.9"))
        assertEquals(-1, AppUpdate.compareVersions("0.9.9", "1.0.0"))
    }

    @Test
    fun `比较时忽略 v 前缀`() {
        // Release 的 tag 带 v，应用里的 versionName 不带——不统一就会判成「有更新」
        assertEquals(0, AppUpdate.compareVersions("v0.0.98", "0.0.98"))
        assertEquals(1, AppUpdate.compareVersions("v0.0.99", "0.0.98"))
    }

    @Test
    fun `解析失败时一律当相同不误报更新`() {
        // 误报的代价是用户下载一个同版本包还可能把当前版本覆盖掉；
        // 漏报的代价只是「少提示一次更新」，两边不对等。
        assertEquals(0, AppUpdate.compareVersions("garbage", "0.0.98"))
        assertEquals(0, AppUpdate.compareVersions("0.0.99", "garbage"))
        assertEquals(0, AppUpdate.compareVersions("garbage", "garbage"))
    }
}