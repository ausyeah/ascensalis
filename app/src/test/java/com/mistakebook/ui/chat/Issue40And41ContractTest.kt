package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #40 用户消息可复制 / #41 抽屉底部两颗等宽按钮（v0.0.127）。
 *
 * #40「发送出去的文本不能复制」：复制入口原本只覆盖「助手消息 + DONE」，
 * 用户自己发的那段字反而拿不出来。
 * #41「对话历史下方新建对话按钮太小右边空了很多」+「建议加一个归档对话的
 * 按钮方便直接跳转查看已归档对话」：底部胶囊按内容宽度、右侧大片留白；
 * 归档对话只能从设置绕三层进去。
 */
class Issue40And41ContractTest {

    private val bubble = readSource("src/main/java/com/mistakebook/ui/chat/ChatMessageBubble.kt")
    private val sheet = readSource("src/main/java/com/mistakebook/ui/chat/ChatHistorySheet.kt")
    private val nav = readSource("src/main/java/com/mistakebook/MistakeBookNavHost.kt")
    private val strings = readSource("src/main/res/values/strings.xml")

    /** 只截抽屉底部那一段（别把历史行里的 weight / fillMaxWidth 算进来）。 */
    private val bottom: String = sheet
        .substringAfter("底部两颗等宽胶囊")
        .substringBefore("左滑操作行：")

    @Test
    fun `用户消息也有复制按钮`() {
        assertTrue(
            "StatusRow 必须为用户消息提供复制分支",
            bubble.contains("message.role == ChatRole.USER && message.content.isNotBlank()")
        )
        assertTrue(
            "复制动作要带上用户正文",
            bubble.contains("onCopy(message.content.trim())")
        )
    }

    @Test
    fun `抽屉底部两颗等宽按钮且能直达归档对话`() {
        assertEquals(
            "新对话与已归档对话必须各占一半（weight 1f），否则右边还是一片空白",
            2,
            // 只数真正的布局修饰符，别把注释里提到的 weight(1f) 算进去
            Regex("""modifier = Modifier\.weight\(1f\)""").findAll(bottom).count()
        )
        assertTrue(
            "底部必须能直达已归档会话",
            bottom.contains("onOpenArchivedSessions()")
        )
        assertTrue(
            "文案在资源里",
            strings.contains("name=\"chat_list_archived\"")
        )
        assertTrue(
            "对话页的归档回调要经 NavHost 接到归档路由",
            nav.contains("onOpenArchivedSessions = { navController.navigate(Routes.ARCHIVED_SESSIONS) }")
        )
    }

    @Test
    fun `底部按钮撑满整行`() {
        assertTrue("底部行必须 fillMaxWidth", bottom.contains(".fillMaxWidth()"))
        assertEquals(
            "底部行本身 + 两颗按钮内部各一次：共 3 处 fillMaxWidth",
            3,
            Regex("""fillMaxWidth\(\)""").findAll(bottom).count()
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