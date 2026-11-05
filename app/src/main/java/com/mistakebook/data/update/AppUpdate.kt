package com.mistakebook.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
 *
 * ## 为什么安装一定要「跳出去」
 * 安卓不允许一个应用静默安装另一个包，这是系统级限制，没有权限可以绕过。
 * 能做的只有一件事：把包交出去，让系统安装器接管——用户在安装器里点「安装」，
 * 系统再用它自己的身份落盘。所以这里的 [requestInstall] 只负责唤起安装器，
 * 绝不自己装。
 */
object AppUpdate {

    /** 本项目的 GitHub 仓库。 */
    const val REPO = "ausyeah/ascensalis"

    /** 仓库主页（给「去 star」用）。 */
    const val REPO_URL = "https://github.com/$REPO"

    /** 新建 issue 页：遇到问题直接从这里说，比让用户去翻仓库强。 */
    const val ISSUE_URL = "$REPO_URL/issues/new"

    private const val TAG = "AppUpdate"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    /**
     * 备用检查通道（v0.0.122）：不走 api.github.com。
     *
     * GitHub 对未认证 API 的限额是**每 IP 每小时 60 次**，手机运营商 NAT 下
     * 几十个人共享一个出口，超了就是 403——用户看到的「检查失败：GitHub 返回
     * 403」几乎全是它。这个页面地址是普通网站路径，不占 API 限额；
     * 访问它会 302 到 `/releases/tag/vX.Y.Z`，从最终地址里就能拿到版本号。
     */
    private const val RELEASES_LATEST_PAGE = "https://github.com/$REPO/releases/latest"

    /** GitHub 要求所有请求带 User-Agent；显式声明一个，别依赖 OkHttp 的默认值。 */
    private const val USER_AGENT = "ascensalis-app"

    /** 下载目录：缓存区即可，装完系统会自己清。 */
    private const val UPDATE_DIR = "updates"

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 下载活在**进程**里，不挂在页面作用域上。
     *
     * 页面一退出就取消下载，是这个功能最难受的地方：用户跳去装个别的应用再回来，
     * 进度已经从 0 开始重来了。作用域放在 object 里，界面只订阅进度。
     */
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** 界面订阅它：进度不随页面销毁归零。 */
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 自动拉起过安装器的版本：同一版本只自动拉一次，免得从安装器回来又被弹走。 */
    private var autoInstalledVersion: String? = null

    /** 正在等「允许安装未知应用」授权的版本：授权回来直接续上安装。 */
    private var awaitingPermissionVersion: String? = null

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
     *
     * v0.0.122 起是**双通道**：先走 API（信息最全——说明、包大小），被限流
     * 或任何原因失败时自动切备用通道（releases/latest 页面的 302 重定向，
     * 不占 API 限额）。两条都失败才把错误抛给界面——且错误信息里带主通道
     * 的失败原因，用户看到的不再是干巴巴的「GitHub 返回 403」。
     */
    suspend fun latestRelease(currentVersion: String): Release? = withContext(Dispatchers.IO) {
        try {
            latestReleaseViaApi(currentVersion)
        } catch (apiError: Exception) {
            try {
                latestReleaseViaRedirect(currentVersion)
            } catch (fallbackError: Exception) {
                throw IllegalStateException(
                    "GitHub 接口受限且备用通道也不可用（${apiError.message}）",
                    fallbackError
                )
            }
        }
    }

    /** 主通道：API JSON，带完整说明与 APK 大小。 */
    private suspend fun latestReleaseViaApi(currentVersion: String): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(API_LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
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
     * 备用通道：跟随 `releases/latest` 的 302，从最终地址拿 tag。
     *
     * 说明与包大小拿不到——说明留空（下载页里看得到），大小置 0：
     * 下载进度对 total<=0 本来就是「不定进度」处理，不影响装包。
     * APK 地址按 CI 的命名规则拼出来（android.yml：`mistakebook-<tag>-android.apk`）。
     */
    private suspend fun latestReleaseViaRedirect(currentVersion: String): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_LATEST_PAGE)
            .header("User-Agent", USER_AGENT)
            .build()
        val tag = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("GitHub 页面返回 ${response.code}")
            }
            val url = response.request.url.toString()
            parseTagFromRedirectUrl(url)
                ?: throw IllegalStateException("没能从 GitHub 页面地址解析出版本：$url")
        } ?: return@withContext null
        val versionName = parseVersion(tag)?.joinToString(".") ?: return@withContext null
        if (compareVersions(versionName, currentVersion) <= 0) return@withContext null

        Release(
            tag = tag,
            versionName = versionName,
            notes = "",
            apkUrl = apkDownloadUrl(tag),
            apkSize = 0L
        )
    }

    /**
     * 从 `releases/latest` 302 后的最终地址里解析 tag（纯函数，单测见
     * AppUpdateFallbackTest）。
     *
     * 只认「主版本.次版本.修订号」的 tag：`/releases/latest` 自身（没发生
     * 重定向）与预发布号都返回 null——后者本来就不是能安全比较的版本。
     */
    internal fun parseTagFromRedirectUrl(url: String): String? {
        val match = Regex("""/releases/tag/(v?\d+\.\d+\.\d+)/?(?:[?#].*)?$""").find(url)
        return match?.groupValues?.get(1)
    }

    /** CI 产物命名：tag 里的版本拼进文件名（android.yml 的 Prepare APK 步骤）。 */
    internal fun apkDownloadUrl(tag: String): String =
        "https://github.com/$REPO/releases/download/$tag/mistakebook-$tag-android.apk"

    /**
     * 给界面展示用的说明文本：剥掉 Markdown 的标题井号与加粗星号（v0.0.111）。
     *
     * 说明框里是纯 Text，`##` 和 `**` 原样摊出来只会让用户以为排版坏了。
     * 列表符号（`- `）保留——它本身就是可读的。
     */
    internal fun notesForDisplay(notes: String): String {
        var out = notes.replace("\r\n", "\n").replace('\r', '\n')
        out = Regex("(?m)^#{1,6}[ \t]*").replace(out, "")
        return out.replace("**", "").trim()
    }

    /** 更新包的文件名按版本分开：换一个版本就是另一个文件，不会拿旧包去装。 */
    internal fun apkFileName(version: String): String = "app-update-$version.apk"

    /** 磁盘上已经下好的包（要求非空）。 */
    fun downloadedApk(context: Context, version: String): File? {
        val file = File(File(context.cacheDir, UPDATE_DIR), apkFileName(version))
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * 把磁盘上已经下好的包接回状态。
     *
     * 从安装器/授权页回来时页面会被重建，`remember` 里的「已下载」全没了；
     * 文件还在，所以状态要能从文件重新长出来——否则用户就得再下一遍。
     */
    fun restoreDownloaded(context: Context, version: String) {
        val current = _state.value
        if (current is UpdateState.Downloading || current is UpdateState.Ready) return
        downloadedApk(context, version)?.let { _state.value = UpdateState.Ready(version, it) }
    }

    /**
     * 开始下载，并把进度写进 [state]。
     *
     * 已经下好同一版本就直接进入待安装，不重复下载；已经在下的忽略重复点击。
     */
    fun startDownload(context: Context, version: String, url: String) {
        val current = _state.value
        if (current is UpdateState.Downloading) return
        if (current is UpdateState.Ready && current.version == version) return

        downloadedApk(context, version)?.let {
            _state.value = UpdateState.Ready(version, it)
            return
        }

        val target = File(File(context.cacheDir, UPDATE_DIR), apkFileName(version))
        _state.value = UpdateState.Downloading(version, 0f)
        downloadScope.launch {
            val ok = runCatching {
                writeTo(url, target) { done, total ->
                    val progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                    _state.value = UpdateState.Downloading(version, progress)
                }
            }.getOrElse { error ->
                Log.w(TAG, "更新包下载失败", error)
                false
            }
            _state.value = if (ok) {
                UpdateState.Ready(version, target)
            } else {
                UpdateState.Failed("网络中断")
            }
        }
    }

    /**
     * 下载到 `*.part`，完整收到才改名。
     *
     * 直接写正式文件名的话，中途断掉会留下一个半截 APK：它看起来「已经下好」，
     * 拿去安装却是一个坏包。改名是这里唯一需要的原子性。
     */
    private fun writeTo(url: String, target: File, onProgress: (Long, Long) -> Unit): Boolean {
        val request = Request.Builder().url(url).build()
        val part = File(target.path + ".part")
        target.parentFile?.mkdirs()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            val total = body.contentLength()
            body.byteStream().use { input ->
                part.outputStream().use { output ->
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
        }
        if (target.exists() && !target.delete()) return false
        return part.renameTo(target)
    }

    /**
     * 把包交给系统安装器。
     *
     * @param auto true 由「下载完自动跳转」调用：同一版本只自动拉一次。
     *   否则从安装器返回时页面重建、[state] 仍是 Ready，自动逻辑会再弹一次，
     *   用户就永远回不到 App 里。
     */
    fun requestInstall(context: Context, version: String, file: File, auto: Boolean) {
        if (auto) {
            if (autoInstalledVersion == version) return
            autoInstalledVersion = version
        }
        if (installPermissionGranted(context)) {
            install(context, file)
        } else {
            // 没有「安装未知应用」授权：先去授权页，授权回来由 [installAfterPermission] 续上
            awaitingPermissionVersion = version
            openInstallPermissionSettings(context)
        }
    }

    /**
     * 从授权页回来时调一次：真的授权了就接着装。
     *
     * 用户已经下好包、也已经点过「同意」，不该再要求他回来点第二次。
     */
    fun installAfterPermission(context: Context) {
        val version = awaitingPermissionVersion ?: return
        if (!installPermissionGranted(context)) return
        awaitingPermissionVersion = null
        val file = downloadedApk(context, version) ?: return
        install(context, file)
    }

    /** 是否已允许本应用安装 APK（minSdk 26，不需要版本判断）。 */
    fun installPermissionGranted(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

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
        return openUrl(context, REPO_URL, "打开仓库页失败")
    }

    /** 打开「新建 issue」页。 */
    fun openIssue(context: Context): Boolean {
        return openUrl(context, ISSUE_URL, "打开 Issue 页失败")
    }

    private fun openUrl(context: Context, url: String, failureLog: String): Boolean {
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.w(TAG, failureLog, it)
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

/**
 * 更新包的状态。
 *
 * 放在进程里而不是 Composable 里：安装器是另一个 Activity，跳过去再回来
 * 页面可能已经被重建，进度和「已下好」这件事都得还在。
 */
sealed interface UpdateState {

    /** 没在下载，也没有下好的包。 */
    data object Idle : UpdateState

    /** 正在下载，[progress] 是 0..1。 */
    data class Downloading(val version: String, val progress: Float) : UpdateState

    /** 已下好、等待安装。[file] 在，就不该再让人下一次。 */
    data class Ready(val version: String, val file: File) : UpdateState

    data class Failed(val message: String) : UpdateState
}
