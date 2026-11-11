package com.mistakebook.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.data.local.entities.Notebook
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.domain.MasteryStatus

/**
 * 三个筛选下拉的统一外观。
 *
 * 需求是「学科 / 掌握程度 / 错题本做成三个可以展开的菜单」。
 * 之前是「下拉框 + FilterChip」混排，视觉语言完全不同——
 * 三个下拉统一成同一种「标签 + 标题 + 计数」的按钮，一眼能看出它们是同一族。
 */
@Composable
private fun FilterMenuButton(
    icon: ImageVector,
    label: String,
    value: String,
    count: Int?,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val active = count != null || value.isNotBlank()
    val borderColor = if (expanded) {
        MaterialTheme.colorScheme.primary
    } else if (active) {
        MaterialTheme.colorScheme.outline
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    }
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(
                if (expanded) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .border(1.dp, borderColor, MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = if (expanded) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "$label · $value",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 132.dp)
        )
        if (count != null) {
            Spacer(Modifier.width(5.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 下拉菜单里的一行：左标题右对勾。 */
@Composable
private fun MenuRow(
    text: String,
    selected: Boolean,
    trailing: String? = null,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = text, modifier = Modifier.weight(1f))
                if (trailing != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = trailing,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        onClick = onClick,
        trailingIcon = {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
}

/** 可滚动的菜单容器。学科/错题本多的时候菜单不能撑破屏幕。 */
@Composable
private fun ScrollableMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
        ) { content() }
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun SubjectFilterMenu(
    subjects: List<Subject>,
    selectedId: Long?,
    count: Int?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val name = subjects.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(R.string.home_subject_all)
    Box(modifier = modifier) {
        FilterMenuButton(
            icon = Icons.Default.School,
            label = stringResource(R.string.home_filter_subject),
            value = name,
            count = count,
            expanded = expanded,
            onClick = { expanded = true }
        )
        ScrollableMenu(expanded, { expanded = false }) {
            MenuRow(
                text = stringResource(R.string.home_subject_all),
                selected = selectedId == null,
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            subjects.forEach { subject ->
                MenuRow(
                    text = subject.name,
                    selected = selectedId == subject.id,
                    onClick = {
                        onSelect(subject.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun MasteryFilterMenu(
    status: MasteryStatus?,
    dueOnly: Boolean,
    count: Int?,
    onSelect: (status: MasteryStatus?, dueOnly: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val value = when {
        dueOnly -> stringResource(R.string.home_due_badge)
        status == null -> stringResource(R.string.home_filter_all)
        status == MasteryStatus.ACTIVE -> stringResource(R.string.home_filter_active)
        else -> stringResource(R.string.home_filter_mastered)
    }
    Box(modifier = modifier) {
        FilterMenuButton(
            icon = Icons.Default.TaskAlt,
            label = stringResource(R.string.home_filter_mastery),
            value = value,
            count = count,
            expanded = expanded,
            onClick = { expanded = true }
        )
        ScrollableMenu(expanded, { expanded = false }) {
            MenuRow(stringResource(R.string.home_filter_all), selected = status == null && !dueOnly) {
                onSelect(null, false)
                expanded = false
            }
            MenuRow(
                stringResource(R.string.home_filter_active),
                selected = status == MasteryStatus.ACTIVE
            ) {
                onSelect(MasteryStatus.ACTIVE, false)
                expanded = false
            }
            MenuRow(
                stringResource(R.string.home_filter_mastered),
                selected = status == MasteryStatus.MASTERED
            ) {
                onSelect(MasteryStatus.MASTERED, false)
                expanded = false
            }
            HorizontalDivider()
            MenuRow(stringResource(R.string.home_due_badge), selected = dueOnly) {
                onSelect(null, !dueOnly)
                expanded = false
            }
        }
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun NotebookFilterMenu(
    notebooks: List<Notebook>,
    selectedId: Long?,
    count: Int?,
    onSelect: (Long?) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val name = notebooks.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(R.string.notebook_filter)
    Box(modifier = modifier) {
        FilterMenuButton(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            label = stringResource(R.string.notebook_filter),
            value = name,
            count = count,
            expanded = expanded,
            onClick = { expanded = true }
        )
        ScrollableMenu(expanded, { expanded = false }) {
            MenuRow(
                text = stringResource(R.string.notebook_filter),
                selected = selectedId == null,
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            notebooks.forEach { notebook ->
                MenuRow(
                    text = notebook.name,
                    selected = selectedId == notebook.id,
                    trailing = if (notebook.isDefault) {
                        stringResource(R.string.notebook_default_badge)
                    } else {
                        null
                    }
                ) {
                    onSelect(notebook.id)
                    expanded = false
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notebook_manage)) },
                onClick = {
                    expanded = false
                    onManage()
                }
            )
        }
    }
}

/** 三个菜单整排铺开，等宽均分保证不换行、不挤压。 */
@Composable
fun FilterMenuRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) { content() }
}
