package com.mistakebook.pipeline

import com.mistakebook.data.prefs.SettingsStore
import com.mistakebook.net.ImageGenClient
import com.mistakebook.util.ImageDiskCache
import java.io.File

/**
 * 两步生图的编排：**LLM 出英文场景 prompt → 生图 API 出图 → 磁盘缓存**。
 *
 * 单词场景图（F-IMG-1）与详情页辅助图（F-IMG-4）共用这一条流水线，
 * 差别只在调用方给的提示词与缓存种子。
 *
 * ## 为什么 prompt 要让 LLM 出
 *
 * 用户看到的只有一个词（"abandon"）或一道题，直接拿它们当生图 prompt
 * 效果很差（模型不知道要画什么场景）。让对话模型先写一段 30~50 词的
 * 英文场景描述，生图模型才画得出「一个人在雨中放下行李箱离开」。
 *
 * ## 缓存
 *
 * key = md5(种子 + 生图模型名)：同词同义项同渠道不重复烧额度；
 * 换了生图渠道算新 key——不同渠道画风不同，理应重新生成。
 */
class SceneImageGenerator(
    private val llmClient: LlmClient,
    private val imageGenClient: ImageGenClient,
    private val imageDiskCache: ImageDiskCache,
    private val settingsStore: SettingsStore
) {

    /**
     * @param cacheKeySeed 缓存种子：单词场景图传 `word + sense`，详情页传 `questionId + 题目hash`
     * @param systemPrompt  prompt 生成指令（告诉 LLM 它要干什么）
     * @param userPrompt    prompt 生成的输入（词 + 义项 / 题目文本）
     * @param width         生图宽（px）
     * @param height        生图高（px）
     */
    suspend fun generate(
        cacheKeySeed: String,
        systemPrompt: String,
        userPrompt: String,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT
    ): SceneImageResult {
        val settings = settingsStore.snapshotNow()
        val imageProfile = settings.activeImageProfile
        if (imageProfile == null || !imageProfile.isConfigured()) {
            return SceneImageResult.NotConfigured
        }
        // 缓存优先：命中直接返回，不碰任何 API。
        val key = imageDiskCache.keyFor(cacheKeySeed, imageProfile.model)
        imageDiskCache.get(key)?.let { return SceneImageResult.Cached(it) }

        // 第一步：对话模型出英文场景 prompt。
        val llmProfile = settings.activeProfile
            ?: return SceneImageResult.Failed("尚未配置大模型，无法生成图片描述")
        val promptReply = llmClient.plainText(
            profile = llmProfile,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt
        )
        val imagePrompt = when (promptReply) {
            is com.mistakebook.net.ApiResult.Success -> promptReply.data.trim()
            is com.mistakebook.net.ApiResult.Failure ->
                return SceneImageResult.Failed(
                    promptReply.error.serverMessage.ifBlank { "图片描述生成失败" }
                )
        }
        if (imagePrompt.isBlank()) {
            return SceneImageResult.Failed("模型没有给出可用的图片描述")
        }

        // 第二步：生图 API 出图。
        val image = imageGenClient.generate(imageProfile, imagePrompt, width, height)
        return when (image) {
            is com.mistakebook.net.ApiResult.Success -> {
                val file = imageDiskCache.put(key, image.data)
                SceneImageResult.Generated(file, imagePrompt)
            }
            is com.mistakebook.net.ApiResult.Failure ->
                SceneImageResult.Failed(
                    image.error.serverMessage.ifBlank { "生图失败，请检查生图配置" }
                )
        }
    }

    companion object {
        const val DEFAULT_WIDTH = 1024
        const val DEFAULT_HEIGHT = 1024
    }
}

/** [SceneImageGenerator.generate] 的结果：四态齐全，UI 按态渲染。 */
sealed interface SceneImageResult {
    /** 缓存命中：[file] 直接可加载。 */
    data class Cached(val file: File) : SceneImageResult

    /** 新生成：[file] 已落盘，[prompt] 是模型写的描述（UI 可展示来源）。 */
    data class Generated(val file: File, val prompt: String) : SceneImageResult

    /** 没配生图渠道：引导去设置。 */
    data object NotConfigured : SceneImageResult

    /** 任一步失败：[message] 是用户可读原因。 */
    data class Failed(val message: String) : SceneImageResult
}
