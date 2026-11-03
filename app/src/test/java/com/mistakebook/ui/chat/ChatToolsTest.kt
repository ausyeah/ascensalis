package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.mistakebook.R

/**
 * 历史对话页的「能力入口」与「置顶」（v0.0.96）。
 *
 * 用户要求：进历史页，左上角是应用签名，下面是几个功能入口（拍题、翻译、
 * 批改、绘图、讲解、生成图片）**及相关情况**，再下面才是历史对话，
 * 长按可置顶 / 重命名 / 删除。
 *
 * 「相关情况」是最容易被省掉的部分：把六个入口做成一排漂亮按钮并不难，
 * 难的是让用户知道**为什么点了没反应**——没配模型就去点「英语翻译」，
 * 看到的是一条发送失败。所以这里钉住的是可用性的判定规则。
 */
class ChatToolsTest {

    @Test
    fun `六个入口一个不多一个不少`() {
        assertEquals(6, ChatTool.entries.size)
        // 用户点名的六个
        listOf(
            R.string.chat_tool_photo,
            R.string.chat_tool_translate,
            R.string.chat_tool_essay,
            R.string.chat_tool_diagram,
            R.string.chat_tool_explain,
            R.string.chat_tool_image
        ).forEach { res ->
            assertTrue(
                "入口文案必须在清单里（res=$res）",
                ChatTool.entries.any { it.labelRes == res }
            )
        }
    }

    @Test
    fun `只有拍题走相机其余都是预填提示词`() {
        assertEquals(
            "拍题是「拍一张」不是「说一句话」",
            null,
            ChatTool.PHOTO.promptRes
        )
        ChatTool.entries.filter { it != ChatTool.PHOTO }.forEach { tool ->
            assertTrue(
                "${tool.name} 必须带一句预填提示词，否则点进去是一片空白",
                tool.promptRes != null
            )
        }
    }

    @Test
    fun `没配模型时所有入口都提示需配模型`() {
        ChatTool.entries.forEach { tool ->
            assertEquals(ChatToolBlock.NEEDS_LLM, tool.blockReason(llmConfigured = false, ocrConfigured = true))
        }
    }

    @Test
    fun `配了模型没配识别时只有拍题受影响`() {
        // 翻译 / 批改 / 绘图 / 讲解 / 生成图片都不依赖 OCR，识别没配不该连坐
        ChatTool.entries.filter { it != ChatTool.PHOTO }.forEach { tool ->
            assertEquals(ChatToolBlock.READY, tool.blockReason(llmConfigured = true, ocrConfigured = false))
        }
        assertEquals(ChatToolBlock.NEEDS_OCR, ChatTool.PHOTO.blockReason(llmConfigured = true, ocrConfigured = false))
    }

    @Test
    fun `都配好了全部可用`() {
        ChatTool.entries.forEach { tool ->
            assertEquals(ChatToolBlock.READY, tool.blockReason(llmConfigured = true, ocrConfigured = true))
        }
    }

    @Test
    fun `识别缺了不至于把模型缺了盖掉`() {
        // 两个都缺时，先说模型——那是所有入口共同的前置，
        // 用户配好模型之后提示会自动变成「需配识别」，一步步走不会迷路
        assertEquals(ChatToolBlock.NEEDS_LLM, ChatTool.PHOTO.blockReason(llmConfigured = false, ocrConfigured = false))
    }

    @Test
    fun `置顶与提示词文案都在资源里`() {
        val strings = readStrings()
        listOf(
            "app_signature",
            "chat_tools_title",
            "chat_history_title",
            "chat_pin",
            "chat_unpin",
            "chat_pinned_badge",
            "chat_tool_translate_prompt",
            "chat_tool_image_prompt",
            "chat_temporary_new"
        ).forEach { name ->
            assertTrue("strings.xml 少了 $name", strings.contains("name=\"$name\""))
        }
        // 签名就是应用名，别处出现中英混排会认不出来
        assertTrue(strings.contains("<string name=\"app_signature\">Ascensalis</string>"))
    }

    @Test
    fun `历史页不许再以对话记录为标题`() {
        val screen = readSource("src/main/java/com/mistakebook/ui/chat/ChatListScreen.kt")
        assertFalse(
            "标题必须是应用签名，不是「对话记录」",
            screen.contains("title = { Text(stringResource(R.string.chat_list_title)) }")
        )
        assertTrue("顶栏必须以应用签名开头", screen.contains("stringResource(R.string.app_signature)"))
    }

    @Test
    fun `长按菜单必须有三项且带置顶`() {
        val screen = readSource("src/main/java/com/mistakebook/ui/chat/ChatListScreen.kt")
        assertTrue("长按出菜单", screen.contains("onLongClick = { menuOpen = true }"))
        // 页面里有两个 menuOpen 菜单：顶栏的「清空全部」、行内的长按菜单。
        // 长按菜单是后写的那个，从后往前找才不会拿错。
        val menu = screen.lastIndexOf("DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {")
        assertTrue(menu > 0)
        val block = screen.substring(menu, screen.indexOf("\n        }", menu))
        assertTrue("菜单里要有置顶", block.contains("R.string.chat_unpin") || block.contains("R.string.chat_pin"))
        assertTrue("菜单里要有重命名", block.contains("R.string.chat_rename"))
        assertTrue("菜单里要有删除", block.contains("R.string.swipe_delete_hint"))
    }

    private fun readStrings(): String = readSource("src/main/res/values/strings.xml")

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