package com.mistakebook.domain

import java.time.LocalDate

/**
 * 复习推进规则（PRD 未给出 6.4 具体间隔表，此处自定并记录到 docs/DECISIONS.md）。
 *
 * 间隔按艾宾浩斯经典节奏：1 / 2 / 4 / 7 / 15 天。
 * - CORRECT：进入下一轮，nextReviewAt = 今天 + 下一轮间隔；
 * - VAGUE：保持当前轮，nextReviewAt = 明天；
 * - WRONG：回到第 0 轮，nextReviewAt = 明天。
 *
 * ## MAX_STAGE 为什么是 6（v0.0.69 修复「15 天档轮空」）
 *
 * 五个间隔（1/2/4/7/15）意味着 **6 个复习节点**：新学 + 五次间隔复习。
 * 旧实现 MAX_STAGE=5：stage4（第 5 次复习）答对直接毕业，+15 天的
 * 最后一次间隔从未发生——文档写的五档实际只走了四档（1/2/4/7）。
 * 现在 stage5 答对才排 +15 天的期末确认，第六次全对（stage6）才毕业。
 */
object ReviewRules {

    val INTERVALS_DAYS = longArrayOf(1, 2, 4, 7, 15)

    /** 毕业 stage：完成全部五档间隔后的最后一次确认通过。 */
    val MAX_STAGE = INTERVALS_DAYS.size + 1

    fun intervalOf(stage: Int): Long = INTERVALS_DAYS[stage.coerceIn(0, INTERVALS_DAYS.lastIndex)]

    fun nextStage(current: Int, result: ReviewResult): Int = when (result) {
        ReviewResult.CORRECT -> (current + 1).coerceAtMost(MAX_STAGE)
        ReviewResult.VAGUE -> current
        ReviewResult.WRONG -> 0
    }

    fun nextReviewDay(current: Int, result: ReviewResult, today: LocalDate): LocalDate = when (result) {
        ReviewResult.CORRECT -> today.plusDays(intervalOf(current))
        ReviewResult.VAGUE -> today.plusDays(1)
        ReviewResult.WRONG -> today.plusDays(1)
    }

    fun isMastered(stage: Int): Boolean = stage >= MAX_STAGE

    fun statusFor(stage: Int, base: MasteryStatus = MasteryStatus.ACTIVE): MasteryStatus =
        if (isMastered(stage)) MasteryStatus.MASTERED else base
}
