package com.mistakebook.wordbook.algo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拼写判定规则单测。
 *
 * 判定错一位，用户就会觉得「我明明拼对了却说错」——
 * 这类规则必须有单测钉住，尤其是大小写与首尾空格这些
 * 手机输入法很容易「顺手加上」的情况。
 */
class SpellRulesTest {

    @Test
    fun `完全正确判对`() {
        assertTrue(SpellRules.isCorrect("abandon", "abandon"))
    }

    @Test
    fun `大小写不同也算对`() {
        // 手机输入法首字母常自动大写，不能因此判错
        assertTrue(SpellRules.isCorrect("Abandon", "abandon"))
        assertTrue(SpellRules.isCorrect("ABANDON", "abandon"))
    }

    @Test
    fun `首尾空格忽略`() {
        assertTrue(SpellRules.isCorrect("  abandon  ", "abandon"))
        assertTrue(SpellRules.isCorrect("\tabandon\n", "abandon"))
    }

    @Test
    fun `拼错判错`() {
        assertFalse(SpellRules.isCorrect("abondon", "abandon"))
        assertFalse(SpellRules.isCorrect("aband", "abandon"))
        assertFalse(SpellRules.isCorrect("", "abandon"))
    }

    @Test
    fun `连字符词必须原样拼对`() {
        // 词库里有 air-conditioning 这类连字符词，
        // 漏连字符要判错——宽松匹配会让拼写练习失去意义
        assertTrue(SpellRules.isCorrect("air-conditioning", "air-conditioning"))
        assertFalse(SpellRules.isCorrect("air conditioning", "air-conditioning"))
    }

    @Test
    fun `空答案永远判错`() {
        assertFalse(SpellRules.isCorrect("", "abandon"))
        assertFalse(SpellRules.isCorrect("   ", "abandon"))
    }

    @Test
    fun `答案为空时判错`() {
        assertFalse(SpellRules.isCorrect("anything", ""))
    }

    @Test
    fun `归一化去空格转小写`() {
        assertTrue(SpellRules.normalize("  Hello  ") == "hello")
    }
}
