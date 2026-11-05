package com.mistakebook.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 归档题目卡片（v0.0.115）。
 *
 * 巡检模拟器才暴露：卡片里摊着 `$f(x)=x^2+2x$` 这种 LaTeX 源码，
 * 而首页与详情页显示的是渲染好的公式；同时卡片用 surface 无描边，
 * 在浅色底上边界完全看不见，滑开归档页第一眼像空列表。
 * 两处退化都不报错、不影响功能，只会让页面像另一个人做的，所以钉源码。
 */
class ArchivedQuestionCardTest {

    @Test
    fun `显示 displayTitle 而不是原始题干`() {
        val screen = readSource("src/main/java/com/mistakebook/ui/settings/ArchivedItemsScreen.kt")

        assertTrue(
            "必须用 displayTitle：stem 里的 $...$ 会原样显示成 LaTeX 源码",
            screen.contains("text = question.displayTitle")
        )
        assertTrue(
            "首页/详情页都用 displayTitle，归档页要跟它们同一套标题来源",
            screen.contains("import com.mistakebook.data.local.displayTitle")
        )
    }

    @Test
    fun `与首页卡片是同一套面`() {
        val screen = readSource("src/main/java/com/mistakebook/ui/settings/ArchivedItemsScreen.kt")
        val card = screen.substringAfter("private fun QuestionArchiveCard(")

        assertTrue(
            "白卡 + 无描边在浅色底上看不见边界",
            card.contains("MaterialTheme.colorScheme.surfaceContainerLow")
        )
        assertTrue(
            "必须留 1dp 描边",
            card.contains("BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)")
        )
        assertTrue("圆角与首页一致", card.contains("MaterialTheme.shapes.medium"))
    }

    /** 会话卡片与题目卡片同一套面，否则两个列表切换时观感跳一下。 */
    @Test
    fun `归档会话卡片同一套面`() {
        val screen = readSource("src/main/java/com/mistakebook/ui/settings/ArchivedItemsScreen.kt")
        val sessionList = screen.substringAfter("private fun ArchivedSessionsList(")
            .substringBefore("@Composable\nprivate fun QuestionArchiveCard(")

        assertTrue(
            sessionList.contains("MaterialTheme.colorScheme.surfaceContainerLow")
        )
        assertTrue(sessionList.contains("BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)"))
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
