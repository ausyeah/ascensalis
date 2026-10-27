package com.mistakebook.poster

import android.content.Context
import android.graphics.Bitmap
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mistakebook.MistakeBookApp
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 海报生图后台任务。
 *
 * ## 为什么必须是 WorkManager
 *
 * 早先生图挂在 `viewModelScope` 里：用户点「生图打卡」后**只要离开这个页面**
 * （切到别的 Tab、返回上一层、被系统回收屏幕），协程随 ViewModel 一起被取消，
 * 生图请求就断了——钱花了、额度烧了，什么都没拿到。
 *
 * 换成 WorkManager 之后：
 * - 任务由系统调度，**离开页面照跑**；
 * - 进程被杀后系统会**重新拉起** Worker 接着跑；
 * - 结果（海报 PNG 文件路径）写进 WorkData，页面回来时读回来分享。
 *
 * ## 数据怎么传
 *
 * WorkManager 的 Data 是 Bundle，值只能是基本类型。整份 [PosterContent]
 * 用 kotlinx.serialization 编成 JSON 字符串塞进去（内容很短，几百字节，
 * 远低于 Data 的 10KB 限制）。
 */
class PosterWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val payload = inputData.getString(KEY_CONTENT)
        val modeName = inputData.getString(KEY_MODE)
        if (payload.isNullOrBlank() || modeName.isNullOrBlank()) {
            return Result.failure(
                Data.Builder().putString(KEY_ERROR, "海报参数缺失").build()
            )
        }
        val content = runCatching {
            Json.decodeFromString(PosterContent.serializer(), payload)
        }.getOrElse {
            return Result.failure(
                Data.Builder().putString(KEY_ERROR, "海报内容解析失败").build()
            )
        }
        val mode = runCatching { PosterImageMode.valueOf(modeName) }
            .getOrElse { PosterImageMode.BACKGROUND }

        val container = MistakeBookApp.from(appContext).container
        val result = container.posterImageService.generate(content, mode)
        return when (result) {
            is com.mistakebook.net.ApiResult.Success -> {
                val file = saveBitmap(appContext, result.data)
                    ?: return Result.failure(
                        Data.Builder().putString(KEY_ERROR, "海报写入磁盘失败").build()
                    )
                Result.success(Data.Builder().putString(KEY_RESULT_PATH, file.absolutePath).build())
            }

            is com.mistakebook.net.ApiResult.Failure -> Result.failure(
                Data.Builder()
                    .putString(
                        KEY_ERROR,
                        result.error.serverMessage.ifBlank { "请检查生图配置" }
                    )
                    .build()
            )
        }
    }

    /** 把海报位图写到 app 私有目录，供页面回来时读取分享。 */
    private fun saveBitmap(context: Context, bitmap: Bitmap): File? = runCatching {
        val dir = File(context.filesDir, "posters").apply { mkdirs() }
        val file = File(dir, "poster_${System.currentTimeMillis()}.png")
        file.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        file
    }.getOrNull()

    companion object {
        /** 工作名前缀；完整名字由 [workName] 按来源拼出，避免两个入口互相 REPLACE。 */
        const val WORK_NAME_PREFIX = "poster_image_generate"

        /** 按来源（词库 / 番茄钟）生成独立的工作名。 */
        fun workName(source: String): String = "${WORK_NAME_PREFIX}_$source"

        /** 生图模式的 WorkInfo 标签前缀。 */
        const val TAG_MODE_PREFIX = "mode:"
        const val KEY_CONTENT = "poster_content"
        const val KEY_MODE = "poster_mode"
        const val KEY_RESULT_PATH = "poster_result_path"
        const val KEY_ERROR = "poster_error"
    }
}

/**
 * 海报生图任务的调度入口。
 *
 * 用 `enqueueUniqueWork` + [ExistingWorkPolicy.REPLACE]：
 * 同一时刻只保留一个生图任务，重复点击就是替换掉上一次（而不是排队烧额度）。
 */
object PosterScheduler {

    /**
     * 提交一次海报生图。
     *
     * @param source 来源标识（"wordbook" / "pomodoro"）。
     *        不同来源用**不同的工作名**，否则在词库页点一次生图、
     *        又在番茄钟页点一次，前一个任务会被 REPLACE 掉。
     */
    fun enqueue(
        context: Context,
        source: String,
        content: PosterContent,
        mode: PosterImageMode
    ) {
        val data = Data.Builder()
            .putString(
                PosterWorker.KEY_CONTENT,
                Json.encodeToString(PosterContent.serializer(), content)
            )
            .putString(PosterWorker.KEY_MODE, mode.name)
            .build()
        val request = OneTimeWorkRequestBuilder<PosterWorker>()
            .setInputData(data)
            // 打一个 mode 标签：WorkInfo 拿不到 inputData，但拿得到 tags，
            // 这样界面上「正在生成背景图 / 正在生成角色图」能显示正确。
            .addTag(PosterWorker.TAG_MODE_PREFIX + mode.name)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            PosterWorker.workName(source),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
