package com.mistakebook.ui.chat

import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * 反向列表（index 0 = 最新一条，贴底）的滚动跟随。
 *
 * ## 谁在滚动
 *
 * 「是否跟随」由**几何位置**驱动，不依赖松手时机：
 * - 只要最新一条不在视口底部，就暂停跟随（用户在上面看历史，新内容不得拽走）；
 * - 回到 / 滚到底部，才恢复跟随。
 *
 * `isDragged`（按住）只是**提前**暂停的输入：手一按就停，响应更快；
 * 但恢复跟随只认位置——避免松手时序与惯性滚动竞争时，
 * `following` 被误置回 true、流式输出把列表拖回底部的问题。
 */
@Composable
fun rememberAutoScrollEffect(
    state: LazyListState,
    latestItemKey: Long?
): AutoScrollCoordinator {
    val coordinator = remember { AutoScrollCoordinator() }
    val isDragged by state.interactionSource.collectIsDraggedAsState()
    val tolerancePx = with(LocalDensity.current) { BOTTOM_TOLERANCE_DP.roundToPx() }

    // 手指按住立即交出滚动权（响应快）；松手后的恢复交给下面的位置驱动。
    LaunchedEffect(isDragged) {
        if (isDragged) coordinator.pause()
    }

    // 位置驱动：不在底部就暂停，回到底部才恢复。
    // pause()/resume() 会同步维护 showJumpButton，这里不需要再写按钮逻辑。
    LaunchedEffect(state, tolerancePx) {
        snapshotFlow { state.isAtBottom(tolerancePx) }
            .distinctUntilChanged()
            .collect { atBottom ->
                if (atBottom) coordinator.resume() else coordinator.pause()
            }
    }

    // 只在「最新一条的 key 变了」时滚。流式期间 key 不变：
    // 气泡长高由反向布局自己锚在底边，不需要反复重启滚动动画。
    LaunchedEffect(latestItemKey, coordinator.following) {
        if (coordinator.following && latestItemKey != null) {
            snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
            // 双保险：动画启动**前**再验一次仍在底部。
            //
            // following 的翻转经 snapshotFlow 异步落地，effect 重启（key 变化）
            // 与位置驱动的 pause() 之间存在竞态窗口——窗口里发起的
            // animateScrollToItem 会把正往上翻历史的人一把拽回底部
            // （真机反馈「生成时不触底却也会往下滑」）。动画前按当前几何
            // 复核一次，不在底部就不滚，等用户自己回去再由 resume 触发。
            //
            // 复核不能只问「index 0 在不在底部」：新消息插入的**瞬间**它还在
            // 视口下方、不在可见集里，按旧判据会误判「不在底部」而干脆不滚——
            // 正是「发送新消息不自动跳到底部」的根因。反向布局下插入一条新
            // 消息时旧末条仍锚在视口底边（gap≈0），所以只要视觉最底部的可见
            // item 贴着底边，就说明插入前用户确实在底部，应该跟随。
            val info = state.layoutInfo
            val newestVisible = info.visibleItemsInfo.any { it.index == 0 }
            val bottomMost = info.visibleItemsInfo.firstOrNull()
            val flushAtBottom = bottomMost != null && isAtBottomOfViewport(
                itemOffset = bottomMost.offset,
                itemSize = bottomMost.size,
                viewportEndOffset = info.viewportEndOffset,
                tolerancePx = tolerancePx
            )
            if (coordinator.following && (newestVisible || flushAtBottom)) {
                state.animateScrollToItem(0)
            }
        }
    }

    return coordinator
}

/**
 * 反向布局里 index 0 就是最新一条，它贴在视口底边才算「在底部」。
 *
 * ## 为什么容差必须对称
 *
 * `viewportEndOffset` 是否把 `contentPadding` 算进去，两种约定都可能出现在真机上：
 * 完全停在底部时 `gap = viewportEndOffset - (offset + size)` 可能是 0，
 * 也可能是**负值**（底部 8dp 内边距被算进视口）。再叠加像素取整误差，
 * 早先的 `gap in 0..TOLERANCE` 会把「已经到底」判成「没到底」——
 * 后果是 `following` 永不恢复、箭头常驻，看起来像列表卡死。
 *
 * 因此判据是 `|gap| <= tolerance`。
 */
internal fun isAtBottomOfViewport(
    itemOffset: Int,
    itemSize: Int,
    viewportEndOffset: Int,
    tolerancePx: Int
): Boolean {
    val gap = viewportEndOffset - (itemOffset + itemSize)
    return gap <= tolerancePx && gap >= -tolerancePx
}

private fun LazyListState.isAtBottom(tolerancePx: Int): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val newest = info.visibleItemsInfo.firstOrNull { it.index == 0 } ?: return false
    return isAtBottomOfViewport(
        itemOffset = newest.offset,
        itemSize = newest.size,
        viewportEndOffset = info.viewportEndOffset,
        tolerancePx = tolerancePx
    )
}

/** 与底部内边距同量级即可；再大就会把「刚上滑一点」也算成底部。 */
private val BOTTOM_TOLERANCE_DP = 12.dp