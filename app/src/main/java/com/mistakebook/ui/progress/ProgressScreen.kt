package com.mistakebook.ui.progress

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.data.local.entities.CaptureTask
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.TaskStatus
import com.mistakebook.ui.common.containerViewModel
import com.mistakebook.ui.common.displayText
import com.mistakebook.ui.common.errorKindOf

/**
 * 识别进度页（PRD 7.4）：状态全部来自 CaptureTask 表。
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun ProgressScreen(
    container: AppContainer,
    taskId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onManualEntry: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val viewModel: ProgressViewModel = containerViewModel(container) { ProgressViewModel(it, taskId) }
    val state by viewModel.uiState.collectAsState()
    var showCancelDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.status) {
        if (state.done) {
            onEdit(taskId)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.progress_title)) }) }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when (state.status) {
                TaskStatus.FAILED -> FailureContent(
                    task = state.task,
                    onRetry = viewModel::retry,
                    onOpenSettings = onOpenSettings,
                    onSkipLlm = { viewModel.skipLlm { onEdit(taskId) } },
                    onManualEntry = onManualEntry,
                    onBack = onBack
                )

                TaskStatus.DONE -> DoneContent(onEdit = { onEdit(taskId) })

                else -> RunningContent(
                    state = state,
                    onCancel = { showCancelDialog = true }
                )
            }
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text(stringResource(R.string.progress_cancel)) },
            text = { Text(stringResource(R.string.progress_cancel_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    viewModel.cancel()
                    onBack()
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun RunningContent(state: ProgressUiState, onCancel: () -> Unit) {
    val stage = state.task?.stageText.orEmpty()
    val percent = extractPercent(stage)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.PhotoCamera,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Text(
            text = stage.ifBlank { stringResource(R.string.progress_stage_uploading) },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        if (state.groupPosition.isNotEmpty()) {
            Text(
                text = state.groupPosition,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (percent != null) {
            val animated by animateFloatAsState(targetValue = percent / 100f, label = "parseProgress")
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth()
            )
            Text("${percent}%", style = MaterialTheme.typography.bodySmall)
        } else {
            CircularProgressIndicator()
        }
        OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.progress_cancel))
        }
    }
}

@Composable
private fun FailureContent(
    task: CaptureTask?,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onSkipLlm: () -> Unit,
    onManualEntry: () -> Unit,
    onBack: () -> Unit
) {
    val kind = errorKindOf(task?.errorKind)
    val message = if (kind != null) {
        com.mistakebook.net.ApiError(kind = kind, serverMessage = task?.errorMessage.orEmpty())
            .displayText()
    } else {
        task?.errorMessage?.takeIf { it.isNotBlank() } ?: stringResource(R.string.error_bad_response)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(56.dp)
        )
        Text(
            text = stringResource(R.string.progress_failed_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        val hasMarkdown = !task?.markdown.isNullOrBlank()
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        if (kind == com.mistakebook.net.ApiErrorKind.NO_KEY) {
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.error_no_key_action))
            }
        }
        if (hasMarkdown) {
            OutlinedButton(onClick = onSkipLlm) {
                Text(stringResource(R.string.progress_skip_llm))
            }
        } else {
            // 识别彻底失败时的兜底：不靠任何 API 也能把题录进去。
            // 只在「连 MinerU 的 markdown 都没拿到」时出现——已经有原始文本的话，
            // 「跳过 AI 直接用原始文本」更合适，没必要让用户手打一遍。
            OutlinedButton(onClick = onManualEntry) {
                Text(stringResource(R.string.progress_manual_entry))
            }
        }
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }
}

@Composable
private fun DoneContent(onEdit: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF2FA84F),
            modifier = Modifier.size(56.dp)
        )
        Text(stringResource(R.string.progress_stage_done), style = MaterialTheme.typography.titleMedium)
        Button(onClick = onEdit) { Text(stringResource(R.string.edit_title)) }
    }
}

// 顶层常量：这个正则原本写在函数体里，每次调用（即每次重组）都要重新编译一次
private val RE_STAGE_PERCENT = Regex("(\\d{1,3})\\s*%")

private fun extractPercent(stage: String): Int? =
    RE_STAGE_PERCENT.find(stage)?.groupValues?.get(1)?.toIntOrNull()
