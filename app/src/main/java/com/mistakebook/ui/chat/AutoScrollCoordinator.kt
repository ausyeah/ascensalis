package com.mistakebook.ui.chat

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

/** Scroll ownership for a reverse-layout chat list. */
class AutoScrollCoordinator {
    var following by mutableStateOf(true)
        private set

    var showJumpButton by mutableStateOf(false)
        internal set

    fun pause() {
        following = false
        showJumpButton = true
    }

    fun resume() {
        following = true
        showJumpButton = false
    }

    suspend fun jumpToBottom(state: LazyListState) {
        if (state.layoutInfo.totalItemsCount > 0) {
            state.animateScrollToItem(0)
        }
        resume()
    }
}

/**
 * 触底跟随判定状态机（纯逻辑，可单测）。
 *
 * ## 为什么容差必须非对称 + 迟滞
 *
 * 旧版用 `|gap| <= 12dp` 的对称大容差：用户上滑一小段（小于 12dp）几何上仍判
 * 「在底部」，流式输出继续跟随、把人拽回底边——真机反馈「想停停不下来」。
 * 12dp 的历史原因是底部 contentPadding 让完全贴底时 gap 为负，容差不放大就
 * 恢复不了；但正确解法是**折算掉 padding**（见 [bottomGapOfViewport]），
 * 而不是放大容差。
 *
 * 现在的判据分两个方向：
 * - **离开**（跟随 → 暂停）：gap > [leaveTolerancePx] 立即判不在底部，
 *   单帧生效——用户上滑超过 3dp 就要停，响应要快；
 * - **恢复**（暂停 → 跟随）：|gap| <= [resumeTolerancePx] 且**连续
 *   [RESUME_FRAMES] 帧**成立才恢复——公式位图到位、消息插入等布局抖动
 *   造成的一两帧几何回摆，不得把人拽回底部。
 *
 * @param gap 最新一条消息底边与内容区底边的间隙（px），见 [bottomGapOfViewport]；
 *   `null` 表示最新一条根本不在可见集里（用户在看历史），必然不在底部。
 */
internal class BottomFollowStateMachine(
    private val leaveTolerancePx: Int,
    private val resumeTolerancePx: Int
) {
    /** 当前是否判「在底部」。与跟随状态同向，由 [onGap] 驱动。 */
    var atBottom: Boolean = true
        private set

    /** 恢复判据已连续成立的帧数。 */
    private var resumeStreak = 0

    /**
     * 喂入一帧的间隙，返回本帧之后是否判「在底部」。
     * 返回值同时是状态机的最新状态。
     */
    fun onGap(gap: Int?): Boolean {
        val candidate = when {
            gap == null -> false
            atBottom -> gap <= leaveTolerancePx
            else -> gap in -resumeTolerancePx..resumeTolerancePx
        }
        if (candidate == atBottom) {
            resumeStreak = 0
            return atBottom
        }
        if (!atBottom && candidate) {
            // 迟滞：恢复判据要连续多帧成立，单帧回摆不恢复
            resumeStreak++
            if (resumeStreak < RESUME_FRAMES) return atBottom
        }
        atBottom = candidate
        resumeStreak = 0
        return atBottom
    }

    companion object {
        /** 恢复跟随要求的连续帧数（迟滞防抖）。 */
        const val RESUME_FRAMES = 2
    }
}

/**
 * 反向列表（index 0 = 最新一条，贴底）的滚动跟随。
 *
 * ## 谁在滚动
 *
 * 「是否跟随」由**几何位置 + 迟滞状态机**驱动，不依赖松手时机：
 * - 上滑离开底部超过 [LEAVE_TOLERANCE_DP] → 立即暂停（用户在上面看历史，
 *   新内容不得拽走）；
 * - 回到底部（|gap| <= [RESUME_TOLERANCE_DP] 连续两帧）→ 才恢复跟随。
 *
 * `isDragged`（按住）只是**提前**暂停的输入：手一按就停，响应更快；
 * 即使拖动手势被气泡内子元素（公式图、图片）消费导致 `isDragged` 不翻，
 * 位置驱动那一路也会在离开底部 3dp 时立刻接手暂停。
 *
 * 暂停期间新内容增长不得掀动用户视口：流式气泡把锚点 item 撑高时，
 * 按增长量补偿滚动偏移（见下方 LaunchedEffect）。
 */
@Composable
fun rememberAutoScrollEffect(
    state: LazyListState,
    latestItemKey: Long?
): AutoScrollCoordinator {
    val coordinator = remember { AutoScrollCoordinator() }
    val isDragged by state.interactionSource.collectIsDraggedAsState()
    val leaveTolerancePx = with(LocalDensity.current) { LEAVE_TOLERANCE_DP.roundToPx() }
    val resumeTolerancePx = with(LocalDensity.current) { RESUME_TOLERANCE_DP.roundToPx() }
    val policy = remember(leaveTolerancePx, resumeTolerancePx) {
        BottomFollowStateMachine(leaveTolerancePx, resumeTolerancePx)
    }

    // 手指按住立即交出滚动权（响应快）；松手后的恢复交给下面的位置驱动。
    LaunchedEffect(isDragged) {
        if (isDragged) coordinator.pause()
    }

    // 位置驱动 + 迟滞：离开底部立即暂停，回到底部连续两帧才恢复。
    // pause()/resume() 会同步维护 showJumpButton。
    LaunchedEffect(state, policy) {
        snapshotFlow { newestGapOf(state.layoutInfo) }
            .collect { gap ->
                if (policy.onGap(gap)) coordinator.resume() else coordinator.pause()
            }
    }

    // 暂停期间的内容增长补偿。
    //
    // 反向布局下用户上滑后，锚点 item（firstVisibleItemIndex == 0）就是正在
    // 流式增长的气泡：它每长高一点，顶边就上移一点，把用户正看的历史内容
    // 往上顶——体感同样是「还在跟随」。按增长量把滚动偏移补回去，顶边不动。
    // 新消息**插入**（index 前移）由反向布局自身的锚定机制保证不动视口，
    // 不需要这里管。用户主动滚动（isScrollInProgress）期间不补偿，
    // 松手后的新位置就是新的基准。
    LaunchedEffect(state, coordinator) {
        var lastNewestSize = -1
        snapshotFlow {
            val newest = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
            Triple(coordinator.following, state.isScrollInProgress, newest?.size ?: -1)
        }.collect { (following, scrolling, newestSize) ->
            if (!following && !scrolling &&
                lastNewestSize > 0 && newestSize > lastNewestSize &&
                state.firstVisibleItemIndex == 0
            ) {
                val delta = newestSize - lastNewestSize
                state.scrollToItem(0, state.firstVisibleItemScrollOffset + delta)
            }
            lastNewestSize = newestSize
        }
    }

    // 只在「最新一条的 key 变了」时滚。流式期间 key 不变：
    // 气泡长高由反向布局自己锚在底边，不需要反复重启滚动动画。
    LaunchedEffect(latestItemKey, coordinator.following) {
        if (coordinator.following && latestItemKey != null) {
            snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
            // 双保险（v0.0.35）：动画启动**前**再验一次仍在底部。
            //
            // following 的翻转经 snapshotFlow 异步落地，effect 重启（key 变化）
            // 与位置驱动的 pause() 之间存在竞态窗口——窗口里发起的
            // animateScrollToItem 会把正往上翻历史的人一把拽回底部。
            // 动画前按当前几何复核一次：用与「保持跟随」同向的判据
            // （gap <= leaveTol，负值 = padding 内/取整误差 = 贴底）。
            // 复核不能只问「index 0 在不在底部」：新消息插入的**瞬间**它还在
            // 视口下方、不在可见集里，按旧判据会误判「不在底部」而干脆不滚——
            // 正是「发送新消息不自动跳到底部」的根因。反向布局下插入一条新
            // 消息时旧末条仍锚在视口底边（gap≈0），所以只要视觉最底部的可见
            // item 贴着底边，就说明插入前用户确实在底部，应该跟随。
            val info = state.layoutInfo
            val newestVisible = info.visibleItemsInfo.any { it.index == 0 }
            val bottomMost = info.visibleItemsInfo.firstOrNull()
            val flushAtBottom = bottomMost != null &&
                bottomGapOfViewport(
                    itemOffset = bottomMost.offset,
                    itemSize = bottomMost.size,
                    viewportEndOffset = info.viewportEndOffset,
                    afterContentPadding = info.afterContentPadding
                ) <= leaveTolerancePx
            if (coordinator.following && (newestVisible || flushAtBottom)) {
                state.animateScrollToItem(0)
            }
        }
    }

    return coordinator
}

/**
 * 最新一条消息底边与「内容区底边」的间隙（px）。纯函数，可单测。
 *
 * ## 为什么必须折算 afterContentPadding
 *
 * `viewportEndOffset` 是否把 `contentPadding` 算进去，两种约定都可能出现在
 * 真机上：完全停在底部时 `gap` 可能是 0，也可能是**负值**（底部 8dp 内边距
 * 被算进视口）。不折算的话，「贴底」的真实判据区间是 [-8dp, +取整误差]，
 * 对称容差时代靠把容差放大到 12dp 掩盖——代价是上滑 12dp 内都判在底部。
 * 现在先减去 [LazyListLayoutInfo.afterContentPadding] 把内容区底边对齐，
 * 容差就可以收紧到 2–3dp。
 */
internal fun bottomGapOfViewport(
    itemOffset: Int,
    itemSize: Int,
    viewportEndOffset: Int,
    afterContentPadding: Int
): Int = (viewportEndOffset - afterContentPadding) - (itemOffset + itemSize)

/**
 * 取最新一条消息（index 0）与内容区底边的间隙。
 * 列表为空视为贴底（0）；index 0 不在可见集里返回 null（必然不在底部）。
 */
private fun newestGapOf(info: LazyListLayoutInfo): Int? {
    if (info.totalItemsCount == 0) return 0
    val newest = info.visibleItemsInfo.firstOrNull { it.index == 0 } ?: return null
    return bottomGapOfViewport(
        itemOffset = newest.offset,
        itemSize = newest.size,
        viewportEndOffset = info.viewportEndOffset,
        afterContentPadding = info.afterContentPadding
    )
}

/** 上滑离开底部超过这个距离立即判「不在底部」（2–4dp 取中，响应优先）。 */
private val LEAVE_TOLERANCE_DP = 3.dp

/** 恢复跟随要求的贴底精度：|gap| <= 该值且连续两帧。 */
private val RESUME_TOLERANCE_DP = 2.dp
