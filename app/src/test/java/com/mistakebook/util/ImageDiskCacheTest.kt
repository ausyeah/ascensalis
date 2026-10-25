package com.mistakebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 生图磁盘 LRU 缓存单测。
 *
 * 真实临时目录（[TemporaryFolder]）而不是 mock——这里测的就是
 * 文件系统行为（命名、touch、淘汰顺序），mock 掉等于什么都没测。
 */
class ImageDiskCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun cache(max: Int = 3) = ImageDiskCache(tempFolder.newFolder(), maxEntries = max)

    @Test
    fun `key 由 prompt 与 model 联合决定`() {
        val c = cache()
        assertEquals(c.keyFor("cat", "m1"), c.keyFor("cat", "m1"))
        // 同 prompt 不同渠道 → 不同 key（渠道不同图本来就该不同）
        assertTrue(c.keyFor("cat", "m1") != c.keyFor("cat", "m2"))
        // 不同 prompt → 不同 key
        assertTrue(c.keyFor("cat", "m1") != c.keyFor("dog", "m1"))
    }

    @Test
    fun `put 后 get 命中且内容一致`() {
        val c = cache()
        val key = c.keyFor("cat", "m1")
        c.put(key, byteArrayOf(1, 2, 3))
        val file = c.get(key)!!
        assertTrue(file.isFile)
        assertTrue(file.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `未写入 get 返回 null`() {
        assertNull(cache().get(cache().keyFor("nope", "m")))
    }

    @Test
    fun `超出上限淘汰最旧且不碰新的`() {
        val c = cache(max = 3)
        val keys = (1..5).map { c.keyFor("p$it", "m") }
        // 逐個写，最后一个 lastModified 最新（文件系统时间戳精度内）
        keys.forEachIndexed { i, k ->
            c.put(k, byteArrayOf(i.toByte()))
            // 让时间戳可区分：部分文件系统 lastModified 精度只到秒
            Thread.sleep(15)
        }
        assertEquals(3, c.count())
        // 最新的三张在，最老的两张被淘汰
        assertNull(c.get(keys[0]))
        assertNull(c.get(keys[1]))
        assertTrue(c.get(keys[2]) != null)
        assertTrue(c.get(keys[3]) != null)
        assertTrue(c.get(keys[4]) != null)
    }

    @Test
    fun `get 命中会 touch 刷新不被淘汰`() {
        val c = cache(max = 2)
        val k1 = c.keyFor("old", "m")
        val k2 = c.keyFor("mid", "m")
        c.put(k1, byteArrayOf(1))
        Thread.sleep(15)
        c.put(k2, byteArrayOf(2))
        Thread.sleep(15)
        // 摸一下最老的 k1，它的「未使用」计时刷新
        assertTrue(c.get(k1) != null)
        Thread.sleep(15)
        // 再写新的：应淘汰 k2（现在最旧），k1 因被摸过而幸存
        c.put(c.keyFor("new", "m"), byteArrayOf(3))
        assertTrue(c.get(k1) != null)
        assertNull(c.get(k2))
    }

    @Test
    fun `peek 不 touch`() {
        val c = cache(max = 1)
        val k1 = c.keyFor("old", "m")
        c.put(k1, byteArrayOf(1))
        Thread.sleep(15)
        assertTrue(c.peek(k1) != null)
        Thread.sleep(15)
        c.put(c.keyFor("new", "m"), byteArrayOf(2))
        // peek 不刷新计时，k1 仍是最旧 → 被淘汰
        assertNull(c.get(k1))
        assertEquals(1, c.count())
    }

    @Test
    fun `clear 清空`() {
        val c = cache()
        c.put(c.keyFor("a", "m"), byteArrayOf(1))
        c.put(c.keyFor("b", "m"), byteArrayOf(2))
        c.clear()
        assertEquals(0, c.count())
    }
}
