package com.mistakebook.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 线性进度条统一口径（v0.0.142，用户：「所有的进度条都在右边有一个颜色
 * 的小点，UI 异常，自己检查发现问题并修改」）。
 *
 * 那个小点是 Material3 1.3 引入的**停止指示点**（stop indicator）：框架
 * 在轨道末端画一个圆点，并把轨道切出对应缺口。本项目的进度条都是「填充
 * 条」语义（掌握率、下载进度、解析进度），末端多一个点会被读成 UI 缺陷。
 *
 * 修法：收敛到 [AppLinearProgressIndicator]，一处关掉 gapSize 与
 * drawStopIndicator。单测钉两件事：
 * ① 包装组件确实关掉（两条都要，只关一个会留下缺口或圆点）；
 * ② main 源码里不得再出现直接调用——否则新增进度条又会带回小点，
 *    而这正是「漏一处就是一处残留」的上一版翻车方式。
 */
class AppProgressBarStyleTest {

    private val component = readSource("src/main/java/com/mistakebook/ui/common/CommonComponents.kt")

    @Test
    fun `包装组件必须同时关掉缺口与停止点`() {
        val fn = component.substringAfter("fun AppLinearProgressIndicator(")
            .substringBefore("\n}")
        assertTrue(
            "必须 drawStopIndicator = {}：只关缺口不关点，小点照样画在轨道末端",
            fn.contains("drawStopIndicator = {}")
        )
        assertTrue(
            "必须 gapSize = 0.dp：只关点不关缺口，轨道末端会留一道莫名其妙的断口",
            fn.contains("gapSize = 0.dp")
        )
        assertTrue(
            "progress 用 lambda 透传，保持调用点的「延迟读取」语义（动画帧不必重组整棵树）",
            fn.contains("progress: () -> Float")
        )
    }

    @Test
    fun `main 源码不得再直接调用 LinearProgressIndicator`() {
        val root = sourceRoot()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "CommonComponents.kt" }
            .filter { file ->
                // 只看真实调用，不看 AppLinearProgressIndicator( 与导入行
                file.readText().let { text ->
                    text.replace("AppLinearProgressIndicator(", "").contains("LinearProgressIndicator(")
                }
            }
            .map { it.path }
            .toList()
        assertFalse(
            "这些文件绕过了统一包装，会把 M3 的停止指示点（右边的小圆点）带回来：$offenders",
            offenders.isNotEmpty()
        )
    }

    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("../src/main/java"),
            File("app/src/main/java")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw IllegalArgumentException("找不到 main 源码根目录：$candidates")
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
