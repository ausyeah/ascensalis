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
        // 最近一个「确实看见过」的最新一条（index 0）的 key。
        //
        // 为什么需要它：`gap == null`（index 0 不在可见集）有两种完全不同的
        // 成因，而它们在几何上**不可区分**——
        // - **插入瞬态**：新消息刚插到 index 0、还在视口下方，原最新一条
        //   （现在是 index 1）仍贴底，它的 offset = 0；
        // - **用户翻到 item 边界**：用户在历史区恰好停在某条 item 的边界上，
        //   最底部可见 item 的 offset **同样是 0**。
        //
        // 旧实现只凭 `isFlushAtBottom` 就认定是瞬态并提前返回，于是第二种
        // 情况下 `following` 一直保持 true——下一条新消息一到，path ③ 就把人
        // 从历史区硬拽回底部。这就是用户反馈的「没有触底仍然会跟随」。
        // （v0.0.54 只在 path ③ 用 `wasFollowing` 加固过，但 `following`
        //   本身已经被这里写坏了，`wasFollowing` 读到的还是错的值。）
        //
        // 判别式：**最底部可见 item 的 key 是否就是上一个最新一条**。
        // 插入瞬态下贴底的那条正是原最新一条；用户翻过去之后，贴底的是更上面
        // 的某条，key 必然不同。这一条把两种情形彻底分开。
        var lastNewestKey: Any? = null
        // 只把 gap 与最新一条的 key 作为流的产物：发射时机与改造前完全一致
        // （这两个值变化才重算）。layoutInfo 在 collect 里现取——
        // 若把它也放进产物，任何布局变化都会触发一次发射，同样的 gap 会被
        // 反复喂进状态机，恢复跟随的两帧迟滞会被无意义地提前凑满。
        snapshotFlow {
            val info = state.layoutInfo
            Pair(newestGapOf(info), newestKeyOf(info))
        }.collect { (gap, newestKey) ->
            if (draggedNow || coordinator.programmaticScroll) return@collect
            // index 0 在场才更新记忆；瞬态期间 index 0 是不可见的，记忆保持原值
            if (newestKey != null) lastNewestKey = newestKey
            val info = state.layoutInfo
            if (gap == null) {
                val bottomMostKey = info.visibleItemsInfo.firstOrNull()?.key
                val transient = isInsertionTransient(
                    bottomMostKey = bottomMostKey,
                    bottomMostFlush = isFlushAtBottom(info),
                    lastNewestKey = lastNewestKey
                )
                if (transient) {
                    // 主动 resume 而不是静默返回：贴底是几何事实，跟跟随态无关。
                    // 若此前因某一帧的误判被置成 false，这里把它纠回来——
                    // 否则接下来 path ④ 会认为「用户在看历史」而启动滚动锚定
                    // 补偿，把视口往上推，用户看到的就是「不跟随，反而小范围
                    // 往上跳一下」（v0.0.56 反馈）。
                    coordinator.resume()
                    return@collect
                }
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
    //
    //    ## 判定逻辑不放在这里
    //
    //    上面这几条规则是「渲染/解析链路的策略」，按仓库文化必须能被单测
    //    断言。所以逐帧判定整段抽成了 [scrollAnchorDecision]：effect 只负责
    //    把一帧的观察结果喂进去、再按返回值决定要不要 scrollBy。所有分支
    //    顺序、基线何时更新、补偿量怎么算，都在那个纯函数里，
    //    由 ScrollAnchorLogicTest 钉住。改这里之前先看那个测试。
    LaunchedEffect(state, coordinator) {
        // anchorKey / anchorOffset / wasScrolling 的当前值。effect 重启时重置
        // （原实现就是三个局部 var，同一个 LaunchedEffect 的生命周期）。
        var baseline = ScrollAnchorBaseline()
        snapshotFlow {
            // 视口为空时 topMost 为 null：key 传 null 让纯函数走「无从锚定」
            // 那一支（offset 用 0 占位，那一支根本不读它）。
            val info = state.layoutInfo
            val topMost = info.visibleItemsInfo.lastOrNull()
            AnchorFrame(
                key = topMost?.key,
                offset = topMost?.offset ?: 0,
                scrolling = state.isScrollInProgress,
                awayFromBottom = !isFlushAtBottom(info)
            )
        }.collect { frame ->
            val outcome = scrollAnchorDecision(
                frameKey = frame.key,
                frameOffset = frame.offset,
                scrolling = frame.scrolling,
                dragged = draggedNow,
                programmaticScroll = coordinator.programmaticScroll,
                following = coordinator.following,
                awayFromBottom = frame.awayFromBottom,
                baseline = baseline
            )
            baseline = outcome.baseline
            // 只有「内容把锚点往上推」才补偿；offset 变小是用户在往上翻，
            // 那正是他要的方向（判据在纯函数里）。
            if (outcome.compensatePx > 0) {
                state.scrollBy(outcome.compensatePx.toFloat())
            }
        }
    }

    return coordinator
}

/**
 * path ④ 每帧的观察结果（原来是个 `Triple`，v0.0.56 加了 [awayFromBottom]）。
 *
 * 之所以从 Triple 换成数据类：第四个字段是「是否确实离开了底部」，
 * 用 Triple 就得写成 `Triple(Triple(a, b, c), d)` 或者把布尔塞进偏移里，
 * 两者都比命名字段难读。而 snapshotFlow 的去重依赖 `equals`，
 * 数据类自动生成，行为与 Triple 一致。
 */
private data class AnchorFrame(
    val key: Any?,
    val offset: Int,
    val scrolling: Boolean,
    /** 最底部可见 item 是否**不**贴底（见 [scrollAnchorDecision] 的对应参数）。 */
    val awayFromBottom: Boolean
)

/**
 * 滚动锚定判定的**基线**：上一帧留下的锚点状态。
 *
 * 与 effect 里原来的三个局部变量一一对应：
 * - [anchorKey] ← `anchorKey`（锚点 item 的 key；`null` = 还没取过基线）；
 * - [anchorOffset] ← `anchorOffset`（取基线那一帧该 item 的 offset）；
 * - [wasScrolling] ← `wasScrolling`（上一帧是否还在滚动）。
 *
 * 之所以做成不可变数据类而不是让 effect 继续持有三个 `var`：
 * 判定函数要能在没有 Compose 的情况下被单测驱动——每帧「喂当前状态、
 * 拿回新状态」就能把整条时序跑完。
 */
internal data class ScrollAnchorBaseline(
    val anchorKey: Any? = null,
    val anchorOffset: Int = 0,
    val wasScrolling: Boolean = false
)

/**
 * [scrollAnchorDecision] 的单帧结论。
 *
 * @param baseline 要写回 effect 的新基线（已按本帧结论更新）；
 * @param compensatePx 本帧应补偿的像素数，`<= 0` 表示不补偿。
 *   这个值就是原来 `drift = offset - anchorOffset` 里为正的那一支，
 *   Compose 侧直接 `scrollBy(compensatePx.toFloat())`——补偿的**时机与量**
 *   因此与重构前逐帧一致。
 */
internal data class ScrollAnchorOutcome(
    val baseline: ScrollAnchorBaseline,
    val compensatePx: Int
)

/**
 * 流式增长期的**滚动锚定**判定（v0.0.54）。纯函数：不碰 Compose / Android，
 * 输入只有基本类型与 item key，可在 JVM 上直接单测。
 *
 * ## 它回答的问题
 *
 * 「这一帧，视口该不该被 scrollBy 拽一下？拽多少？」
 *
 * ## 与 effect 局部变量的对应关系
 *
 * | 参数 | 来源 |
 * |---|---|
 * | [frameKey] / [frameOffset] | 最顶部可见 item 的 `key` / `offset` |
 * | [scrolling] | `state.isScrollInProgress` |
 * | [dragged] | `collectIsDraggedAsState()`（经 rememberUpdatedState 取实时值） |
 * | [programmaticScroll] | `coordinator.programmaticScroll` |
 * | [following] | `coordinator.following` |
 * | [awayFromBottom] | 最底部可见 item 是否**不**贴底（`!isFlushAtBottom`） |
 * | [baseline] | 上一帧的 [ScrollAnchorBaseline] |
 *
 * ## 分支顺序即优先级，与原实现逐行同构
 *
 * 0. **视口为空**（[frameKey] 为 null）→ 无从锚定：不补偿，且**不动基线**
 *    （连 `wasScrolling` 都不动，与原来 `if (info == null) return@collect`
 *    在一切之前返回一致）；
 * 1. **正在滚动** → 只把 `wasScrolling` 置 true，不重取基线也不补偿。
 *    手势 / 惯性 / 自己的回底动画期间一律不插手；
 * 2. **刚静止的首帧**（`wasScrolling`）→ 清标志、重取基线、不补偿。
 *    向下 fling 停在半路时 offset 也会变大，那是滚动量减少造成的，
 *    不是内容增长——不区分就会跟惯性滑动打架；
 * 3. **手指按着 / 回底动画中** → 不插手，且**不重取基线**（基线维持按住前
 *    那一刻，松手后的静止帧会重新取）；
 * 4. **贴底**（[following] 或 `!awayFromBottom`）→ 内容上推就是「跟随」本身，
 *    不干扰；但仍重取基线，这样退出跟随态后的第一帧就有新鲜基线可比；
 * 5. **锚点 key 变了** → 重取基线，不当成增长（滑着换了一条 / 列表结构变了）；
 * 6. **同 key** → `drift = offset - anchorOffset`；`drift <= 0` 不补偿
 *    （offset 变小是用户在往上翻，那正是他要的方向），`drift > 0` 才补偿。
 *
 * ## 分支 4 为什么把「贴底」和「跟随态」并列（v0.0.56）
 *
 * 补偿只为一种场景存在：**用户翻进历史区后，新内容长高不要把他正在读的
 * 段落往上拽**。贴在底部时反向布局把最新一条钉在内容区底边，它长高只是
 * 向上延伸，视口里的东西一动不动——补了反而把人从底部往上推。
 *
 * 原先只靠 [following] 挡。但插入瞬态那几帧 `following` 可能被误置为
 * false（见 `rememberAutoScrollEffect` path ② 的注释），补偿于是照常执行，
 * 用户看到的就是「新消息来了不跟随，内容反而小范围往上跳一下」。
 * 所以这里按**几何事实**再挡一道：最底部可见 item 仍贴底（含插入瞬态）
 * 就一律不补偿，与跟随态是否误判无关。
 *
 * ## 模糊点（按现有实现的行为建模，未擅自修正）
 *
 * - **`dragged` 与 `programmaticScroll` 分支不更新基线**：若用户按住不动
 *   （`isScrollInProgress == false` 但 `isDragged == true`）期间内容长高，
 *   这段增长不会立刻补，而是累积到松手后的第一个静止帧一次性补足。
 *   现有实现即是如此；因为那之前必然先经过分支 2 重取基线，实际上补的是
 *   「松手落点 → 当前」的增量，不是整段按住期，行为上是自洽的。
 * - **补偿本身会改 offset**：`scrollBy(+drift)` 之后锚点 offset 应回到
 *   [ScrollAnchorBaseline.anchorOffset]，下一帧 `drift == 0` 自然不补
 *   （由 `LazyListState` 兑现，函数不负责验证）。若某平台版本 scrollBy
 *   有阻尼/取整，这里会留下 1px 级的残差，下一帧继续补——自愈，不会漂移。
 */
internal fun scrollAnchorDecision(
    frameKey: Any?,
    frameOffset: Int,
    scrolling: Boolean,
    dragged: Boolean,
    programmaticScroll: Boolean,
    following: Boolean,
    awayFromBottom: Boolean,
    baseline: ScrollAnchorBaseline
): ScrollAnchorOutcome {
    // 视口里一条都没有（极端短列表 / 正在重建）：无从锚定。
    // 这里必须排在 `scrolling` 之前：原来 `info == null` 是在一切判断之前
    // 返回的，空视口那一帧连 wasScrolling 都不该被置起来。
    if (frameKey == null) {
        return ScrollAnchorOutcome(baseline, compensatePx = 0)
    }
    // 列表正在动（用户手势 / 惯性 / 自己的回底动画）：不插手，
    // 也不重取基线——动了之后的静止帧会重新取。
    if (scrolling) {
        return ScrollAnchorOutcome(baseline.copy(wasScrolling = true), compensatePx = 0)
    }
    // 刚停下来的第一帧：这是用户手势的落点，不是内容增长。
    if (baseline.wasScrolling) {
        return ScrollAnchorOutcome(
            baseline.copy(wasScrolling = false, anchorKey = frameKey, anchorOffset = frameOffset),
            compensatePx = 0
        )
    }
    // 手指按着 / 自己的回底动画进行中：同样不插手。
    // 注意这里**不**重取基线——否则松手后的静止帧会把「按下时的位置」当基线，
    // 把手势期间攒下的增长当成该补的量，反而跟用户打架。
    if (dragged || programmaticScroll) {
        return ScrollAnchorOutcome(baseline, compensatePx = 0)
    }
    // 跟随态下内容上推就是「跟随」本身，不能干扰。
    // 贴底时同理（哪怕跟随态被误置为 false）——理由见函数注释里
    // 「分支 4 为什么把贴底和跟随态并列」。
    if (following || !awayFromBottom) {
        return ScrollAnchorOutcome(baseline.copy(anchorKey = frameKey, anchorOffset = frameOffset), compensatePx = 0)
    }
    if (frameKey != baseline.anchorKey) {
        // 锚点换了（滑着换了一条 / 列表结构变了）：重新取基线。
        return ScrollAnchorOutcome(baseline.copy(anchorKey = frameKey, anchorOffset = frameOffset), compensatePx = 0)
    }
    val drift = frameOffset - baseline.anchorOffset
    // 只有「内容把锚点往上推」才补偿；offset 变小是用户在往上翻，
    // 那正是他要的方向。
    return ScrollAnchorOutcome(baseline.copy(anchorOffset = frameOffset), compensatePx = drift)
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
 * 最新一条消息（index 0）的 key；不在可见集返回 null。
 *
 * 供位置驱动区分「插入瞬态」与「用户翻到 item 边界」用——两者几何上不可区分，
 * 只能靠「贴底的那条是不是上一个最新一条」来分辨。见
 * [rememberAutoScrollEffect] 里 path ② 的注释。
 */
internal fun newestKeyOf(info: LazyListLayoutInfo): Any? =
    info.visibleItemsInfo.firstOrNull { it.index == 0 }?.key

/**
 * `gap == null`（最新一条不在可见集）时，这一帧是不是**插入瞬态**。
 *
 * ## 为什么不能只看 `bottomMostFlush`
 *
 * 插入瞬态与「用户在历史区恰好停在 item 边界」在几何上完全一样：
 * 都是最底部可见 item 的 offset = 0。只凭几何判定，就会把后一种当成前一种，
 * 让 `following` 一直保持 true——下一条新消息到达时把人从历史区拽回底部
 * （用户反馈「没有触底仍然会跟随」）。
 *
 * ## 判别式
 *
 * 插入瞬态下，贴底的那条正是**上一个最新一条**（它被新消息挤成了 index 1），
 * 所以 `bottomMostKey == lastNewestKey`；用户翻过去之后，贴底的是更上面的某条，
 * key 必然不同。
 *
 * @param bottomMostKey 最底部可见 item 的 key（视口为空时为 null）；
 * @param bottomMostFlush 最底部可见 item 是否贴底，见 [isFlushAtBottom]；
 * @param lastNewestKey 最近一个确实看见过的最新一条的 key（从没见过为 null）。
 */
internal fun isInsertionTransient(
    bottomMostKey: Any?,
    bottomMostFlush: Boolean,
    lastNewestKey: Any?
): Boolean = bottomMostFlush && bottomMostKey != null && bottomMostKey == lastNewestKey

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
