package com.mistakebook.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 横向手势定向判定 [shouldClaimCardSwipe] 的边界单测（v0.0.89 重写）。
 *
 * 纯 JVM：函数本体只是两个绝对值比大小，不依赖 Android。
 *
 * v0.0.89 修的是「错题左滑删除没了」：v0.0.84~0.0.88 按速度给门户 Pager
 * 让道，阈值 slop/90ms ≈ 270 px/s 低于常人左滑的速度，删除永远划不出来。
 * 现在规则只有方向一条——下面每条用例都钉住这条规则的边界。
 */
class HomeScreenSwipeClaimTest {

    @Test
    fun `横向占优认领为展开删除`() {
        assertTrue(shouldClaimCardSwipe(30f, 0f))
        assertTrue(shouldClaimCardSwipe(30f, 20f))
    }

    @Test
    fun `向左向右都能认领方向只是符号`() {
        // 判定只看绝对值：从右往左（露出删除）和从左往右（收回）都归卡片管
        assertTrue(shouldClaimCardSwipe(-30f, 0f))
        assertTrue(shouldClaimCardSwipe(-30f, -10f))
    }

    @Test
    fun `纵向占优让给列表滚动`() {
        assertFalse(shouldClaimCardSwipe(0f, 60f))
        assertFalse(shouldClaimCardSwipe(20f, 60f))
    }

    @Test
    fun `横竖位移相等时保守让给滚动`() {
        // |accY| >= |accX| 的边界取等号：不让卡片认领，
        // 斜着拖 45° 时按滚动处理，用户感知更接近「手指往下走」
        assertFalse(shouldClaimCardSwipe(30f, 30f))
        assertFalse(shouldClaimCardSwipe(-30f, 30f))
    }

    @Test
    fun `判定与速度无关慢滑快滑一致认领`() {
        // 同一位移，慢拖与快甩的结论必须相同——速度分叉正是旧实现的病灶
        assertTrue(shouldClaimCardSwipe(30f, 0f))
        assertTrue(shouldClaimCardSwipe(300f, 0f))
    }
}

/**
 * 松手落点判定 [resolveCardSwipeSettle] 的单测（v0.0.142）。
 *
 * 修的是用户实测的两条：「拉住这个滑块细微慢速运动的时候还会不停的
 * 闪烁」与「你现在完全拉不回来，学习一下左滑的逻辑啊」。阈值必须相对
 * **手势起点**——预览态起手时 offset 已是满程，用绝对阈值的话往回拖
 * 大半程仍判「开」，松手就弹回去。
 *
 * 另一条同样重要的钉子：手势里的位移必须取 [positionChange]，不能手工
 * `position.x - lastX`。卡片自己正被 Modifier.offset 位移，position 是
 * 节点本地坐标，手工相减会形成反馈回路——实测一次 800px 的滑动只走出
 * 379.8px，相邻事件还正负交替（+44.8 / −18.6 / …），既是闪烁也是
 * 「拉不回来」的真因。这条只能在源码层面钉（JVM 上跑不了指针分发）。
 */
class HomeScreenSwipeSettleTest {

    private val reveal = 200f
    private val image = 1000f
    private val rightSlop = 210f  // 80dp @ 2.625x

    private fun settle(start: Float, cur: Float) = resolveCardSwipeSettle(
        startOffset = start,
        curOffset = cur,
        revealWidthPx = reveal,
        imageRevealPx = image,
        rightOpenSlopPx = rightSlop
    )

    @Test
    fun `从零起手轻推过 80dp 即看题图`() {
        val s = settle(0f, rightSlop + 1f)
        assertTrue(s.revealImage)
        assertFalse(s.revealActions)
        assertEquals(image, s.target, 0.01f)
    }

    @Test
    fun `从零起手推不足 80dp 回弹归零`() {
        val s = settle(0f, rightSlop - 1f)
        assertFalse(s.revealImage)
        assertEquals(0f, s.target, 0.01f)
    }

    @Test
    fun `预览态往回拖过一半即收回`() {
        // 起点满程 1000，拖回 499 —— 不足一半，判收
        val s = settle(image, 499f)
        assertFalse(s.revealImage)
        assertEquals(0f, s.target, 0.01f)
    }

    @Test
    fun `预览态只往回拖一点保持预览`() {
        // 拖到 600：仍在一半以上，保持预览——轻微抖动不该误收
        val s = settle(image, 600f)
        assertTrue(s.revealImage)
        assertEquals(image, s.target, 0.01f)
    }

    @Test
    fun `预览态阈值必须相对起点而非绝对`() {
        // 同一 curOffset=300：从零起手是「已推过 80dp」，从预览态追手
        // 则是「往回拖了七成」——两者结论必须相反。若实现里丢了
        // startOffset 分支，这条会红。
        assertTrue(settle(0f, 300f).revealImage)
        assertFalse(settle(image, 300f).revealImage)
    }

    @Test
    fun `左滑操作区同样相对起点`() {
        val opened = settle(0f, -reveal - 5f)
        assertTrue(opened.revealActions)
        assertEquals(-reveal, opened.target, 0.01f)
        // 展开态只往回拖一点点：保持展开
        assertTrue(settle(-reveal, -reveal + 20f).revealActions)
        // 展开态往回拖过一半：收回
        assertFalse(settle(-reveal, -reveal * 0.4f).revealActions)
    }

    @Test
    fun `两层露出永不重叠`() {
        // 左滑与右滑是同一个 offset 的两个方向，任一状态下最多点亮一层
        for (cur in listOf(-400f, -100f, 0f, 100f, 400f, 1000f)) {
            val s = settle(0f, cur)
            assertFalse("cur=$cur 同时点亮两层", s.revealActions && s.revealImage)
        }
    }

    @Test
    fun `手势位移必须用 positionChange 而非手工相减`() {
        val src = readSource("src/main/java/com/mistakebook/ui/home/HomeScreen.kt")
        val gesture = src.substringAfter(".pointerInput(revealWidthPx)")
            .substringBefore("QuestionCard(")
        assertTrue(
            "手势里必须取 change.positionChange——手工 position - lastX 会被" +
                "卡片自身位移吃掉一半行程（闪烁 + 拉不回来的真因）",
            gesture.contains("change.positionChange")
        )
        assertFalse(
            "不得再出现手工跨帧相减的 lastX 口径",
            gesture.contains("position.x - lastX") || gesture.contains("var lastX")
        )
    }

    private fun readSource(path: String): String {
        val candidates = listOf(
            File(path),
            File("../$path"),
            File(path.removePrefix("app/")),
            File("../" + path.removePrefix("app/"))
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalArgumentException("Cannot find file: $path among $candidates")
        return file.readText()
    }
}
