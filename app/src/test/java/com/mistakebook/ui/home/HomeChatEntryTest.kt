package com.mistakebook.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首页「会话」入口的落点（v0.0.96）。
 *
 * 用户要求：「在主页面点击『会话』按钮，就能直接进入新对话页面，
 * 模仿 ChatGPT 手机端的运行模式。」
 *
 * 改之前这颗按钮进的是「对话记录」列表：想提问得先看一页历史，
 * 再点一次新对话。这条链路上没有一步是在回答「我要问点东西」。
 *
 * 这类退化没有崩溃可查——把回调改回 `Routes.CHAT_LIST` 一样能编译能跑，
 * 只是用户又得多点一次。所以钉源码。
 */
class HomeChatEntryTest {

    @Test
    fun `首页会话按钮直接开新对话`() {
        val nav = readSource("src/main/java/com/mistakebook/MistakeBookNavHost.kt")
        val start = nav.indexOf("onNewChat = {")
        assertTrue("首页必须接 onNewChat", start >= 0)
        val block = nav.substring(start, nav.indexOf("},", start) + 2)
        assertTrue("必须解析出一条自由会话", block.contains("openFreeSession()"))
        assertTrue("必须导航到对话页", block.contains("Routes.chat("))
    }

    @Test
    fun `首页不再直接跳对话记录`() {
        val nav = readSource("src/main/java/com/mistakebook/MistakeBookNavHost.kt")
        assertFalse(
            "首页的会话按钮不许再落到对话记录页（历史改从对话页左上角进）",
            nav.contains("onOpenChatList = { navController.navigate(Routes.CHAT_LIST) }")
        )
    }

    @Test
    fun `按钮说自己是开启新对话`() {
        val home = readSource("src/main/java/com/mistakebook/ui/home/HomeScreen.kt")
        assertTrue("参数名要说清落点是新对话", home.contains("onNewChat: () -> Unit"))
        assertTrue("图标必须带加号，一眼看出是「新开」", home.contains("Icons.Default.AddComment"))
        assertTrue(
            "无障碍标签必须是「开启新对话」",
            home.contains("contentDescription = stringResource(R.string.chat_new_conversation)")
        )
        assertFalse(
            "不许再把标签写成「对话记录」——读屏用户会以为点进去是列表",
            home.contains("contentDescription = stringResource(R.string.chat_list_title)")
        )
    }

    @Test
    fun `门户容器原样透传新回调`() {
        val portal = readSource("src/main/java/com/mistakebook/ui/portal/MainPortalScreen.kt")
        assertTrue("门户签名必须收 onNewChat", portal.contains("onNewChat: () -> Unit"))
        assertTrue("必须透传给首页", portal.contains("onNewChat = onNewChat,"))
        assertFalse("旧的 onOpenChatList 不许残留", portal.contains("onOpenChatList"))
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