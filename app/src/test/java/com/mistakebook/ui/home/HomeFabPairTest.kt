package com.mistakebook.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首页右下角两颗 FAB 的一致性（v0.0.118）。
 *
 * 用户看着截图说：「这两个按钮以下面为准，两个要保证一致啊，美观程度太差了」。
 * 当时上面那颗是 40dp + 白底蓝字的 SmallFloatingActionButton，下面是 56dp
 * 的浅蓝胶囊——同高不同、同色不同，并排站着像两个人拼的。
 *
 * 这类退化不会报错也不会影响点击，只会让首页第一眼显得廉价，
 * 所以把「同高、同底色、同内容色」三条钉在源码上。
 */
class HomeFabPairTest {

    private val home = readSource("src/main/java/com/mistakebook/ui/home/HomeScreen.kt")

    private val fabBlock: String = home
        .substringAfter("floatingActionButton = {")
        .substringBefore("snackbarHost =")

    @Test
    fun `两颗都是 56dp 的完整 FAB`() {
        assertTrue(
            "「问一问」必须换成标准 FloatingActionButton，与下面胶囊同高",
            fabBlock.contains("FloatingActionButton(")
        )
        assertFalse(
            "SmallFloatingActionButton 只有 40dp，与 56dp 胶囊并排必然一头大一头小",
            fabBlock.contains("SmallFloatingActionButton(")
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