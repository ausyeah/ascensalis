package com.mistakebook.wordbook.algo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错词本毕业规则单测。
 *
 * 毕业线从「答对 1 次」改成「连对 2 次」，这条规则同时驱动：
 * - ViewModel 两条打分路径（selectOption 答对分支 / rateCard「认识」档）
 * - UI 文案「连对 x/2」
 * 抽成纯函数后这里直接钉死行为，改规则的人先过这关。
 */
class WordbookRulesTest {

    @Test
    fun `连对1次不毕业`() {
        assertFalse(WordbookRules.shouldGraduateFromWrongBook(reps = 1))
    }

    @Test
    fun `连对2次毕业`() {
        assertTrue(WordbookRules.shouldGraduateFromWrongBook(reps = 2))
    }

    @Test
    fun `连对3次及以上照样毕业`() {
        assertTrue(WordbookRules.shouldGraduateFromWrongBook(reps = 3))
        assertTrue(WordbookRules.shouldGraduateFromWrongBook(reps = 99))
    }

    @Test
    fun `没连对过不毕业`() {
        assertFalse(WordbookRules.shouldGraduateFromWrongBook(reps = 0))
    }

    @Test
    fun `毕业线常量就是2`() {
        // UI 文案与逻辑共用此常量，这里防止有人单方面改数值
        assertTrue(WordbookRules.GRADUATION_REPS == 2)
    }
}
