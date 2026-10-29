package com.mistakebook.pipeline

import com.mistakebook.data.AppFiles
import com.mistakebook.data.prefs.UsageStatsStore
import com.mistakebook.net.ApiError
import com.mistakebook.net.ApiErrorKind
import com.mistakebook.net.ApiResult
import com.mistakebook.net.HttpFactory
import com.mistakebook.net.mineru.FileUrlsData
import com.mistakebook.net.mineru.FileUrlsItem
import com.mistakebook.net.mineru.FileUrlsRequest
import com.mistakebook.net.mineru.ExtractResultItem
import com.mistakebook.net.mineru.MineruApi
import com.mistakebook.util.isWithinDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

// 识别产物：Markdown 主结果（图片路径已改写为绝对路径）+ 附带的图片文件。
data class MineruOutcome(
    val batchId: String,
    val markdown: String,
    val imagePaths: List<String>,
    val rawDir: File,

    /**
     * 本次解析的页数（MinerU 计费单位）。
     *
     * 从轮询期间观察到的 `extract_progress.total_pages` 取最后一个
     * 非零值。服务端全程不给这个字段时为 0——用量页数记 0，
     * 次数照记（次数是「调用过」的口径，与页数无关）。
     */
    val pages: Int = 0
)

/**
 * MinerU v4 本地文件上传链路（PRD 4.1）：
 * 申请上传地址 -> PUT 上传 -> 轮询 -> 下载 zip -> 解压 -> 图片与路径整理。
 *
 * 轮询策略：首延迟 2s，之后每 3s；连续 5 次 running 后按 3/5/8/10s 退避；总超时 300s。
 */
class MineruClient(
    private val api: MineruApi,
    private val files: AppFiles,
    private val usageStatsStore: UsageStatsStore
) {

    suspend fun recognize(
        taskId: Long,
        file: File,
        fileName: String,
        mineruKey: String,
        modelVersion: String,
        language: String,
        forceOcr: Boolean,
        onStage: suspend (String) -> Unit
    ): ApiResult<MineruOutcome> = recognizeInto(
        file = file,
        fileName = fileName,
        mineruKey = mineruKey,
        modelVersion = modelVersion,
        language = language,
        forceOcr = forceOcr,
        outDir = files.mineruDir(taskId),
        imageDir = files.questionImageDir(taskId),
        onStage = onStage
    )

    /**
     * 识别一个文件，结果落到**指定目录**。
     *
     * ## 为什么要把目录变成参数
     *
     * 原先只有 [recognize] 一个入口，产物固定写到 `mineru/{taskId}` 与
     * `questions/{taskId}/images`——那套目录是给「拍照识别成题」用的，
     * 依赖 CaptureTask 的 id。
     *
     * 对话页的「拿图片里的文字」没有 CaptureTask，也**不该**占用
     * `questions/` 目录：那里的图片是题目的封面，混进别的产物会让
     * 备份/导出把聊天 OCR 的中间文件当成题目图片带走。
     *
     * @param imageDir 图片产物的落盘目录；**传 null 表示不要图片**——
     *   对话场景只需要 markdown 文本，图片对它没有意义。
     */
    suspend fun recognizeInto(
        file: File,
        fileName: String,
        mineruKey: String,
        modelVersion: String,
        language: String,
        forceOcr: Boolean,
        outDir: File,
        imageDir: File?,
        onStage: suspend (String) -> Unit
    ): ApiResult<MineruOutcome> = withContext(Dispatchers.IO) {
        if (mineruKey.isBlank()) {
            return@withContext fail(ApiErrorKind.NO_KEY)
        }
        val auth = "Bearer $mineruKey"

        onStage("上传识别请求…")
        val batch = when (val r = requestUploadUrl(auth, fileName, modelVersion, language, forceOcr)) {
            is ApiResult.Success -> r.data
            is ApiResult.Failure -> return@withContext r
        }
        // 请求里只发了一个文件，所以 file_urls 也应当只有一个。
        // 真出现多个时**不能随便取第一个**——若服务端返回顺序与请求顺序不一致，
        // 我们会把文件传到「另一个文件」的名下，于是服务端那边永远等不到我们的文件，
        // 状态就一直停在 waiting-file / pending，而用量统计却在涨（别的文件被解析了）。
        val uploadUrl = batch.file_urls.singleOrNull()
            ?: if (batch.file_urls.isEmpty()) {
                return@withContext fail(ApiErrorKind.BAD_RESPONSE, "未获得上传地址")
            } else {
                return@withContext fail(
                    ApiErrorKind.BAD_RESPONSE,
                    "服务端返回了 ${batch.file_urls.size} 个上传地址（预期 1 个），无法确定哪个对应本次文件"
                )
            }

        // 关键：OSS 签名不允许带 Content-Type 头，带了会 SignatureDoesNotMatch(403)
        onStage("上传文件 ${formatSize(file.length())}…")
        val uploaded = rethrowCancellation {
            api.uploadFile(
                uploadUrl = uploadUrl,
                body = file.asRequestBody()
            )
        }.getOrElse { return@withContext ApiResult.Failure(HttpFactory.throwableError(it)) }
        if (!uploaded.isSuccessful) {
            return@withContext fail(
                HttpFactory.httpError(uploaded.code(), uploaded.errorBody()?.string().orEmpty())
            )
        }

        // 一次 recognize 成功 = 一次 MinerU 用量。放在这里而不是 downloadAndExtract：
        // 只有服务端真的解析完（state=done）才算数，上传/轮询阶段的失败不烧额度口径。
        // 页数（MinerU 的真实计费单位）随 outcome 一起传出，一并记账。
        val outcome = pollResults(auth, batch.batch_id, fileName, outDir, imageDir, onStage)
        if (outcome is ApiResult.Success) usageStatsStore.recordMineru(outcome.data.pages)
        outcome
    }

    private suspend fun requestUploadUrl(
        auth: String,
        fileName: String,
        modelVersion: String,
        language: String,
        forceOcr: Boolean
    ): ApiResult<FileUrlsData> {
        val response = rethrowCancellation {
            api.fileUrlsBatch(
                authorization = auth,
                request = FileUrlsRequest(
                    files = listOf(FileUrlsItem(name = fileName, is_ocr = forceOcr)),
                    model_version = modelVersion,
                    language = language
                )
            )
        }.getOrElse { return ApiResult.Failure(HttpFactory.throwableError(it)) }
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            return fail(HttpFactory.httpError(response.code(), response.errorBody()?.string().orEmpty()))
        }
        if (!body.isOk() || body.data == null) {
            return fail(ApiError(kind = ApiErrorKind.AUTH, serverMessage = body.msg))
        }
        return ApiResult.Success(body.data)
    }

    private suspend fun pollResults(
        auth: String,
        batchId: String,
        fileName: String,
        outDir: File,
        imageDir: File?,
        onStage: suspend (String) -> Unit
    ): ApiResult<MineruOutcome> {
        val startedAt = System.currentTimeMillis()
        var runningCount = 0
        var first = true
        // 页数跟踪：`total_pages` 在 running 阶段持续给出，done 时可能不再带。
        // 取最后一个非零值，随 outcome 传给用量记账（MinerU 按页计费）。
        var lastTotalPages = 0
        // 状态计时器：记录最后一次变化时刻，判断是否停滞。
        // 抽成对象是为了可测——见 [MineruStallTracker]。
        val stallTracker = MineruStallTracker()
        // 轮询瞬断容忍（v0.0.68）：一次网络抖动 / 5xx 就整体判死的话，
        // 服务端多半还在解析（文件已上传、额度已计入），用户重试 =
        // 重新上传 + 再烧一份额度。连续 [MAX_POLL_ERRORS] 次轮询错误才判死；
        // 4xx（鉴权 / 参数错）不属于瞬断，立即失败不浪费等待。
        var pollErrors = 0
        while (System.currentTimeMillis() - startedAt < TOTAL_TIMEOUT_MS) {
            val waitMs = when {
                first -> {
                    first = false
                    FIRST_DELAY_MS
                }
                runningCount >= 5 -> {
                    val step = (runningCount - 5).coerceAtMost(BACKOFF.size - 1)
                    BACKOFF[step]
                }
                else -> POLL_INTERVAL_MS
            }
            delay(waitMs)

            // 网络异常 / 非 2xx 统一转成「本轮轮询错误」交给瞬断计数；
            // CancellationException 由 rethrowCancellation 原样上抛
            val pollOutcome = runCatching {
                val response = rethrowCancellation { api.extractResults(auth, batchId) }.getOrThrow()
                val respBody = response.body()
                if (!response.isSuccessful || respBody == null) {
                    throw PollHttpError(
                        HttpFactory.httpError(
                            response.code(),
                            response.errorBody()?.string().orEmpty()
                        )
                    )
                }
                respBody
            }
            val body = pollOutcome.getOrNull()
            if (body == null) {
                val cause = pollOutcome.exceptionOrNull()
                val pollError = (cause as? PollHttpError)?.error
                    ?: HttpFactory.throwableError(cause ?: IllegalStateException("轮询失败"))
                pollErrors++
                val fatal4xx = pollError.detail.startsWith("HTTP 4") &&
                    !pollError.detail.startsWith("HTTP 429")
                if (fatal4xx || pollErrors >= MAX_POLL_ERRORS) {
                    return fail(pollError)
                }
                continue
            }
            pollErrors = 0
            if (!body.isOk()) {
                // -60012 task not found 结合 ping 语义即可判断 Key 有效性，这里一律按错误处理
                return fail(ApiError(kind = ApiErrorKind.AUTH, serverMessage = body.msg))
            }
            // 每轮都把诊断信息写进 stage——**用户看不到 logcat**，
            // 界面上的这行字是我们唯一的排障依据。
            // 包含：已等待秒数、服务端 state、返回项数、文件名匹配结果。
            val elapsedSec = (System.currentTimeMillis() - startedAt) / 1000
            val rawItems = body.data?.extract_result.orEmpty()

            val item = matchItem(rawItems, fileName)
            // 观测必须在**任何**使用 lastSeenState 的地方之前完成。
            // 这里曾经先写 `lastSeenState = item.state` 再去判定停滞，
            // 导致「状态变了没变」的比较恒为假、计时器永不重置。
            // 收进 tracker.observe() 之后这个顺序就由类型保证了。
            val stalled = stallTracker.observe(item?.state, elapsedSec)

            if (item != null) {
                val percent = item.progressPercent()
                val pages = item.totalPages()
                if (pages > 0) lastTotalPages = pages
                val detail = buildString {
                    append("MinerU ").append(stageVerb(item.state)).append(' ')
                    percent?.let { append(it).append("% ") }
                    append("· ").append(elapsedSec).append("s")
                    append(" · state=").append(item.state.ifBlank { "(空)" })
                    append(" · 返回").append(rawItems.size).append("项")
                }
                onStage(detail)
            } else {
                onStage(
                    "MinerU 等待服务端登记 · ${elapsedSec}s · 返回${rawItems.size}项" +
                        if (rawItems.isEmpty()) "" else " · 文件名未匹配"
                )
            }

            // `waiting-file` = 服务端还没收到我们上传的文件。
            // 这是**我们这边**的问题（上传没落到位），继续轮询只会白等，
            // 必须立刻报错并说清该查什么。
            if (item?.state == "waiting-file" && elapsedSec > WAITING_FILE_GRACE_SEC) {
                return fail(
                    ApiErrorKind.BAD_RESPONSE,
                    "文件已提交但服务端 90 秒仍未收到。" +
                        "通常是上传未成功——请检查网络后重试；若反复出现请换个网络环境试"
                )
            }

            /**
             * **停滞检测**。
             *
             * 用户报告「返回有内容但是迟迟不进入下一步」，诊断信息显示
             * `state=pending · 返回1项`——请求正确、文件已被认到、匹配的也是
             * 我们自己的文件，所以**客户端该做的都做了**，剩下的是服务端队列。
             *
             * 但干等到 6 分钟超时对用户毫无价值。所以一旦状态长时间不变，
             * 就立刻给出判断：这不是「还在处理」，而是「服务端没在推进」。
             *
             * 只在 [isStallable] 的「尚未开工」状态上判定——
             * `running` 状态下长时间没变化是正常的（大 PDF 本来就慢）。
             */
            if (stalled) {
                val heldSec = stallTracker.heldFor(elapsedSec)
                return fail(
                    ApiErrorKind.TIMEOUT,
                    "MinerU 停在「${item?.state}」已 ${heldSec / 60} 分钟没有进展。" +
                        "服务端已收到文件（诊断：返回${rawItems.size}项、文件名已匹配），" +
                        "所以不是上传问题——通常是 MinerU 队列拥堵。" +
                        "建议过一会儿点「重试」；若多次如此，换个时间段或检查 MinerU 控制台的额度"
                )
            }

            when (item?.state) {
                null -> runningCount++
                "done" -> {
                    val zipUrl = item.fullZipUrl
                        ?: return fail(ApiErrorKind.BAD_RESPONSE, "解析完成但未返回结果包地址")
                    return downloadAndExtract(zipUrl, outDir, imageDir, batchId, lastTotalPages)
                }
                "failed" -> {
                    return fail(ApiErrorKind.SERVER, item.errMsg.orEmpty().ifBlank { "MinerU 解析失败" })
                }
                else -> runningCount++
            }
        }
        // 超时文案要带**最后观察到的状态**。
        //
        // 只说「解析超时」的话，用户无法判断该重试还是该等：
        // 停在 `pending` 通常是服务端队列长（重试只会更糟），
        // 停在 `running` 更可能是文件太大或内容复杂。
        val waitedSec = (System.currentTimeMillis() - startedAt) / 1000
        val lastState = stallTracker.lastSeenState.ifBlank { "未知" }
        val advice = when (lastState) {
            "pending" -> "服务端队列较长，重试会排到更后面。建议稍后再试，或减少同时提交的任务数。"
            "waiting-file" -> "服务端始终没收到文件，请检查上传时的网络。"
            "running", "converting" -> "任务在服务端处理中但耗时过长，可能是图片过大或页数过多。"
            else -> "可以重试；若反复超时请检查网络或稍后再试。"
        }
        return fail(
            ApiErrorKind.TIMEOUT,
            "MinerU 解析超时（已等待 ${waitedSec / 60} 分 ${waitedSec % 60} 秒，最后状态 $lastState）。$advice"
        )
    }

    private suspend fun downloadAndExtract(
        zipUrl: String,
        outDir: File,
        imageDir: File?,
        batchId: String,
        pages: Int = 0
    ): ApiResult<MineruOutcome> {
        val response = rethrowCancellation { api.downloadZip(zipUrl) }
            .getOrElse { return ApiResult.Failure(HttpFactory.throwableError(it)) }
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            return fail(HttpFactory.httpError(response.code(), response.errorBody()?.string().orEmpty()))
        }
        val mineruDir = outDir.apply { mkdirs() }
        val zipFile = File(mineruDir, "result.zip")
        body.byteStream().use { input ->
            FileOutputStream(zipFile).use { output -> input.copyTo(output) }
        }
        val markdownFile = unzip(zipFile, mineruDir)
            ?: return fail(ApiErrorKind.BAD_RESPONSE, "结果包中未找到 full.md")
        var markdown = markdownFile.readText()
        val absolutePaths = mutableListOf<String>()
        // imageDir 为 null 时**不抽图片**：对话页只要 markdown 文本，
        // 抽出来的图既没人看，又会在聊天目录里堆垃圾。
        if (imageDir != null) {
            imageDir.mkdirs()
            val referenced = collectImageRefs(markdown)
            referenced.forEach { ref ->
                val source = resolveRef(mineruDir, ref) ?: return@forEach
                val target = File(imageDir, source.name)
                runCatching { source.copyTo(target, overwrite = true) }
                if (target.exists()) {
                    absolutePaths.add(target.absolutePath)
                    markdown = markdown.replace(ref, target.absolutePath)
                }
            }
        }
        return ApiResult.Success(
            MineruOutcome(
                batchId = batchId,
                markdown = markdown,
                imagePaths = absolutePaths,
                rawDir = mineruDir,
                pages = pages
            )
        )
    }

    private fun unzip(zipFile: File, targetDir: File): File? {
        var markdown: File? = null
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = File(targetDir, entry.name)
                    // 防 zip slip
                    if (isWithinDirectory(targetDir, out)) {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                        if (entry.name.substringAfterLast('/') == "full.md") markdown = out
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return markdown
    }

    /** 状态对应的中文动作词。纯粹为了界面可读。 */
    @androidx.annotation.VisibleForTesting
    fun stageVerb(state: String): String = when (state) {
        "done" -> "完成"
        "failed" -> "失败"
        "running" -> "解析中"
        "pending" -> "排队中"
        "waiting-file" -> "等待文件上传"
        "converting" -> "转换中"
        else -> "处理中"
    }

    private fun collectImageRefs(markdown: String): List<String> =
        Regex("!\\[[^\\]]*\\]\\(([^)]+)\\)").findAll(markdown)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() && !it.startsWith("http") }
            .distinct()
            .toList()

    private fun resolveRef(root: File, ref: String): File? {
        val cleaned = ref.substringBefore('#').substringBefore('?')
        val direct = File(root, cleaned)
        if (direct.exists()) return direct
        return root.walkTopDown().firstOrNull {
            it.isFile && (it.name == cleaned.substringAfterLast('/') || it.path.endsWith(cleaned))
        }
    }

    private fun fail(kind: ApiErrorKind, message: String = ""): ApiResult.Failure =
        ApiResult.Failure(ApiError(kind = kind, serverMessage = message))

    private fun fail(error: ApiError): ApiResult.Failure = ApiResult.Failure(error)

    /** 设置页「测试连接」：ping 返回 -60012(task not found) 即视为 Key 有效。 */
    suspend fun testConnection(mineruKey: String): ApiResult<Boolean> {
        if (mineruKey.isBlank()) return fail(ApiErrorKind.NO_KEY)
        val response = rethrowCancellation { api.extractResults("Bearer $mineruKey", "ping") }
            .getOrElse { return ApiResult.Failure(HttpFactory.throwableError(it)) }
        if (!response.isSuccessful) {
            return fail(HttpFactory.httpError(response.code(), response.errorBody()?.string().orEmpty()))
        }
        val body = response.body() ?: return fail(ApiErrorKind.BAD_RESPONSE)
        if (body.isOk()) return ApiResult.Success(true)
        return if (body.code == PING_TASK_NOT_FOUND || body.msg.contains("not found", ignoreCase = true)) {
            ApiResult.Success(true)
        } else {
            fail(ApiError(kind = ApiErrorKind.AUTH, serverMessage = body.msg))
        }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "${bytes / 1024 / 1024} MB"

    /** 轮询阶段的 HTTP 错误载体（[ApiError] 不是 Throwable，包一层才能走 runCatching 的异常通道）。 */
    private class PollHttpError(val error: ApiError) : Exception(error.serverMessage)

    companion object {
        const val PING_TASK_NOT_FOUND = -60012
        const val FIRST_DELAY_MS = 2_000L
        const val POLL_INTERVAL_MS = 3_000L
        val BACKOFF = longArrayOf(3_000L, 5_000L, 8_000L, 10_000L)

        /** 轮询瞬断容忍上限：连续这么多次网络/5xx 错误才判死（4xx 不容忍）。 */
        const val MAX_POLL_ERRORS = 3
    }

/**
 * 执行一个会弃异常的块，但**取消异常直接抛出**。
 *
 * ## 为什么不能直接用 [runCatching]
 *
 * `runCatching` 捕获 `Throwable`，而 [kotlinx.coroutines.CancellationException] 正是
 * `Throwable` 的子类。所以用户按「取消」时若正处于 HTTP 调用中，
 * 取消被转成普通 `Failure`，协程继续跑到结束。
 *
 * 界面上就是「识别失败，请重试」——用户重试即是真的重新上传一遍，
 * 又消耗一次 MinerU 额度。为一个取消按钮供应事故更多额度。
 */
private inline fun <T> rethrowCancellation(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
}

/**
 * 从批量结果里找出属于本次文件的那一项。
 *
 * ## 为什么不留 `items.firstOrNull()` 兜底
 *
 * 早先最后一行是 `return items.firstOrNull()`。一旦文件名对不上
 * （服务端改名、加后缀、编码差异），就会**静默拿到别人的那一项**——
 * 显示的是别的文件的状态，于是「一直 pending」这种症状
 * 完全无法归因：是它真的在排队，还是我根本在看错的那一项？
 *
 * 现在只接受三种明确匹配，且**列表里有多项时不再猜**：
 * 猜错的代价是用户看到错误的状态却无从判断。
 *
 * 提成顶层函数是为了**能单测**。放成 private 时测试只能自己抄一份
 * 实现——而本项目已经吃过三次「测试复制实现」的亏（BatchProtocolTest、
 * DocxPackageTest、ManualEntryTest）：抄一份的行为，测的是那份拷贝，
 * 真实的代码改了它不会红。
 */
@androidx.annotation.VisibleForTesting
fun matchItem(items: List<ExtractResultItem>, fileName: String): ExtractResultItem? {
    if (items.isEmpty()) return null
    // **查询名为空时不参与匹配。**
    // 服务端偶尔对某些文件不返回 `file_name`（空串），
    // 那时 `it.fileName == fileName` 会把那一项当成匹配成功，
    // 而它未必是本次的文件——等于又回到了「静默拿到别人的那一项」。
    if (fileName.isNotBlank()) {
        items.firstOrNull { it.fileName == fileName }?.let { return it }
        items.firstOrNull { it.name == fileName }?.let { return it }
        items.firstOrNull {
            it.fileName.substringAfterLast('/') == fileName.substringAfterLast('/')
        }?.let { return it }
    }
    // 只有一项时它必然是本次的（批量接口对单文件也返回单项）
    if (items.size == 1) return items[0]
    return null
}
        /**
 * 轮询总预算。
 *
 * **不是 300 秒。** 循环条件只在每轮开头检查一次，而单次 HTTP 调用受
 * `HttpFactory.CALL_TIMEOUT_SECONDS = 300` 约束，最坏退避 10s。
 * 于是最坏路径 ≈ 300（预算耗尽）+ 300（单次 call 卡满）+ 10 ≈ **10 分钟**才吐超时。
 * 用户看到的就是「一直在加载」，远超预期。
 */
const val TOTAL_TIMEOUT_MS = 360_000L
/**
 * `waiting-file` 状态宽限秒数。
 *
 * 超过这个时间还停在这个状态，说明我们上传的文件服务端一直没收到——
 * 那是**我们这边**的问题（上传没落到位），不是队列问题，
 * 继续轮询只是白等，不如立刻报错说清该查什么。
 */
const val WAITING_FILE_GRACE_SEC = 90L
/**
 * 停滞判定秒数。
 *
 * 只对 [STALLABLE_STATES] 里的状态生效。取 150 秒（2.5 分钟）：
 * 正常情况下 `pending` 不会超过这个时长，而干等到 6 分钟总超时
 * 对用户毫无价值——他要的是「现在到底行不行」。
 */
const val STALL_SEC = 150L


/**
 * 属于「尚未开工」的状态——长时间不变 = 服务端没在推进，可以提前下结论。
 *
 * `running` / `converting` **不在其中**：大 PDF 解析几分钟是正常的，
 * 早判停滞会把本来能成功的任务误杀。
 *
 * 用函数而不是顶层 `val`：测试源集无法直接引用顶层 `val`
 * （只有 `const val` 能跨源集可见），这点已经踩过一次。
 */
fun isStallable(state: String): Boolean =
    state == "pending" || state == "waiting-file" || state.isBlank()

/**
 * 服务端状态计时器：判断 MinerU 是否**停滞**。
 *
 * ## 为什么要做成对象
 *
 * 这个类的第一版是散在 `pollResults` 里的两个裸变量：
 *
 * ```kotlin
 * val item = matchItem(rawItems, fileName)
 * if (item != null) lastSeenState = item.state      // ← 这里先把状态记下了
 * // ……诊断输出……
 * if (item != null) {
 *     if (item.state != lastSeenState) {             // ← 恒为假
 *         stateChangedAt = elapsedSec                // ← 一次都执行不到
 *     }
 * }
 * ```
 *
 * 因为上一行刚把 `lastSeenState` 赋成了当前状态，
 * 这里的「变了没变」永远是 `false`，**`stateChangedAt` 恒为 0**，
 * 计时器从头到尾没有重置过。
 *
 * 后果不是「停滞检测不生效」，而是**它在错误的时刻生效**：
 * `pending` 撑 100 秒 → 变成 `waiting-file` → 再过 30 秒，
 * 就会报「停在 waiting-file 已 2 分钟没有进展」——实际只等了 30 秒。
 *
 * 而且这个 bug **注释里写对了、代码写错了**：注释明说「最后一次观察到的
 * 状态，以及它开始不变的时刻」，实际那一刻从来不会被记录。
 *
 * 收进对象之后，「先记再判」的顺序由类型保证，
 * 且整个计时规则可以脱开网络直接测。
 *
 * @param stallSec 停滞阈值秒数，默认 [STALL_SEC]
 */
class MineruStallTracker(private val stallSec: Long = STALL_SEC) {

    /** 最后一次匹配上文件时观察到的服务端状态。 */
    var lastSeenState: String = ""
        private set

    /** 该状态开始保持不变的绝对时刻（秒）。 */
    private var changedAtSec: Long = 0L

    /**
     * 记录一次观测，返回该状态此刻是否已停滞。
     *
     * @param state 服务端 state；`null` 表示还没匹配上文件，不参与判定
     * @param elapsedSec 自提交起经过的秒数
     */
    fun observe(state: String?, elapsedSec: Long): Boolean {
        // 没匹配上文件时不记录：此时「服务端是什么状态」根本无从谈起，
        // 一旦把 null 记进去，之后匹配上了会误判成「状态刚变」。
        if (state == null) return false
        if (state != lastSeenState) {
            lastSeenState = state
            changedAtSec = elapsedSec
        }
        return isStallable(state) && elapsedSec - changedAtSec > stallSec
    }

    /** 当前状态已经持续了多少秒。 */
    fun heldFor(elapsedSec: Long): Long =
        if (lastSeenState.isEmpty()) 0L else elapsedSec - changedAtSec
}
