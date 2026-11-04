package com.mistakebook.wordbook.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题干（单词本身）取宽与选字号的单测（v0.0.102 改口径）。
 *
 * 纯 JVM：两个函数都只是浮点换算，不依赖 Android。
 *
 * ## v0.0.109：宽度不再扣、改成往下让
 * 用户原话：「英语背单词单词要居中的只是我说个上面 UI 的底部要错开。
 * 也就是单词宽度无限，上顶部在右上角 UI 下边界的下面就不会重合了，
 * 你这个横向错位是错的」。
 *
 * 所以可用宽度就是整行宽度，单词水平居中；右上角那排图标靠
 * WORD_HEADLINE_TOP_CLEARANCE_DP 让出垂直空间——横向扣减那版会把
 * 单词挤得偏左，看着像排版歪了。
 *
 * 之前的两条规矩把这件事做反了：
 * 1. 可用宽度**左右各扣** 96dp 的工具带——右边是真占用，左边空着也扣，
 *    单词被塞进一半宽的带子里，「hammer」这种 6 个字母的词都放不下，只能折行；
 * 2. 字号被**下限**托住（18sp），一旦托到放不下，就只能靠 maxLines=2 折行兜底。
 *
 * 现在的规矩只有一条：**单词永远单行、永远不越界**。
 * 宽度只扣右边那一条带子（那是真占用），单词在剩下的区域里整体向左错开一点；
 * 字号按可用宽度算，**不设会破坏「塞得下」的下限**；真放不下时，
 * 界面上还有按**实测宽度**的等比缩放兜底。
 */
class WordHeadlineSizeTest {

    /**
     * Noto Serif Bold 的实测字宽：PIXEL_5 渲染 ubiquitous（10 字母）
     * 36sp 宽 193.9dp → 0.539em/字。测试里取 0.54，比生产常量
     * WORD_HEADLINE_EM_PER_CHAR（0.58）略小，用来**验证那 7% 余量
     * 真实存在**——拿生产常量自己验自己等于没验。
     */
    private val realEmPerChar = 0.54f

    /** PIXEL_5 实测：屏幕 392dp - LazyColumn 左右 16dp - 卡片左右 20dp。 */
    private val phoneContent = 320f

    /** 360dp 宽的设计下限屏。 */
    private val narrowContent = 288f

    @Test
    fun `可用宽度就是整行宽度`() {
        // 图标挪到了「顶部让位」里，横向不再占用任何宽度：
        // 单词用满整行并居中，长单词也才有地方站
        assertEquals(phoneContent, wordHeadlineMaxWidthDp(phoneContent), 0f)
    }

    @Test
    fun `窄屏兜底不低于九十六dp`() {
        assertEquals(narrowContent, wordHeadlineMaxWidthDp(narrowContent), 0f)
        // 再窄也不该吐负数或零宽——那会让题干直接不显示
        assertEquals(96f, wordHeadlineMaxWidthDp(0f), 0f)
        assertEquals(100f, wordHeadlineMaxWidthDp(100f), 0f)
    }

    @Test
    fun `六个字母以内的短单词用满三十六号`() {
        // cat / water / book 这类常见词必须保持设计稿的 36sp
        assertEquals(WORD_HEADLINE_MAX_SP, wordHeadlineSizeSp("cat", 224f, 1f), 0f)
        assertEquals(WORD_HEADLINE_MAX_SP, wordHeadlineSizeSp("book", 224f, 1f), 0f)
        assertEquals(WORD_HEADLINE_MAX_SP, wordHeadlineSizeSp("water", 224f, 1f), 0f)
        // hammer 之前正好是被折成两行的那个词
        assertEquals(WORD_HEADLINE_MAX_SP, wordHeadlineSizeSp("hammer", 224f, 1f), 0f)
    }

    @Test
    fun `第十一个字母起就按比例缩`() {
        // 224dp 里 10 个字母仍是 36sp（10 * 0.58 * 36 = 209dp）；
        // 13 个字母就要缩了——这正是「sophisticated」这类长词的表现
        val size = wordHeadlineSizeSp("sophisticated", 224f, 1f)
        assertTrue("实际 $size", size < WORD_HEADLINE_MAX_SP)
    }

    @Test
    fun `空串不缩字号`() {
        assertEquals(WORD_HEADLINE_MAX_SP, wordHeadlineSizeSp("", 224f, 1f), 0f)
    }

    @Test
    fun `选出来的字号一定塞得进可用宽度`() {
        // 这是整条修复的核心不变量：**没有后门**。
        // 之前有「触到 18sp 下限就不管了，靠 maxLines=2 折行兜底」——
        // 那个后门正是单词被折成两行的原因。现在任何词、任何宽度、
        // 任何系统字号放大倍数，吐出来的字号都必须塞得下。
        val words = listOf(
            "cat", "book", "water", "hammer", "history", "language",
            "ubiquitous", "sophisticated", "antidisestablishmentarianism"
        )
        for (word in words) {
            for (available in listOf(96f, 128f, 224f, 320f)) {
                for (scale in listOf(1f, 1.3f, 2f)) {
                    val size = wordHeadlineSizeSp(word, available, scale)
                    val needed = word.length * realEmPerChar * size * scale
                    assertTrue(
                        "$word / $available / $scale 需要 ${needed}dp（字号 $size）",
                        needed <= available + 0.05f
                    )
                }
            }
        }
    }

    @Test
    fun `系统字号放大时同长度单词缩得更小`() {
        // 用户在设置里把字号调到 1.3x：sp 占的 dp 会同比例变大，
        // 不一起算进去的话放大模式又会压图标。
        val normal = wordHeadlineSizeSp("ubiquitous", 224f, 1f)
        val enlarged = wordHeadlineSizeSp("ubiquitous", 224f, 1.3f)
        assertTrue("$normal vs $enlarged", enlarged < normal)
    }

    @Test
    fun `同一个词可用宽度越小字号不会越大`() {
        // 单调性：屏幕更窄不能让单词变得更大（否则必然越过工具带）
        val narrow = wordHeadlineSizeSp("sophisticated", 96f, 1f)
        val wide = wordHeadlineSizeSp("sophisticated", 320f, 1f)
        assertTrue("$narrow vs $wide", narrow <= wide)
    }

    @Test
    fun `字号永远不超过上限`() {
        // 下限没有了：字号由「塞得下」唯一决定，只剩上限这条硬规矩
        val words = listOf("a", "cat", "sophisticated", "antidisestablishmentarianism")
        for (word in words) {
            for (available in listOf(0f, 50f, 128f, 320f, 1000f)) {
                for (scale in listOf(0.5f, 1f, 3f)) {
                    val size = wordHeadlineSizeSp(word, available, scale)
                    assertTrue(
                        "$word / $available / $scale 吐出 $size",
                        size <= WORD_HEADLINE_MAX_SP
                    )
                }
            }
        }
    }
}
