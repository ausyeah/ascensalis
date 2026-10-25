package com.mistakebook.ui.edit

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.ErrorReason
import com.mistakebook.ui.common.DifficultyPicker
import com.mistakebook.ui.common.FullScreenImageDialog
import kotlinx.coroutines.launch
import com.mistakebook.ui.common.RichText
import com.mistakebook.ui.common.SectionLabel
import com.mistakebook.ui.common.containerViewModel
import com.mistakebook.ui.theme.WarningAmber

/**
 * 编辑页（PRD 7.5）：一次识别可含多道题，顶部翻页切换。
 *
 * [taskId] 传 null 即「手动录入」：MinerU 连不上、Key 没配、或者用户就是想手写一道题时，
 * 走的是同一套表单，只是草稿从空白开始、且没有原图与原始识别文本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    container: AppContainer,
    taskId: Long?,
    initialIndex: Int,
    onRecrop: (String) -> Unit,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit
) {
    val viewModel: EditViewModel =
        containerViewModel(container) { EditViewModel(it, taskId, initialIndex) }
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 文本体检：KaTeX 渲染失败的公式由 RichText 回传。
    // 那是 KaTeX 自己的判定，比正则猜准得多，也不用额外渲染一遍。
    var mathFailures by remember { mutableStateOf(emptySet<String>()) }
    var showAuditSheet by remember { mutableStateOf(false) }
    // 修正前的快照：没有它，启发式判错就等于逼用户手工逐字改回来。
    var auditSnapshot by remember { mutableStateOf<EditableDraft?>(null) }
    val context = LocalContext.current
    var showRawSheet by remember { mutableStateOf(false) }
    var showFullImage by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // 重裁剪回填：编辑页从裁剪页返回时（重新进入组合）取用新路径
    val recropped = container.recroppedImagePath
    if (!recropped.isNullOrBlank()) {
        container.recroppedImagePath = null
        viewModel.setImagePath(recropped)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.manualEntry -> stringResource(R.string.edit_title_manual)
                            state.total > 1 -> stringResource(
                                R.string.edit_title_with_index,
                                state.index + 1,
                                state.total
                            )

                            else -> stringResource(R.string.edit_title)
                        },
                        // 标题槽和右侧的「查看 / 体检 / 左右箭头」共享一行宽度，
                        // 不限行数时「确认题目 1/2」会被挤成三行换行。
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (state.total > 1) {
                        IconButton(
                            onClick = { viewModel.goTo(state.index - 1) },
                            enabled = state.index > 0
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.edit_prev))
                        }
                        IconButton(
                            onClick = { viewModel.goTo(state.index + 1) },
                            enabled = state.index < state.total - 1
                        ) {
                            Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.edit_next))
                        }
                    }
                    // 手动录入没有识别文本可看，这个入口必须藏起来，
                    // 否则点开是一张空白面板，更让人以为坏了
                    if (!state.manualEntry) {
                        TextButton(onClick = { showRawSheet = true }) {
                            Text(stringResource(R.string.edit_view_raw))
                        }
                    }
                    // 文本体检入口。放在原始文本旁边——两者都是「看一眼内容对不对」。
                    TextButton(
                        onClick = { showAuditSheet = true }
                    ) {
                        Text(stringResource(R.string.edit_audit))
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.total > 1) {
                        Text(
                            text = stringResource(R.string.edit_saved_count_format, state.savedCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Button(
                        onClick = {
                            viewModel.saveCurrent { id -> onSaved(id) }
                        },
                        enabled = state.canSave,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.edit_save))
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }

    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.taskMissing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.error_bad_response))
                }

                else -> {
                    val draft = state.current ?: return@Scaffold

    // stringResource 只能在组合期调用，先取出来给协程里的 Snackbar 用
    val undoLabel = stringResource(R.string.action_undo)
    val currentDraft = state.current

    if (showAuditSheet && currentDraft != null) {
        val issues = runAudit(currentDraft, mathFailures)
        AuditSheet(
            issues = issues,
            onDismiss = { showAuditSheet = false },
            onApply = { toFix ->
                auditSnapshot = currentDraft
                viewModel.updateCurrent { draft -> applyAuditFixes(draft, toFix) }
                showAuditSheet = false
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "已还原 ${toFix.size} 处",
                        actionLabel = undoLabel,
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        val snap = auditSnapshot
                        if (snap != null) viewModel.updateCurrent { snap }
                        auditSnapshot = null
                    }
                }
            }
        )
    }
                    val hasImage = draft.imagePath.endsWith(".jpg", true) ||
                        draft.imagePath.endsWith(".jpeg", true) ||
                        draft.imagePath.endsWith(".png", true)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        if (state.degraded) {
                            WarningBanner(
                                text = state.degradedReason.ifBlank {
                                    stringResource(R.string.edit_degraded_warning)
                                }
                            )
                        }
                        if (state.truncated) {
                            WarningBanner(text = stringResource(R.string.edit_truncated_warning))
                        }
                        // 手动录入没有原图，也没有「识别文本 vs 修正结果」的对照。
                        // 提前说清楚，免得用户一直找那个不存在的原图。
                        if (state.manualEntry) {
                            InfoBanner(text = stringResource(R.string.edit_manual_hint))
                        }

                        // 渲染预览：下方输入框是 LaTeX 源码，这里实时显示排版后的效果
                        val previewText = buildString {
                            if (draft.stem.isNotBlank()) append(draft.stem).append("\n\n")
                            draft.options.forEach { append("${it.label}. ${it.text}\n") }
                            if (draft.answer.isNotBlank()) append("\n**答案：** ").append(draft.answer).append("\n")
                            if (draft.analysis.isNotBlank()) append("\n**解析：** ").append(draft.analysis)
                        }
                        if (previewText.contains("$")) {
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = stringResource(R.string.edit_preview),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    RichText(
                                        text = previewText,
                                        mathRenderer = container.mathRenderer,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        // 题目附图：MinerU 从原图切出的图形部分，也是打印时用的图
                        if (draft.figurePaths.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            SectionLabel(stringResource(R.string.edit_figures))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(draft.figurePaths) { ref ->
                                    Box {
                                        com.mistakebook.ui.common.QuestionThumb(
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
                                                imageVector = androidx.compose.material.icons.Icons.Default.Close,
                                                contentDescription = stringResource(R.string.edit_figure_remove),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 原图：点开可全屏看，点「重新裁剪」进裁剪页重新框选/旋转
                        if (hasImage) {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            onClick = { showFullImage = true },
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                com.mistakebook.ui.common.QuestionThumb(
                                    imagePath = draft.imagePath,
                                    contentDescription = stringResource(R.string.edit_original_image),
                                    modifier = Modifier.height(56.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.edit_original_image),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = stringResource(R.string.edit_view_original),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        // 标记进入重裁剪模式：裁剪页确认后只回填路径，不重新识别
                                        container.recropping = true
                                        onRecrop(draft.imagePath)
                                    },
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.edit_recrop),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                        }

                        Spacer(Modifier.height(12.dp))
                        SectionLabel(stringResource(R.string.edit_subject))
                        SubjectPicker(
                            subjects = state.subjects,
                            selectedId = draft.subjectId,
                            suggestedName = draft.subjectName,
                            onSelect = viewModel::setSubject
                        )
                        // 学科识别不出来时（subjectName 为空）给手填框，避免题目标成「其他」
                        if (draft.subjectName.isBlank() || draft.subjectId == null) {
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

                        // 题目标题：列表页展示用，可自定义，留空则回退显示题干
                        Spacer(Modifier.height(10.dp))
                        SectionLabel(stringResource(R.string.edit_question_title))
                        OutlinedTextField(
                            value = draft.title,
                            onValueChange = viewModel::setTitle,
                            label = { Text(stringResource(R.string.edit_title_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 错题本：识别完就能归类，不用等入库后再去改
                        Spacer(Modifier.height(10.dp))
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
                            placeholder = { Text(stringResource(R.string.edit_stem)) },
                            isError = draft.stem.isBlank()
                        )
                        if (draft.stem.isBlank()) {
                            Text(
                                text = stringResource(R.string.edit_stem_empty_warning),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

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
                                    text = option.label,
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

                        if (state.imageRefs.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            SectionLabel(stringResource(R.string.edit_referenced_images))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(state.imageRefs) { ref ->
                                    InputChip(
                                        selected = false,
                                        onClick = { viewModel.removeImageRef(ref) },
                                        label = {
                                            Text(
                                                text = ref.substringAfterLast('/'),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
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

                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    if (showFullImage) {
        state.current?.let { draft ->
            FullScreenImageDialog(
                imagePath = draft.imagePath,
                contentDescription = stringResource(R.string.edit_original_image),
                onDismiss = { showFullImage = false }
            )
        }
    }

    if (showRawSheet) {
        ModalBottomSheet(
            onDismissRequest = { showRawSheet = false },
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.edit_view_raw),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = state.markdown.ifBlank { "—" },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, state.markdown)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    }) { Text(stringResource(R.string.action_copy)) }
                    TextButton(onClick = { showRawSheet = false }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningBanner(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        color = WarningAmber.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/** 中性提示条：说明当前状态，但不是错误。手动录入时就用这个而不是警告色。 */
@Composable
private fun InfoBanner(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(12.dp)
        )
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

// 学科选择：列表只包含用户已建的学科；AI 建议但库里没有的学科作为「新增」项直接可选，
// 选中后保存时自动入库（不再预置十个学科）。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubjectPicker(
    subjects: List<com.mistakebook.data.local.entities.Subject>,
    selectedId: Long?,
    suggestedName: String,
    onSelect: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = subjects.firstOrNull { it.id == selectedId }?.name
    val display = selectedName ?: suggestedName.ifBlank { "未分类" }
    val options = remember(subjects, suggestedName) {
        buildList {
            subjects.forEach { add(it.id to it.name) }
            if (suggestedName.isNotBlank() && subjects.none { it.name == suggestedName }) {
                add(null to suggestedName)
            }
        }
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.edit_subject)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(if (id == null) "$name（新增）" else name) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    }
                )
            }
        }
    }
}
