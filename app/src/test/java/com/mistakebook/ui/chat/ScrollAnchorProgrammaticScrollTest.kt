package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增长锚定（path ④）在**滚动**面前必须让路。
 *
 * ## 为什么值得单测
 *
 * 折叠块展开/收起时，它自己会 `scrollBy` 把折叠键钉回原位。但在改之前，锚定
 * 只看 item 的 `offset`，而本仓实测语义是 `offset = 布局起点 - 滚动量`：折叠块
 * 那次程序化滚动在 offset 上看起来与「内容又长高了」一模一样。于是
 * - 展开：同一次布局变化有两份补偿（2Δ，按钮反而从手指底下掉出去）；
 * - 收起：折叠块往上补的那一下被锚定当成增长**等量抵消**，按钮白补。
 *
 * 判据改成「布局坐标增量 = offset 增量 + 滚动量增量」后，纯滚动的两项严格抵消、
 * 恒为 0。这里把这条钉死：谁把滚动量那一项去掉，或者把基线里的滚动量漏存，
 * 这里立刻红。
 *
 * 模型与 [ScrollAnchorLogicTest] 同构（逐帧喂状态、拿回补偿量），
 * 只是每帧多喂一个滚动量 —— 两个字段必须成对给，分开喂就等于把别帧的滚动
 * 算进这一帧的增长里。
 */
class ScrollAnchorProgrammaticScrollTest {

    /** 迷你帧驱动器：持有基线，喂一帧返回本帧补偿量。 */
    private class Session {
        var baseline = ScrollAnchorBaseline()
            private set

        /** @return 本帧补偿量（`<= 0` = 这一帧不补偿）。 */
        fun feed(
            offset: Int,
            scroll: Int,
            key: Any? = "m5",
            scrolling: Boolean = false,
            dragged: Boolean = false,
            pointerDown: Boolean = false,
            programmaticScroll: Boolean = false,
            following: Boolean = false,
            awayFromBottom: Boolean = true
        ): Int {
            val outcome = scrollAnchorDecision(
                frameKey = key,
                frameOffset = offset,
                frameScroll = scroll,
                scrolling = scrolling,
                dragged = dragged,
                pointerDown = pointerDown,
                programmaticScroll = programmaticScroll,
                following = following,
                awayFromBottom = awayFromBottom,
                baseline = baseline
            )
            baseline = outcome.baseline
            return outcome.compensatePx
        }
    }

    // ---- 布局坐标的算术 ----

    @Test
    fun `布局坐标增量等于offset增量加滚动量增量`() {
        val baseline = ScrollAnchorBaseline(anchorKey = "m5", anchorOffset = 500, anchorScroll = 300)
        // 内容长高 40：offset +40，滚动量不动
        assertEquals(40, layoutGrowth(frameOffset = 540, frameScroll = 300, baseline = baseline))
        // 纯滚动 60：offset -60，滚动量 +60 → 两者抵消
        assertEquals(0, layoutGrowth(frameOffset = 440, frameScroll = 360, baseline = baseline))
        // 长高 30 又同时滚动 50：只有长高那一份算数
        assertEquals(30, layoutGrowth(frameOffset = 480, frameScroll = 350, baseline = baseline))
    }

    @Test
    fun `基线把滚动量一起存下来`() {
        // 漏存滚动量的实现会在下一帧把整段滚动当成长高：这条钉住它
        val s = Session()
        s.feed(offset = 500, scroll = 0)
        s.feed(offset = 400, scroll = 100)
        assertEquals(400, s.baseline.anchorOffset)
        assertEquals(100, s.baseline.anchorScroll)
    }

    // ---- 增长照旧补，滚动一律不补 ----

    @Test
    fun `内容长高按增量补偿`() {
        val s = Session()
        s.feed(offset = 500, scroll = 0)
        assertEquals(40, s.feed(offset = 540, scroll = 0))
        assertEquals(0, s.feed(offset = 540, scroll = 0))
    }

    @Test
    fun `纯程序化滚动不算增长——两个方向都不补`() {
        val s = Session()
        s.feed(offset = 500, scroll = 0)
        // 折叠块自己往上补 60（滚动量 +60）：offset 反向 -60
        assertEquals(0, s.feed(offset = 440, scroll = 60))
        // 再往下补回 60：offset +60、滚动量 -60 → 同样不算增长
        assertEquals(0, s.feed(offset = 500, scroll = 0))
    }

    @Test
    fun `用户翻页落在未被标志覆盖的那一帧时也不补`() {
        // 真实链路里拖拽/惯性帧会被 dragging/scrolling 挡在更前面；
        // 这里钉的是判据本身：位移全部来自滚动量时，锚定一律不插手。
        val s = Session()
        s.feed(offset = 500, scroll = 0)
        assertEquals(0, s.feed(offset = 400, scroll = 100))
    }

    @Test
    fun `按住不动期间的程序化补偿也不被算成增长`() {
        // v0.0.134 的 pointerDown 分支会绕过贴底/跟随闸直接补——它同样要吃
        // 新判据，否则按住手指时折叠块的补偿会被它再补一遍（2Δ）。
        val s = Session()
        s.feed(offset = 500, scroll = 0, pointerDown = true)
        assertEquals(0, s.feed(offset = 440, scroll = 60, pointerDown = true))
    }

    // ---- 两个回归：抵消与 2Δ ----

    @Test
    fun `收起时折叠块的向上补偿不再被锚定抵消`() {
        // 展开态：气泡高 871、折叠块已把它自己那次补偿垫进滚动量（滚动量 371）。
        val s = Session()
        s.feed(offset = 500, scroll = 371)
        // ① 收起：气泡矮了 371 → 布局起点 -371，滚动量没动。
        //    纯收缩 → 补偿量是负的，调用方只在 > 0 时才 scrollBy（不补）。
        assertTrue(s.feed(offset = 129, scroll = 371) <= 0)
        // ② 折叠块随后 scrollBy(-371) 把按钮钉回原位：滚动量 371→0、offset 129→500。
        //    布局一分没长 → 必须不补。旧判据在这一帧算出 +371，会把折叠块的补偿
        //    原样推回去（用户看到的就是「收起后按钮还是跑了」）。
        assertEquals(0, s.feed(offset = 500, scroll = 0))
    }

    @Test
    fun `展开后锚定补过的那一帧之后不再出现第二份——滚动不是增长`() {
        // 用户在历史区读着上面某条（贴底判定为「不在底部」）。
        val s = Session()
        s.feed(offset = 200, scroll = 300)
        // ① 展开：气泡长高 371 → 布局起点 +371 → 锚定补 371（按钮被钉住）
        assertEquals(371, s.feed(offset = 571, scroll = 300))
        // ② 折叠块那条链接着自己也补了一次（归属判错时的形态）：滚动量 +371。
        //    布局没再长 → 锚定一分都不该再补（否则就是 2Δ+）。
        assertEquals(0, s.feed(offset = 200, scroll = 671))
    }

    @Test
    fun `回底动画解冻后不再一次性补出一整段`() {
        // 旧实现的模糊点：programmaticScroll 帧不重取基线，解冻帧把整段滚动
        // 位移一次性算成增长（回底后列表被莫名往上推）。回底是纯滚动，
        // 布局坐标不动，新判据算出来就是 0。
        val s = Session()
        s.feed(offset = 100, scroll = 400)
        // 回底动画期间：滚动量 400 → 0，offset 反向 100 → 500
        s.feed(offset = 200, scroll = 300, programmaticScroll = true)
        s.feed(offset = 300, scroll = 200, programmaticScroll = true)
        s.feed(offset = 500, scroll = 0, programmaticScroll = true)
        // 动画结束后的第一个静止帧：布局没长，不补
        assertEquals(0, s.feed(offset = 500, scroll = 0))
        // 之后真长高才补
        assertEquals(25, s.feed(offset = 525, scroll = 0))
    }

    @Test
    fun `锚点换条时基线里的滚动量同步换新`() {
        val s = Session()
        s.feed(offset = 100, scroll = 40)
        // 用户滑到上一条：key 换了 → 只重取基线，不当增长
        assertTrue(s.feed(offset = 60, scroll = 260, key = "m4") <= 0)
        assertEquals(260, s.baseline.anchorScroll)
        // 新锚点上的增长照常补
        assertEquals(15, s.feed(offset = 75, scroll = 260, key = "m4"))
    }
}
