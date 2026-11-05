package com.mistakebook.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首页右下角这对 FAB（v0.0.121 第二轮）。
 *
 * v0.0.118 统一了高度和配色，但上面那颗还是圆钮——图标悬在胶囊
 * 旁边，用户看截图说「这个对话和这个拍错题一样，是一个长条胶囊形，
 * 保证长度一致，图标、文字等对位要求工整」。
 *
 * 现在两颗同为 ExtendedFloatingActionButton：同结构（图标 + 三个字）、
 * 同底色、同内容色，宽度天然一致。这条只能钉源码——圆钮一回来，
 * 「等长」就没了。
 */
class HomeFabPairTest {

    private val home = readSource("src/main/java/com/mistakebook/ui/home/HomeScreen.kt")

    private val fabBlock: String = home
        .substringAfter("floatingActionButton = {")
        .substringBefore("snackbarHost =")

    @Test
    fun `两颗都是带文字的胶囊`() {
        assertEquals(
            "问一问与拍错题都必须是 ExtendedFloatingActionButton，宽窄才一致",
            2,
            fabBlock.split("ExtendedFloatingActionButton(").size - 1
        )
        assertFalse(
            "圆钮 FAB 与胶囊并排永远像两个组件（v0.0.121 起禁用）",
            Regex("(?<!Extended)FloatingActionButton\\(").containsMatchIn(fabBlock)
        )
    }

    @Test
    fun `两颗都有图标加文字的完整结构`() {
        assertEquals(
            "两颗都要 icon { } + text { }，只有一边带文字就会一头宽一头窄",
            2,
            fabBlock.split("icon = {").size - 1
        )
        assertEquals(
            2,
            fabBlock.split("text = {").size - 1
        )
    }

    @Test
    fun `两颗同底色同内容色`() {
        assertEquals(
            "两颗都必须用 primaryContainer 作底色，否则一颗浅蓝一颗白",
            2,
            fabBlock.split("containerColor = MaterialTheme.colorScheme.primaryContainer").size - 1
        )
        assertEquals(
            "内容色也统一取 onPrimaryContainer",
            2,
            fabBlock.split("contentColor = MaterialTheme.colorScheme.onPrimaryContainer").size - 1
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
