package com.mistakebook.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/** Scroll ownership for a reverse-layout chat list. */
class AutoScrollCoordinator {
    /**
     * 是否处于「触底跟随」状态。
     *
     * 语义：**只有贴底时才跟随**。用户上滑离开底部 → 立即变成 false；
     * 回到底部 → 恢复 true。
     */
    var following by mutableStateOf(true)
        private set

    /** 是否显示「回到底部」悬浮按钮。 */
    var showJumpButton by mutableStateOf(false)
        internal set

    /**
     * 本 App 自己发起的滚动（回底动画）进行中。
     *
     * 这段时间里位置驱动那一路**不得**把状态改成「不跟随」——
     * 否则回底动画刚起步就被自己的中间帧打断，表现为「点按钮也回不去」。
     */
    var programmaticScroll by mutableStateOf(false)
        internal set

    /** 用户手指接触屏幕（或已滚离底部）：交出滚动权。 */
    fun pause() {
        following = false
        showJumpButton = true
    }

    /** 回到触底：恢复跟随。 */
    fun resume() {
        following = true
        showJumpButton = false
    }

    /**
     * 点「回到底部」：滑到底部并恢复跟随。
     *
     * 动画期间置 [programmaticScroll]，避免位置驱动把回底动画误判成
     * 「用户又滚走了」。
     */
    suspend fun jumpToBottom(state: LazyListState) {
        if (state.layoutInfo.totalItemsCount > 0) {
            programmaticScroll = true
            try {
                state.animateScrollToItem(0)
            } finally {
                programmaticScroll = false
            }
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
 * ## 用户要求的行为契约（v0.0.53）
 *
 * 1. **触底就跟随**：贴在底部时，新消息/新内容自动保持在底边；
 * 2. **手接触就停止跟随**：手指一按上去，立刻交出滚动权；
 * 3. **点向下按钮就滑到触底然后跟随**：按钮 → 动画回底 → 恢复跟随；
 * 4. **一旦不触底就不跟随**：离开底部后，任何新内容都不再把列表拽回去。
 *
 * ## 为什么 v0.0.52 还会「一跳一跳」
 *
 * 老实现有两条互相打架的滚动路径：
 * - 「暂停期间的内容增长补偿」：每帧比较锚点 item 顶边的 Y 值，
 *   不等就 `scrollToItem` 把它拨回去；
 * - 「跟随时的回底动画」：`animateScrollToItem(0)`。
 *
 * 前者的判据是**精确浮点不等**（`currentTop != anchorTopY`），而它拨回去
 * 的量又是用 `scrollToItem` 的绝对偏移算的——两者的坐标基准并不严格一致，
 * 于是每一帧都在「拨过去 → 差一点点 → 再拨」，用户看到的就是列表在
 * **不触底时也一跳一跳**（v0.0.52 用户反馈原话）。
 *
 * 现在把这件事彻底简化：
 * - **跟随与否只看几何**（贴底判定），不再有「补偿滚动」这条路径；
 * - 跟随的回底只在**新消息到达**这一个时刻发生一次（key 变化驱动），
 *   流式增长期间不重复重启动画；
 * - 「是否跟随」与「要不要回底」解耦：回底与否用**紧贴底的窄带**判定，
 *   用户哪怕上滑 3px 也不算贴底，不会被莫名拽回底部。
 *
 * @param state 反向布局的列表状态；
 * @param latestItemKey 最新一条消息的 key，变化 = 有新消息到达。
 */
@Composable
fun rememberAutoScrollEffect(
    state: LazyListState,
    latestItemKey: Long?
): AutoScrollCoordinator {
    val coordinator = remember { AutoScrollCoordinator() }
    val isDragged by state.interactionSource.collectIsDraggedAsState()
    // collect{} 里读的是**组合期捕获**的值，会一直是旧的。
    // 手指状态必须用 rememberUpdatedState 才能拿到实时值。
    val draggedNow by rememberUpdatedState(isDragged)
    val leaveTolerancePx = with(LocalDensity.current) { LEAVE_TOLERANCE_DP.roundToPx() }
    val resumeTolerancePx = with(LocalDensity.current) { RESUME_TOLERANCE_DP.roundToPx() }
    val policy = remember(leaveTolerancePx, resumeTolerancePx) {
        BottomFollowStateMachine(leaveTolerancePx, resumeTolerancePx)
    }

    // ① 手指接触屏幕：立刻交出滚动权（比等位置变化更快，用户「手一碰就知道
    //    列表不会自己跑了」）。
    LaunchedEffect(isDragged) {
        if (isDragged) coordinator.pause()
    }

    // ② 位置驱动：贴底 → 跟随；离开底部 → 停止跟随并显示回底按钮。
    //
    //    手指还按着的时候**不判**位置：用户正在拖拽，位置每帧都在变，
    //    此刻判出来的「在不在底部」没有意义。
    LaunchedEffect(state, policy) {
        snapshotFlow { newestGapOf(state.layoutInfo) }
            .collect { gap ->
                if (draggedNow || coordinator.programmaticScroll) return@collect
                if (gap == null) {
                    // index 0 不在可见集。两种可能：
                    // - 「新消息刚插入、还没进视口」的瞬态 → 底部仍贴着，不动；
                    // - 用户翻到了历史 → 不在底部，暂停跟随。
                    if (isFlushAtBottom(state.layoutInfo)) return@collect
                    policy.onGap(null)
                    coordinator.pause()
                    return@collect
                }
                if (policy.onGap(gap)) coordinator.resume() else coordinator.pause()
            }
    }

    // ③ 新消息到达 → 只有「本来就在底部」才回底。
    //
    //    判据用**跟随态本身**，而且必须在拿到 key 变化的那一刻读：
    //    `wasFollowing` 在 effect 重启（= 新消息插入）时定格。
    //    不能等到布局后再判 `isFlushAtBottom`：
    //    - 用户在历史区恰好停在 item 边界时，最底部可见 item 的 offset
    //      也是 0 → 旧判据会把它误判成「插入瞬态」，从历史区硬拽回底
    //      （v0.0.54 用户反馈「未触底也会自动往下滚」的来源之一）；
    //    - 反过来，真在底部时新 item 插入的瞬间 gap 会变成负的
    //      （新气泡落在视口下沿之外），位置驱动那一路会先一步 pause——
    //      布局后再读 following 就会错过这次回底。
    //    所以：插入前的跟随态才是「用户本来在不在底部」的权威判据。
    LaunchedEffect(latestItemKey) {
        if (latestItemKey == null) return@LaunchedEffect
        val wasFollowing = coordinator.following
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        // 等一帧：确保新消息已经进布局（index 0 已是最新那条），
        // 否则 animateScrollToItem(0) 会滚到「上一条」上。
        withFrameNanos { }
        if (draggedNow || coordinator.programmaticScroll || !wasFollowing) return@LaunchedEffect
        coordinator.programmaticScroll = true
        try {
            state.animateScrollToItem(0)
        } finally {
            coordinator.programmaticScroll = false
        }
    }

    // ④ 非跟随期间的**滚动锚定**：流式内容变长时视口保持视觉静止。
    //
    //    ## 为什么需要它
    //
    //    反向布局的滚动位置 =「第一个可见 item + 它在布局里的偏移」，
    //    而布局坐标是从**最新一条**开始往下数的。最新一条每长高 Δ，
    //    它上面所有历史 item 的布局坐标都 +Δ，屏幕位置整体上移 Δ——
    //    用户正在读的段落被强行往上拽。用户反馈「没触底也在自动
    //    往下滚」的正是这个（v0.0.54）。
    //
    //    ## 为什么用「最顶部可见 item 的 offset」当锚点
    //
    //    按仓库实测语义（见 [newestGapOf] 注释）：
    //        offset = 该 item 的布局起点 - 滚动量
    //    - 内容长高 Δ（最新一条长高 / 插入新消息）→ 历史 item 布局起点 +Δ
    //          → offset **增加** Δ；
    //    - 用户向前滚（手指上滑，看历史）→ 滚动量 +d → offset **减少** d。
    //    两者符号相反，所以「offset 变正」几乎只能是内容增长造成的。
    //    再叠一层 `isScrollInProgress == false`（列表静止）兜底：
    //    用户手势 / 惯性滑动期间一律不插手，绝不跟手指打架。
    //
    //    选最顶部可见 item 而不是最新一条：用户翻进历史区后最新一条
    //    早已不可见，拿它当锚点根本观察不到增长（v0.0.54 第一版补偿
    //    就是这个漏洞——只覆盖「最新一条还可见」的情况）。
    //    最顶部可见 item 是用户正在读的那条，它在不在视口里都能反映
    //    上方内容的变化，两种情况一套逻辑全覆盖。
    //
    //    补偿量 = offset 增量，方向是 `scrollBy(+Δ)`：把长出来的部分
    //    吃进滚动量里，锚点屏幕位置纹丝不动。
    //
    //    ## 为什么「刚静止的首帧只重取基线」
    //
    //    用户向下 fling（朝最新消息去）但停在半路时，offset 同样会变大
    //    ——滚动量减少了。若不区分，补偿会把这个 offset 增量当成内容增长，
    //    反向 scrollBy 把用户刚滑到的位置又推回去，等于跟惯性滑动打架。
    //    所以凡是「上一帧还在动」的静止首帧，一律只重取基线不补偿：
    //    手势的结果优先，内容增长的补偿从下一个静止帧开始。
    LaunchedEffect(state, coordinator) {
        var anchorKey: Any? = null
        var anchorOffset = 0
        var wasScrolling = false
        snapshotFlow {
            val topMost = state.layoutInfo.visibleItemsInfo.lastOrNull()
            if (topMost == null) null else Triple(topMost.key, topMost.offset, state.isScrollInProgress)
        }.collect { info ->
            // 视口里一条都没有（极端短列表 / 正在重建）：无从锚定
            if (info == null) return@collect
            val (key, offset, scrolling) = info
            // 列表正在动（用户手势 / 惯性 / 自己的回底动画）：不插手，
            // 也不重取基线——动了之后的静止帧会重新取。
            if (scrolling) {
                wasScrolling = true
                return@collect
            }
            // 刚停下来的第一帧：这是用户手势的落点，不是内容增长。
            if (wasScrolling) {
                wasScrolling = false
                anchorKey = key
                anchorOffset = offset
                return@collect
            }
            // 手指按着 / 自己的回底动画进行中：同样不插手
            if (draggedNow || coordinator.programmaticScroll) return@collect
            // 跟随态下内容上推就是「跟随」本身，不能干扰。
            if (coordinator.following) {
                anchorKey = key
                anchorOffset = offset
                return@collect
            }
            if (key != anchorKey) {
                // 锚点换了（滑着换了一条 / 列表结构变了）：重新取基线。
                anchorKey = key
                anchorOffset = offset
                return@collect
            }
            val drift = offset - anchorOffset
            anchorOffset = offset
            // 只有「内容把锚点往上推」才补偿；offset 变小是用户在往上翻，
            // 那正是他要的方向。
            if (drift <= 0) return@collect
            state.scrollBy(drift.toFloat())
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

/**
 * 最底部可见 item 是否仍**贴在视口底边**。
 *
 * ## 为什么要单独判这一条
 *
 * 反向列表插入一条新消息时，列表内容整体下移：
 * 新消息落在 index 0（视口下方），原来的最底部可见 item 仍然紧贴视口底边，
 * 但 `newestGapOf` 此时返回 null（index 0 还没进视口）。
 *
 * 用它区分两种「index 0 不在视口」：
 * - 最底部可见 item 仍贴底 → 这是**插入瞬态**，应当继续跟随；
 * - 最底部可见 item 被切掉一截 → 用户已经离开底部，不该再跟随。
 *
 * 带宽取 2px（不是 dp）：贴底时 offset 精确为 0；用户哪怕上滑 3px
 * 也会让 offset 明显变负 → 判为「不在底部」，不会被莫名拽回底部。
 */
internal fun isFlushAtBottom(
    info: LazyListLayoutInfo,
    tolerancePx: Int = FLUSH_TOLERANCE_PX
): Boolean {
    val bottomMost = info.visibleItemsInfo.firstOrNull() ?: return false
    return bottomMost.offset in -tolerancePx..0
}

/** 贴底判定的紧带宽（px）：极窄，用户轻微上滑即判「不在底部」。 */
private const val FLUSH_TOLERANCE_PX = 2

/** 上滑离开底部超过这个距离立即判「不在底部」（2–4dp 取中，响应优先）。 */
private val LEAVE_TOLERANCE_DP = 3.dp

/** 恢复跟随要求的贴底精度：|gap| <= 该值且连续两帧。 */
private val RESUME_TOLERANCE_DP = 2.dp
