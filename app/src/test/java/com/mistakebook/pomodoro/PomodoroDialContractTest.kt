package com.mistakebook.pomodoro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 计时表盘的主操作（v0.0.121）。
 *
 * 用户对着表盘说：「蓝色小圆圈就不要了，直接做一个蓝色的圆润停止三角
 * 和一个圆润的红色停止，不要有这个额外的圆圈来包裹它，直接悬浮在白底上」。
 *
 * 圆底一回来，「悬浮」就没了——所以钉死三条：无 CircleShape 底、
 * 图标用 Rounded 系字形（圆角）、停止键用 error 红而不是容器色。
 */
class PomodoroDialContractTest {

    private val source = readSource("src/main/java/com/mistakebook/pomodoro/PomodoroTimerTab.kt")

    private val dial: String = source
        .substringAfter("private fun TimerDial(")
        .substringBefore("private fun formatClock")

    @Test
    fun `按钮不再有圆形底`() {
        assertFalse(
            "主操作不能再包 CircleShape 圆底，直接悬浮在白底上",
            dial.contains("CircleShape")
        )
    }

    @Test
    fun `图标用圆润字形`() {
        assertTrue(
            "播放/停止必须用 Icons.Rounded 系（自带圆角），Filled 的尖角不够圆润",
            dial.contains("Icons.Rounded.PlayArrow") &&
                dial.contains("Icons.Rounded.Pause") &&
                dial.contains("Icons.Rounded.Stop")
        )
        assertFalse(
            "不允许再回到 Filled 尖角字形",
            dial.contains("Icons.Filled.")
        )
    }

    @Test
    fun `开始是主题蓝，停止是唯一的红`() {
        val idle = dial.substringAfter("RunStatus.IDLE -> {")
        assertTrue(
            "开始三角用 primary 蓝，不再是容器底上的深蓝",
            idle.contains("MaterialTheme.colorScheme.primary")
        )
        assertTrue(
            "停止键用 error 红，直接给图标上色",
            dial.contains("MaterialTheme.colorScheme.error\n")
        )
    }

    @Test
    fun `无底不等于无热区`() {
        assertTrue(
            "热区保留 56dp 以上：裸图标也要够拇指按",
            dial.contains(".size(64.dp)") && dial.contains(".size(56.dp)")
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
