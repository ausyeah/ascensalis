package com.mistakebook.net

import org.junit.Assert.assertEquals
import org.junit.Test
import com.mistakebook.data.prefs.LlmKeyEntry
import com.mistakebook.data.prefs.LlmProfile

/**
 * Key 粘贴带空白（v0.0.150 用户实测）的三道防线单测。
 *
 * 用户从聊天记录复制 key 时带回一个换行，存进配置后每次请求都带着它，
 * OkHttp 拼 Authorization 头直接抛 `Unexpected char 0x0a at 69`——报错
 * 跟「key 填错」毫无关系，肉眼根本查不出来。三道防线：
 * ① 输入框即时剥离；② normalized() 保存时清洗；③ 拼 Bearer 头的
 * 统一收口兜底（已存库的脏数据也被救活）。
 */
class KeyPasteSanitizeTest {

    /**
     * 手机上真实撞到的报文形态：62 位 key + 「Bearer 」前缀 = 换行正好在第 69 位。
     *
     * 这里用**等长假 Key**：2026-11-11 发现该文件曾把用户真实 Key 当字面量提交，
     * 已替换（真实 Key 须在服务商后台吊销，git 历史里的那份改不掉）。
     */
    private val dirtyKey = "sk-magpie-TESTEXAMPLE-NOT-A-REAL-KEY00000000000000000000000000\n"

    @Test
    fun `bearer 头剥掉换行与空格`() {
        assertEquals("Bearer $dirtyKey".trim() + "", HttpFactory.bearer(dirtyKey.trim()))
        assertEquals(
            "换行必须被剥掉，OkHttp 才不拒绝请求头",
            "Bearer sk-test",
            HttpFactory.bearer("sk-test\n")
        )
        assertEquals("Bearer sk-test", HttpFactory.bearer(" sk-test "))
        assertEquals("Bearer sk-test", HttpFactory.bearer("sk\t-test"))
    }

    @Test
    fun `normalized 清洗主 key 与附加 key`() {
        val profile = LlmProfile(
            baseUrl = " http://60.205.247.57:38421/v1 \n",
            apiKey = dirtyKey,
            model = " group/flash-low \n",
            extraKeys = listOf(LlmKeyEntry(key = " sk-extra \n"))
        )
        val clean = profile.normalized()
        assertEquals(
            "主 key 的换行必须被剥掉",
            dirtyKey.trim(),
            clean.apiKey
        )
        assertEquals("sk-extra", clean.extraKeys.single().key)
        assertEquals("模型名的尾随换行会让路由表查不到", "group/flash-low", clean.model)
        assertEquals("http://60.205.247.57:38421/v1", clean.baseUrl)
    }

    @Test
    fun `normalized 已带路径的 URL 保持原样`() {
        assertEquals(
            "https://host/api",
            LlmProfile(baseUrl = "https://host/api").normalized().baseUrl
        )
    }

    @Test
    fun `空模型名时回退主 key（拉列表不再误报没填 Key）`() {
        val profile = LlmProfile(baseUrl = "https://x.com/v1", apiKey = "sk-main")
        assertEquals(
            "探活的链路是 resolveKey(model)——模型没选时也该拿到主 key",
            "sk-main",
            profile.resolveKey("")
        )
        assertEquals(1, profile.keyCandidates("").size)
    }

    @Test
    fun `空模型名且主 key 为空时回退附加 key`() {
        val profile = LlmProfile(
            baseUrl = "https://x.com/v1",
            extraKeys = listOf(LlmKeyEntry(key = "sk-a"), LlmKeyEntry(key = "  \n"))
        )
        val candidates = profile.keyCandidates("")
        assertEquals("空白的附加 key 要被过滤掉", listOf("sk-a"), candidates.map { it.key })
    }
}
