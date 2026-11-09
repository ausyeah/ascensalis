package com.mistakebook.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 思考过程折叠键的交互契约（v0.0.96）。
 *
 * 用户的三条原话：
 * 1. 「按键稍微大一点，太容易误触到复制了」；
 * 2. 「折叠完始终维持折叠前后按钮位置不变」；
 * 3. 「展开永远是向下的」。
 *
 * 这三条都是**会无声退化**的：把 40dp 改回 18dp 能编译，把复制键挪回标题行
 * 能编译，把箭头翻上去也能编译——只有源码结构能守住它们。
 */
class ThinkingToggleTest {

    private val bubble: String by lazy { readSource("src/main/java/com/mistakebook/ui/chat/ChatMessageBubble.kt") }
    private val block: String by lazy {
        val start = bubble.indexOf("internal fun ThinkingBlock(")
        val end = bubble.indexOf("\n}\n", start)
        bubble.substring(start, end)
    }

    @Test
    fun `折叠键是固定大小的按钮而不是裸图标`() {
        assertTrue(
            "折叠键必须是 40dp 的可点 Box——18dp 的图标按不中，按到的总是旁边的复制",
            block.contains(".size(40.dp)") && block.contains("role = Role.Button")
        )
    }

    @Test
    fun `箭头恒指向下`() {
        assertFalse(
            "箭头不许再翻成向上——展开的内容就在按钮下方长出来，向下才是它描述的动作",
            block.contains("Icons.Default.ExpandLess")
        )
        assertTrue(block.contains("Icons.Default.ExpandMore"))
    }

    @Test
    fun `折叠展开后按钮留在手指底下`() {
        // 列表是反向布局：气泡变高时整体向上长，按钮会被顶离原位，
        // 用户连点第二下就点空了。所以要量位移、让列表反着滚回来。
        // v0.0.144：量的是块自身的高度增量（纯布局量），不是按钮的窗口坐标
        // ——后者混进了列表滚动，按它补偿会形成反馈环。
        assertTrue("必须量块自身高度", block.contains("onSizeChanged { blockHeight = it.height }"))
        assertTrue("点下去那一刻要记块高度", block.contains("heightAtToggle = blockHeight"))
        assertTrue("布局落地后按增量让列表补偿", block.contains("onAnchorShift(delta.toFloat())"))
        assertTrue(
            "列表要真的按位移滚回去",
            readSource("src/main/java/com/mistakebook/ui/chat/ChatMessageList.kt")
                .contains("listState.scrollBy(shiftPx)")
        )
    }

    @Test
    fun `复制键不在标题行里`() {
        val header = block.substring(block.indexOf("Row(verticalAlignment"), block.indexOf("if (expanded && displayText.isNotBlank())"))
        assertFalse(
            "复制键不许和折叠键同在一行——想折叠的时候按到的总是复制",
            header.contains("ContentCopy")
        )
        // 复制键得存在，只是挪到了内容末尾
        assertTrue(block.contains("ContentCopy"))
    }

    @Test
    fun `整块不再整体可点`() {
        assertFalse(
            "思考块本体不许再 clickable——想选中一段文字却把块折叠了",
            block.contains("Modifier.clickable {")
        )
    }

    @Test
    fun `复制提示文案进了资源`() {
        val strings = readSource("src/main/res/values/strings.xml")
        assertTrue(
            "strings.xml 必须有 chat_thinking_copied",
            strings.contains("<string name=\"chat_thinking_copied\">思考过程已复制</string>")
        )
        assertFalse(
            "不许再硬编码这句提示",
            bubble.contains("\"思考过程已复制\"")
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