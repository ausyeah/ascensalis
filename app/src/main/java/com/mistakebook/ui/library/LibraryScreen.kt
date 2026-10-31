package com.mistakebook.ui.library

import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.PictureAsPdf
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
 * 资料库管理页（v0.0.51 T6，v0.0.55 反馈补 docx/pdf 与导入文件名修复）。
 *
 * 操作 App 私有目录 `files/library/` 下的资料文件：导入（SAF）、新建
 * （.md/.txt）、重命名、删除（二次确认）、预览。列表显示名称、大小、
 * 修改时间。
 *
 * 支持 `.md` / `.txt` / `.json` / `.csv` 四个文本类，以及 `.docx` / `.pdf`
 * 两个二进制类（原文原样落库，读取时经
 * [com.mistakebook.data.library.DocumentTextExtractor] 提取正文）。
 * 二进制类只能读、不能改：编辑保存会把二进制原文件冲掉，入口直接不给。
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

    // 从内容提供方查文件显示名；查不到返回 null，由调用方回退 lastPathSegment。
    // 必须声明在 importLauncher **之前**：Kotlin 的局部函数不支持前向引用，
    // WIP 把声明放在使用之后，从未编译所以没暴露（CI 报 Unresolved reference）。
    fun queryDisplayName(context: android.content.Context, uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    // SAF 导入：拷进 library 目录（只收支持的扩展名，其余提示不支持）。
    // 文件名优先查 DISPLAY_NAME：部分文档选择器的 uri.lastPathSegment
    // 是数字 id（如 msf:100），拿来当文件名必然解析失败。
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                val name = queryDisplayName(context, uri)
                    ?.substringAfterLast('/')
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: return@withContext null
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
                            leadingContent = {
                                // 类型图标：一眼分辨「这是表格 / 文档 / 纯文本」，
                                // 与主流阅读类 App 的文件列表一致（v0.0.57）。
                                Icon(
                                    imageVector = libraryFileIcon(file.name),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            headlineContent = { Text(file.name) },
                            supportingContent = {
                                Text("${formatLibrarySize(file.length())} · ${libraryFileTypeLabel(file.name)} · $stamp")
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
            onSave = if (LibraryStore.isBinaryDoc(file)) null else { content ->
                scope.launch {
                    withContext(Dispatchers.IO) { LibraryStore.write(file, content) }
                    refresh()
                }
                editing = null
            }
        )
    }
}

/**
 * 预览 / 编辑。文本类直接展示全文，可改可存；.docx / .pdf 走提取出的
 * 正文做只读预览（编辑保存会把二进制原文件冲掉，必须挡死）。
 * 提取可能耗时（大文档），统一异步读，不卡 UI 线程。
 */
@Composable
private fun LibraryPreviewDialog(
    file: File,
    onClose: () -> Unit,
    onSave: ((String) -> Unit)?
) {
    var draft by remember(file) { mutableStateOf<String?>(null) }
    var truncated by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file) {
        val (text, wasTruncated) = withContext(Dispatchers.IO) {
            LibraryStore.read(file)
        } ?: ("" to false)
        draft = text
        truncated = wasTruncated
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(file.name) },
        text = {
            Column {
                val content = draft
                if (content == null) {
                    Text(
                        text = stringResource(R.string.library_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (onSave == null) {
                    Text(
                        text = stringResource(R.string.library_binary_preview_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .height(280.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                } else {
                if (truncated) {
                    Text(
                        text = stringResource(R.string.library_truncated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 14
                )
                }
            }
        },
        // 注意形参必须是「 composable lambda 」本身，不能是 if 表达式的结果：
        // `confirmButton = if (...) { TextButton(...) } else { ... }` 求值得到的是
        // Unit（TextButton 返回 Unit），类型对不上 `@Composable () -> Unit`，
        // 而且会让整个 AlertDialog 的重载解析失败，连带 title/text 两个槽一起报
        // 「@Composable invocations can only happen from...」。所以 if 要写在
        // lambda **里面**。
        confirmButton = {
            if (onSave != null) {
                TextButton(onClick = { draft?.let(onSave) }) {
                    Text(stringResource(R.string.action_confirm))
                }
            } else {
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.action_back))
                }
            }
        },
        // 二进制文档不给「取消」：只有「知道了」一个出口，避免用户以为能改。
        dismissButton = if (onSave != null) {
            { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } }
        } else null
    )
}

/**
 * 资料库文件类型图标（v0.0.57；v0.0.84 换官方 Material 图标并细化到每种类型）。
 * 早期 md/txt/json/csv 全落到 else 的 Description，一列下来几乎全是同一个图标。
 */
private fun libraryFileIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> Icons.Default.PictureAsPdf
        "doc", "docx" -> Icons.Default.Description
        "xls", "xlsx", "csv" -> Icons.Default.TableChart
        "md", "markdown" -> Icons.Default.Article
        "txt" -> Icons.Default.TextSnippet
        "json" -> Icons.Default.DataObject
        "png", "jpg", "jpeg", "webp", "gif" -> Icons.Default.Image
        "mp3", "wav", "m4a", "flac", "ogg" -> Icons.Default.LibraryMusic
        "mp4", "mov", "webm", "mkv" -> Icons.Default.Movie
        "zip", "rar", "7z" -> Icons.Default.FolderZip
        else -> Icons.Default.InsertDriveFile
    }
}

/** 资料库文件类型文字（与图标互补，方便色弱/无图标场景辨认）。 */
private fun libraryFileTypeLabel(name: String): String =
    name.substringAfterLast('.', "").lowercase().ifBlank { "文件" }.uppercase()

/** 人类可读的文件大小（B / KB / MB）。 */
private fun formatLibrarySize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}