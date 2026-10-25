package com.mistakebook.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 取消与完成的竞态守卫契约（T6）。
 *
 * 场景：任务刚好在 DONE 之后点取消——无条件的 fail() 会把已完成覆写成
 * 「已取消」。守卫逻辑在引擎里（依赖链太重，JVM 起不来整条 DI），
 * 沿用源码契约测试的取舍把它钉住：谁把 DONE/FAILED 的跳过分支删了，
 * 这里就红。
 */
class CancelRaceGuardTest {

    private val engineSource: String by lazy {
        val candidates = listOf(
            File("src/main/java/com/mistakebook/pipeline/RecognitionEngine.kt"),
            File("app/src/main/java/com/mistakebook/pipeline/RecognitionEngine.kt")
        )
        candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 RecognitionEngine.kt")
    }

    @Test
    fun `cancel 对终态任务不得覆写`() {
        val cancelBody = Regex("fun cancel\\(taskId: Long\\) \\{[\\s\\S]*?\\n    \\}")
            .find(engineSource)?.value
            ?: error("找不到 cancel 方法体")
        assertTrue(
            "cancel 丢失终态守卫：DONE/FAILED 的任务会被覆写成「已取消」",
            cancelBody.contains("TaskStatus.DONE") && cancelBody.contains("TaskStatus.FAILED")
        )
    }
}
