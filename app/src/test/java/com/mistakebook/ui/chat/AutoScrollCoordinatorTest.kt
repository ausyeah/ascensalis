package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScrollCoordinatorTest {
    @Test
    fun dragPauseDisablesFollowingUntilResumed() {
        val coordinator = AutoScrollCoordinator()

        coordinator.pause()

        assertFalse(coordinator.following)
        assertTrue(coordinator.showJumpButton)

        coordinator.resume()

        assertTrue(coordinator.following)
        assertFalse(coordinator.showJumpButton)
    }

    // ---- 反向布局的「是否已在底部」几何：gap 折算 afterContentPadding ----

    /** 完全贴底：内容底边与内容区底边重合，gap = 0。 */
    private fun flushGap(viewportEndOffset: Int = 1000, afterPadding: Int = 0) =
        bottomGapOfViewport(
            itemOffset = 900,
            itemSize = 100,
            viewportEndOffset = viewportEndOffset,
            afterContentPadding = afterPadding
        )

    @Test
    fun flushWithViewportBottomIsAtBottom() {
        assertEquals(0, flushGap())
    }

    @Test
    fun bottomPaddingIsFoldedIntoViewportEnd() {
        // viewportEndOffset 含 8dp 内边距（24px）时，折算后贴底仍是 gap = 0。
        // 旧版不折算，靠 12dp 对称容差掩盖这 24px——上滑 12dp 内不停跟。
        assertEquals(0, flushGap(viewportEndOffset = 1024, afterPadding = 24))
    }

    @Test
    fun roundingOvershootYieldsNegativeGap() {
        // 取整误差可能让内容底边略微越过内容区底边，此时间隙为负
        assertEquals(-1, flushGap(viewportEndOffset = 999))
    }

    @Test
    fun scrolledUpYieldsPositiveGap() {
        val gap = bottomGapOfViewport(
            itemOffset = 500,
            itemSize = 100,
            viewportEndOffset = 1000,
            afterContentPadding = 0
        )
        assertEquals(400, gap)
    }

    // ---- 状态机：离开要快，恢复要迟滞 ----

    /** 常见手机的 3dp/2dp 像素量级（@3x）。 */
    private val stateMachine = BottomFollowStateMachine(leaveTolerancePx = 9, resumeTolerancePx = 6)

    @Test
    fun staysAtBottomWithinLeaveTolerance() {
        assertTrue(stateMachine.onGap(0))
        assertTrue(stateMachine.onGap(5)) // 贴底与取整误差之间
        assertTrue(stateMachine.onGap(-20)) // padding 内（负值）仍在底部
        assertTrue(stateMachine.onGap(9)) // 恰好等于离开容差，仍在底部
    }

    @Test
    fun leavesBottomImmediatelyBeyondLeaveTolerance() {
        assertTrue(stateMachine.onGap(0))
        // 上滑 10px（> 3dp@3x）→ 单帧立即判不在底部
        assertFalse(stateMachine.onGap(10))
    }

    @Test
    fun doesNotResumeOnSingleFrameJitter() {
        stateMachine.onGap(0)
        assertFalse(stateMachine.onGap(50)) // 用户上滑 → 暂停
        // 布局抖动把 gap 拉回 0：单帧不恢复
        assertFalse(stateMachine.onGap(0))
        // 抖动又把 gap 推开：连击清零，之后需要重新连续两帧贴底
        assertFalse(stateMachine.onGap(50))
        assertFalse(stateMachine.onGap(0)) // 第 1 帧
        assertTrue(stateMachine.onGap(0)) // 第 2 帧 → 恢复
    }

    @Test
    fun resumesOnlyAfterTwoConsecutiveFramesAtBottom() {
        stateMachine.onGap(0)
        assertFalse(stateMachine.onGap(100)) // 暂停
        assertFalse(stateMachine.onGap(5)) // 第 1 帧贴底（|5| <= 6）
        assertTrue(stateMachine.onGap(0)) // 第 2 帧贴底 → 恢复
    }

    @Test
    fun resumeStreakResetsWhenFrameLeavesResumeZone() {
        stateMachine.onGap(0)
        assertFalse(stateMachine.onGap(100)) // 暂停
        assertFalse(stateMachine.onGap(5)) // 第 1 帧贴底
        assertFalse(stateMachine.onGap(300)) // 又滑走了 → 连击清零
        assertFalse(stateMachine.onGap(2)) // 重新第 1 帧
        assertTrue(stateMachine.onGap(0)) // 第 2 帧 → 恢复
    }

    @Test
    fun newestItemOutOfViewportIsNeverAtBottom() {
        stateMachine.onGap(0)
        // null = index 0 不在可见集（用户在看历史）
        assertFalse(stateMachine.onGap(null))
        // 恢复仍要走迟滞
        assertFalse(stateMachine.onGap(3))
        assertTrue(stateMachine.onGap(0))
    }

    @Test
    fun emptyListCountsAsBottom() {
        val fresh = BottomFollowStateMachine(9, 6)
        assertTrue(fresh.onGap(0))
    }

    @Test
    fun resumeZoneIsAsymmetricAboutZeroButSymmetricInTolerance() {
        val m = BottomFollowStateMachine(leaveTolerancePx = 9, resumeTolerancePx = 6)
        m.onGap(0)
        assertFalse(m.onGap(200))
        // 负 gap（内容伸进 padding）在恢复容差内也算贴底
        assertFalse(m.onGap(-3)) // 第 1 帧
        assertTrue(m.onGap(-2)) // 第 2 帧（|-2| <= 6）→ 恢复
    }
}
