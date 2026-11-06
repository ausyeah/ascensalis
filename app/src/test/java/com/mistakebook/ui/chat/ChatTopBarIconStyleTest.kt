package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话顶栏两枚图标的风格一致性（v0.0.129，issue #49）。
 *
 * 用户两轮反馈都指向同一件事：「左上角的图标风格不一致重做，右上角的
 * 临时对话风格也不一致很丑」。
 *
 * 第一轮（#47）把左边从「带箭头时钟」换成 Material 的**实心**侧栏图标、
 * 右边换成自绘的**线描**方框铅笔——结果是一填充一描边，并排像两套设计。
 * 这一轮把两枚都改成自绘线描，并钉死四条一致性：同线宽、同 tint、
 * 同尺寸、都是 drawable（不再混用 Material 内置字形）。
 */
class ChatTopBarIconStyleTest {

    private val screen = readSource("src/main/java/com/mistakebook/ui/chat/ChatScreen.kt")
    private val sidebar = readSource("src/main/res/drawable/ic_chat_sidebar.xml")
    private val newChat = readSource("src/main/res/drawable/ic_new_chat.xml")

    /** 顶栏那段（navigationIcon 到 actions 结束）。 */
    private val topBar: String = screen
        .substringAfter("navigationIcon = {")
        .substringBefore("snackbarHost = {")

    @Test
    fun `两枚图标都来自自绘 drawable，不混用 Material 字形`() {
        assertTrue(
            "左边必须是自绘侧栏图标",
            topBar.contains("painterResource(R.drawable.ic_chat_sidebar)")
        )
        assertTrue(
            "右边必须是自绘「方框+铅笔」",
            topBar.contains("painterResource(R.drawable.ic_new_chat)")
        )
        assertTrue(
            "顶栏不许再出现 Material 内置图标字形（实心/描边混用就是风格不一致的来源）",
            !topBar.contains("Icons.Default.") &&
                !topBar.contains("Icons.Filled.") &&
                !topBar.contains("Icons.AutoMirrored.")
        )
    }

    @Test
    fun `两枚图标线宽完全一致`() {
        val sidebarWidths = Regex("""android:strokeWidth="([0-9.]+)"""").findAll(sidebar)
            .map { it.groupValues[1] }.toSet()
        val newChatWidths = Regex("""android:strokeWidth="([0-9.]+)"""").findAll(newChat)
            .map { it.groupValues[1] }.toSet()
        assertEquals("侧栏图标内部线宽要统一", 1, sidebarWidths.size)
        assertEquals("新对话图标内部线宽要统一", 1, newChatWidths.size)
        assertEquals(
            "两枚图标的线宽必须一致，否则并排还是粗细不一",
            sidebarWidths.first(),
            newChatWidths.first()
        )
        assertEquals("口径固定为 2（48 视口 → 24dp 下 1dp 线）", "2", sidebarWidths.first())
    }

    @Test
    fun `两枚图标同色同尺寸`() {
        val sizeCount = Regex("""modifier = Modifier\.size\(24\.dp\)""").findAll(topBar).count()
        assertEquals("两枚图标都要 24dp", 2, sizeCount)
        val tintCount = Regex("""tint = MaterialTheme\.colorScheme\.onSurface,""")
            .findAll(topBar).count()
        assertEquals(
            "两枚图标必须同 tint——一边默认一边灰一档也是「风格不一致」",
            2,
            tintCount
        )
        assertTrue(
            "不许再给某一边单独降对比度",
            !topBar.contains("onSurfaceVariant")
        )
    }

    @Test
    fun `临时对话横幅也用同一枚新对话图标`() {
        val banner = screen.substringAfter("TemporaryChatBanner")
        assertTrue(
            "横幅里不该再出现旧的虚线圆环",
            !banner.contains("ic_temp_chat")
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