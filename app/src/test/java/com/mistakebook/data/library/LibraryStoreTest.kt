package com.mistakebook.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 资料库路径穿越防护与读写行为的单测（任务书 T6 硬性要求）。
 *
 * 穿越反例覆盖 Windows / POSIX 两种分隔符、多重穿越、伪装扩展名、
 * 绝对路径注入；正例覆盖四种一期扩展名。
 */
class LibraryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun root(): File = tmp.newFolder("library")

    // ---- 路径安全 ----

    @Test
    fun `simple names resolve inside root`() {
        val root = root()
        listOf("a.md", "笔记.txt", "data.json", "表.csv").forEach { name ->
            val f = LibraryStore.resolveLibraryPath(root, name)
            assertNotNull("应接受 $name", f)
            assertTrue(f!!.path.startsWith(root.canonicalPath))
        }
    }

    @Test
    fun `parent traversal is rejected`() {
        val root = root()
        assertNull(LibraryStore.resolveLibraryPath(root, "../evil.md"))
        assertNull(LibraryStore.resolveLibraryPath(root, "..\\evil.md"))
        assertNull(LibraryStore.resolveLibraryPath(root, "a/../../evil.md"))
        assertNull(LibraryStore.resolveLibraryPath(root, ".."))
        assertNull(LibraryStore.resolveLibraryPath(root, "x.md/.."))
    }

    @Test
    fun `absolute path injection is rejected`() {
        val root = root()
        assertNull(LibraryStore.resolveLibraryPath(root, "C:\\Windows\\evil.md"))
        assertNull(LibraryStore.resolveLibraryPath(root, "/etc/passwd.md"))
    }

    @Test
    fun `unsupported or missing extension is rejected`() {
        val root = root()
        assertNull(LibraryStore.resolveLibraryPath(root, "file.pdf"))
        assertNull(LibraryStore.resolveLibraryPath(root, "file.exe"))
        assertNull(LibraryStore.resolveLibraryPath(root, "noext"))
        assertNull(LibraryStore.resolveLibraryPath(root, ""))
        assertNull(LibraryStore.resolveLibraryPath(root, "  "))
    }

    @Test
    fun `nested names are rejected outright`() {
        val root = root()
        // 目录分量语义直接拒绝：资料库是平铺目录，不做子目录（也不给穿越留门缝）
        assertNull(LibraryStore.resolveLibraryPath(root, "sub/dir/x.md"))
    }

    // ---- 读写行为 ----

    @Test
    fun `write then read round trips`() {
        val root = root()
        val f = LibraryStore.resolveLibraryPath(root, "note.md")!!
        val bytes = LibraryStore.write(f, "# 标题\n\n正文内容")
        assertTrue(bytes > 0)
        val (text, truncated) = LibraryStore.read(f)!!
        assertEquals("# 标题\n\n正文内容", text)
        assertFalse(truncated)
    }

    @Test
    fun `read truncates over 50KB`() {
        val root = root()
        val f = LibraryStore.resolveLibraryPath(root, "big.txt")!!
        LibraryStore.write(f, "x".repeat(LibraryStore.MAX_READ_BYTES + 100))
        val (text, truncated) = LibraryStore.read(f)!!
        assertTrue(truncated)
        assertEquals(LibraryStore.MAX_READ_BYTES, text.toByteArray().size)
    }

    @Test
    fun `append grows file`() {
        val root = root()
        val f = LibraryStore.resolveLibraryPath(root, "log.md")!!
        LibraryStore.write(f, "A")
        LibraryStore.append(f, "B")
        assertEquals("AB", LibraryStore.read(f)!!.first)
    }

    @Test
    fun `delete removes file`() {
        val root = root()
        val f = LibraryStore.resolveLibraryPath(root, "gone.md")!!
        LibraryStore.write(f, "x")
        assertTrue(LibraryStore.delete(f))
        assertFalse(f.exists())
    }

    @Test
    fun `list returns files sorted`() {
        val root = root()
        LibraryStore.write(LibraryStore.resolveLibraryPath(root, "b.md")!!, "1")
        LibraryStore.write(LibraryStore.resolveLibraryPath(root, "a.md")!!, "2")
        val names = LibraryStore.list(root).map { it.name }
        assertEquals(listOf("a.md", "b.md"), names)
    }

    // ---- LibraryTool 分发 ----

    @Test
    fun `tool list and write round trip`() {
        val root = root()
        val w = LibraryTool.dispatch(root, "library_write", mapOf("name" to "x.md", "content" to "hello"))
        assertTrue(w.ok)
        val r = LibraryTool.dispatch(root, "library_read", mapOf("name" to "x.md"))
        assertTrue(r.ok)
        assertTrue(r.message.contains("hello"))
    }

    @Test
    fun `tool rejects traversal in every op`() {
        val root = root()
        listOf("library_read", "library_write", "library_edit", "library_delete").forEach { op ->
            val r = LibraryTool.dispatch(root, op, mapOf("name" to "../evil.md", "content" to "x"))
            assertFalse("$op 应拒绝穿越", r.ok)
        }
    }

    @Test
    fun `tool edit replaces first occurrence`() {
        val root = root()
        LibraryTool.dispatch(root, "library_write", mapOf("name" to "e.md", "content" to "AAA BBB"))
        val r = LibraryTool.dispatch(
            root, "library_edit",
            mapOf("name" to "e.md", "old" to "AAA", "content" to "CCC")
        )
        assertTrue(r.ok)
        assertEquals("CCC BBB", LibraryStore.read(LibraryStore.resolveLibraryPath(root, "e.md")!!)!!.first)
    }

    @Test
    fun `tool edit replace fails when old text missing`() {
        val root = root()
        LibraryTool.dispatch(root, "library_write", mapOf("name" to "e.md", "content" to "AAA"))
        val r = LibraryTool.dispatch(
            root, "library_edit",
            mapOf("name" to "e.md", "old" to "ZZZ", "content" to "CCC")
        )
        assertFalse(r.ok)
    }
}
