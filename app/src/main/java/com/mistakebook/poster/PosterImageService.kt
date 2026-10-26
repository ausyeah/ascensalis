package com.mistakebook.poster

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mistakebook.data.prefs.SettingsStore
import com.mistakebook.net.ApiResult
import com.mistakebook.net.ImageGenClient
import com.mistakebook.util.ImageDiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 海报生图编排：本地渲染 →（可选）生图模型出背景 → 合成。
 *
 * ## 两种模式
 *
 * - [PosterImageMode.BACKGROUND]：生图模型只画**背景**（不含文字），
 *   本地 Canvas 把中文数据叠上去。数字 100% 准确，中文不会写错，
 *   代价是风格由模板决定。
 * - [PosterImageMode.FULL]：把**全部文字内容**写进提示词，
 *   生图模型连文字一起画。风格自由，代价是中文可能写错、
 *   数字可能对不上——适合「发朋友圈无所谓准确性」的场景。
 *
 * ## 缓存
 *
 * key = md5(内容 + 模式 + 生图模型名)。同一天同一份数据不重复烧额度；
 * 换生图渠道算新 key（不同渠道画风不同，理应重画）。
 */
class PosterImageService(
    private val imageGenClient: ImageGenClient,
    private val imageDiskCache: ImageDiskCache,
    private val settingsStore: SettingsStore
) {

    /**
     * 出一张海报。
     *
     * @return 成功时是可直接分享的 Bitmap；失败时给出用户可读原因。
     */
    suspend fun generate(
        content: PosterContent,
        mode: PosterImageMode
    ): ApiResult<Bitmap> = withContext(Dispatchers.IO) {
        val settings = settingsStore.snapshotNow()
        val imageProfile = settings.activeImageProfile
        if (imageProfile == null || !imageProfile.isConfigured()) {
            return@withContext ApiResult.Failure(
                com.mistakebook.net.ApiError(
                    kind = com.mistakebook.net.ApiErrorKind.NO_KEY,
                    serverMessage = "尚未配置生图渠道（设置 → 生图设置）"
                )
            )
        }

        // FULL 模式：生图模型直接出整张海报，本地不再叠字。
        if (mode == PosterImageMode.FULL) {
            val key = imageDiskCache.keyFor(
                prompt = fullCacheSeed(content),
                model = imageProfile.model
            )
            imageDiskCache.get(key)?.let { cached ->
                decode(cached)?.let { return@withContext ApiResult.Success(it) }
            }
            val prompt = PosterPrompt.build(content, mode)
            return@withContext when (val result = imageGenClient.generate(
                imageProfile,
                prompt,
                POSTER_WIDTH,
                POSTER_HEIGHT
            )) {
                is ApiResult.Success -> {
                    val file = imageDiskCache.put(key, result.data, pinned = true)
                    decode(file)?.let { ApiResult.Success(it) }
                        ?: ApiResult.Failure(
                            com.mistakebook.net.ApiError(
                                kind = com.mistakebook.net.ApiErrorKind.BAD_RESPONSE,
                                serverMessage = "生图结果无法解码成图片"
                            )
                        )
                }

                is ApiResult.Failure -> ApiResult.Failure(result.error)
            }
        }

        // BACKGROUND 模式：生图模型出背景 → 本地叠字。
        val key = imageDiskCache.keyFor(
            prompt = backgroundCacheSeed(content),
            model = imageProfile.model
        )
        val background = imageDiskCache.get(key)?.let { decode(it) }
            ?: run {
                val prompt = PosterPrompt.build(content, mode)
                when (val result = imageGenClient.generate(
                    imageProfile,
                    prompt,
                    POSTER_WIDTH,
                    POSTER_HEIGHT
                )) {
                    is ApiResult.Success -> {
                        val file = imageDiskCache.put(key, result.data, pinned = true)
                        decode(file)
                    }

                    is ApiResult.Failure -> return@withContext ApiResult.Failure(result.error)
                }
            }
        if (background == null) {
            return@withContext ApiResult.Failure(
                com.mistakebook.net.ApiError(
                    kind = com.mistakebook.net.ApiErrorKind.BAD_RESPONSE,
                    serverMessage = "生图结果无法解码成图片"
                )
            )
        }
        ApiResult.Success(PosterRenderer.render(content, background))
    }

    /** 不走生图的纯本地海报（生图没配、或用户只想快点拿到）。 */
    suspend fun renderLocal(content: PosterContent): Bitmap = withContext(Dispatchers.Default) {
        PosterRenderer.render(content, null)
    }

    private fun decode(file: File): Bitmap? =
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()

    /** 内容 + 模式一起进 key：换模式必须重画（背景图 vs 整图）。 */
    private fun fullCacheSeed(content: PosterContent): String =
        "poster_full:${content.dateText}:${content.headline}:" +
            content.stats.joinToString("|") { "${it.label}=${it.value}${it.unit}" } +
            ":${content.footerText}"

    private fun backgroundCacheSeed(content: PosterContent): String =
        "poster_bg:${content.headline}:${content.stats.size}"

    private companion object {
        /** 生图的画布尺寸（竖版 2:3）。 */
        const val POSTER_WIDTH = 1024
        const val POSTER_HEIGHT = 1536
    }
}
