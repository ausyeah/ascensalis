package com.mistakebook.ui.badge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 成就墙页头（v0.0.117）。
 *
 * 用户原话：「成就墙最上面的那三个灯光不要了，丑死了」。那三道梯形渐变
 * 光束占了 120dp 的首屏，实际渲染出来是三团半透明黄，还把战绩卡挤到
 * 了一屏之下——首屏该留给进度本身。
 *
 * 删掉的东西没人会再注意到，但重构时很容易「顺手」把氛围装饰加回来，
 * 所以钉住：墙上不再有 Canvas 绘制的光束，战绩卡就是第一个孩子。
 */
class BadgeWallLayoutTest {

    @Test
    fun `墙上不再有聚光灯`() {
        val screen = readSource("src/main/java/com/mistakebook/ui/badge/BadgeWallScreen.kt")

        assertFalse(
            "Spotlights 已删除，不该再有这个 composable",
            screen.contains("private fun Spotlights(")
        )
        assertFalse(
            "该文件已不再需要 Canvas 绘制装饰",
            screen.contains("Canvas(")
        )
    }

    @Test
    fun `战绩卡是页头第一块内容`() {
        val screen = readSource("src/main/java/com/mistakebook/ui/badge/BadgeWallScreen.kt")
        val summary = screen.indexOf("WallSummaryCard(")
        val grid = screen.indexOf("LazyVerticalGrid(")

        assertTrue("战绩卡必须存在", summary > 0)
        assertTrue("网格必须在战绩卡之后", grid > summary)
        assertTrue(
            "两者之间不该再夹任何氛围装饰",
            screen.substring(summary, grid).let { between ->
                !between.contains("Canvas(") && !between.contains("Spotlights")
            }
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