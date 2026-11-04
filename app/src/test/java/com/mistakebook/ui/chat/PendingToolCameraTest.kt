package com.mistakebook.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「开不开相机」只能由入口本身决定（v0.0.101）。
 *
 * 用户实测报过：**点「英语翻译」却拉开了相机**。
 *
 * 原来的写法是「调用方把 `tool.needsCamera` 抄成 `PendingTool.camera`」，
 * 于是「开不开相机」这件事在两个地方各写了一遍——只要有一处没跟上，
 * 纯文字入口就会去碰相机，而且这种错**不崩、不报错**，只是行为诡异。
 *
 * 现在 [com.mistakebook.di.PendingTool] 只带入口本身，
 * 相机与自动发送一律从 `ChatTool.needsCamera` 推导。
 * 这里把这条性质钉住：交接包里不许再出现「要不要开相机」这种可抄错的开关。
 */
class PendingToolCameraTest {

    @Test
    fun `交接包里没有相机开关`() {
        val container = readSource("src/main/java/com/mistakebook/di/AppContainer.kt")
        val start = container.indexOf("internal data class PendingTool(")
        assertTrue("PendingTool 必须存在", start > 0)
        val block = container.substring(start, container.indexOf("\n)", start))
        assertTrue("交接包必须带入口本身", block.contains("val tool: ChatTool"))
        assertFalse(
            "不许再有 camera 开关——「开不开相机」只能由入口推导",
            block.contains("camera:") || block.contains("autoSend:")
        )
        assertTrue(
            "但要记账：相机已经为这个入口拉起过",
            block.contains("val launchedCamera: Boolean = false")
        )
    }

    @Test
    fun `对话页开相机前判的是入口自己`() {
        val screen = readSource("src/main/java/com/mistakebook/ui/chat/ChatScreen.kt")
        assertTrue(
            "开相机必须判 mine.tool.needsCamera",
            screen.contains("if (!mine.tool.needsCamera")
        )
        assertFalse(
            "不许再判一个抄过来的开关",
            screen.contains("mine.camera") || screen.contains("mine.autoSend")
        )
    }

    @Test
    fun `纯文字入口不碰相机而翻译要拍照`() {
        // 绘图生图是纯文字/纯生成，它点了绝不能去拍照
        assertFalse("DIAGRAM 不该拉相机", ChatTool.DIAGRAM.needsCamera)
        // v0.0.106 用户改口：「英语翻译」也是拍一段英文再讲，要先进相机。
        // 手持一段英文却要先把它敲进输入框，等于让工具替用户做了两遍活。
        assertTrue("英语翻译要先拍照", ChatTool.TRANSLATE.needsCamera)
        // 拍题 / 作文批改本来就是照片入口
        listOf(ChatTool.PHOTO, ChatTool.ESSAY).forEach { tool ->
            assertTrue("${tool.name} 该拉相机", tool.needsCamera)
        }
    }

    @Test
    fun `相机只自动拉起一次`() {
        // 用户实测报过：从对话进相机，左上角关闭点了没反应——其实是关掉后
        // ChatScreen 的 LaunchedEffect 又拉了一次，看起来就是「关不掉」。
        val screen = readSource("src/main/java/com/mistakebook/ui/chat/ChatScreen.kt")
        assertTrue("开相机前必须判「已经拉过」", screen.contains("mine.launchedCamera"))
        assertTrue("拉起时必须把账记上", screen.contains("mine.copy(launchedCamera = true)"))
    }

    @Test
    fun `桌面图标前景是黑墨而不是白墨`() {
        // 之前把前景做成白山、背景做成黑色，等于把 artwork 整体反相：
        // 山体里的白色雪线与缝隙变成黑色，中间那道「A」形的白三角就消失了，
        // 远看只剩一团白。桌面图标必须保留原 artwork 的黑白关系：黑山白底。
        val launcher = readSource("src/main/res/values/colors.xml")
        assertTrue(
            "桌面图标背景必须是白的",
            launcher.contains("<color name=\"ic_launcher_background\">#FFFFFFFF</color>")
        )
        val png = File("src/main/res/drawable-nodpi/ic_launcher_foreground.png")
        assertTrue("桌面图标前景必须是黑墨位图", png.exists())
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
