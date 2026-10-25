package com.mistakebook.data.repos

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LIKE 关键词转义（T9）。
 *
 * 无 Room in-memory 先例（见 MigrationRegistrationTest 的取舍），
 * DAO 侧的 `ESCAPE '\'` 由契约对齐保证；这里钉死纯函数语义：
 * `%`/`_`/`\` 必须被转成字面量，反斜杠先转才不会破坏后续转义序列。
 */
class EscapeLikeTest {

    @Test
    fun `百分号被转义`() {
        assertEquals("100\\%", escapeLike("100%"))
    }

    @Test
    fun `下划线被转义`() {
        assertEquals("a\\_b", escapeLike("a_b"))
    }

    @Test
    fun `反斜杠先被翻倍`() {
        assertEquals("a\\\\b", escapeLike("a\\b"))
    }

    @Test
    fun `混合序列不互相破坏`() {
        // 先转反斜杠再转 %/_：如果顺序反了，\% 里的 \ 会被再翻倍一次
        assertEquals("100\\%\\_x\\\\y", escapeLike("100%_x\\y"))
    }

    @Test
    fun `普通关键词原样通过`() {
        assertEquals("三角函数", escapeLike("三角函数"))
        assertEquals("", escapeLike(""))
    }
}
