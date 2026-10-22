package com.mistakebook.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.mistakebook.R
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.ErrorReason
import com.mistakebook.ui.common.DifficultyPicker
import com.mistakebook.ui.common.QuestionThumb
import com.mistakebook.ui.common.RichText
import com.mistakebook.ui.common.SectionLabel
import com.mistakebook.ui.common.containerViewModel

// 编辑已入库的题目（详情页入口）。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionEditScreen(
    container: AppContainer,
    questionId: Long,
    onRecrop: (String) -> Unit,
    onBack: () -> Unit
) {
    val viewModel: QuestionEditViewModel =
        containerViewModel(container) { QuestionEditViewModel(it, questionId) }
    val state by viewModel.uiState.collectAsState()

    // 重裁剪回填：裁剪页确认后把新原图路径塞进容器，这里取用并清空。
    // 与 EditScreen 同一套机制（container.recropping 标志 + recroppedImagePath 结果）。
    val recropped = container.recroppedImagePath
    if (!recropped.isNullOrBlank()) {
        container.recroppedImagePath = null
        viewModel.setImagePath(recropped)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Button(
                    onClick = { viewModel.save(onBack) },
                    enabled = state.canSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val draft = state.draft
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                draft == null -> Text(
                    text = stringResource(R.string.error_bad_response),
                    modifier = Modifier.align(Alignment.Center)
                )

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // 渲染预览：下面全是 LaTeX 源码输入框，没有预览用户根本看不出
                    // 公式写对没有、排出来是什么样。这块之前只有首次识别页有。
                    val preview = remember(draft) {
                        buildString {
                            if (draft.stem.isNotBlank()) append(draft.stem).append("\n\n")
                            draft.options.forEach { append("${it.label}. ${it.text}\n") }
                            if (draft.answer.isNotBlank()) {
                                append("\n**答案：** ").append(draft.answer).append("\n")
                            }
                            if (draft.analysis.isNotBlank()) {
                                append("\n**解析：** ").append(draft.analysis)
                            }
                        }.trim()
                    }
                    if (preview.isNotBlank()) {
                        SectionLabel(stringResource(R.string.edit_preview))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .padding(12.dp)
                        ) {
                            RichText(
                                text = preview,
                                mathRenderer = container.mathRenderer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // 原图：可预览、可重新裁剪/旋转（复用裁剪页，不重新识别）
                    Card(
                        onClick = { onRecrop(draft.imagePath) },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            QuestionThumb(
                                imagePath = draft.imagePath,
                                contentDescription = stringResource(R.string.edit_original_image),
                                modifier = Modifier.height(56.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.edit_original_image),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(R.string.edit_recrop),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            OutlinedButton(
                                onClick = { onRecrop(draft.imagePath) },
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.edit_recrop),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                    SectionLabel(stringResource(R.string.edit_figures))
                    if (draft.figurePaths.isEmpty()) {
                        Text(
                            text = stringResource(R.string.edit_figures_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(draft.figurePaths) { ref ->
                                Box {
                                    QuestionThumb(
                                        imagePath = ref,
                                        contentDescription = stringResource(R.string.edit_figures),
                                        modifier = Modifier
                                            .height(96.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                    IconButton(
                                        onClick = { viewModel.removeFigure(ref) },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(R.string.edit_figure_remove),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    SectionLabel(stringResource(R.string.edit_subject))
                    SubjectDropdown(
                        subjects = state.subjects,
                        selectedId = draft.subjectId,
                        onSelect = viewModel::setSubject
                    )
                    // 学科空着（或只填了「其他」）时给手填框，用户可以自己写「通信原理」
                    if (draft.subjectId == null ||
                        draft.subjectName == com.mistakebook.data.local.entities.Subject.FALLBACK
                    ) {
                        OutlinedTextField(
                            value = draft.manualSubject,
                            onValueChange = viewModel::setManualSubject,
                            label = { Text(stringResource(R.string.edit_subject_manual)) },
                            placeholder = { Text(stringResource(R.string.edit_subject_manual_hint)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    SectionLabel(stringResource(R.string.edit_question_title))
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = viewModel::setTitle,
                        label = { Text(stringResource(R.string.edit_title_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))
                    com.mistakebook.ui.common.NotebookPicker(
                        notebooks = state.notebooks,
                        selectedId = draft.notebookId,
                        onSelect = viewModel::setNotebook
                    )

                    Spacer(Modifier.height(8.dp))
                    SectionLabel(stringResource(R.string.edit_stem))
                    OutlinedTextField(
                        value = draft.stem,
                        onValueChange = viewModel::setStem,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp),
                        isError = draft.stem.isBlank()
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(stringResource(R.string.edit_options))
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = viewModel::addOption) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text(stringResource(R.string.edit_add_option))
                        }
                    }
                    draft.options.forEachIndexed { index, option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = option.label.ifBlank { com.mistakebook.domain.labelFor(index) },
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.width(24.dp)
                            )
                            OutlinedTextField(
                                value = option.text,
                                onValueChange = { viewModel.updateOption(index, it) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            IconButton(onClick = { viewModel.removeOption(index) }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_delete)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    SectionLabel(stringResource(R.string.edit_answer))
                    OutlinedTextField(
                        value = draft.answer,
                        onValueChange = viewModel::setAnswer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp)
                    )

                    Spacer(Modifier.height(8.dp))
                    SectionLabel(stringResource(R.string.edit_analysis))
                    OutlinedTextField(
                        value = draft.analysis,
                        onValueChange = viewModel::setAnalysis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp)
                    )

                    Spacer(Modifier.height(8.dp))
                    SectionLabel(stringResource(R.string.edit_error_reason))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ErrorReason.entries.toList()) { reason ->
                            FilterChip(
                                selected = draft.errorReason == reason,
                                onClick = { viewModel.setReason(reason) },
                                label = { Text(reason.label) }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    SectionLabel(stringResource(R.string.edit_difficulty))
                    DifficultyPicker(difficulty = draft.difficulty, onChange = viewModel::setDifficulty)

                    Spacer(Modifier.height(8.dp))
                    SectionLabel(stringResource(R.string.edit_knowledge_points))
                    KnowledgePointEditor(
                        points = draft.knowledgePoints,
                        onAdd = viewModel::addKnowledgePoint,
                        onRemove = viewModel::removeKnowledgePoint
                    )

                    Spacer(Modifier.height(8.dp))
                    SectionLabel(stringResource(R.string.edit_note))
                    OutlinedTextField(
                        value = draft.note,
                        onValueChange = viewModel::setNote,
                        placeholder = { Text(stringResource(R.string.edit_note_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubjectDropdown(
    subjects: List<com.mistakebook.data.local.entities.Subject>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = subjects.firstOrNull { it.id == selectedId }?.name ?: "未分类"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.edit_subject)) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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

@Composable
private fun KnowledgePointEditor(
    points: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text(stringResource(R.string.edit_knowledge_add_hint)) },
            singleLine = true,
            keyboardActions = KeyboardActions(
                onDone = {
                    onAdd(input)
                    input = ""
                }
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (points.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(points) { point ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(point) },
                        label = { Text(point) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_delete),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}
