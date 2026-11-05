package com.mistakebook.ui.library

import com.mistakebook.data.library.LibraryIndexer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资料库索引状态文案（v0.0.115）。
 *
 * 巡检时列表里明明有一份 `linear_algebra.md`，索引状态却写着
 * 「资料库为空，导入资料后建立索引」——因为 `chunks == 0` 同时代表
 * 「没资料」和「有资料但没建索引」两种处境。这里把五种归宿钉死。
 */
class LibraryIndexStatusTest {

    private fun progress(
        running: Boolean = false,
        done: Int = 0,
        total: Int = 0,
        docs: Int = 0,
        chunks: Int = 0,
        lastError: String? = null
    ) = LibraryIndexer.Progress(
        running = running,
        done = done,
        total = total,
        docs = docs,
        chunks = chunks,
        lastError = lastError
    )

    @Test
    fun `有资料但未索引不再说资料库为空`() {
        assertEquals(
            IndexStatusKind.MISSING,
            indexStatusKind(progress(), hasFiles = true)
        )
    }

    @Test
    fun `真没有资料才说资料库为空`() {
        assertEquals(
            IndexStatusKind.EMPTY,
            indexStatusKind(progress(), hasFiles = false)
        )
    }

    @Test
    fun `运行中与失败优先于空态`() {
        assertEquals(
            IndexStatusKind.RUNNING,
            indexStatusKind(progress(running = true, done = 1, total = 3), hasFiles = true)
        )
        assertEquals(
            IndexStatusKind.FAILED,
            indexStatusKind(progress(lastError = "401"), hasFiles = true)
        )
    }

    @Test
    fun `有块就是已索引`() {
        assertEquals(
            IndexStatusKind.READY,
            indexStatusKind(progress(docs = 1, chunks = 7), hasFiles = true)
        )
    }
}
