package com.mistakebook.ui.print

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.data.prefs.AppFont
import com.mistakebook.data.prefs.FontScale
import com.mistakebook.data.local.displayTitle
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.data.local.printImagePath
import com.mistakebook.domain.MasteryStatus
import com.mistakebook.print.ExportFormat
import com.mistakebook.ui.common.EmptyState
import com.mistakebook.ui.common.QuestionThumb
import com.mistakebook.ui.common.ReorderDragHandle
import com.mistakebook.ui.settings.BlankHeightRow

@Composable
fun PrintBottomBar(
    generating: Boolean,
    selectedCount: Int,
    formatName: String,
    onGenerate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Button(
            onClick = onGenerate,
            enabled = selectedCount > 0 && !generating,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            if (generating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = stringResource(
                    R.string.print_selected_format,
                    selectedCount,
                    formatName
                )
            )
        }
    }
}

@Composable
fun PrintFilters(
    state: PrintUiState,
    onSubjectChange: (Long?) -> Unit,
    onStatusChange: (MasteryStatus?) -> Unit,
    onKeywordChange: (String) -> Unit,
    onSetAll: (Boolean) -> Unit,
    onInvert: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        SubjectDropdown(
            subjects = state.subjects,
            selectedId = state.subjectId,
            onSelect = onSubjectChange,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = state.allSelected,
                onCheckedChange = onSetAll,
                enabled = state.questions.isNotEmpty()
            )
            Text(stringResource(R.string.print_select_all), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onInvert, enabled = state.questions.isNotEmpty()) {
                Text(stringResource(R.string.print_select_invert))
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf<Pair<MasteryStatus?, Int>>(
                null to R.string.home_filter_all,
                MasteryStatus.ACTIVE to R.string.home_filter_active,
                MasteryStatus.MASTERED to R.string.home_filter_mastered
            ).forEach { (value, labelRes) ->
                FilterChip(
                    selected = state.status == value,
                    onClick = { onStatusChange(value) },
                    label = { Text(stringResource(labelRes)) }
                )
            }
        }
    }
}

@Composable
fun PrintOptionsPanel(
    includeImage: Boolean,
    showAnswer: Boolean,
    blankRedo: Boolean,
    blankHeight: Int,
    format: ExportFormat,
    onIncludeImage: (Boolean) -> Unit,
    onShowAnswer: (Boolean) -> Unit,
    onBlankRedo: (Boolean) -> Unit,
    onBlankHeight: (Int) -> Unit,
    onFormatChange: (ExportFormat) -> Unit,
    /** 打印专用字号（v0.0.99）。 */
    fontScale: FontScale = FontScale.DEFAULT,
    /** 打印专用字体（v0.0.99）。 */
    fontFamily: AppFont = AppFont.DEFAULT,
    onFontScaleChange: (FontScale) -> Unit = {},
    onFontFamilyChange: (AppFont) -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.print_export_format),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportFormat.entries.forEach { f ->
                        FilterChip(
                            selected = format == f,
                            onClick = { onFormatChange(f) },
                            label = { Text(stringResource(f.shortLabelRes)) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // 字号与字体（v0.0.99）：打印专用的两档，与屏幕上的字号/字体各存各的——
            // 手上看着舒服的字号，和纸上给孩子练的字号，本来就不是一回事。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.print_font_size),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FontScale.entries.forEach { scale ->
                        FilterChip(
                            selected = fontScale == scale,
                            onClick = { onFontScaleChange(scale) },
                            label = { Text(stringResource(scale.printLabelRes)) }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.print_font_family),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppFont.entries.forEach { family ->
                        FilterChip(
                            selected = fontFamily == family,
                            onClick = { onFontFamilyChange(family) },
                            label = { Text(stringResource(family.printLabelRes)) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            OptionSwitch(
                label = stringResource(R.string.print_include_image),
                checked = includeImage,
                onChange = onIncludeImage
            )
            OptionSwitch(
                label = stringResource(R.string.print_show_answer),
                checked = showAnswer,
                onChange = onShowAnswer
            )
            OptionSwitch(
                label = stringResource(R.string.print_blank_redo),
                checked = blankRedo,
                onChange = onBlankRedo
            )
            if (blankRedo) {
                BlankHeightRow(height = blankHeight, onChange = onBlankHeight)
            }
            Spacer(Modifier.height(10.dp))
            PdfViaBrowserHint()
        }
    }
}

@Composable
internal fun PrintRow(
    question: Question,
    subjectName: String?,
    index: Int,
    checked: Boolean,
    dragging: Boolean,
    onToggle: () -> Unit,
    onReorderStart: () -> Unit,
    onReorder: (Float) -> Unit,
    onReorderEnd: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            QuestionThumb(
                imagePath = question.printImagePath,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                keepAspect = false
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.print_item_index, index),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = subjectName ?: stringResource(R.string.print_unclassified),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = question.displayTitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
            }
            ReorderDragHandle(
                dragging = dragging,
                onDragStart = onReorderStart,
                onDrag = onReorder,
                onDragEnd = onReorderEnd
            )
        }
    }
}

@Composable
private fun OptionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PdfViaBrowserHint() {
    Text(
        text = stringResource(R.string.print_pdf_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubjectDropdown(
    subjects: List<Subject>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val label = subjects.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(R.string.home_subject_all)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_subject_all)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            subjects.forEach { subject ->
                DropdownMenuItem(
                    text = { Text(subject.name) },
                    onClick = {
                        onSelect(subject.id)
                        expanded = false
                    }
                )
            }
        }
    }
}


/**
 * 打印页的字号档标签（v0.0.99）。
 *
 * **复用设置页那几条文案**：同一个概念（小/标准/大/特大）在两处就该是同一句话，
 * 复制一份新字符串只会在以后改词时漏掉一处。
 */
internal val FontScale.printLabelRes: Int
    get() = when (this) {
        FontScale.SMALL -> R.string.settings_font_size_small
        FontScale.STANDARD -> R.string.settings_font_size_standard
        FontScale.LARGE -> R.string.settings_font_size_large
        FontScale.EXTRA_LARGE -> R.string.settings_font_size_extra_large
    }

/** 打印页的字体标签（v0.0.99），同样复用设置页的文案。 */
internal val AppFont.printLabelRes: Int
    get() = when (this) {
        AppFont.SYSTEM -> R.string.settings_font_family_system
        AppFont.SERIF -> R.string.settings_font_family_serif
        AppFont.MONOSPACE -> R.string.settings_font_family_monospace
    }
