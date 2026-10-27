package com.mistakebook.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 反向列表贴底几何的回归测试（v0.0.52）。
 *
 * ## 为什么值得单测
 *
 * v0.0.51 的 T1 按**正序布局**的公式算反向列表的贴底间隙：
 * `(viewportEnd - afterPadding) - (itemOffset + itemSize)`。实测（Paparazzi，
 * Pixel5 / 8dp 竖向内边距 / 50dp item）反向布局下 `item.offset` 本身就是
 * 「内容区底边 - item 底边」，贴底时 = 0；旧公式贴底时算出 ≈ 视口高，
 * 直接导致「触底不跟随 / 不触底有时又跟随」两个回归。
 *
 * 这个测试把实测语义钉成断言：谁再把布局几何想反，这里立刻红。
 */
class LazyReverseGeometryTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private fun renderAt(anchorIndex: Int, anchorOffset: Int): androidx.compose.foundation.lazy.LazyListState {
        val box = arrayOfNulls<androidx.compose.foundation.lazy.LazyListState>(1)
        paparazzi.snapshot(name = "anchor=${anchorIndex}_$anchorOffset") {
            val state = rememberLazyListState(
                initialFirstVisibleItemIndex = anchorIndex,
                initialFirstVisibleItemScrollOffset = anchorOffset
            )
            box[0] = state
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = state,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(List(30) { it }, key = { it }) { index ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) { Text("item $index") }
                    }
                }
            }
        }
        return box[0]!!
    }

    @Test
    fun atRest_newestItemSitsFlushWithContentBottom() {
        val state = renderAt(anchorIndex = 0, anchorOffset = 0)
        // 实测：贴底时最新一条（index 0）的 offset = 0
        assertEquals(0, newestGapOf(state.layoutInfo))
    }

    @Test
    fun scrolledWithinNewestItem_gapIsNegative() {
        // 实测：state(0, 100) → item 0 的 offset = -100（底边被切出内容区 100px）
        val state = renderAt(anchorIndex = 0, anchorOffset = 100)
        assertEquals(-100, newestGapOf(state.layoutInfo))
    }

    @Test
    fun scrolledPastNewestItem_gapIsNull() {
        // 用户翻过最新一条：index 0 已不在可见集
        val state = renderAt(anchorIndex = 6, anchorOffset = 0)
        assertNull(newestGapOf(state.layoutInfo))
    }

    @Test
    fun anchorOffsetEqualsNegativeItemOffset() {
        // 实测：state 的 firstVisibleItemScrollOffset = -锚点 item.offset。
        // 补偿逻辑靠这个换算，语义变了立刻红。
        val state = renderAt(anchorIndex = 5, anchorOffset = 50)
        val anchored = state.layoutInfo.visibleItemsInfo.first { it.index == 5 }
        assertEquals(-50, anchored.offset)
        assertEquals(50, state.firstVisibleItemScrollOffset)
    }

    @Test
    fun viewportEndExcludesBottomPadding() {
        // 实测：viewportEndOffset = 屏高 - afterContentPadding（反向布局下
        // contentPadding 不镜像，before 在顶、after 在底）。
        val state = renderAt(anchorIndex = 0, anchorOffset = 0)
        val info = state.layoutInfo
        assertEquals(22, info.beforeContentPadding)
        assertEquals(22, info.afterContentPadding)
        assertTrue(info.viewportEndOffset < info.viewportSize.height)
    }
}
