package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文里本地图片引用的清理。
 *
 * 这条逻辑只有一个目的：让**库里已有的坏消息**不再触发本地图片解码
 * （用户报「生图后会话点进去闪退」）。远程图片是正常内容，不能误伤。
 */
class LegacyImageRefsTest {

    @Test
    fun `本地绝对路径图片被清掉`() {
        val text = "示意图：\n\n![](/data/user/0/com.mistakebook/files/images/gen/p_abc.png)\n\n继续讲解"
        val cleaned = LegacyImageRefs.strip(text)
        assertFalse(cleaned.contains("/data/user/0"))
        assertTrue(cleaned.contains("继续讲解"))
        assertTrue(cleaned.contains("示意图"))
    }

    @Test
    fun `file 协议前缀也清掉`() {
        val text = "![](file:///data/user/0/x.png)"
        assertFalse(LegacyImageRefs.strip(text).contains("file://"))
    }

    @Test
    fun `远程图片保留`() {
        val text = "看这张图\n\n![](https://example.com/a.png)"
        val cleaned = LegacyImageRefs.strip(text)
        assertTrue(cleaned.contains("https://example.com/a.png"))
    }

    @Test
    fun `普通文本原样返回`() {
        val text = "没有图片的回答，**加粗**也没事"
        assertEquals(text, LegacyImageRefs.strip(text))
    }

    @Test
    fun `多个连续本地图片都清掉且不留下三连换行`() {
        val text = "a\n\n![](/data/x1.png)\n\n![](/data/x2.png)\n\nb"
        val cleaned = LegacyImageRefs.strip(text)
        assertFalse(cleaned.contains("/data/"))
        assertFalse(cleaned.contains("\n\n\n"))
        assertTrue(cleaned.contains("a"))
        assertTrue(cleaned.contains("b"))
    }

    @Test
    fun `替换后保留一句说明`() {
        // 不能让内容凭空少一块，用户要知道这里原本有图
        val cleaned = LegacyImageRefs.strip("![](/data/x.png)")
        assertTrue(cleaned.contains("示意图"))
    }

    @Test
    fun `带中文文件名的路径也能清`() {
        val text = "![](/data/user/0/x/files/images/gen/p_示意图.png)"
        assertFalse(LegacyImageRefs.strip(text).contains("示意图.png"))
    }
}
