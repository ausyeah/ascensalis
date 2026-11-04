package com.mistakebook.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用更新：查 GitHub 最新 Release、下 APK、调系统安装器。
 *
 * ## 为什么走 GitHub Release 而不是自建接口
 * 这个项目**已经有**一条成熟的发版链路：打 tag → CI 构建 → 把 APK 挂到
 * Release。复用它等于零额外基建：发布即上线，版本号与 Release tag 一一对应，
 * 用户装的就是 CI 验过签名的那一份。自己再搭一套更新服务只会多一个会过期的
 * 接口和多一份要同步的版本表。
 *
 * ## 为什么不做「Play 商店」那套
 * 这是国内直装的 App，没有商店上架这一环。
 */
object AppUpdate {

    /** 本项目的 GitHub 仓库。 */
    const val REPO = "ausyeah/ascensalis"

    /** 仓库主页（给「去 star」用）。 */
    const val REPO_URL = "https://github.com/$REPO"

    private const val TAG = "AppUpdate"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 一个可安装的更新。 */
    data class Release(
        val tag: String,
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long
    )

    /**
     * 版本号比较（纯函数，单测见 AppUpdateVersionTest）。
     *
     * 返回 1 表示 [a] 更新，-1 表示 [a] 更旧，0 表示相同。
     * 解析不出来时返回 0——**宁可说「已是最新」也不误报更新**：误报的代价是
     * 用户下载一个同版本包，浪费流量还可能把当前版本覆盖掉。
     */
    internal fun compareVersions(a: String, b: String): Int {
        val pa = parseVersion(a) ?: return 0
        val pb = parseVersion(b) ?: return 0
        for (i in 0 until 3) {
            val d = pa[i] - pb[i]
            if (d != 0) return if (d > 0) 1 else -1
        }
        return 0
    }

    /**
     * 解析 "v0.0.98" / "0.0.98" → [major, minor, patch]。
     *
     * 解析不出来返回 null：预发布号（v0.1.0-beta1）、带构建号的（0.0.98+12）
     * 都不是能安全比较的版本，宁可当「相同」处理。
     */
    internal fun parseVersion(raw: String): IntArray? {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('.')
        if (parts.size != 3) return null
        val nums = IntArray(3)
        parts.forEachIndexed { index, part ->
            nums[index] = part.toIntOrNull() ?: return null
        }
        return nums
    }

    /**
     * 查最新 Release。没有更新时返回 null。
     *
     * 只认**带 APK 资产的** Release：有的 tag 可能只是打标记（比如灰度），
     * 没有可下载的包，那就不算「有更新」。
     */
    suspend fun latestRelease(currentVersion: String): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(API_LATEST)
            .header("Accept", "application/vnd.github+json")
            .build()
        // 网络异常**往上抛**：界面要能区分「已是最新」和「根本没查成」，
        // 全都吞掉的话用户看到「已是最新」却其实是断网了。
        val release = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("GitHub 返回 ${response.code}")
            }
            response.body?.string()?.let { json.parseToJsonElement(it).jsonObject }
        } ?: return@withContext null

        val tag = release.string("tag_name") ?: return@withContext null
        val versionName = parseVersion(tag)?.joinToString(".") ?: return@withContext null
        if (compareVersions(versionName, currentVersion) <= 0) return@withContext null

        val assets = release["assets"] as? JsonArray ?: return@withContext null
        val apk = assets.mapNotNull { it.jsonObject }
            .firstOrNull { it.string("name")?.endsWith(".apk", ignoreCase = true) == true }
            ?: return@withContext null

        Release(
            tag = tag,
            versionName = versionName,
            notes = release.string("body").orEmpty(),
            apkUrl = apk.string("browser_download_url").orEmpty(),
            apkSize = apk.string("size")?.toLongOrNull() ?: 0L
        )
    }

    /**
     * 下载 APK 到 [target]。
     *
     * @param onProgress 已下载字节 / 总字节。
     * @return 成功返回 true。
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).build()
            val ok = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = response.body ?: return@use false
                val total = body.contentLength()
                target.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
                true
            }
            ok
        }.getOrElse {
            Log.w(TAG, "更新包下载失败", it)
            false
        }
    }

    /**
     * 调系统安装器安装 [apk]。
     *
     * 走 FileProvider 而不是 file:// URI：Android 7 起 file:// 会被系统拒收
     * （FileUriExposedException），必须用 content:// 并临时授权。
     */
    fun install(context: Context, apk: File): Boolean {
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.w(TAG, "拉起安装器失败", it)
            false
        }
    }

    /** 打开仓库主页（给 star 用）。 */
    fun openRepo(context: Context): Boolean {
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.w(TAG, "打开仓库页失败", it)
            false
        }
    }

    /** 跳到「允许安装未知应用」的设置页；没有这个权限就装不了 APK。 */
    fun openInstallPermissionSettings(context: Context): Boolean {
        return runCatching {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.w(TAG, "打开安装权限设置失败", it)
            false
        }
    }

    /** 读当前安装的版本名（拿不到就返回 null）。 */
    fun currentVersionName(context: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}
