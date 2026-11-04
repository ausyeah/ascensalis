package com.mistakebook.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.di.AppContainer
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 记忆库（v0.0.112，单文档模型）。
 *
 * ## 页面结构
 * 记忆是一篇**分节的文档**（类似 WorkBuddy 的「我的记忆」）：上部是定高
 * 滚动框展示全文，底部输入框「告诉它要记住或忘记什么」。输入的内容不直接
 * 入库，而是连同现有全文一起交给默认模型**改写出一份新文档**——用户描述
 * 得再零散（「我叫黄浩 在准备考研 别用 emoji」），落进文档的都是整理好的
 * 分节条目。
 *
 * ## 为什么整页只读 + 一个编辑入口
 * 直接给可编辑大框的话，模型改写和手动编辑会互相覆盖（改写请求在途时
 * 用户还在打字）。读是常态、改是低频：全文只读展示，「编辑」进对话框
 * 显式保存，两条路不会撞。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val doc by container.memoryRepository.document.collectAsState(initial = "")
    var version by remember { mutableStateOf(0) }
    var updatedAt by remember { mutableStateOf(0L) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            version = container.memoryRepository.versionNow()
            updatedAt = container.memoryRepository.updatedAtNow()
        }
    }

    // 进入页面先拉一次版本与更新时间
    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }

    fun runRewrite(request: String) {
        if (busy) return
        busy = true
        scope.launch {
            val profile = runCatching { container.settingsStore.snapshotNow().activeProfile }.getOrNull()
            val result = container.memoryRepository.applyRequest(request, profile)
            busy = false
            refresh()
            snackbar.showSnackbar(
                if (result.error != null) {
                    context.getString(R.string.memory_organize_failed, result.error)
                } else {
                    context.getString(R.string.memory_apply_done, result.version)
                }
            )
        }
    }

    fun organize() {
        if (busy) return
        busy = true
        scope.launch {
            val profile = runCatching { container.settingsStore.snapshotNow().activeProfile }.getOrNull()
            val result = container.memoryRepository.organize(profile)
            busy = false
            refresh()
            snackbar.showSnackbar(
                if (result.error != null) {
                    context.getString(R.string.memory_organize_failed, result.error)
                } else {
                    context.getString(R.string.memory_apply_done, result.version)
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.memory_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    // 手动编辑：模型改写之外的第二条路（加一条 / 删一段都行）
                    IconButton(onClick = { showEditor = true }, enabled = !busy) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.memory_edit)
                        )
                    }
                    TextButton(onClick = { organize() }, enabled = !busy) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.memory_organizing))
                        } else {
                            Text(stringResource(R.string.memory_organize))
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            MemoryComposer(
                value = input,
                onValueChange = { input = it },
                busy = busy,
                onSend = {
                    val text = input.trim()
                    input = ""
                    runRewrite(text)
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (version > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.memory_version_line,
                        version,
                        formatMemoryTime(updatedAt)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            if (doc.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.memory_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = doc,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showEditor) {
        MemoryEditorDialog(
            initial = doc,
            onDismiss = { showEditor = false },
            onSave = { text ->
                showEditor = false
                scope.launch {
                    container.memoryRepository.saveManually(text)
                    refresh()
                    snackbar.showSnackbar(
                        context.getString(R.string.memory_apply_done, container.memoryRepository.versionNow())
                    )
                }
            }
        )
    }
}

@Composable
private fun MemoryEditorDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_edit)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 360.dp),
                placeholder = { Text(stringResource(R.string.memory_edit_hint)) },
                textStyle = MaterialTheme.typography.bodySmall
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.memory_edit_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun MemoryComposer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.memory_hint)) },
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium
            )
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = value.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.memory_add)
                    )
                }
            }
        }
    }
}

private fun formatMemoryTime(millis: Long): String =
    if (millis <= 0L) {
        ""
    } else {
        DateTimeFormatter.ofPattern("yyyy-M-d HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))
    }
