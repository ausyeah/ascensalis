package com.mistakebook.di

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * F6 连拍批次的状态机单测。
 *
 * 批次是「未裁队列 paths + 已裁成果 cropped」两段式，四条最容易错的边：
 * 满员拦截、接力出队、放弃出队且不留残影、提交取走但**绝不删文件**。
 */
class CaptureBatchTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "capture-batch-test-${System.nanoTime()}")
        tmp.mkdirs()
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun newFile(name: String): File =
        File(tmp, name).apply { writeText("fake-jpeg") }

    @Test
    fun `新批次为空且未满`() {
        val batch = CaptureBatch()
        assertEquals(0, batch.size)
        assertFalse(batch.hasPending)
        assertFalse(batch.isFull())
    }

    @Test
    fun `加入达到上限后拦截且来源以先来者为准`() {
        val batch = CaptureBatch()
        repeat(CaptureBatch.MAX) { i ->
            assertTrue(batch.add(newFile("p$i.jpg").absolutePath, fromGallery = false))
        }
        assertTrue(batch.isFull())
        assertEquals(CaptureBatch.MAX, batch.size)
        assertFalse(batch.add(newFile("overflow.jpg").absolutePath, fromGallery = false))
        assertEquals("满员后不得再收", CaptureBatch.MAX, batch.size)
        // 先拍照后选图：定性为拍照
        assertFalse(batch.sourceIsGallery)
    }

    @Test
    fun `相册来源批次整批标记为相册导入`() {
        val batch = CaptureBatch()
        batch.add(newFile("g0.jpg").absolutePath, fromGallery = true)
        batch.add(newFile("g1.jpg").absolutePath, fromGallery = true)
        assertTrue(batch.sourceIsGallery)
    }

    @Test
    fun `裁切确认一张出队一张且成果按序累积`() {
        val batch = CaptureBatch()
        batch.add("a.jpg", fromGallery = false)
        batch.add("b.jpg", fromGallery = false)
        batch.add("c.jpg", fromGallery = false)
        assertEquals(3, batch.size)

        batch.commitCropped("crop_a.jpg")
        assertEquals(listOf("b.jpg", "c.jpg"), batch.paths)
        assertEquals(listOf("crop_a.jpg"), batch.cropped)
        assertEquals(3, batch.size)

        batch.commitCropped("crop_b.jpg")
        assertEquals(listOf("c.jpg"), batch.paths)
        assertEquals(listOf("crop_a.jpg", "crop_b.jpg"), batch.cropped)
    }

    @Test
    fun `放弃当前张只出队不计成果且删除未裁原图`() {
        val batch = CaptureBatch()
        val dropped = newFile("drop.jpg")
        val kept = newFile("keep.jpg")
        batch.add(dropped.absolutePath, fromGallery = false)
        batch.add(kept.absolutePath, fromGallery = false)

        val returned = batch.dropCurrent()
        assertEquals(dropped.absolutePath, returned)
        assertEquals(listOf(kept.absolutePath), batch.paths)
        assertTrue("没裁完的原图必须删掉，防幽灵残留", !dropped.exists())
        assertTrue("未放弃的原图不能动", kept.exists())
    }

    @Test
    fun `空批次 dropCurrent 返回 null 不崩`() {
        val batch = CaptureBatch()
        assertNull(batch.dropCurrent())
    }

    @Test
    fun `丢弃整批清空状态并删除全部文件`() {
        val batch = CaptureBatch()
        val raw = newFile("raw.jpg")
        val done = newFile("done.jpg")
        batch.add(raw.absolutePath, fromGallery = true)
        batch.commitCropped(done.absolutePath)
        assertTrue(batch.sourceIsGallery)

        batch.clear()
        assertEquals(0, batch.size)
        assertFalse(batch.hasPending)
        assertFalse(batch.sourceIsGallery)
        assertTrue("未裁原图应删除", !raw.exists())
        assertTrue("已裁成果应删除", !done.exists())
    }

    @Test
    fun `drainCropped 取走成果但绝不删文件`() {
        val batch = CaptureBatch()
        val raw = newFile("raw.jpg")
        val done = newFile("done.jpg")
        batch.add(raw.absolutePath, fromGallery = false)
        batch.commitCropped(done.absolutePath)

        val files = batch.drainCropped()
        assertEquals(listOf(done), files)
        // 关键：提交后 MinerU 还要读这些文件，删了就全批失败
        assertTrue("提交取走时绝不能删除成果文件", done.exists())
        assertEquals(0, batch.size)
        assertFalse(batch.sourceIsGallery)
    }

    @Test
    fun `批次夹在中间放弃后还能继续裁完剩余`() {
        val batch = CaptureBatch()
        val a = newFile("a.jpg")
        val b = newFile("b.jpg")
        val c = newFile("c.jpg")
        batch.add(a.absolutePath, fromGallery = false)
        batch.add(b.absolutePath, fromGallery = false)
        batch.add(c.absolutePath, fromGallery = false)

        batch.commitCropped("crop_a.jpg")
        // 裁 b 时返回：b 出队，剩 c
        batch.dropCurrent()
        assertEquals(listOf(c.absolutePath), batch.paths)
        assertNotNull(batch.paths.firstOrNull())
        // 相机页「本批 N 张」= 未裁 + 已裁 = 1 + 1 = 2（计数与实际一致）
        assertEquals(2, batch.size)

        batch.commitCropped("crop_c.jpg")
        assertFalse(batch.hasPending)
        assertEquals(listOf("crop_a.jpg", "crop_c.jpg"), batch.cropped)
    }
}
