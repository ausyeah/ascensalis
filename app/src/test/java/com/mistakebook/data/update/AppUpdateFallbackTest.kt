package com.mistakebook.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 更新检查的双通道（v0.0.122）。
 *
 * 用户在 v0.0.120 上看到「检查失败：GitHub 返回 403」：未认证的 GitHub API
 * 限额是每 IP 每小时 60 次，手机运营商 NAT 下几十个人共享一个出口，
 * 光靠主通道迟早撞墙。现在主通道（API JSON）失败时自动切备用通道
 * （`releases/latest` 页面的 302 重定向，不占 API 限额），
 * 两条都挂才报错。这里钉住三件不能退化的事。
 */
class AppUpdateFallbackTest {

    @Test
    fun `从重定向后的地址解析版本号`() {
        val base = "https://github.com/ausyeah/ascensalis"
        assertEquals(
            "v0.0.121",
            AppUpdate.parseTagFromRedirectUrl("$base/releases/tag/v0.0.121")
        )
        assertEquals(
            "v0.0.121",
            AppUpdate.parseTagFromRedirectUrl("$base/releases/tag/v0.0.121/")
        )
        assertEquals(
            "v1.2.3",
            AppUpdate.parseTagFromRedirectUrl("$base/releases/tag/v1.2.3?utm=x")
        )
        assertEquals(
            "0.0.121",
            AppUpdate.parseTagFromRedirectUrl("$base/releases/tag/0.0.121")
        )
    }

    @Test
    fun `没发生重定向或不是可比较版本时返回 null`() {
        val base = "https://github.com/ausyeah/ascensalis"
        assertNull(
            "没重定向就停在 releases/latest，解析不出版本",
            AppUpdate.parseTagFromRedirectUrl("$base/releases/latest")
        )
        assertNull(
            "预发布号不许当可比较版本",
            AppUpdate.parseTagFromRedirectUrl("$base/releases/tag/v0.1.0-beta1")
        )
        assertNull(AppUpdate.parseTagFromRedirectUrl("$base/"))
    }

    @Test
    fun `备用通道的 APK 地址与 CI 产物命名一致`() {
        assertEquals(
            "https://github.com/ausyeah/ascensalis/releases/download/v0.0.121/" +
                "mistakebook-v0.0.121-android.apk",
            AppUpdate.apkDownloadUrl("v0.0.121")
        )
    }

    @Test
    fun `主通道失败先切备用通道，两条都挂才报错`() {
        // Room/JVM 无法起真实网络，按仓库惯例钉源码：latestRelease 必须
        // 先试 API、catch 后切 redirect、最终错误信息里带主通道原因。
        val src = readSource("src/main/java/com/mistakebook/data/update/AppUpdate.kt")
        val body = src.substringAfter("suspend fun latestRelease(")
            .substringBefore("private suspend fun latestReleaseViaApi")

        assertTrue(
            "主通道失败必须切备用通道",
            body.contains("latestReleaseViaRedirect(currentVersion)")
        )
        assertTrue(
            "两条都挂时错误信息要带主通道原因，用户看到的不该是干巴巴的 403",
            body.contains("apiError.message")
        )
        assertTrue(
            "备用通道必须走 releases/latest 页面而不是 api.github.com",
            src.contains("RELEASES_LATEST_PAGE = \"https://github.com/\$REPO/releases/latest\"")
        )
        assertTrue(
            "两条通道都要显式带 User-Agent（GitHub 对无 UA 请求直接 403）",
            src.contains(".header(\"User-Agent\", USER_AGENT)")
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
}
