package com.mistakebook.ui.notebook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.R
import com.mistakebook.data.local.entities.Notebook
import com.mistakebook.di.AppContainer
import com.mistakebook.ui.common.EmptyState
import com.mistakebook.ui.common.containerViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotebookViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.notebookRepository

    val notebooks: StateFlow<List<Notebook>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String, onDone: (Notebook) -> Unit) {
        viewModelScope.launch { onDone(repository.create(name)) }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch { repository.rename(id, name) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}

/**
 * 错题本管理。
 *
 * 交互约定：
 * - 右下角 ➕ 新建，直接在弹窗里输名字，输完立刻建好并选中新本（省掉二次确认）；
 * - 行内 ✏️ 重命名；
 * - 行内 🗑 删除，**必须二次确认**并说明「题目会移到默认错题本，不会被删」。
 *   删分类是破坏性操作，且用户可能没意识到里面有几十道题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookScreen(
    container: AppContainer,
    onBack: () -> Unit,
    /** 新建/选中后返回首页并切到该错题本筛选。 */
    onSelected: (Long?) -> Unit
) {
    val viewModel: NotebookViewModel =
        containerViewModel(container) { NotebookViewModel(it) }
    val notebooks by viewModel.notebooks.collectAsState()

    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Notebook?>(null) }
    var deleting by remember { mutableStateOf<Notebook?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notebook_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            // 与首页/Library 的悬浮按钮同色（见 LibraryScreen 的注释）。
            FloatingActionButton(
                onClick = { showCreate = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.notebook_create))
            }
        }
    ) { padding ->
        if (notebooks.isEmpty()) {
            Box0(padding) {
                EmptyState(
                    title = stringResource(R.string.notebook_empty),
                    subtitle = stringResource(R.string.notebook_empty_hint)
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "__all__") {
                Card(
                    onClick = { onSelected(null) },
                    shape = MaterialTheme.shapes.small,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.notebook_all),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.notebook_all_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(notebooks, key = { it.id }) { notebook ->
                Card(
                    onClick = { onSelected(notebook.id) },
                    shape = MaterialTheme.shapes.small,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = notebook.name,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                if (notebook.isDefault) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.notebook_default_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { renaming = notebook }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.notebook_rename)
                            )
                        }
                        // 默认本不可删：它同时是「删本时题目的接盘者」
                        if (!notebook.isDefault) {
                            IconButton(onClick = { deleting = notebook }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.notebook_delete)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        NameDialog(
            title = stringResource(R.string.notebook_create),
            initial = "",
            confirmLabel = stringResource(R.string.action_confirm),
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                showCreate = false
                viewModel.create(name) { notebook -> onSelected(notebook.id) }
            }
        )
    }

    renaming?.let { target ->
        NameDialog(
            title = stringResource(R.string.notebook_rename),
            initial = target.name,
            confirmLabel = stringResource(R.string.action_confirm),
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                viewModel.rename(target.id, name)
            }
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.notebook_delete)) },
            text = { Text(stringResource(R.string.notebook_delete_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleting = null
                }) { Text(stringResource(R.string.notebook_delete_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun Box0(padding: androidx.compose.foundation.layout.PaddingValues, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
    ) { content() }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.notebook_name_hint)) }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
