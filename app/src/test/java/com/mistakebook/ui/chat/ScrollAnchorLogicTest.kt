package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式增长期**滚动锚定**判定的回归测试（v0.0.54）。
 *
 * ## 为什么值得单测
 *
 * 这是「没触底时列表也被自动往下拽」那个 bug 的修复逻辑，而它整段住在
 * @Composable 里、只靠 `withFrameNanos` 逐帧驱动。这种代码肉眼几乎没法审：
 * 分支顺序、基线何时刷新、补偿量怎么算，错一处就是「用户读着读着列表自己
 * 跳一下」，而且只在特定手势时序下复现。
 *
 * 所以判定被抽成纯函数 [scrollAnchorDecision]（不碰 LazyListState /
 * snapshotFlow / CoroutineScope），这里把每条边界钉成断言：
 * 谁改分支顺序、改基线更新时机、改补偿量，这里立刻红。
 *
 * 测试用 [Session] 逐帧驱动，字段与 effect 里那个 collect 完全同构：
 * 每帧拿回 (baseline, compensatePx)，`compensatePx > 0` 就相当于 effect
 * 调了一次 `scrollBy(compensatePx.toFloat())`。
 */
class ScrollAnchorLogicTest {

    /** 一帧的观察结果，字段与 [scrollAnchorDecision] 的入参一一对应。 */
    private data class Frame(
        val key: Any? = "m5",
        val offset: Int = 0,
        val scrolling: Boolean = false,
        val dragged: Boolean = false,
        val programmaticScroll: Boolean = false,
        val following: Boolean = false
    )

    /**
     * 迷你帧驱动器：持有 baseline，喂一帧返回本帧的补偿量。
     *
     * 之所以不直接单发调用 [scrollAnchorDecision]：锚定是一条**时序**逻辑，
     * 「上一帧在干什么」决定这一帧补不补，必须能把状态续着往下喂。
     */
    private class Session {
        var baseline = ScrollAnchorBaseline()
            private set

        /** 喂一帧，返回本帧补偿量（`<= 0` 表示这次不会 scrollBy）。 */
        fun feed(frame: Frame): Int {
            val outcome = scrollAnchorDecision(
                frameKey = frame.key,
                frameOffset = frame.offset,
                scrolling = frame.scrolling,
                dragged = frame.dragged,
                programmaticScroll = frame.programmaticScroll,
                following = frame.following,
                baseline = baseline
            )
            baseline = outcome.baseline
            return outcome.compensatePx
        }
    }

    // ---- 基线：首帧 / 锚点 key 变化 ----

    @Test
    fun `首帧只取基线不补偿`() {
        val s = Session()

        // anchorKey 初值是 null，走「锚点换了」那一支：只重取基线。
        // 没有上一帧可比，任何 offset 都不该被当成增长。
        assertTrue(s.feed(Frame(key = "m5", offset = 500)) <= 0)
        assertEquals("m5", s.baseline.anchorKey)
        assertEquals(500, s.baseline.anchorOffset)

        // 基线就位后才开始判增量
        assertEquals(40, s.feed(Frame(key = "m5", offset = 540)))
    }

    @Test
    fun `锚点key变了重取基线不当成增长`() {
        val s = Session()
        s.feed(Frame(key = "m5", offset = 100))

        // 用户继续往上翻，最顶部可见 item 换成了一条：锚点换了。
        // 新旧锚点的 offset 没有可比性，绝不能算成 drift。
        assertTrue(s.feed(Frame(key = "m4", offset = 60)) <= 0)
        assertEquals("m4", s.baseline.anchorKey)
        assertEquals(60, s.baseline.anchorOffset)

        // 新锚点上的增长才该补
        assertEquals(15, s.feed(Frame(key = "m4", offset = 75)))
    }

    @Test
    fun `锚点key按值比较而非按实例`() {
        // 真实列表 key 是消息 id（Long）。两个相等但不同实例的 key 必须
        // 视为同一条，否则每帧都走「锚点换了」，补偿永远不生效。
        val s = Session()
        s.feed(Frame(key = 7L, offset = 100))
        assertEquals(30, s.feed(Frame(key = 7L, offset = 130)))
        assertEquals(0, s.feed(Frame(key = 7L, offset = 130)))
    }

    // ---- 增长 / 回缩 / 静止：补偿量的正负 ----

    @Test
    fun `内容长高时补偿且补偿量等于offset增量`() {
        val s = Session()
        s.feed(Frame(key = "m5", offset = 100))

        // 最新一条每长高 Δ，它上面所有历史 item 的布局坐标都 +Δ →
        // 锚点 offset +Δ → 补 Δ，锚点屏幕位置纹丝不动。
        assertEquals(40, s.feed(Frame(key = "m5", offset = 140)))
        assertEquals(40, s.feed(Frame(key = "m5", offset = 180)))
    }

    @Test
    fun `offset变小时不补偿`() {
        val s = Session()
        s.feed(Frame(key = "m5", offset = 100))

        // 用户在往上翻（看历史）：offset 减少 = 滚动量增加，正是他要的方向。
        // 补偿量就是原始 drift（负的），effect 侧只在 > 0 时才 scrollBy。
        assertTrue(s.feed(Frame(key = "m5", offset = 40)) <= 0)

        // 基线照样推进到 40：停在原地不动时下一帧不再产生任何量
        assertTrue(s.feed(Frame(key = "m5", offset = 40)) <= 0)
        assertEquals(40, s.baseline.anchorOffset)
    }

    @Test
    fun `offset不变时不补偿`() {
        val s = Session()
        s.feed(Frame(key = "m5", offset = 100))

        assertTrue(s.feed(Frame(key = "m5", offset = 100)) <= 0)
        assertEquals(100, s.baseline.anchorOffset)
    }

    @Test
    fun `负offset区的增长同样按增量补偿`() {
        // 用户翻进历史区后锚点 offset 本来就是负的；内容长高让它「负得少一点」，
        // 符号与用户上滑相反，仍然该补。
        val s = Session()
        s.feed(Frame(key = "m5", offset = -200))

        assertEquals(50, s.feed(Frame(key = "m5", offset = -150)))
        assertEquals(0, s.feed(Frame(key = "m5", offset = -150)))

        // 继续往上翻：offset 变更负 → 不补
        assertTrue(s.feed(Frame(key = "m5", offset = -260)) <= 0)
    }

    @Test
    fun `连续多帧增长每帧各补各的增量`() {
        val s = Session()
        s.feed(Frame(key = "m5", offset = 0))

        // 流式输出：每帧长高一点。每帧只补这一帧的增量——
        // 基线每帧都推进，所以同一段增长不会被重复补。
        assertEquals(7, s.feed(Frame(key = "m5", offset = 7)))
        assertEquals(3, s.feed(Frame(key = "m5", offset = 10)))
        assertEquals(0, s.feed(Frame(key = "m5", offset = 10))) // 有一帧没长高
        assertEquals(12, s.feed(Frame(key = "m5", offset = 22)))
        assertEquals(1, s.feed(Frame(key = "m5", offset = 23)))

        // 累计 7+3+0+12+1 = 23 = 总增长量，一段不多一段不少
        assertEquals(23, s.baseline.anchorOffset)
    }

    @Test
    fun `补偿生效后offset回到基线下一帧不补`() {
        val s = Session()
        s.feed(Frame(key = "m5", offset = 100))

        assertEquals(40, s.feed(Frame(key = "m5", offset = 140)))

        // scrollBy(40) 兑现：锚点 offset 被拨回基线 → drift 归零。
        // 少了这一条，同一段增长会被反复补，列表原地抽搐。
        assertTrue(s.feed(Frame(key = "m5", offset = 100)) <= 0)
        assertEquals(100, s.baseline.anchorOffset)

        // 再涨再补，不会把上一段重复补
        assertEquals(5, s.feed(Frame(key = "m5", offset = 105)))
        assertTrue(s.feed(Frame(key = "m5", offset = 100)) <= 0)
    }

    // ---- 手势 / 动画：绝不跟用户打架 ----

    @Test
    fun `滚动帧只置标志不动基线且优先于其他分支`() {
        val s = Session()
        s.feed(Frame(key = "m5", offset = 100))

        // 同时命中多个条件时 scrolling 先判：只置 wasScrolling。
        // 用户手势 / 惯性 / 自己的回底动画期间一律不插手。
        assertTrue(
            s.feed(
                Frame(
                    key = "m9",
                    offset = 300,
                    scrolling = true,
                    dragged = true,
                    programmaticScroll = true,
                    following = true
                )
            ) <= 0
        )
        // 基线冻结：手势造成的 offset 位移不许被记进增长判定
        assertEquals("m5", s.baseline.anchorKey)
        assertEquals(100, s.baseline.anchorOffset)
        assertTrue(s.baseline.wasScrolling)
    }

    @Test
    fun `刚静止的首帧只重取基线不补偿即使offset变大了`() {
        val s = Session()
        s.feed(Frame(key = "m5", offset = 100))
        s.feed(Frame(key = "m5", offset = 200, scrolling = true))

        // 向下 fling 停在半路：offset 看起来「长高」了 60，但那是滚动量
        // 减少造成的，不是内容增长。不区分就会反向 scrollBy 把用户刚滑到的
        // 位置又推回去，等于跟惯性滑动打架。
        assertTrue(s.feed(Frame(key = "m5", offset = 260)) <= 0)
        assertEquals(260, s.baseline.anchorOffset)
        assertFalse(s.baseline.wasScrolling)

        // 手势的结果优先：从落点这一帧开始才重新比增量
        assertEquals(30, s.feed(Frame(key = "m5", offset = 290)))
    }

    @Test
    fun `滚动帧只影响紧随其后那一帧`() {
        val s = Session()
        s.feed(Frame(key = "m5", offset = 100))
        s.feed(Frame(key = "m5", offset = 300, scrolling = true))

        assertTrue(s.feed(Frame(key = "m5", offset = 300)) <= 0) // 静止首帧
        // 第二帧起恢复逐帧增量判定
        assertEquals(10, s.feed(Frame(key = "m5", offset = 310)))
    }

    @Test
    fun `手指按着时不干预也不动基线`() {
        val s = Session()
        s.feed(Frame(key = "m5", offset = 100))

        // 手指按着：内容长高也不插手，基线冻结在按下前那一刻。
        assertTrue(s.feed(Frame(key = "m5", offset = 180, dragged = true)) <= 0)
        assertEquals(100, s.baseline.anchorOffset)
        assertFalse(s.baseline.wasScrolling)
    }

    @Test
    fun `拖动结束后经过静止首帧重取基线`() {
        val s = Session()
        s.feed(Frame(key = "m5", offset = 100))

        // 拖动中：既可能是 isScrollInProgress=true（正在滚），
        // 也可能是按着不动（只有 isDragged=true）
        s.feed(Frame(key = "m5", offset = 60, dragged = true, scrolling = true))
        assertTrue(s.feed(Frame(key = "m5", offset = 60, dragged = true)) <= 0)

        // 松手：dragged 变 false 且列表已静止 → 落点帧只重取基线
        assertTrue(s.feed(Frame(key = "m5", offset = 60)) <= 0)
        assertEquals(60, s.baseline.anchorOffset)

        // 落点之后内容再长高才补
        assertEquals(18, s.feed(Frame(key = "m5", offset = 78)))
    }

    @Test
    fun `回底动画进行中时不干预`() {
        val s = Session()
        s.feed(Frame(key = "m5", offset = 100))

        // animateScrollToItem(0) 把视口拽回底部：这一段位移不是内容增长
        assertTrue(s.feed(Frame(key = "m5", offset = 260, programmaticScroll = true)) <= 0)
        // 基线冻结在动画前：动画期间攒下的位移不会被当成增长
        assertEquals(100, s.baseline.anchorOffset)

        // 动画帧 isScrollInProgress 为 true → 先走「刚静止首帧」重取基线，
        // 所以落到 260 之后补的是「落点 → 之后」的增量，不是整段动画位移
        assertTrue(s.feed(Frame(key = "m5", offset = 260, scrolling = true)) <= 0)
        assertTrue(s.feed(Frame(key = "m5", offset = 260)) <= 0)
        assertEquals(260, s.baseline.anchorOffset)
        assertEquals(20, s.feed(Frame(key = "m5", offset = 280)))
    }

    @Test
    fun `程序化滚动帧冻结基线解冻后按冻结点算增量`() {
        // 模糊点（按现有实现建模，未擅自修正）：dragged / programmaticScroll
        // 分支只冻结基线、不重取。于是这段期间攒下的 offset 增量不会丢，而是
        // 在解冻后的第一个静止帧一次性算出来。
        //
        // 真实链路里 animateScrollToItem 一定伴随 isScrollInProgress==true 的
        // 帧，会先走「刚静止首帧」把这段增量吃掉；但如果哪天出现
        // 「programmaticScroll 为真而列表没在动」的帧（例如标志置上/清除的
        // 极短窗口、或用户按住不动），这里就会一次性补出一段。
        val s = Session()
        s.feed(Frame(key = "m5", offset = 100))
        assertTrue(s.feed(Frame(key = "m5", offset = 260, programmaticScroll = true)) <= 0)
        assertEquals(100, s.baseline.anchorOffset)

        // 没有滚动帧过渡：解冻帧直接按冻结点算出整段增量
        assertEquals(160, s.feed(Frame(key = "m5", offset = 260)))
    }

    @Test
    fun `跟随态下不干预只重取基线`() {
        val s = Session()

        // 跟随中：锚点位置每帧都在被回底动画改写，一律只取基线。
        // 跟随态下内容上推就是「跟随」本身，不能干扰。
        assertTrue(s.feed(Frame(key = "m5", offset = 0, following = true)) <= 0)
        assertTrue(s.feed(Frame(key = "m5", offset = 40, following = true)) <= 0)
        assertEquals(40, s.baseline.anchorOffset)

        // 退出跟随态（用户上滑离开底部）后的第一帧：基线是刚才那一帧的 40，
        // 不会把跟随期间累积的 40px 一次性补出去——那正是 v0.0.54 之前
        // 「没触底也在自动往下滚」的形态之一。
        assertTrue(s.feed(Frame(key = "m5", offset = 40, following = false)) <= 0)

        // 之后才恢复逐帧增量补偿
        assertEquals(25, s.feed(Frame(key = "m5", offset = 65)))
    }

    // ---- 视口为空：不崩 ----

    @Test
    fun `视口为空时不崩也不补偿`() {
        val fresh = Session()
        // 冷启动第一帧就没有可见 item：不抛异常、不补、基线留空
        assertTrue(fresh.feed(Frame(key = null)) <= 0)
        assertEquals(ScrollAnchorBaseline(), fresh.baseline)

        val s = Session()
        s.feed(Frame(key = "m5", offset = 100))

        // 极端短列表 / 正在重建：visibleItemsInfo 为空 → topMost 为 null
        assertTrue(s.feed(Frame(key = null, offset = 0)) <= 0)
        // 基线原样不动（连 wasScrolling 都不置）
        assertEquals("m5", s.baseline.anchorKey)
        assertEquals(100, s.baseline.anchorOffset)
        assertFalse(s.baseline.wasScrolling)

        // 空视口期间即使 isScrollInProgress 为真也不记成「刚滚动过」，
        // 否则恢复后的第一个静止帧会被误判成手势落点、白丢一次补偿机会
        assertTrue(s.feed(Frame(key = null, offset = 0, scrolling = true)) <= 0)
        assertFalse(s.baseline.wasScrolling)

        // 恢复后照常判定
        assertEquals(30, s.feed(Frame(key = "m5", offset = 130)))
    }
}
