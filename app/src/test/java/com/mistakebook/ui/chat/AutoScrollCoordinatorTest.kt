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

    // ---- v0.0.53：贴底判定与跟随契约 ----

    @Test
    fun flushAtBottomWhenBottomMostItemSitsFlush() {
        // 贴底：最底部可见 item 的 offset = 0
        val info = layoutInfo(listOf(Triple(0, "m0", 0), Triple(1, "m1", 138)))
        assertTrue(isFlushAtBottom(info))
    }

    @Test
    fun notFlushWhenBottomMostItemIsCutOff() {
        // 用户上滑：最底部可见 item 被切掉一截（offset 明显为负）
        val info = layoutInfo(listOf(Triple(0, "m0", -120), Triple(1, "m1", 18)))
        assertFalse(isFlushAtBottom(info))
    }

    @Test
    fun notFlushWhenBottomMostItemIsAboveBottomEdge() {
        // offset 为正 = 该 item 底边在内容区底边**上方** → 下方还有空白，不是贴底
        val info = layoutInfo(listOf(Triple(0, "m0", 200), Triple(1, "m1", 338)))
        assertFalse(isFlushAtBottom(info))
    }

    @Test
    fun flushToleranceIsVeryNarrow() {
        // 用户只上滑 1~2px：仍算贴底（继续跟随，不打断阅读）
        val info = layoutInfo(listOf(Triple(0, "m0", -1), Triple(1, "m1", 137)))
        assertTrue(isFlushAtBottom(info))
        // 上滑 5px：判为离开底部
        val info2 = layoutInfo(listOf(Triple(0, "m0", -5), Triple(1, "m1", 133)))
        assertFalse(isFlushAtBottom(info2))
    }

    @Test
    fun emptyVisibleSetIsNotFlush() {
        assertFalse(isFlushAtBottom(layoutInfo(emptyList(), total = 0)))
    }

    @Test
    fun programmaticScrollFlagSuppressesUserPause() {
        val coordinator = AutoScrollCoordinator()
        assertFalse(coordinator.programmaticScroll)
        coordinator.programmaticScroll = true
        // 处于程序化回底动画中：pause 不应被调用（由调用方守卫）
        coordinator.programmaticScroll = false
        coordinator.pause()
        assertFalse(coordinator.following)
        assertTrue(coordinator.showJumpButton)
    }

    // ---- v0.0.55：gap == null 时区分「插入瞬态」与「用户停在 item 边界」----

    /**
     * 插入瞬态：新消息插到 index 0、还在视口下方，原最新一条（现在是 index 1）
     * 仍贴底。此时贴底的那条的 key 正是上一个最新一条 → 判为瞬态，继续跟随。
     */
    @Test
    fun insertionTransientWhenFlushItemIsThePreviousNewest() {
        assertTrue(
            isInsertionTransient(
                bottomMostKey = "m0",
                bottomMostFlush = true,
                lastNewestKey = "m0"
            )
        )
    }

    /**
     * **本次修复的核心用例**：用户在历史区恰好停在 item 边界上，最底部可见 item
     * 的 offset 同样是 0（与插入瞬态几何上不可区分），但它不是上一个最新一条。
     * 旧实现只凭 `isFlushAtBottom` 就当成瞬态并提前返回，`following` 一直保持
     * true，下一条新消息到达就把人从历史区拽回底部——用户反馈的
     * 「没有触底仍然会跟随」。
     */
    @Test
    fun notTransientWhenUserRestsOnAnItemBoundaryInHistory() {
        assertFalse(
            isInsertionTransient(
                bottomMostKey = "m3",
                bottomMostFlush = true,
                lastNewestKey = "m0"
            )
        )
    }

    /** 贴底的那条被切掉一截 → 用户已经离开底部，绝不是瞬态。 */
    @Test
    fun notTransientWhenBottomMostItemIsCutOff() {
        assertFalse(
            isInsertionTransient(
                bottomMostKey = "m0",
                bottomMostFlush = false,
                lastNewestKey = "m0"
            )
        )
    }

    /**
     * 从没见过最新一条（视口为空 / 刚进页面）时不能判瞬态：
     * `null == null` 会把「空视口」误判成瞬态，从此再也不 pause。
     */
    @Test
    fun notTransientWhenNewestWasNeverSeen() {
        assertFalse(
            isInsertionTransient(
                bottomMostKey = null,
                bottomMostFlush = false,
                lastNewestKey = null
            )
        )
        // 贴底 + 两侧都是 null：同样不能算瞬态（没有证据表明贴底的是旧最新一条）
        assertFalse(
            isInsertionTransient(
                bottomMostKey = null,
                bottomMostFlush = true,
                lastNewestKey = null
            )
        )
    }

    /** newestKeyOf：index 0 在可见集里取它的 key，不在则 null。 */
    @Test
    fun newestKeyOfReadsIndexZeroKey() {
        assertEquals(
            "m0",
            newestKeyOf(layoutInfo(listOf(Triple(0, "m0", 0), Triple(1, "m1", 138))))
        )
        assertNull(
            newestKeyOf(layoutInfo(listOf(Triple(3, "m3", 0), Triple(4, "m4", 138))))
        )
    }
}
