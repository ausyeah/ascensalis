package com.mistakebook.ui

import com.mistakebook.markdown.HtmlDocument
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.0.80 5大优化与拍照排版修复的契约守护。
 *
 * 1. HTML 渲染自适应与 JS 桥接 (HtmlDocument, HtmlBlock)
 * 2. 思考折叠收起动画与位置重锚定 (ChatScreen, ThinkingBlock)
 * 3. 主题色圆球 2 排 4 列排版 (SettingsSections)
 * 4. MinerU 配置更名为 OCR 配置 (SettingsScreen, strings.xml, README)
 * 5. 番茄钟左右滑动切换 (PomodoroScreen, HorizontalPager)
 * 6. 对话拍照裁切后直发会话、不送 OCR，连拍完成按钮排版与禁用态
 */
class Version80FeaturesContractTest {

    @Test
    fun `HTML 文档包含 HtmlBridge 与 ResizeObserver 通信脚本且防截断`() {
        val doc = HtmlDocument.wrap("<div>测试内容</div>", 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertTrue("必须包含 HtmlBridge 桥接调用", doc.contains("HtmlBridge.onHeight"))
        assertTrue("必须包含 ResizeObserver 自适应监听", doc.contains("ResizeObserver"))
        assertTrue("必须设置 body 底部内边距防截断", doc.contains("padding:4px 2px 20px 2px"))
        assertTrue("必须包含视口设置", doc.contains("viewport"))
    }

    @Test
    fun `HTMLBlock 源码直接将 CSS 像素转为 dp`() {
        val src = readSource("app/src/main/java/com/mistakebook/ui/common/HtmlBlock.kt")
        assertTrue("HtmlBlock 必须注册 HtmlBridge 接口", src.contains("addJavascriptInterface(HtmlBridge"))
        assertTrue("HtmlBlock 必须直接将 CSS 像素应用为 dp 避免密度除法截断", src.contains(".height(heightDp.dp"))
        assertTrue("HtmlBlock 必须拦截外链跳转系统浏览器", src.contains("shouldOverrideUrlLoading"))
    }

    @Test
    fun `思考过程折叠移除冲突动画并增加重锚定回调`() {
        val src = readSource("app/src/main/java/com/mistakebook/ui/chat/ChatScreen.kt")
        assertTrue("ThinkingBlock 必须提供 onToggle 回调", src.contains("onToggle: (Boolean) -> Unit = {}"))
        assertTrue("MessageList 必须在折叠时回滚到列表底部", src.contains("onToggleThinking = {"))
        assertTrue("ThinkingBlock 移除多帧高度过渡动画防 reverseLayout 抖动", !src.contains("ThinkingBlock") || !src.contains(".animateContentSize()\n            .clip(RoundedCornerShape(8.dp))\n            .background(MaterialTheme.colorScheme.surface"))
    }

    @Test
    fun `主题色排版一排 8 个等宽居中排布防止第八个圆球挤压`() {
        val src = readSource("app/src/main/java/com/mistakebook/ui/settings/SettingsSections.kt")
        assertTrue("AccentTone 采用单排 8 列自适应排布", src.contains("AccentTone.entries.forEach") && src.contains("weight(1f)"))
    }

    @Test
    fun `设置页已更名为 OCR 配置`() {
        val settingsSrc = readSource("app/src/main/java/com/mistakebook/ui/settings/SettingsScreen.kt")
        assertTrue(
            "设置分组标题必须更名为 OCR 配置（文案已外移到 strings.xml）",
            settingsSrc.contains("R.string.settings_group_ocr")
        )
        assertFalse("设置分组标题不得保留 MinerU 配置", settingsSrc.contains("title = \"MinerU 配置\""))

        val stringsSrc = readSource("app/src/main/res/values/strings.xml")
        assertTrue("strings 资源中 settings_mineru_key 必须更名为 OCR API Key", stringsSrc.contains("<string name=\"settings_mineru_key\">OCR API Key</string>"))
    }

    @Test
    fun `番茄钟支持左右滑动手势`() {
        val src = readSource("app/src/main/java/com/mistakebook/pomodoro/PomodoroScreen.kt")
        assertTrue("PomodoroScreen 必须引入 HorizontalPager", src.contains("HorizontalPager("))
        assertTrue("PomodoroScreen 必须记住 PagerState", src.contains("rememberPagerState("))
    }

    @Test
    fun `对话拍照完成交接直接返回会话且不走 OCR`() {
        val navSrc = readSource("app/src/main/java/com/mistakebook/MistakeBookNavHost.kt")
        assertTrue("submitChatBatch 必须弹出当前裁切页返回会话", navSrc.contains("navController.popBackStack()"))
        assertTrue("onBatchNext 在 CHAT 链路下不得 popUpTo HOME", navSrc.contains("isChat"))

        val captureSrc = readSource("app/src/main/java/com/mistakebook/ui/capture/CaptureScreen.kt")
        assertTrue("快门行右侧必须包含连拍完成按钮", captureSrc.contains("doneLabel"))
        assertTrue("未拍照时完成按钮必须禁用", captureSrc.contains("enabled = batchActive"))
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
