package com.mistakebook.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mistakebook.R
import com.mistakebook.data.update.AppUpdate
import com.mistakebook.data.update.UpdateState
import kotlinx.coroutines.launch
import com.mistakebook.ui.common.AppLinearProgressIndicator

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

    // 下载/待安装状态活在 AppUpdate 里，不在 Composable 里：安装器是另一个
    // Activity，跳过去再回来页面会被重建，进度和「已下好」都得还在。
    val update by AppUpdate.state.collectAsState()

    // 从「允许安装未知应用」回来时，授权了就直接续上安装
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) AppUpdate.installAfterPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                enabled = !checking && update !is UpdateState.Downloading,
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
                        // 上一个版本已经下好过（比如刚被安装器弹回来）：直接接回状态
                        found?.let { AppUpdate.restoreDownloaded(context, it.versionName) }
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
                val current = release!!
                val readyFor = (update as? UpdateState.Ready)
                    ?.takeIf { it.version == current.versionName }
                val downloadingFor = (update as? UpdateState.Downloading)
                    ?.takeIf { it.version == current.versionName }

                // 下好就自动把安装器拉起来：用户点的是「下载并安装」，那就一次点完。
                // 自动只拉一次（见 AppUpdate.requestInstall），否则从安装器返回会被再弹走。
                LaunchedEffect(readyFor) {
                    readyFor?.let {
                        AppUpdate.requestInstall(context, it.version, it.file, auto = true)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_about_new_version, current.versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (current.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    // 更新说明**全文**放在一个定高可滚动的框里（v0.0.111）。
                    //
                    // 之前 `take(300)` 截断：用户只看到开头，具体改了什么
                    // 得自己去仓库翻——而「这个版本改了什么」正是他点开
                    // 更新的原因。定高而不是撑开整个设置页：几十行说明
                    // 全部展开会把「下载」按钮顶出屏幕外。
                    //
                    // 用 heightIn(max) 而不是 height：说明短的时候框就矮，
                    // 不留一片空白。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = AppUpdate.notesForDisplay(current.notes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    downloadingFor != null -> {
                        Column {
                            AppLinearProgressIndicator(
                                progress = { downloadingFor.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(
                                    R.string.settings_about_downloading,
                                    (downloadingFor.progress * 100).toInt()
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 已经下好：只给「安装」。让人再下一次是最没道理的一种浪费
                    readyFor != null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                AppUpdate.requestInstall(
                                    context,
                                    current.versionName,
                                    readyFor.file,
                                    auto = false
                                )
                            }) {
                                Text(stringResource(R.string.settings_about_install))
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.settings_about_ready),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    else -> {
                        TextButton(onClick = {
                            AppUpdate.startDownload(
                                context,
                                current.versionName,
                                current.apkUrl
                            )
                        }) {
                            Text(stringResource(R.string.settings_about_download))
                        }
                    }
                }
                (update as? UpdateState.Failed)?.let { failure ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.settings_about_download_failed,
                            failure.message
                        ),
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

        Spacer(Modifier.height(6.dp))

        // 提 Issue：紧挨着 star 放。用户遇到问题时最不想做的就是
        // 「去仓库 → 找 Issues → 新建」，路径每多一步，反馈就少一半。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_about_issue),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.settings_about_issue_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { AppUpdate.openIssue(context) }) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.settings_about_issue_action))
            }
        }
    }
}
