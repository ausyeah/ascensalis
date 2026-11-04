package com.mistakebook.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新包的「下载 → 跳系统安装器」流程（v0.0.105）。
 *
 * 用户报的两个问题，其实是同一件事的两面：
 *
 * 1. **安卓不允许应用静默安装另一个应用**。这不是权限能绕过的东西，唯一正解是
 *    把包交给系统安装器——所以「下载完自动跳转安装」不是偷懒，是这道限制下
 *    用户最省事的路径。
 * 2. **跳去安装器再回来，进度归零、又要重下**。原因是下载和「已下好」都记在
 *    Composable 的 `remember` 里：安装器是另一个 Activity，回来时页面重建，
 *    状态就没了，于是又回到「下载并安装」。
 *
 * 所以这里钉住的是：状态活在进程里（AppUpdate）而不是页面里，已下好的包能从
 * 磁盘接回来，同一个包绝不让人下第二遍，并且自动跳转安装只发生一次
 * （否则会从安装器被反复弹走）。
 */
class AppUpdateInstallFlowTest {

    @Test
    fun `安装包按版本分文件名`() {
        assertEquals("app-update-0.0.105.apk", AppUpdate.apkFileName("0.0.105"))
        // 换版本就是另一个文件：绝不能拿上一版下好的包去装
        assertNotEquals(
            AppUpdate.apkFileName("0.0.104"),
            AppUpdate.apkFileName("0.0.105")
        )
    }

    @Test
    fun `下载状态活在进程里而不是页面里`() {
        val source = readSource("src/main/java/com/mistakebook/data/update/AppUpdate.kt")
        assertTrue(
            "进度必须是 StateFlow，页面重建后才能接上",
            source.contains("val state: StateFlow<UpdateState>")
        )
        assertTrue("下载跑在应用级作用域里", source.contains("private val downloadScope"))
        assertFalse("页面作用域一销毁下载就没了，不许再交给界面跑", source.contains("fun download("))
    }

    @Test
    fun `下好之后自动跳转安装器`() {
        val ui = readSource("src/main/java/com/mistakebook/ui/settings/SettingsAboutSection.kt")
        assertTrue("下好要自动拉起安装器", ui.contains("AppUpdate.requestInstall(context, it.version, it.file, auto = true)"))
        assertTrue("自动跳转必须挂在 Ready 状态上", ui.contains("UpdateState.Ready"))
    }

    @Test
    fun `自动跳转同一版本只拉一次`() {
        val source = readSource("src/main/java/com/mistakebook/data/update/AppUpdate.kt")
        // 不做这个闸门：从安装器返回 → 页面重建 → 自动逻辑再弹一次 → 用户回不来
        assertTrue("必须记住自动拉过的版本", source.contains("private var autoInstalledVersion"))
        assertTrue(
            "同一版本不再自动拉起",
            source.contains("if (autoInstalledVersion == version) return")
        )
    }

    @Test
    fun `授权回来接着装不用再点一次`() {
        val source = readSource("src/main/java/com/mistakebook/data/update/AppUpdate.kt")
        assertTrue("要记住在等哪个版本的授权", source.contains("private var awaitingPermissionVersion"))
        assertTrue("授权后自动续装", source.contains("fun installAfterPermission(context: Context)"))

        val ui = readSource("src/main/java/com/mistakebook/ui/settings/SettingsAboutSection.kt")
        assertTrue(
            "回到前台要检查一次授权结果",
            ui.contains("Lifecycle.Event.ON_RESUME") &&
                ui.contains("AppUpdate.installAfterPermission(context)")
        )
    }

    @Test
    fun `已下好的包从磁盘接回来不重下`() {
        val source = readSource("src/main/java/com/mistakebook/data/update/AppUpdate.kt")
        assertTrue("按版本找已下好的包", source.contains("fun downloadedApk(context: Context, version: String): File?"))
        assertTrue("状态能从磁盘恢复", source.contains("fun restoreDownloaded(context: Context, version: String)"))
        assertTrue(
            "已下好就直接进待安装，不再走网络",
            source.contains("downloadedApk(context, version)?.let {\n            _state.value = UpdateState.Ready(version, it)")
        )

        val ui = readSource("src/main/java/com/mistakebook/ui/settings/SettingsAboutSection.kt")
        assertTrue(
            "查到新版后先把已下好的包接回状态",
            ui.contains("found?.let { AppUpdate.restoreDownloaded(context, it.versionName) }")
        )
        assertTrue("已下好只给「安装」", ui.contains("R.string.settings_about_install"))
        assertFalse(
            "不许再写死那一个缓存文件（旧版不分版本、半截也能被当成下好）",
            ui.contains("updates/app-update.apk")
        )
    }

    @Test
    fun `半截文件不会被当成下好`() {
        val source = readSource("src/main/java/com/mistakebook/data/update/AppUpdate.kt")
        // 断网留下半截 APK 却显示「已下载」，用户点安装只会看到一个坏包
        assertTrue("先写 .part", source.contains("val part = File(target.path + \".part\")"))
        assertTrue("完整收到才改名", source.contains("part.renameTo(target)"))
    }

    @Test
    fun `关于页提供提 Issue 的入口`() {
        val appUpdate = readSource("src/main/java/com/mistakebook/data/update/AppUpdate.kt")
        assertTrue(
            "要有一个直达「新建 issue」的地址",
            appUpdate.contains("const val ISSUE_URL = \"\$REPO_URL/issues/new\"")
        )

        val ui = readSource("src/main/java/com/mistakebook/ui/settings/SettingsAboutSection.kt")
        // 用户明确要求：紧挨着 star 给一个提 issue 的按钮
        assertTrue("按钮要点得动", ui.contains("AppUpdate.openIssue(context)"))
        assertTrue("按钮文案走 strings.xml", ui.contains("R.string.settings_about_issue_action"))
    }

    @Test
    fun `更新说明全文放在定高滚动框里`() {
        val ui = readSource("src/main/java/com/mistakebook/ui/settings/SettingsAboutSection.kt")
        assertFalse(
            "不许再 take(300) 截断——用户点开更新就是要看全改了什么",
            ui.contains("notes.take(300)")
        )
        assertTrue("说明框必须可以框内滚动", ui.contains(".verticalScroll(rememberScrollState())"))
        assertTrue("说明框必须有高度上限，否则把下载按钮顶出屏幕", ui.contains("heightIn(max"))
        assertTrue("框里放的是完整说明（先剥 markdown 标记）", ui.contains("AppUpdate.notesForDisplay(current.notes)"))
    }

    @Test
    fun `说明文本剥掉 markdown 井号与加粗星号`() {
        val raw = "## 思考块的折叠键\n\n列表是**倒序贴底**：气泡一变高，整条**往上长**。\n\n- 按钮留在原地\n- 内容向下长"
        val cleaned = AppUpdate.notesForDisplay(raw)
        assertFalse("标题井号要去掉", cleaned.contains("##"))
        assertFalse("加粗星号要去掉", cleaned.contains("**"))
        assertTrue("标题文字保留", cleaned.startsWith("思考块的折叠键"))
        assertTrue("列表符号保留", cleaned.contains("- 按钮留在原地"))
        assertTrue("换行结构保留", cleaned.contains("\n\n"))
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
