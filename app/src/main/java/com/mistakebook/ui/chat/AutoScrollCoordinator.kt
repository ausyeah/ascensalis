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
import kotlin.math.roundToInt
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
 * ## 间隙的正确语义（v0.0.52 修正，Paparazzi 实测）
 *
 * 旧版把 `(viewportEndOffset - afterContentPadding) - (itemOffset + itemSize)`
 * 当作「最新一条底边与内容区底边的间隙」——那是**正序布局**的算法。反向布局下
 * `item.offset` 本身就是「内容区底边 - item 底边」（实测：贴底时 item 0 的
 * offset = 0，被往下滚出 d px 时 offset = -d），旧公式在贴底时算出的是
 * 「视口高 - item 高」这种量级（≈2000px），于是：
 *
 * - 贴底时 gap ≈ 2000 > 离开容差 → 第一帧就判「不在底部」→ **触底不跟随**；
 * - 用户滚到列表接近最顶时，旧公式偶然落进恢复容差 → 恢复跟随并把人拽回底部
 *   ——**不触底有的时候又在跟随**。
 *
 * 现在 [newestGapOf] 直接返回 item 0 的 offset：贴底 = 0，滚离 = 负，
 * index 0 不在可见集 = null。判据因此变成以 0 为中心的小带：
 * - **离开**（跟随 → 暂停）：gap 跌出 `[-resumeTol, +leaveTol]` 单帧生效；
 * - **恢复**（暂停 → 跟随）：gap 落回 `[-resumeTol, +resumeTol]` 且连续
 *   [RESUME_FRAMES] 帧成立（公式位图到位等布局抖动造成的一两帧回摆，
 *   不得把人拽回底部）。
 *
 * @param gap 最新一条消息底边与内容区底边的间隙（px），见 [newestGapOf]；
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
            atBottom -> gap in -resumeTolerancePx..leaveTolerancePx
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
 * - 用户滚离底部（锚点序号变大 / 同一锚点被切得更多）→ 立即暂停；
 * - 用户回到底部（|gap| ≤ 2dp 连续两帧）→ 才恢复跟随。
 *
 * `isDragged`（按住）只是**提前**暂停的输入：手一按就停，响应更快；
 * 即使拖动手势被气泡内子元素（公式图、图片）消费导致 `isDragged` 不翻，
 * 位置驱动那一路也会在离开底部时立刻接手暂停。
 *
 * **布局瞬态不暂停**：新消息插入的瞬间，最新一条还在视口下方、不在可见集里
 * （gap = null）。若据此暂停，跟随会被插入动作本身掐断——发送新消息后不再
 * 自动回底。所以位置驱动那一路只在「用户正在滚动且正在滚离」时暂停；
 * 自己发起的回底动画（锚点序号递减）不算滚离。
 *
 * 暂停期间新内容增长不得掀动用户视口：按「锚点 item 顶边」补偿滚动位置
 * （见下方 LaunchedEffect，保持首可见项稳定，泛化到任意滚动位置）。
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

    // 位置驱动 + 迟滞：回到底部连续两帧才恢复；只有用户主动滚离才暂停。
    LaunchedEffect(state, policy) {
        var lastAnchorIndex = -1
        var lastAnchorOffset = 0
        snapshotFlow {
            val gap = newestGapOf(state.layoutInfo)
            Triple(gap, policy.onGap(gap), state.isScrollInProgress)
        }.collect { (_, atBottom, scrolling) ->
            val anchorIndex = state.firstVisibleItemIndex
            val anchorOffset = state.firstVisibleItemScrollOffset
            // 「正在滚离底部」：锚点序号变大（翻过更新的消息），或同一个锚点
            // 被切掉更多（state offset 变大 = item 底边越低出内容区底边）。
            // 回底动画与用户往回滚都是序号递减/offset 递减，不算滚离。
            val movingAway = anchorIndex > lastAnchorIndex ||
                (anchorIndex == lastAnchorIndex && anchorOffset > lastAnchorOffset)
            lastAnchorIndex = anchorIndex
            lastAnchorOffset = anchorOffset
            when {
                atBottom -> coordinator.resume()
                scrolling && movingAway -> coordinator.pause()
            }
        }
    }

    // 暂停期间的内容增长补偿（保持锚点 item 的顶边屏幕位置不动）。
    //
    // 反向布局下，底边的新内容长高（流式气泡、新消息插入后的首条）会把锚点
    // 连同它上面的内容一起往上顶——体感同样是「还在跟随」。这里以「最底部
    // 可见 item 的顶边 Y」为锚：跟随时/滚动中持续重设基准；暂停且静止时
    // 若顶边被顶动了，就 scrollToItem 把它拨回原位。
    //
    // 锚点按 key 找（不按 index）：新消息插入会让 index 整体前移，key 稳定。
    // 补偿量换算：scrollToItem 的第二参数量 = -锚点 item.offset，
    // 而 item.offset = viewportEnd - item 顶边Y - item.size，代入即得。
    LaunchedEffect(state, coordinator) {
        var anchorKey: Any? = null
        var anchorTopY = 0f
        snapshotFlow {
            val info = state.layoutInfo
            val bottomMost = info.visibleItemsInfo.firstOrNull()
            Triple(coordinator.following, state.isScrollInProgress, info to bottomMost)
        }.collect { (following, scrolling, pair) ->
            val (info, bottomMost) = pair
            val topOf = { item: androidx.compose.foundation.lazy.LazyListItemInfo ->
                info.viewportEndOffset - item.offset - item.size
            }
            // 跟随时/滚动中：以当前位置为新的锚点基准（key 非空，无需判空）
            if (following || scrolling || bottomMost == null) {
                anchorKey = bottomMost?.key
                anchorTopY = bottomMost?.let { topOf(it).toFloat() } ?: 0f
                return@collect
            }
            if (anchorKey == null) {
                anchorKey = bottomMost.key
                anchorTopY = topOf(bottomMost).toFloat()
                return@collect
            }
            val anchored = info.visibleItemsInfo.firstOrNull { it.key == anchorKey }
            if (anchored == null) {
                // 锚点被滚出视口：放弃补偿（用户在自由阅读别处）
                anchorKey = null
                return@collect
            }
            val currentTop = topOf(anchored)
            if (currentTop.toFloat() != anchorTopY) {
                val target = (anchorTopY + anchored.size - info.viewportEndOffset).roundToInt()
                state.scrollToItem(anchored.index, target)
            }
        }
    }

    // 只在「最新一条的 key 变了」或「跟随状态翻转」时滚。流式期间 key 不变：
    // 气泡长高由反向布局自己锚在底边，不需要反复重启滚动动画。
    LaunchedEffect(latestItemKey, coordinator.following) {
        if (coordinator.following && latestItemKey != null) {
            snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
            // 双保险（v0.0.35，v0.0.52 按实测语义重写）：动画启动**前**再验
            // 一次仍在底部。反向布局语义（Paparazzi 实测，见 [newestGapOf]）：
            // item.offset = 内容区底边 - item 底边，贴底 = 0。所以：
            // - 最新一条可见且贴底 → 在底部；
            // - 最新一条还在视口下方（新消息插入瞬间）时，最底部可见 item 贴底
            //   （offset ≈ 0）同样说明插入前用户就在底部，应该跟随。
            // 用户滚离后最底部可见 item 的 offset 是负的（被切），不会误判。
            val info = state.layoutInfo
            val newest = info.visibleItemsInfo.firstOrNull { it.index == 0 }
            val bottomMost = info.visibleItemsInfo.firstOrNull()
            val band = -resumeTolerancePx..leaveTolerancePx
            val atBottomNow = newest != null && newest.offset in band
            val flushAtBottom = bottomMost != null && bottomMost.offset in band
            if (coordinator.following && (atBottomNow || flushAtBottom)) {
                state.animateScrollToItem(0)
            }
        }
    }

    return coordinator
}

/**
 * 最新一条消息（index 0）底边与「内容区底边」的间隙（px）。纯函数，可单测。
 *
 * ## 语义来自 Paparazzi 实测（Pixel5 / 8dp 竖向内边距 / 50dp item）
 *
 * - 反向布局下 `contentPadding` **不镜像**：`beforeContentPadding` 在顶部、
 *   `afterContentPadding` 在底部；`viewportEndOffset` = 屏高 - afterPadding，
 *   即内容区底边；
 * - `LazyListItemInfo.offset` = 内容区底边 - item 底边屏幕坐标。贴底时
 *   item 0 的 offset = 0；用户把它往下滚出 d px（看历史）时 offset = -d。
 *
 * 所以间隙就是 offset 本身，**不需要**再折 `afterContentPadding`——
 * 旧版按正序布局的公式折了一次，贴底时算出 ≈ 视口高，是 T1 回归的根因。
 *
 * 列表为空视为贴底（0）；index 0 不在可见集里返回 null（必然不在底部）。
 */
internal fun newestGapOf(info: LazyListLayoutInfo): Int? {
    if (info.totalItemsCount == 0) return 0
    val newest = info.visibleItemsInfo.firstOrNull { it.index == 0 } ?: return null
    return newest.offset
}

/** 上滑离开底部超过这个距离立即判「不在底部」（2–4dp 取中，响应优先）。 */
private val LEAVE_TOLERANCE_DP = 3.dp

/** 恢复跟随要求的贴底精度：|gap| <= 该值且连续两帧。 */
private val RESUME_TOLERANCE_DP = 2.dp
