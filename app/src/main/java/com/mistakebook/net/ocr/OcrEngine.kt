package com.mistakebook.net.ocr

import com.mistakebook.data.prefs.OcrProfile
import com.mistakebook.data.prefs.OcrProvider
import com.mistakebook.data.prefs.OcrType
import com.mistakebook.data.prefs.SettingsSnapshot
import com.mistakebook.net.ApiError
import com.mistakebook.net.ApiErrorKind
import com.mistakebook.net.ApiResult
import com.mistakebook.net.HttpFactory
import com.mistakebook.net.llm.ChatContentPart
import com.mistakebook.net.llm.ChatMessage
import com.mistakebook.net.llm.ChatRequest
import com.mistakebook.net.llm.ImageUrl
import com.mistakebook.net.llm.LlmApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 统一的 OCR 识别接口。
 *
 * 支持 MinerU 官方文档解析与任意 OpenAI 兼容的视觉模型端点（DeepSeek OCR、讯飞星辰 MaaS、Qwen-VL 等）。
 */
interface OcrEngine {

    val provider: OcrProvider

    /**
     * 把一张图片读成文字（Markdown / 纯文本）。
     *
     * @param onStage 阶段回调（MinerU 轮询进度这类长流程用）。
     */
    suspend fun recognizeText(
        image: File,
        settings: SettingsSnapshot,
        onStage: suspend (String) -> Unit
    ): ApiResult<String>

    companion object {
        /** 按当前设置挑实现（优先选用 activeOcrProfile）。 */
        fun forSettings(settings: SettingsSnapshot, deps: OcrEngineDeps): OcrEngine {
            val profile = settings.activeOcrProfile
            return if (profile != null) {
                when (profile.type) {
                    OcrType.MINERU -> MineruOcrEngine(deps, profile.apiKey)
                    OcrType.OPENAI_VISION -> DeepSeekOcrEngine(deps.llmApi, deps.httpClient, profile)
                }
            } else {
                when (settings.ocrProvider) {
                    OcrProvider.MINERU -> MineruOcrEngine(deps, settings.mineruKey)
                    OcrProvider.DEEPSEEK -> DeepSeekOcrEngine(deps.llmApi, deps.httpClient)
                }
            }
        }
    }
}

/** 引擎依赖（由 AppContainer 注入，测试可替换）。 */
class OcrEngineDeps(
    val mineru: com.mistakebook.pipeline.MineruClient,
    val llmApi: LlmApi,
    val httpClient: okhttp3.OkHttpClient
)

/**
 * MinerU 实现：委托给既有 [com.mistakebook.pipeline.MineruClient]，
 * 只取 Markdown（不落图片）。
 */
class MineruOcrEngine(
    private val deps: OcrEngineDeps,
    private val apiKeyOverride: String? = null
) : OcrEngine {

    override val provider: OcrProvider = OcrProvider.MINERU

    override suspend fun recognizeText(
        image: File,
        settings: SettingsSnapshot,
        onStage: suspend (String) -> Unit
    ): ApiResult<String> {
        val key = apiKeyOverride?.trim()?.ifBlank { null } ?: settings.mineruKey
        if (key.isBlank()) {
            return ApiResult.Failure(
                ApiError(ApiErrorKind.NO_KEY, serverMessage = "未配置 MinerU API Key")
            )
        }
        val outDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "ocr_${System.currentTimeMillis()}")
        return runCatching {
            deps.mineru.recognizeInto(
                file = image,
                fileName = image.name,
                mineruKey = key,
                modelVersion = settings.mineruModelVersion,
                language = settings.ocrLanguage,
                forceOcr = settings.forceOcr,
                outDir = outDir,
                imageDir = null,
                onStage = onStage
            )
        }.getOrElse { thrown ->
            if (thrown is CancellationException) throw thrown
            ApiResult.Failure(
                ApiError(ApiErrorKind.NETWORK, serverMessage = thrown.message ?: "MinerU 请求异常")
            )
        }.let { result ->
            when (result) {
                is ApiResult.Success -> ApiResult.Success(result.data.markdown)
                is ApiResult.Failure -> result
            }
        }
    }
}

/**
 * 视觉大模型 OCR 实现（v0.0.78 / v0.0.81）：OpenAI 兼容 `chat/completions`，
 * 图片以 image_url(data URL) 送入，让视觉模型把内容转写为 Markdown。
 *
 * 自动适配各家端点（讯飞星辰 MaaS、DeepSeek 官方、Qwen-VL、中转站）。
 * 严格带超时控制（35 秒网络超时 + 40 秒协程熔断），防转圈卡死。
 */
class DeepSeekOcrEngine(
    private val llmApi: LlmApi,
    private val httpClient: okhttp3.OkHttpClient,
    private val profile: OcrProfile? = null
) : OcrEngine {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override val provider: OcrProvider = OcrProvider.DEEPSEEK

    override suspend fun recognizeText(
        image: File,
        settings: SettingsSnapshot,
        onStage: suspend (String) -> Unit
    ): ApiResult<String> {
        val key = profile?.apiKey?.trim()?.ifBlank { null } ?: settings.deepseekOcrApiKey.trim()
        val model = profile?.model?.trim()?.ifBlank { null } ?: settings.deepseekOcrModel.trim()
        if (key.isBlank() || model.isBlank()) {
            return ApiResult.Failure(
                ApiError(ApiErrorKind.NO_KEY, serverMessage = "未配置 OCR 的 API Key 或模型名")
            )
        }
        if (!image.isFile || image.length() <= 0L) {
            return ApiResult.Failure(
                ApiError(ApiErrorKind.BAD_RESPONSE, serverMessage = "图片文件不存在")
            )
        }
        onStage("正在编码图片…")
        val dataUrl = runCatching {
            val bytes = image.readBytes()
            "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
        }.getOrElse {
            return ApiResult.Failure(
                ApiError(ApiErrorKind.BAD_RESPONSE, serverMessage = "读取图片失败")
            )
        }

        val url = profile?.endpointUrl() ?: run {
            val base = settings.deepseekOcrBaseUrl.trim().trimEnd('/').ifBlank { "https://api.deepseek.com" }
            if (base.endsWith("/chat/completions") || base.endsWith("/responses")) {
                base
            } else if (base.endsWith("/v1") || base.endsWith("/v2")) {
                "$base/chat/completions"
            } else {
                "$base/v1/chat/completions"
            }
        }

        onStage("等待 OCR 响应…")
        val parts = listOf(
            ChatContentPart(type = ChatContentPart.TYPE_TEXT, text = TRANSCRIBE_PROMPT),
            ChatContentPart(type = ChatContentPart.TYPE_IMAGE, imageUrl = ImageUrl(dataUrl))
        )
        val contentJson = runCatching {
            json.parseToJsonElement(
                json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatContentPart.serializer()), parts)
            )
        }.getOrElse {
            return ApiResult.Failure(
                ApiError(ApiErrorKind.BAD_RESPONSE, serverMessage = "构造 OCR 请求失败")
            )
        }
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = contentJson)),
            stream = false,
            max_tokens = 8_192
        )

        val outcome = withTimeoutOrNull(40_000L) {
            try {
                val response = llmApi.chatCompletions(url, "Bearer $key", request)
                if (!response.isSuccessful) {
                    val body = response.errorBody()?.string().orEmpty()
                    return@withTimeoutOrNull ApiResult.Failure(HttpFactory.httpError(response.code(), body))
                }
                val text = response.body()?.choices?.firstOrNull()?.message?.content.orEmpty()
                if (text.isBlank()) {
                    return@withTimeoutOrNull ApiResult.Failure(
                        ApiError(ApiErrorKind.BAD_RESPONSE, serverMessage = "OCR 服务返回内容为空")
                    )
                }
                ApiResult.Success(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                ApiResult.Failure(HttpFactory.throwableError(t))
            }
        }

        return outcome ?: ApiResult.Failure(
            ApiError(ApiErrorKind.TIMEOUT, serverMessage = "OCR 请求超时（40 秒），请检查网络或端点地址")
        )
    }

    companion object {
        /**
         * 转写指令：只要内容，不要寒暄/解释。
         * 公式按 LaTeX 输出——本 App 全链路吃 KaTeX。
         */
        const val TRANSCRIBE_PROMPT =
            "请把图片里的全部内容逐字转写为 Markdown（保留原有段落与题号顺序）。" +
                "数学公式用 LaTeX：行内 $...$、独立 $$...$$。" +
                "表格用 Markdown 表格。不要添加任何解释、总结或寒暄，只输出转写结果。"

        /**
         * 测试 OCR 供应商连通性。
         * 发送一条极简 ping 请求验证 Key、Base URL 与 Model Name 是否有效。
         */
        suspend fun testConnection(
            profile: OcrProfile,
            httpClient: okhttp3.OkHttpClient
        ): Result<String> = withContext(Dispatchers.IO) {
            runCatching {
                val start = System.currentTimeMillis()
                val url = profile.endpointUrl()
                val key = profile.apiKey.trim()
                val model = profile.model.trim()
                if (key.isBlank()) throw IllegalArgumentException("请先填写 API Key")
                if (model.isBlank()) throw IllegalArgumentException("请先填写模型名称（如 xopdeepseekocr）")

                val client = httpClient.newBuilder()
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(18, TimeUnit.SECONDS)
                    .writeTimeout(18, TimeUnit.SECONDS)
                    .callTimeout(22, TimeUnit.SECONDS)
                    .build()

                val bodyJson = """{"model":"$model","messages":[{"role":"user","content":"ping"}],"max_tokens":10,"stream":false}"""
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $key")
                    .header("Content-Type", "application/json")
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .build()

                val resp = client.newCall(req).execute()
                val elapsed = System.currentTimeMillis() - start
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val err = HttpFactory.httpError(resp.code, respBody)
                    throw IllegalStateException("HTTP ${resp.code}: ${err.detail}")
                }
                "连接成功（耗时 ${elapsed}ms）"
            }
        }
    }
}
