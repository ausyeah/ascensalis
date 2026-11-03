package com.mistakebook.wordbook.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题干（单词本身）取宽与选字号的单测（v0.0.90）。
 *
 * 纯 JVM：两个函数都只是浮点换算，不依赖 Android。
 * 钉住的是**不压图标**这条硬规矩：右上角工具带占 96dp，长单词
 * （sophisticated 13 字母、antidisestablishmentarianism 28 字母）
 * 不管屏幕多宽、用户把系统字号调多大，都不允许越过工具带。
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

    /** 360dp 宽的设计下限屏：卡片内容区正好被工具带吃到 96dp。 */
    private val narrowContent = 288f

    @Test
    fun `可用宽度左右各扣一个工具带`() {
        // 320 - 2*96 = 128：右侧是真实占用，左侧让出来保持单词居中
        assertEquals(128f, wordHeadlineMaxWidthDp(phoneContent), 0f)
    }

    @Test
    fun `窄屏兜底不低于九十六dp`() {
        assertEquals(96f, wordHeadlineMaxWidthDp(narrowContent), 0f)
        // 再窄也不该吐负数或零宽——那会让题干直接不显示
        assertEquals(96f, wordHeadlineMaxWidthDp(0f), 0f)
        assertEquals(96f, wordHeadlineMaxWidthDp(100f), 0f)
    }

    @Test
    fun `六个字母以内的短单词用满三十六号`() {
        // cat / water / book 这类常见词必须保持设计稿的 36sp。
        // 到 6 个字母为止：6 * 0.539 * 36 = 116dp，128dp 装得下。
        assertEquals(WORD_HEADLINE_MAX_SP, wordHeadlineSizeSp("cat", 128f, 1f), 0f)
        assertEquals(WORD_HEADLINE_MAX_SP, wordHeadlineSizeSp("book", 128f, 1f), 0f)
        assertEquals(WORD_HEADLINE_MAX_SP, wordHeadlineSizeSp("water", 128f, 1f), 0f)
    }

    @Test
    fun `第七个字母起就按比例缩`() {
        // history：7 * 0.539 * 36 = 136dp > 128dp，塞不下就必须缩
        val size = wordHeadlineSizeSp("history", 128f, 1f)
        assertTrue("实际 $size", size < WORD_HEADLINE_MAX_SP)
        assertTrue("实际 $size", size >= WORD_HEADLINE_MIN_SP)
    }

    @Test
    fun `空串不缩字号`() {
        assertEquals(WORD_HEADLINE_MAX_SP, wordHeadlineSizeSp("", 128f, 1f), 0f)
    }

    @Test
    fun `sophisticated 停在下限且仍不越过工具带`() {
        // 13 个字母在 128dp 里最多 18sp；这不是显示 bug，是几何必然
        val size = wordHeadlineSizeSp("sophisticated", 128f, 1f)
        assertEquals(WORD_HEADLINE_MIN_SP, size, 0f)
        // 关键：18sp 的 13 个字母仍塞得进 128dp（126dp），所以单行放得下
        val needed = 13 * realEmPerChar * size
        assertTrue("需要 ${needed}dp", needed <= 128f + 0.05f)
    }

    @Test
    fun `选出来的字号一定塞得进可用宽度`() {
        // 这是整条修复的核心不变量：只要没触到 18sp 下限，按实测字宽算出的
        // 宽度必须 <= 可用宽度。超出下限的极端长词靠 widthIn 折行兜底。
        val words = listOf("cat", "book", "water", "history", "language", "ubiquitous", "sophisticated")
        for (word in words) {
            for (available in listOf(96f, 128f, 200f, 320f)) {
                for (scale in listOf(1f, 1.3f, 2f)) {
                    val size = wordHeadlineSizeSp(word, available, scale)
                    val needed = word.length * realEmPerChar * size * scale
                    assertTrue(
                        "$word / $available / $scale 需要 ${needed}dp",
                        needed <= available + 0.05f || size == WORD_HEADLINE_MIN_SP
                    )
                }
            }
        }
    }

    @Test
    fun `系统字号放大时同长度单词缩得更小`() {
        // 用户在设置里把字号调到 1.3x：sp 占的 dp 会同比例变大，
        // 不一起算进去的话放大模式又会压图标。
        val normal = wordHeadlineSizeSp("ubiquitous", 128f, 1f)
        val enlarged = wordHeadlineSizeSp("ubiquitous", 128f, 1.3f)
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
    fun `二十八字母的极端长词停在下限交给折行`() {
        // antidisestablishmentarianism：再怎么缩也不该小到看不清，
        // 18sp 以下交给 maxLines=2 + widthIn 折行，图标照样不沾边。
        val size = wordHeadlineSizeSp("antidisestablishmentarianism", 128f, 1f)
        assertEquals(WORD_HEADLINE_MIN_SP, size, 0f)
    }

    @Test
    fun `字号永远落在地下限与上限之间`() {
        val words = listOf("a", "cat", "sophisticated", "antidisestablishmentarianism")
        for (word in words) {
            for (available in listOf(0f, 50f, 128f, 320f, 1000f)) {
                for (scale in listOf(0.5f, 1f, 3f)) {
                    val size = wordHeadlineSizeSp(word, available, scale)
                    assertTrue(
                        "$word / $available / $scale 吐出 $size",
                        size in WORD_HEADLINE_MIN_SP..WORD_HEADLINE_MAX_SP
                    )
                }
            }
        }
    }
}
