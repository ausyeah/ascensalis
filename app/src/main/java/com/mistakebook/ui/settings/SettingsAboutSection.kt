package com.mistakebook.ui.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.data.update.AppUpdate
import kotlinx.coroutines.launch
import java.io.File

/**
 * 「关于与更新」区块：当前版本、检查更新、给 star。
 *
 * ## 为什么更新直接指向 GitHub Release
 * 发版链路已经是「打 tag → CI 构建 → 挂 APK 到 Release」，
 * 这里就是把它接上给用户：检查走 GitHub 的 latest 接口，下载走 Release 资产，
 * 安装调系统安装器。版本号以 Release tag 为准，与 CI 构建的那一份一一对应。
 *
 * ## 为什么「已是最新」也要说出口
 * 静默失败最伤信任：用户点了「检查更新」，屏幕上什么也没发生，
 * 他只能猜是网络问题还是自己版本太新。查完必须给一句话的结论。
 */
@Composable
internal fun AboutUpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentVersion = remember { AppUpdate.currentVersionName(context) ?: "—" }

    // 检查中 / 已有结果 / 下载中 三种状态互斥，用一个 sealed 之外的两个字段表达：
    // checking 管「正在查」，release 管「查到了新版」，其余是「查过了/还没查」
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<AppUpdate.Release?>(null) }
    var checkError by remember { mutableStateOf<String?>(null) }

    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_about_version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = currentVersion,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            TextButton(
                enabled = !checking && !downloading,
                onClick = {
                    scope.launch {
                        checking = true
                        checkError = null
                        release = null
                        checked = false
                        val found = try {
                            AppUpdate.latestRelease(currentVersion)
                        } catch (error: Exception) {
                            checkError = error.message ?: error.javaClass.simpleName
                            null
                        }
                        release = found
                        checked = true
                        checking = false
                    }
                }
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.settings_about_check_update))
                }
            }
        }

        when {
            checkError != null -> {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_about_check_failed, checkError.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            release != null -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_about_new_version, release!!.versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (release!!.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = release!!.notes.take(300),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (downloading) {
                    Column {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.settings_about_downloading,
                                (downloadProgress * 100).toInt()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    TextButton(onClick = {
                        scope.launch {
                            downloading = true
                            downloadError = null
                            val target = File(context.cacheDir, "updates/app-update.apk")
                            val ok = AppUpdate.download(release!!.apkUrl, target) { done, total ->
                                downloadProgress = if (total > 0) {
                                    (done.toFloat() / total).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                            }
                            downloading = false
                            if (!ok) {
                                downloadError = "网络中断"
                                return@launch
                            }
                            downloadError = null
                            // 没有「安装未知应用」权限时，系统会直接拒收安装器——
                            // 先跳到授权页，别让用户对着一个毫无反应的界面
                            if (context.packageManager.canRequestPackageInstalls()) {
                                AppUpdate.install(context, target)
                            } else {
                                AppUpdate.openInstallPermissionSettings(context)
                            }
                        }
                    }) {
                        Text(stringResource(R.string.settings_about_download))
                    }
                }
                if (downloadError != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_about_download_failed, downloadError.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            checked -> {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_about_latest),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // 给个 star：一条直通仓库主页的入口。开源项目被人用起来，
        // 靠的就是这一下——但做成两级菜单（帮助 → 关于 → 链接）就等于没有。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_about_star),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.settings_about_star_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { AppUpdate.openRepo(context) }) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.settings_about_star_action))
            }
        }
    }
}
