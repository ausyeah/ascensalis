package com.mistakebook.ui.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SuggestionChip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.data.prefs.LlmKeyEntry
import com.mistakebook.data.prefs.MAX_DAILY_NEW_LIMIT
import com.mistakebook.data.prefs.MIN_DAILY_NEW_LIMIT
import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.data.chat.ChatSearchProvider
import com.mistakebook.data.prefs.NetworkMode
import com.mistakebook.data.prefs.ReasoningEffort
import com.mistakebook.data.prefs.RecognitionMode
import com.mistakebook.data.prefs.ThemeMode
import com.mistakebook.data.prefs.UsageSnapshot
import com.mistakebook.data.prefs.formatCount
import com.mistakebook.domain.ErrorReason

/**
 * 存储占用信息。
 *
 * 用户在数据管理这一块最想知道的其实是「我的错题占了多少磁盘」——
 * 照片是错题本里最大的开销，删了几百道题后空间没降会让人以为删失败。
 * 原来只有三个按钮、零信息，出了问题无从判断。
 *
 * 目录统计走 IO 线程，不阻塞滚动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageInfoRow(container: com.mistakebook.di.AppContainer) {
    var stats by remember { mutableStateOf<StorageStats?>(null) }

    LaunchedEffect(Unit) {
        stats = withContext(Dispatchers.IO) { computeStorageStats(container) }
    }

    val current = stats
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_storage_title),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (current == null) {
                        stringResource(R.string.settings_storage_loading)
                    } else {
                        stringResource(
                            R.string.settings_storage_detail,
                            current.questionCount,
                            com.mistakebook.ui.common.Format.fileSize(current.imagesBytes),
                            com.mistakebook.ui.common.Format.fileSize(current.databaseBytes),
                            com.mistakebook.ui.common.Format.fileSize(current.totalBytes)
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal data class StorageStats(
    val questionCount: Int,
    /** 原图 + 题目附图 + MinerU 切图 */
    val imagesBytes: Long,
    /** 数据库（含 -wal / -shm） */
    val databaseBytes: Long,
    val totalBytes: Long
)


/**
 * 按实际目录规划统计，不猜。
 *
 * 分开统计「图片」和「数据库」是有意义的：图片占绝大部分且会随删除题目而下降，
 * 数据库则基本不变。用户删完题发现空间没降，多半是没意识到图片还在。
 */
internal fun computeStorageStats(container: com.mistakebook.di.AppContainer): StorageStats {
    val files = container.files
    var images = 0L
    images += dirSize(files.cropDir)
    images += dirSize(files.importDir)
    images += dirSize(java.io.File(files.cropDir.parentFile, "questions"))
    images += dirSize(java.io.File(files.cropDir.parentFile, "mineru"))

    val db = files.databaseFile
    var database = if (db.exists()) db.length() else 0L
    // WAL 里可能有还没 checkpoint 的数据，不算进来会低估
    listOf("-wal", "-shm").forEach { suffix ->
        val extra = java.io.File(db.path + suffix)
        if (extra.exists()) database += extra.length()
    }

    return StorageStats(
        questionCount = countFiles(java.io.File(files.cropDir.parentFile, "questions")),
        imagesBytes = images,
        databaseBytes = database,
        totalBytes = images + database
    )
}


internal fun dirSize(dir: java.io.File?): Long {
    if (dir == null || !dir.exists()) return 0L
    var total = 0L
    val stack = ArrayDeque<java.io.File>()
    stack.addLast(dir)
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        val children = current.listFiles() ?: continue
        children.forEach { child ->
            if (child.isDirectory) stack.addLast(child) else total += child.length()
        }
    }
    return total
}


internal fun countFiles(dir: java.io.File?): Int {
    if (dir == null || !dir.exists()) return 0
    var total = 0
    val stack = ArrayDeque<java.io.File>()
    stack.addLast(dir)
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        val children = current.listFiles() ?: continue
        children.forEach { child ->
            if (child.isDirectory) stack.addLast(child) else total++
        }
    }
    return total
}


@Composable
internal fun DataManageSection(
    container: com.mistakebook.di.AppContainer,
    viewModel: SettingsViewModel,
    onExportClick: () -> Unit,
    onRestoreClick: () -> Unit
) {
    SettingsGroup(title = stringResource(R.string.settings_data_manage)) {
        StorageInfoRow(container = container)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onExportClick,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.settings_export_backup),
                    maxLines = 1
                )
            }
            OutlinedButton(
                onClick = onRestoreClick,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.settings_restore_backup),
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // 两步确认：清空回收站不可恢复，首击变全红 + 「确认清空」，再击才执行
        com.mistakebook.ui.common.TwoStepConfirmButton(
            text = stringResource(R.string.settings_clear_trash),
            confirmText = stringResource(R.string.settings_clear_trash_confirm),
            onConfirm = viewModel::clearTrash,
            modifier = Modifier.fillMaxWidth()
        )
    }
}


/**
 * 英语单词数据重置区。
 *
 * 两个口径分开是因为破坏力完全不同：
 * - **重置今日记录**：只删今天的作答流水。今日已学归零、热力图今天
 *   那一格清空、连续打卡回到今天之前；熟词/错词本/生词本全保留。
 *   是「手滑乱点了一通」之后的橡皮擦。
 * - **全部重置**：进度 + 流水全部清空，从零开始。确认文案必须写清楚
 *   会没掉什么——不可逆操作里，写明白是唯一能救用户的。
 *
 * 删库后必须 bump `wordbookEpoch`：背单词页的抽词缓存是进程内内存
 * （progressCache / wrongWordsCache），不广播的话它会继续按「已掌握」
 * 跳过一堆词，而库里其实已经空了。
 */
@Composable
internal fun WordbookDataSection(viewModel: SettingsViewModel) {
    var confirmToday by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }

    SettingsGroup(title = stringResource(R.string.settings_wordbook_reset_title)) {
        Text(
            text = stringResource(R.string.settings_wordbook_reset_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { confirmToday = true },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.settings_wordbook_reset_today),
                    maxLines = 1
                )
            }
            OutlinedButton(
                onClick = { confirmAll = true },
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.settings_wordbook_reset_all),
                    maxLines = 1
                )
            }
        }
    }

    if (confirmToday) {
        AlertDialog(
            onDismissRequest = { confirmToday = false },
            title = { Text(stringResource(R.string.settings_wordbook_reset_today)) },
            text = { Text(stringResource(R.string.settings_wordbook_reset_today_body)) },
            confirmButton = {
                com.mistakebook.ui.common.TwoStepConfirmTextButton(
                    text = stringResource(R.string.action_confirm),
                    confirmText = stringResource(R.string.settings_wordbook_reset_today_confirm),
                    onConfirm = {
                        confirmToday = false
                        viewModel.resetWordbookToday()
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmToday = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.settings_wordbook_reset_all)) },
            text = { Text(stringResource(R.string.settings_wordbook_reset_all_body)) },
            confirmButton = {
                com.mistakebook.ui.common.TwoStepConfirmTextButton(
                    text = stringResource(R.string.action_confirm),
                    confirmText = stringResource(R.string.settings_wordbook_reset_all_confirm),
                    onConfirm = {
                        confirmAll = false
                        // 进度与流水在 DAO 的 resetAll() 里同一个事务清：
                        // 早先两步分跑在 UI scope 里，导航取消正好落在两步
                        // 之间就制造出注释警告的「有流水无进度」中间态。
                        viewModel.resetWordbookAll()
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmAll = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

// 题目编辑页共用的学科下拉



