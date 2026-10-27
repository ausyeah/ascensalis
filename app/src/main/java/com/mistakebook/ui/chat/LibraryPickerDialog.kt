package com.mistakebook.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.data.library.LibraryStore
import java.io.File

/**
 * 「@资料库」文件选择弹层（v0.0.51 T6 降级注入）。
 *
 * 列出资料库全部文本文件，勾选即加入待注入列表（可多选）。
 * 发送时选中文件的内容（50KB 截断）前置为本轮上下文——这是
 * tool calling 框架合入前，AI 读取资料库的降级路径。
 */
@Composable
fun LibraryPickerDialog(
    filesDir: File,
    selectedNames: List<String>,
    onPick: (File) -> Unit,
    onRemove: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val files = remember {
        LibraryStore.list(LibraryStore.ensureDir(filesDir))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_attach_library)) },
        text = {
            if (files.isEmpty()) {
                Text(
                    text = stringResource(R.string.library_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    items(files, key = { it.absolutePath }) { file ->
                        val selected = file.name in selectedNames
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selected) onRemove(file) else onPick(file)
                                }
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = selected, onCheckedChange = null)
                            Spacer000()
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${file.length()} 字节",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        }
    )
}

/** 占位分隔（避免直接引 Spacer 的额外 import 冲突）。 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.Spacer000() {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(4.dp))
}

/** 输入框待发区的资料库注入 chip：显示文件名 + 移除按钮。 */
@Composable
fun LibraryInjectionChip(
    fileName: String,
    onRemove: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.padding(end = 2.dp)
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.chat_attachment_remove),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
