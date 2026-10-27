package com.mistakebook.ui.chat

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- 反向布局的贴底几何：间隙 = item 0 的 offset（Paparazzi 实测语义）----

    /** 假 layoutInfo：只填判定用到的字段，数值取自探针实测（Pixel5 / 8dp / 50dp item）。 */
    private fun layoutInfo(
        items: List<Triple<Int, Any, Int>>, // (index, key, offset)
        total: Int = items.size,
        itemSize: Int = 138,
        viewportEnd: Int = 2318
    ) = object : LazyListLayoutInfo {
        override val visibleItemsInfo: List<LazyListItemInfo> = items.map { (index, key, offset) ->
            object : LazyListItemInfo {
                override val index: Int = index
                override val key: Any = key
                override val offset: Int = offset
                override val size: Int = itemSize
                override val contentType: Any? = null
            }
        }
        override val totalItemsCount: Int = total
        override val viewportStartOffset: Int = -22
        override val viewportEndOffset: Int = viewportEnd
        override val viewportSize: IntSize = IntSize(1080, 2340)
        override val orientation: androidx.compose.foundation.gestures.Orientation =
            androidx.compose.foundation.gestures.Orientation.Vertical
        override val reverseLayout: Boolean = true
        override val beforeContentPadding: Int = 22
        override val afterContentPadding: Int = 22
        override val mainAxisItemSpacing: Int = 0
    }

    @Test
    fun flushAtRestYieldsZeroGap() {
        // 实测：贴底时最新一条 offset = 0（底边与内容区底边重合）
        val gap = newestGapOf(layoutInfo(listOf(Triple(0, "m0", 0), Triple(1, "m1", 138))))
        assertEquals(0, gap)
    }

    @Test
    fun scrolledAwayWithinNewestItemYieldsNegativeGap() {
        // 实测：state(0, offset=100) → item 0 的 offset = -100（被切出内容区底边 100px）
        val gap = newestGapOf(layoutInfo(listOf(Triple(0, "m0", -100), Triple(1, "m1", 38))))
        assertEquals(-100, gap)
    }

    @Test
    fun newestItemOutOfVisibleSetYieldsNull() {
        // 用户翻过最新一条：index 0 不在可见集里
        val gap = newestGapOf(layoutInfo(listOf(Triple(3, "m3", 0), Triple(4, "m4", 138))))
        assertNull(gap)
    }

    @Test
    fun emptyListCountsAsFlush() {
        assertEquals(0, newestGapOf(layoutInfo(emptyList(), total = 0)))
    }

    // ---- 状态机：以 0 为中心的小带；离开单帧生效，恢复要两帧迟滞 ----

    /** 常见手机的 3dp/2dp 像素量级（@3x）。 */
    private val stateMachine = BottomFollowStateMachine(leaveTolerancePx = 9, resumeTolerancePx = 6)

    @Test
    fun staysAtBottomWithinToleranceBand() {
        assertTrue(stateMachine.onGap(0))
        assertTrue(stateMachine.onGap(5)) // 贴底与取整误差之间
        assertTrue(stateMachine.onGap(-3)) // 轻微回摆仍在带内
        assertTrue(stateMachine.onGap(9)) // 恰好等于离开容差，仍在底部
    }

    @Test
    fun leavesBottomImmediatelyWhenScrolledAway() {
        assertTrue(stateMachine.onGap(0))
        // 滚离底部 20px（item 0 被切出内容区底边）→ 单帧立即判不在底部。
        // 旧版把负 gap 一律当「在底部」，这就是「上滑了还在跟随」的根因。
        assertFalse(stateMachine.onGap(-20))
    }

    @Test
    fun leavesBottomImmediatelyBeyondLeaveToleranceUpward() {
        assertTrue(stateMachine.onGap(0))
        assertFalse(stateMachine.onGap(10)) // 上滑 10px（> 3dp@3x）
    }

    @Test
    fun doesNotResumeOnSingleFrameJitter() {
        stateMachine.onGap(0)
        assertFalse(stateMachine.onGap(-50)) // 用户滚离 → 暂停
        // 布局抖动把 gap 拉回 0：单帧不恢复
        assertFalse(stateMachine.onGap(0))
        // 抖动又把 gap 推开：连击清零，之后需要重新连续两帧贴底
        assertFalse(stateMachine.onGap(-50))
        assertFalse(stateMachine.onGap(0)) // 第 1 帧
        assertTrue(stateMachine.onGap(0)) // 第 2 帧 → 恢复
    }

    @Test
    fun resumesOnlyAfterTwoConsecutiveFramesAtBottom() {
        stateMachine.onGap(0)
        assertFalse(stateMachine.onGap(-100)) // 暂停
        assertFalse(stateMachine.onGap(-5)) // 第 1 帧贴底（|-5| <= 6）
        assertTrue(stateMachine.onGap(0)) // 第 2 帧贴底 → 恢复
    }

    @Test
    fun resumeStreakResetsWhenFrameLeavesResumeZone() {
        stateMachine.onGap(0)
        assertFalse(stateMachine.onGap(-100)) // 暂停
        assertFalse(stateMachine.onGap(-5)) // 第 1 帧贴底
        assertFalse(stateMachine.onGap(-300)) // 又滑走了 → 连击清零
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
    fun resumeZoneIsSymmetricAroundZero() {
        val m = BottomFollowStateMachine(leaveTolerancePx = 9, resumeTolerancePx = 6)
        m.onGap(0)
        assertFalse(m.onGap(-200)) // 深滚离
        // 负 gap 在恢复容差内也算贴底（与正 gap 对称）
        assertFalse(m.onGap(-3)) // 第 1 帧
        assertTrue(m.onGap(-2)) // 第 2 帧（|-2| <= 6）→ 恢复
    }
}
