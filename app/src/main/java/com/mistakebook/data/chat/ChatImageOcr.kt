package com.mistakebook.data.chat

import com.mistakebook.data.AppFiles
import com.mistakebook.data.prefs.SettingsSnapshot
import com.mistakebook.net.ApiResult
import com.mistakebook.pipeline.MineruClient
import java.io.File

/**
 * 对话页的「图片先过 MinerU」预处理。
 *
 * ## 场景
 *
 * 用户接的大模型**不识图**（DeepSeek-chat、Qwen-plus 这类）。
 * 用户在对话里发了张题目照片，模型看不见——于是要么答非所问，
 * 要么服务端直接报错「image_url 不支持」。
 *
 * 本 App 已经接了 MinerU，所以有一条现成的路：
 * **让 MinerU 读图，把读出来的文字喂给模型。**
 *
 * ## 为什么要缓存
 *
 * MinerU 是**按页计费**的（见 `UsageStatsStore.recordMineru`）。
 * 同一张图反复识别就是反复烧钱。
 *
 * 缓存键用「文件路径 + 修改时间 + 大小」：附件文件是不可变的，
 * 但用户删了重传同名文件时只按路径当键会命中旧内容。
 */
class ChatImageOcr(
    private val mineruClient: MineruClient,
    private val files: AppFiles
) {

    /**
     * 识别一张图片，返回整理好的文字。
     *
     * @param sessionId 会话 id——产物目录按会话隔离，删会话时一并清掉
     * @param imageFile 本地图片文件
     * @param settings 当前设置（取 MinerU Key 与识别参数）
     * @param onStage 阶段回调，用于给用户显示进度
     */
    suspend fun recognize(
        sessionId: Long,
        imageFile: File,
        settings: SettingsSnapshot,
        onStage: suspend (String) -> Unit
    ): ApiResult<String> {
        if (!imageFile.exists() || imageFile.length() == 0L) {
            return ApiResult.Failure(
                com.mistakebook.net.ApiError(
                    kind = com.mistakebook.net.ApiErrorKind.BAD_RESPONSE,
                    serverMessage = "图片文件不存在"
                )
            )
        }
        val cached = cacheFile(sessionId, imageFile)
        if (cached.exists() && cached.length() > 0L) {
            onStage("已识别过，直接复用")
            return ApiResult.Success(ChatOcrText.prepare(cached.readText()))
        }
        val result = mineruClient.recognizeInto(
            file = imageFile,
            fileName = imageFile.name,
            mineruKey = settings.mineruKey,
            modelVersion = settings.mineruModelVersion,
            language = settings.ocrLanguage,
            forceOcr = settings.forceOcr,
            outDir = files.chatOcrDir(sessionId),
            // 只要文字，不要图片：对话页用不上，抽出来只是浪费磁盘。
            imageDir = null,
            onStage = onStage
        )
        return when (result) {
            is ApiResult.Success -> {
                val prepared = ChatOcrText.prepare(result.data.markdown)
                if (prepared.isNotBlank()) {
                    runCatching {
                        cached.parentFile?.mkdirs()
                        cached.writeText(result.data.markdown)
                    }
                }
                ApiResult.Success(prepared)
            }

            is ApiResult.Failure -> result
        }
    }

    /** 清掉某个会话的 OCR 缓存（删会话时调用）。 */
    fun clearCache(sessionId: Long) {
        files.deleteRecursively(files.chatOcrDir(sessionId))
    }

    private fun cacheFile(sessionId: Long, imageFile: File): File {
        val key = "${imageFile.absolutePath}:${imageFile.lastModified()}:${imageFile.length()}"
            .hashCode().toString()
        return File(files.chatOcrDir(sessionId), "$key.md")
    }
}
