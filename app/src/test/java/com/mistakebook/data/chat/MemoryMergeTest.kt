package com.mistakebook.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长期记忆的写入与自动提取（v0.0.128，issue #42）。
 *
 * 用户：「对话中没有记忆工具，也不会根据对话内容总结部分重要信息丢进记忆库」。
 * 这里钉住四件容易退化的事：
 * - 合并只追加、去重、不碰人工写过的分节；
 * - 同一事实重复写入不产生重复条目；
 * - 自动提取的闸门只对「长期信息」措辞放行（每轮都跑 = 成本翻倍 + 噪音）；
 * - 模型返回的条目解析（行/JSON、去「无」、去序号）。
 */
class MemoryMergeTest {

    @Test
    fun `空文档新建自动节并写入条目`() {
        val merged = MemoryMerge.merge("", listOf("考研英语一", "喜欢拉格朗日法"))
        assertTrue(merged.contains(MemoryMerge.AUTO_SECTION))
        assertTrue(merged.contains("- 考研英语一"))
        assertTrue(merged.contains("- 喜欢拉格朗日法"))
    }

    @Test
    fun `重复事实不重复写入`() {
        val once = MemoryMerge.merge("", listOf("考研英语一"))
        val twice = MemoryMerge.merge(once, listOf("考研英语一"))
        assertEquals("重复条目必须原样返回（调用方据此不写盘）", once, twice)
        assertEquals(1, twice.split("- 考研英语一").size - 1)
    }

    @Test
    fun `空白与大小写差异视为同一条`() {
        val doc = "## 身份\n- 考研英语一\n"
        val merged = MemoryMerge.merge(doc, listOf(" 考研 英语一 "))
        assertEquals(doc, merged)
    }

    @Test
    fun `人工写的分节不被动`() {
        val doc = "## 身份\n- 大三学生\n\n## 目标\n- 考研上岸\n"
        val merged = MemoryMerge.merge(doc, listOf("每天学习两小时"))
        assertTrue(merged.startsWith(doc.trimEnd()))
        assertTrue(merged.contains("## 身份"))
        assertTrue(merged.contains("- 大三学生"))
        assertTrue(merged.contains("- 每天学习两小时"))
    }

    @Test
    fun `自动节已有条目时追加在该节末尾而不是文件末尾`() {
        val doc = "## 对话里记下的\n- 第一条\n\n## 其他\n- 人工条目\n"
        val merged = MemoryMerge.merge(doc, listOf("第二条"))
        val autoAt = merged.indexOf("- 第一条")
        val newAt = merged.indexOf("- 第二条")
        val otherAt = merged.indexOf("## 其他")
        assertTrue("新条目要落在自动节里", autoAt < newAt && newAt < otherAt)
    }

    @Test
    fun `超长时先裁自动节最早的条目`() {
        val longHuman = "## 身份\n" + (1..200).joinToString("\n") { "- 人工条目$it" }
        val merged = MemoryMerge.merge(longHuman + "\n\n## 对话里记下的\n- 旧条目\n", listOf("新条目"))
        // 人工内容必须完整保留
        assertTrue(merged.contains("- 人工条目1"))
        assertTrue(merged.contains("- 人工条目200"))
        assertTrue(merged.contains("- 新条目"))
    }

    // ---- 自动提取闸门 ----

    @Test
    fun `只有长期信息的措辞才触发提取`() {
        assertTrue(MemoryAutoExtract.shouldAttempt("我是大三的，准备考研"))
        assertTrue(MemoryAutoExtract.shouldAttempt("以后都用拉格朗日法吧"))
        assertTrue(MemoryAutoExtract.shouldAttempt("记住我不喜欢看视频课"))
        assertFalse("问一道题不该触发", MemoryAutoExtract.shouldAttempt("帮我算一下 2+2"))
        assertFalse("太短的句子不算", MemoryAutoExtract.shouldAttempt("嗯"))
    }

    @Test
    fun `解析模型返回的条目`() {
        assertEquals(
            listOf("考研英语一", "偏好图解"),
            MemoryAutoExtract.parseFacts("- 考研英语一\n- 偏好图解")
        )
        assertEquals(
            listOf("大三学生", "目标 400+"),
            MemoryAutoExtract.parseFacts("""["大三学生","目标 400+"]""")
        )
        assertTrue("「无」表示没有可记的", MemoryAutoExtract.parseFacts("无").isEmpty())
        assertTrue(MemoryAutoExtract.parseFacts("").isEmpty())
        assertEquals(
            "序号与项目符号都要剥掉",
            listOf("目标院校 A"),
            MemoryAutoExtract.parseFacts("1. 目标院校 A")
        )
    }

    // ---- 工具定义 ----

    @Test
    fun `记忆工具声明 facts 数组参数`() {
        val tool = MemoryTools.all().first()
        assertEquals(MemoryTools.SAVE, tool.function.name)
        assertTrue(tool.function.parameters.toString().contains("facts"))
        assertEquals(
            listOf("考研英语一", "偏好图解"),
            MemoryTools.parseFacts("""{"facts":["考研英语一","偏好图解"]}""")
        )
        assertTrue(MemoryTools.parseFacts("""{"facts":[]}""").isEmpty())
    }
}