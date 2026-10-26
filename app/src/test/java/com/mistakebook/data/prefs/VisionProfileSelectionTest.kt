package com.mistakebook.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识图模型的选取逻辑单测。
 *
 * 用户反馈「只能选择哪个供应商来进行识图，不能自己指定模型」——
 * 现在识图是一套**独立配置**（地址 / Key / 模型名全自己填），
 * 同时保留「从对话模型里指定」的老设置作为回落。两条路的优先级
 * 必须测清楚，否则又会出现「配了却没生效」。
 */
class VisionProfileSelectionTest {

    private fun profile(id: String, model: String, name: String = "") = LlmProfile(
        id = id,
        name = name,
        baseUrl = "https://api.example.com",
        apiKey = "sk-test",
        model = model
    )

    @Test
    fun `独立配置的识图模型优先`() {
        val vision = profile("v1", "qwen-vl-max")
        val chat = profile("c1", "deepseek-chat")
        val snapshot = SettingsSnapshot(
            llmProfiles = listOf(chat),
            visionProfiles = listOf(vision),
            activeVisionProfileId = "v1",
            // 老设置指向对话模型：独立配置存在时它不该生效
            visionProfileId = "c1"
        )
        assertEquals("qwen-vl-max", snapshot.visionProfile?.model)
        assertTrue(snapshot.visionConfigured)
    }

    @Test
    fun `没有独立配置时回落到从对话模型指定的那一套`() {
        val chat = profile("c1", "glm-4v")
        val snapshot = SettingsSnapshot(
            llmProfiles = listOf(chat),
            visionProfileId = "c1"
        )
        assertEquals("glm-4v", snapshot.visionProfile?.model)
        assertTrue(snapshot.visionConfigured)
    }

    @Test
    fun `指到已删除的配置时返回 null 而不是第一套`() {
        val snapshot = SettingsSnapshot(
            llmProfiles = listOf(profile("c1", "a"), profile("c2", "b")),
            // 指向一个不存在的 id：删了配置就等于没指定
            visionProfileId = "gone"
        )
        assertNull(snapshot.visionProfile)
        assertFalse(snapshot.visionConfigured)
    }

    @Test
    fun `没选激活项时用第一套`() {
        val first = profile("v1", "model-1")
        val second = profile("v2", "model-2")
        val snapshot = SettingsSnapshot(visionProfiles = listOf(first, second))
        assertEquals("model-1", snapshot.activeVisionProfile?.model)
    }

    @Test
    fun `配置不完整的识图模型不算可用`() {
        val incomplete = LlmProfile(id = "v1", baseUrl = "https://x", apiKey = "k", model = "")
        val snapshot = SettingsSnapshot(visionProfiles = listOf(incomplete))
        assertFalse(snapshot.visionConfigured)
    }

    @Test
    fun `识图模型名由用户自由填写不被改写`() {
        // 关键诉求：模型名是什么就是什么（不是供应商名、不是对话模型名）
        val snapshot = SettingsSnapshot(
            visionProfiles = listOf(profile("v1", "Pro/Qwen/Qwen2.5-VL-7B-Instruct"))
        )
        assertEquals("Pro/Qwen/Qwen2.5-VL-7B-Instruct", snapshot.visionProfile?.model)
    }
}
