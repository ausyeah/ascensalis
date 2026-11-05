package com.mistakebook.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 空会话第一眼的品牌契约（v0.0.96）。
 *
 * 用户的原话：「用黑白为本应用制作一个 logo，替换掉当前对话页面没有说话时
 * 中间那个蓝色的金鱼」「下方配上我英文名字的签名……这个签名就是这个应用的名字」。
 *
 * 这类改动没有可断言的运行时行为——它退化的方式极其安静：
 * 有人把 tint 改回 `colorScheme.primary`（品牌色又跟着主题走）、
 * 有人把签名写成普通正文样式（品牌标识变成一句普通的话）、
 * 有人把底部提示改回「问点什么…」（用户要的那句话没了）。
 * 所以钉源码 + 资源，三条一起守。
 */
class ChatEmptyBrandTest {

    @Test
    fun `标志是带透明通道的黑白剪影`() {
        val mark = readSource("src/main/res/drawable-nodpi/ic_app_mark.png")

        // 位图而非矢量：v0.0.99 换成用户指定的山景线稿——这种带雪线、云与
        // 星芒的插画手写成路径不现实。资源仍是「黑 + alpha」，
        // 界面按主题染色（浅色墨黑、深色纸白）。
        assertTrue("标志必须是 PNG 资源", mark.isNotEmpty())
        assertFalse(
            "矢量版本已换成山景位图，两边同时存在会变成重复资源",
            File("src/main/res/drawable/ic_app_mark.xml").exists() ||
                File("app/src/main/res/drawable/ic_app_mark.xml").exists()
        )
        assertTrue(
            "桌面图标前景必须是同一枚山的白标版本",
            File("src/main/res/drawable-nodpi/ic_launcher_foreground.png").exists()
        )
    }

    @Test
    fun `旧的鲸鱼位图已删除`() {
        val candidates = listOf(
            File("src/main/res/drawable-nodpi/chat_empty_whale.png"),
            File("app/src/main/res/drawable-nodpi/chat_empty_whale.png")
        )
        assertTrue(
            "chat_empty_whale.png 已不再被任何界面引用，留着就是白白占包体",
            candidates.none { it.exists() }
        )
        assertFalse(
            "源码里不许再有鲸鱼引用",
            readSource("src/main/java/com/mistakebook/ui/chat/ChatScreen.kt").contains("chat_empty_whale")
        )
    }

    @Test
    fun `空状态中央是用户定稿的山岳徽章且明暗跟随主题`() {
        val screen = readSource("src/main/java/com/mistakebook/ui/chat/ChatScreen.kt")
        val start = screen.indexOf("private fun ChatEmptyState(")
        assertTrue("ChatEmptyState 必须存在", start >= 0)
        val body = screen.substring(start, screen.indexOf("\n}", start))

        // v0.0.121（用户定稿两版手作徽章）：选版由 LocalDarkTheme 决定——
        // 它是应用**实际渲染**的主题（设置里可强制浅色/深色）；
        // 用 -night 资源限定符会跟系统夜间开关走，用户强制浅色时
        // 就会在米白纸面上贴炭黑徽章（实机踩过）。
        assertTrue("空状态必须渲染 brand_emblem 徽章", body.contains("R.drawable.brand_emblem"))
        assertTrue(
            "选版必须读 LocalDarkTheme（跟随应用实际背景）",
            body.contains("LocalDarkTheme.current")
        )
        assertTrue(
            "浅色版必须在 drawable-nodpi",
            File("src/main/res/drawable-nodpi/brand_emblem_light.png").exists() ||
                File("app/src/main/res/drawable-nodpi/brand_emblem_light.png").exists()
        )
        assertTrue(
            "深色版必须在 drawable-nodpi（强制浅色/深色主题时也要有得换）",
            File("src/main/res/drawable-nodpi/brand_emblem_dark.png").exists() ||
                File("app/src/main/res/drawable-nodpi/brand_emblem_dark.png").exists()
        )
        assertFalse(
            "Canvas 相机已退役（v0.0.121 徽章替代），不许残留在空状态里",
            body.contains("CameraMark(")
        )
        assertFalse(
            "徽章不许 tint 强调色——它自带明暗两版，染色反而破坏配色",
            body.contains("ColorFilter.tint(MaterialTheme.colorScheme.primary)")
        )
    }

    @Test
    fun `抽屉签名旁也挂同一枚徽章`() {
        val sheet = readSource("src/main/java/com/mistakebook/ui/chat/ChatHistorySheet.kt")
        // 头部区域 = 「头部」注释之后、功能行（ChatToolRows）之前
        val header = sheet.substringAfter("头部：左上角徽章 + 应用签名")
            .substringBefore("ChatToolRows")
        assertTrue(
            "签名前必须有 brand_emblem 徽章",
            header.contains("R.drawable.brand_emblem")
        )
        assertTrue(
            "签名本身还在",
            header.contains("R.string.app_signature")
        )
    }

    @Test
    fun `签名就是这个应用的名字且用书法感的排版`() {
        val strings = readSource("src/main/res/values/strings.xml")
        assertTrue(
            "签名文案必须在资源里",
            strings.contains("<string name=\"app_signature\">Ascensalis</string>")
        )

        val screen = readSource("src/main/java/com/mistakebook/ui/chat/ChatScreen.kt")
        val start = screen.indexOf("private fun ChatEmptyState(")
        val body = screen.substring(start, screen.indexOf("\n}", start))
        assertTrue("必须显示签名", body.contains("stringResource(R.string.app_signature)"))
        // 系统自带字体里最接近行楷英语字样的组合；也保证不打包字体文件
        assertTrue("签名用衬线体", body.contains("fontFamily = FontFamily.Serif"))
        assertTrue("签名用斜体", body.contains("fontStyle = FontStyle.Italic"))
        assertTrue("签名要有字距，否则衬线斜体挤在一起像正文", body.contains("letterSpacing = 2.5.sp"))
    }

    @Test
    fun `底部提示是用户要的那句话`() {
        val strings = readSource("src/main/res/values/strings.xml")
        assertTrue(
            "输入栏提示必须是「你想问我什么问题」",
            strings.contains("<string name=\"chat_input_hint\">你想问我什么问题</string>")
        )
        // 原来的「问点什么…（可附图或文档）」已被用户点名替换
        assertFalse(strings.contains("问点什么…"))
    }

    @Test
    fun `空状态不再是一句问候`() {
        val strings = readSource("src/main/res/values/strings.xml")
        // 签名取代了问候语：两条问候文案已无用，留着只会让以后的译者以为还在用
        assertFalse("chat_empty_greeting_free 应已删除", strings.contains("chat_empty_greeting_free"))
        assertFalse("chat_empty_greeting_question 应已删除", strings.contains("chat_empty_greeting_question"))
        assertFalse(
            "源码里不许再引用问候文案",
            readSource("src/main/java/com/mistakebook/ui/chat/ChatScreen.kt").contains("chat_empty_greeting")
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