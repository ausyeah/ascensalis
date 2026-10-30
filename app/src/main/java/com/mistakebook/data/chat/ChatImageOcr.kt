package com.mistakebook.data.chat

import com.mistakebook.data.AppFiles
import com.mistakebook.data.prefs.SettingsSnapshot
import com.mistakebook.net.ApiError
import com.mistakebook.net.ApiErrorKind
import com.mistakebook.net.ApiResult
import com.mistakebook.pipeline.MineruClient
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
 * 同一张图反复识别就是反复烧钱。缓存键用「路径 + 修改时间 + 大小」：
 * 附件文件不可变，但用户删了重传同名文件时只按路径当键会命中旧内容。
 *
 * ## 为什么要额外记一张「已尝试」表
 *
 * 磁盘缓存只对**识别成功**的图生效。一旦失败、或跑通了但图里没抽出字，
 * 就没有东西可缓存——于是用户每发一句话，同一张图就被再上传一次，
 * 一句话烧一次额度。
 *
 * 主防线在调用方那边：失败时会往附件上落一条**失败说明**
 * （见 [ChatOcrText.failureNote]），有文字就不再进待识别队列，
 * 模型也能如实知道「这张图没读出来」。
 *
 * 这里的 `tried` 是**兜底**：万一那条说明没落成库，
 * 同一张图本轮会话内也绝不重复上传。想主动重试，
 * 通知条上的「重试」会调 [forget] 清掉标记再排队。
 */
class ChatImageOcr(
    /**
     * v0.0.78：OCR 供应商可切换（MinerU / DeepSeek OCR）后，
     * 这里不再直接绑死 MineruClient——统一走 [com.mistakebook.net.ocr.OcrEngine]。
     */
    private val ocrDeps: com.mistakebook.net.ocr.OcrEngineDeps,
    private val files: AppFiles
) {

    /** 按当前设置挑 OCR 引擎。 */
    private fun engineFor(settings: SettingsSnapshot): com.mistakebook.net.ocr.OcrEngine =
        com.mistakebook.net.ocr.OcrEngine.forSettings(settings, ocrDeps)

    /** 本次会话内已经认真尝试过的图片（无论成败）。 */
    private val tried = ConcurrentHashMap.newKeySet<String>()

    /** 成功结果：同一张图在多轮消息里复用，不再走网络。 */
    private val completed = ConcurrentHashMap<String, String>()

    /**
     * 这张图本次会话内是否已经尝试过。
     *
     * 调用方据此**静默跳过**：既不重传，也不弹错误——用户已经见过上一次的失败了。
     */
    fun alreadyTried(imageFile: File): Boolean = tried.contains(keyOf(imageFile))

    /**
     * 清掉某张图的「已尝试」标记（用户在通知条上点「重试」时用）。
     *
     * 只清 tried，不清 completed：成功的图不需要重试，
     * 而它的内存结果继续复用没有坏处。
     */
    fun forget(imageFile: File) {
        tried.remove(keyOf(imageFile))
    }

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
                ApiError(kind = ApiErrorKind.BAD_RESPONSE, serverMessage = "图片文件不存在")
            )
        }
        val key = keyOf(imageFile)

        completed[key]?.let { prepared ->
            onStage("这张图已识别过，直接复用")
            return ApiResult.Success(prepared)
        }

        // 本次会话已经认真试过、且没成功的图，不再重复上传。
        //
        // 正常路径下失败说明已由调用方落库（见 ChatOcrText.failureNote），
        // 这张图根本不会再排到识别队列；这里是**兜底**——
        // 万落库失败，至少不该每发一句话就再烧一次额度。
        if (tried.contains(key)) {
            return ApiResult.Failure(
                ApiError(
                    kind = ApiErrorKind.BAD_RESPONSE,
                    serverMessage = "本次已尝试识别过这张图片，未重复上传（重试请退出会话再进）"
                )
            )
        }

        val cached = cacheFile(sessionId, imageFile)
        if (cached.exists() && cached.length() > 0L) {
            val prepared = ChatOcrText.prepare(cached.readText())
            if (prepared.isNotBlank()) {
                completed[key] = prepared
                tried.add(key)
                onStage("这张图已识别过，直接复用")
                return ApiResult.Success(prepared)
            }
        }

        val result = runCatching {
            // v0.0.78：走 OCR 统一出口——供应商由设置决定
            // （MinerU / DeepSeek OCR），不再直接绑死 MineruClient。
            engineFor(settings).recognizeText(imageFile, settings, onStage)
        }.getOrElse { thrown ->
            // 取消必须原样抛出：用户点了「停止」，不能当成一次普通失败记进 tried
            if (thrown is CancellationException) throw thrown
            ApiResult.Failure(
                ApiError(kind = ApiErrorKind.NETWORK, serverMessage = thrown.message ?: "识别请求异常")
            )
        }

        val mapped = when (result) {
            is ApiResult.Success -> {
                val prepared = ChatOcrText.prepare(result.data)
                if (prepared.isNotBlank()) {
                    runCatching {
                        cached.parentFile?.mkdirs()
                        cached.writeText(result.data)
                    }
                    completed[key] = prepared
                    tried.add(key)
                    ApiResult.Success(prepared)
                } else {
                    // 跑通了但没抽到字：同样记一笔，别让下一句话再烧一次额度
                    tried.add(key)
                    ApiResult.Failure(
                        ApiError(
                            kind = ApiErrorKind.BAD_RESPONSE,
                            serverMessage = "${settings.ocrProvider.label} 已返回，但这张图里没有识别出文字"
                        )
                    )
                }
            }

            is ApiResult.Failure -> {
                tried.add(key)
                result
            }
        }
        return mapped
    }

    /** 清掉某个会话的 OCR 缓存（删会话时调用）。 */
    fun clearCache(sessionId: Long) {
        files.deleteRecursively(files.chatOcrDir(sessionId))
    }

    private fun keyOf(imageFile: File): String =
        "${imageFile.absolutePath}:${imageFile.lastModified()}:${imageFile.length()}"

    private fun cacheFile(sessionId: Long, imageFile: File): File =
        File(files.chatOcrDir(sessionId), "${keyOf(imageFile).hashCode()}.md")
}
