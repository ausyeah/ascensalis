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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.data.local.entities.Memory
import com.mistakebook.di.AppContainer
import kotlinx.coroutines.launch

/**
 * 记忆库（v0.0.108）。
 *
 * ## 为什么是「一页 + 底部输入框」
 * 记忆只有两种操作：看一眼现在记住了什么、增删一条。列表给前者，
 * 底部输入框给后者——用户说「我不吃辣」，应当和聊天一样说完就生效，
 * 而不是先点「新增」、填表单、再保存。
 *
 * ## 为什么「整理」放在右上角
 * 它是低频但重要的动作（让模型从最近的对话里挑出长期信息）。
 * 放在最显眼处会诱导用户频繁点（每次都要烧一次模型调用），
 * 收进顶栏一个文字按钮，需要时找得到，不需要时不碍事。
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

    val memories by container.memoryRepository.observeAll()
        .collectAsState(initial = emptyList<Memory>())
    var input by remember { mutableStateOf("") }
    var organizing by remember { mutableStateOf(false) }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        scope.launch {
            val added = container.memoryRepository.add(text)
            snackbar.showSnackbar(
                context.getString(if (added) R.string.memory_added else R.string.memory_duplicate)
            )
        }
    }

    fun organize() {
        if (organizing) return
        organizing = true
        scope.launch {
            val profile = runCatching { container.settingsStore.snapshotNow().activeProfile }.getOrNull()
            val result = container.memoryRepository.organize(profile)
            organizing = false
            val message = when {
                result.error != null ->
                    context.getString(R.string.memory_organize_failed, result.error)
                result.added == 0 -> context.getString(R.string.memory_organize_empty)
                else -> context.getString(R.string.memory_organize_done, result.added)
            }
            snackbar.showSnackbar(message)
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
                    TextButton(onClick = { organize() }, enabled = !organizing) {
                        if (organizing) {
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
                onSend = { submit() }
            )
        }
    ) { padding ->
        if (memories.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.memory_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 20.dp,
                    vertical = 8.dp
                )
            ) {
                items(memories, key = { it.id }) { memory ->
                    MemoryRow(
                        memory = memory,
                        onDelete = {
                            scope.launch {
                                container.memoryRepository.delete(memory.id)
                                snackbar.showSnackbar(context.getString(R.string.memory_deleted))
                            }
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

/** 一条记忆：正文 + （模型整理的）来源标记 + 删除。 */
@Composable
private fun MemoryRow(
    memory: Memory,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium
            )
            if (memory.fromModel) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.memory_from_model),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.memory_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 底部输入条：说一句就记住，和聊天同一个动作。 */
@Composable
private fun MemoryComposer(
    value: String,
    onValueChange: (String) -> Unit,
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
            IconButton(
                onClick = onSend,
                enabled = value.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = stringResource(R.string.memory_add)
                )
            }
        }
    }
}