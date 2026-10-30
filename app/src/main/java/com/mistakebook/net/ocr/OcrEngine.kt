package com.mistakebook.net.ocr

import com.mistakebook.data.prefs.OcrProvider
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
import java.io.File
import java.util.Base64

/**
 * 图片转文字（OCR）的统一出口（v0.0.78）。
 *
 * ## 为什么要有这一层
 *
 * 之前「读图文字」只有 MinerU 一条路：`ChatImageOcr` 直接绑死
 * `MineruClient`，扫描件 PDF、识图工具也各自写一遍调用。用户提出
 * 「配置改成 OCR 配置，希望适配 MinerU 和 DeepSeekOCR」之后，
 * 把「选谁」与「怎么调」分开：上层只说「把这张图读成文字」，
 * 供应商由设置决定。
 *
 * ## 能力边界（不在接口里假装统一）
 *
 * [OcrEngine.recognizeText] 只承诺**文字**。MinerU 那份文档级解析
 * （提取题目配图）仍由识别流水线单独走 `MineruClient`——这里不吞掉
 * 那个能力，也不假装 DeepSeek 有它。
 */
interface OcrEngine {

    /** 供应商名（错误信息与日志用）。 */
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
        /** 按当前设置挑实现。 */
        fun forSettings(settings: SettingsSnapshot, deps: OcrEngineDeps): OcrEngine =
            when (settings.ocrProvider) {
                OcrProvider.MINERU -> MineruOcrEngine(deps)
                OcrProvider.DEEPSEEK -> DeepSeekOcrEngine(deps.llmApi, deps.httpClient)
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
class MineruOcrEngine(private val deps: OcrEngineDeps) : OcrEngine {

    override val provider: OcrProvider = OcrProvider.MINERU

    override suspend fun recognizeText(
        image: File,
        settings: SettingsSnapshot,
        onStage: suspend (String) -> Unit
    ): ApiResult<String> {
        if (settings.mineruKey.isBlank()) {
            return ApiResult.Failure(
                ApiError(ApiErrorKind.NO_KEY, serverMessage = "未配置 MinerU API Key")
            )
        }
        // 产物落系统临时目录：只取文字，调用方自己决定要不要缓存
        val outDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "ocr_${System.currentTimeMillis()}")
        return runCatching {
            deps.mineru.recognizeInto(
                file = image,
                fileName = image.name,
                mineruKey = settings.mineruKey,
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
 * DeepSeek OCR 实现（v0.0.78）：OpenAI 兼容 `chat/completions`，
 * 图片以 image_url(data URL) 送入，让视觉模型把内容转写为 Markdown。
 *
 * 参数全部由用户填（Base URL / Key / 模型名），以适配官方端点与中转站。
 */
class DeepSeekOcrEngine(
    private val llmApi: LlmApi,
    private val httpClient: okhttp3.OkHttpClient
) : OcrEngine {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override val provider: OcrProvider = OcrProvider.DEEPSEEK

    override suspend fun recognizeText(
        image: File,
        settings: SettingsSnapshot,
        onStage: suspend (String) -> Unit
    ): ApiResult<String> {
        val key = settings.deepseekOcrApiKey.trim()
        val model = settings.deepseekOcrModel.trim()
        if (key.isBlank() || model.isBlank()) {
            return ApiResult.Failure(
                ApiError(ApiErrorKind.NO_KEY, serverMessage = "未配置 DeepSeek OCR 的 Key 或模型名")
            )
        }
        if (!image.isFile || image.length() <= 0L) {
            return ApiResult.Failure(
                ApiError(ApiErrorKind.BAD_RESPONSE, serverMessage = "图片文件不存在")
            )
        }
        onStage("OCR 识别中…")
        val dataUrl = runCatching {
            val bytes = image.readBytes()
            "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
        }.getOrElse {
            return ApiResult.Failure(
                ApiError(ApiErrorKind.BAD_RESPONSE, serverMessage = "读取图片失败")
            )
        }
        val base = settings.deepseekOcrBaseUrl.trim().trimEnd('/').ifBlank { "https://api.deepseek.com" }
        val url = if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
        // 多模态消息：ChatMessage.content 是 JsonElement，图片走 parts 数组
        // （与 ChatViewModel.toLlm 同一套构造方式，服务端只认这个形状）。
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
        val response = try {
            llmApi.chatCompletions(url, "Bearer $key", request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            return ApiResult.Failure(HttpFactory.throwableError(t))
        }
        if (!response.isSuccessful) {
            val body = response.errorBody()?.string().orEmpty()
            return ApiResult.Failure(HttpFactory.httpError(response.code(), body))
        }
        val text = response.body()?.choices?.firstOrNull()?.message?.content.orEmpty()
        if (text.isBlank()) {
            return ApiResult.Failure(
                ApiError(ApiErrorKind.BAD_RESPONSE, serverMessage = "OCR 返回为空")
            )
        }
        return ApiResult.Success(text)
    }

    private companion object {
        /**
         * 转写指令：只要内容，不要寒暄/解释。
         * 公式按 LaTeX 输出——本 App 全链路吃 KaTeX。
         */
        const val TRANSCRIBE_PROMPT =
            "请把图片里的全部内容逐字转写为 Markdown（保留原有段落与题号顺序）。" +
                "数学公式用 LaTeX：行内 $...$、独立 $$...$$。" +
                "表格用 Markdown 表格。不要添加任何解释、总结或寒暄，只输出转写结果。"
    }
}
