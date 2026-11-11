package com.mistakebook.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import com.mistakebook.R
import com.mistakebook.data.local.entities.Notebook

/**
 * 错题本选择器：编辑页与详情页共用。
 *
 * 为什么不用 ExposedDropdownMenuBox：这个框只是「选一个」，
 * 用普通 DropdownMenu 足够，而且能和已有的 SubjectPicker 保持同样的视觉。
 */
@Composable
fun NotebookPicker(
    notebooks: List<Notebook>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    label: String = stringResource(R.string.edit_notebook),
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = notebooks.firstOrNull { it.id == selectedId }
    val display = selected?.name ?: stringResource(R.string.notebook_filter)

    Box(modifier = modifier) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notebook_filter)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            notebooks.forEach { notebook ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(notebook.name)
                            if (notebook.isDefault) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.notebook_default_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(notebook.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** 删除确认弹窗里用到的提示语，避免各处硬编码。 */
@Composable
fun rememberNotebookDeleteMessage(name: String): String =
    stringResource(R.string.notebook_delete_confirm, name)
