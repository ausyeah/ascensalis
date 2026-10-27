package com.mistakebook.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mistakebook.data.library.LibraryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 资料库管理页（v0.0.51 T6）。
 *
 * 操作 App 私有目录 `files/library/` 下的文本文件：导入（SAF）、新建
 * （.md/.txt）、重命名、删除（二次确认）、预览。列表显示名称、大小、
 * 修改时间。一期只收文本类；PDF 走 MinerU 的解析列为二期（本版无入口）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    filesDir: File,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember { LibraryStore.ensureDir(filesDir) }
    var files by remember { mutableStateOf(LibraryStore.list(root)) }
    var editing by remember { mutableStateOf<File?>(null) } // 预览/编辑的文件
    var showNewDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    var newName by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        files = LibraryStore.list(root)
    }

    // SAF 导入：拷进 library 目录（只收一期支持的扩展名，其余提示不支持）
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                val rawName = uri.lastPathSegment?.substringAfterLast('/') ?: return@withContext null
                val name = rawName.substringAfterLast('/')
                val target = LibraryStore.resolveLibraryPath(root, name)
                    ?: return@withContext null
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext null
                    target
                }.getOrNull()
            }
            if (imported != null) {
                refresh()
            } else {
                notice = context.getString(R.string.library_import_failed)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
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
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.padding(bottom = 10.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.library_import))
                }
                FloatingActionButton(onClick = { showNewDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.library_new))
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Text(
                text = stringResource(R.string.library_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (files.isEmpty()) {
                Text(
                    text = stringResource(R.string.library_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files, key = { it.absolutePath }) { file ->
                        val stamp = remember(file.lastModified()) {
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                .format(Date(file.lastModified()))
                        }
                        ListItem(
                            headlineContent = { Text(file.name) },
                            supportingContent = {
                                Text("${file.length()} 字节 · $stamp")
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = {
                                        newName = file.name
                                        renameTarget = file
                                    }) {
                                        Icon(
                                            Icons.Default.DriveFileRenameOutline,
                                            contentDescription = stringResource(R.string.library_rename)
                                        )
                                    }
                                    IconButton(onClick = { deleteTarget = file }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.library_delete)
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.clickable { editing = file }
                        )
                    }
                }
            }
        }
    }

    notice?.let {
        LaunchedEffect(it) {
            notice = null
        }
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text(stringResource(R.string.library_import_failed)) },
            text = { Text(it) },
            confirmButton = {
                TextButton(onClick = { notice = null }) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
    }

    if (showNewDialog) {
        var newFileName by remember { mutableStateOf("") }
        var newContent by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text(stringResource(R.string.library_new)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text(stringResource(R.string.library_new_name)) },
                        supportingText = { Text(stringResource(R.string.library_new_name_hint)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newContent,
                        onValueChange = { newContent = it },
                        label = { Text(stringResource(R.string.library_new_content)) },
                        minLines = 4,
                        maxLines = 10
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val target = withContext(Dispatchers.IO) {
                            LibraryStore.resolveLibraryPath(root, newFileName)
                        }
                        if (target == null) {
                            notice = context.getString(R.string.library_bad_name)
                        } else {
                            withContext(Dispatchers.IO) { LibraryStore.write(target, newContent) }
                            showNewDialog = false
                            refresh()
                        }
                    }
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.library_rename)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    supportingText = { Text(stringResource(R.string.library_new_name_hint)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val done = withContext(Dispatchers.IO) {
                            val dest = LibraryStore.resolveLibraryPath(root, newName)
                            if (dest == null || dest.exists()) null
                            else {
                                runCatching { target.renameTo(dest) }.getOrDefault(false)
                            }
                        }
                        if (done == true) {
                            renameTarget = null
                            refresh()
                        } else {
                            notice = context.getString(R.string.library_bad_name)
                            renameTarget = null
                        }
                    }
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.library_delete_confirm_title)) },
            text = { Text(stringResource(R.string.library_delete_confirm_body, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { LibraryStore.delete(target) }
                        deleteTarget = null
                        refresh()
                    }
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    editing?.let { file ->
        LibraryPreviewDialog(
            file = file,
            onClose = { editing = null },
            onSave = { content ->
                scope.launch {
                    withContext(Dispatchers.IO) { LibraryStore.write(file, content) }
                    refresh()
                }
                editing = null
            }
        )
    }
}

/** 文本预览 / 简单编辑：一期文本类直接展示全文，可改可存（无 PDF 占位需求）。 */
@Composable
private fun LibraryPreviewDialog(
    file: File,
    onClose: () -> Unit,
    onSave: (String) -> Unit
) {
    var draft by remember(file) {
        mutableStateOf(LibraryStore.read(file)?.first.orEmpty())
    }
    val truncated = remember(file) { LibraryStore.read(file)?.second ?: false }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(file.name) },
        text = {
            Column {
                if (truncated) {
                    Text(
                        text = stringResource(R.string.library_truncated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 14
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
