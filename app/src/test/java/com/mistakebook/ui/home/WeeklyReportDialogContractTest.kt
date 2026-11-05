package com.mistakebook.ui.home

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 周报弹层的按钮行（v0.0.121）。
 *
 * 用户截图里的「异常的错位UI」：生成中时确认槽是一颗裸 Text，
 * 旁边「关闭」是 40dp 高的 TextButton——同一行里一高一矮、
 * 基线错开，看上去像两颗按钮各摆各的。
 *
 * 修法是让等待文案也住进 TextButton（禁用态），两颗按钮同高同基线。
 * 裸 Text 一回来错位就会复发，所以钉在源码上。
 */
class WeeklyReportDialogContractTest {

    private val source = readSource("src/main/java/com/mistakebook/ui/home/WeeklyReportDialog.kt")

    private val confirmBlock: String = source
        .substringAfter("confirmButton = {")
        .substringBefore("dismissButton =")

    @Test
    fun `生成中的等待文案也是 TextButton`() {
        assertTrue(
            "等待文案必须包在 TextButton 里，与「关闭」同高同基线",
            confirmBlock.contains("TextButton(onClick = {}, enabled = false)")
        )
        assertTrue(
            "等待文案必须在禁用的 TextButton 体内",
            confirmBlock.substringAfter("TextButton(onClick = {}, enabled = false)")
                .contains("R.string.weekly_report_wait")
        )
    }

    @Test
    fun `等待文案禁用但关闭仍然可点`() {
        val dismissBlock = source
            .substringAfter("dismissButton = {")
            .substringBefore("\n    )\n}")
        assertTrue(
            "「关闭」必须是可点的 TextButton",
            dismissBlock.contains("TextButton(onClick = onDismiss)")
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
