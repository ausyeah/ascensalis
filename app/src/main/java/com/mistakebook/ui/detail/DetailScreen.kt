package com.mistakebook.ui.detail

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.data.local.figurePaths
import com.mistakebook.di.AppContainer
import com.mistakebook.ui.common.DifficultyPicker
import com.mistakebook.domain.ReviewResult
import com.mistakebook.ui.common.DifficultyStars
import com.mistakebook.ui.common.EmptyState
import com.mistakebook.ui.common.ErrorReasonChip
import com.mistakebook.ui.common.Format
import com.mistakebook.ui.common.FullScreenImageDialog
import com.mistakebook.ui.common.InfoRow
import com.mistakebook.ui.common.NotebookPicker
import com.mistakebook.ui.common.QuestionThumb
 import com.mistakebook.ui.common.NoteEditor
import com.mistakebook.ui.common.RichText
 import com.mistakebook.ui.common.SectionLabel
import com.mistakebook.ui.common.SubjectDot
import com.mistakebook.ui.common.containerViewModel
import com.mistakebook.ui.theme.Danger
import com.mistakebook.ui.theme.SuccessGreen

/**
 * 详情页（PRD 7.6）：展示、编辑、删除、标记已掌握、复习打卡与历史时间线。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    container: AppContainer,
    questionId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onRecrop: (String) -> Unit = {},
    onReRecognize: (Long) -> Unit = {},
    /** 重新识别（先裁切再识别）：确认后带原图路径进裁剪页。 */
    onReRecognizeWithCrop: (String) -> Unit = {},
    onOpenChat: (Long) -> Unit = {},
    /** 变式题存为错题后带新题 id 回调（跳新题详情）。 */
    onVariantSaved: (Long) -> Unit = {}
) {
    val viewModel: DetailViewModel =
        containerViewModel(container) { DetailViewModel(it, questionId) }
    val state by viewModel.uiState.collectAsState()
    val variantState by viewModel.variantState.collectAsState()
    val auxImageState by viewModel.auxImage.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showFullImage by remember { mutableStateOf(false) }
    // 点开的具体图片：null 表示原始照片，非空为某张附图
    var fullImagePath by remember { mutableStateOf<String?>(null) }
    // 重新识别会覆盖当前题目，必须让用户明确确认
    var showReRecognizeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.question == null) {
        if (!state.loading && state.question == null) onBack()
    }

    val question = state.question
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (question != null) {
                        // AI 对话入口：放在最前面，因为它是对这道题最常用的后续动作
                        IconButton(onClick = { onOpenChat(question.id) }) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = stringResource(R.string.chat_title_question)
                            )
                        }
                        // 举一反三：基于本题出一道同知识点的变式题，可存为新错题
                        IconButton(onClick = { viewModel.openVariant() }) {
                            Icon(
                                Icons.Default.Shuffle,
                                contentDescription = stringResource(R.string.detail_variant_generate)
                            )
                        }
                        IconButton(onClick = { showReRecognizeDialog = true }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.detail_re_recognize)
                            )
                        }
                        IconButton(onClick = { onEdit(question.id) }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val hasImage = question != null && (
                question.imagePath.endsWith(".jpg", true) ||
                    question.imagePath.endsWith(".jpeg", true) ||
                    question.imagePath.endsWith(".png", true)
            )
            when {
                state.loading || question == null -> EmptyState(
                    title = stringResource(R.string.detail_title),
                    subtitle = ""
                )

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    if (hasImage) {
                        QuestionThumb(
                            imagePath = question.imagePath,
                            contentDescription = stringResource(R.string.edit_original_image),
                            // 宽度撑满、高度完全交给图片比例（min 只是加载中的
                            // 占位下限）：长题图整幅可见，不再被固定高度裁头去尾
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp),
                            onClick = { showFullImage = true; fullImagePath = null }
                        )
                        // 重新裁切：只换这张显示图/封面，不重新识别。
                        // 与右上角「重新识别」的分工——那个是先裁切再识别。
                        TextButton(
                            onClick = { onRecrop(question.imagePath) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(
                                Icons.Default.Crop,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.edit_recrop))
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    // 题目附图：MinerU 从原图切出的图形部分。与上面的整张原图明显区分，
                    // 打印时用的也是这一张。
                    if (question.figurePaths.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(question.figurePaths) { ref ->
                                QuestionThumb(
                                    imagePath = ref,
                                    contentDescription = stringResource(R.string.edit_figures),
                                    modifier = Modifier
                                        .height(120.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    onClick = { showFullImage = true; fullImagePath = ref }
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SubjectDot(subjectName = state.subjectName)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = state.subjectName ?: stringResource(R.string.print_unclassified),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.width(8.dp))
                        ErrorReasonChip(reason = question.errorReason)
                        Spacer(Modifier.weight(1f))
                        // 难度直接点星改。大模型给的只是初值，用户有最终判断权。
                        DifficultyPicker(
                            difficulty = question.difficulty,
                            onChange = viewModel::setDifficulty
                        )
                    }

                    // 错题本：详情页直接改归属，不用跳去编辑页
                    Spacer(Modifier.height(10.dp))
                    NotebookPicker(
                        notebooks = state.notebooks,
                        selectedId = question.notebookId,
                        onSelect = viewModel::setNotebook,
                        label = stringResource(R.string.detail_notebook)
                    )
                    if (state.dueToday) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.home_due_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = Danger
                        )
                    }
                    if (question.status == com.mistakebook.domain.MasteryStatus.MASTERED) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.detail_mastered_hint),
                            style = MaterialTheme.typography.titleMedium,
                            color = SuccessGreen
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    RichText(
                        text = question.stem,
                        mathRenderer = container.mathRenderer,
                        style = MaterialTheme.typography.bodyLarge
                    )

                    if (state.options.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        state.options.forEach { option ->
                            // 选项也要走 RichText：里面常含 $...$，用裸 Text 会直接显示 LaTeX 源码
                            RichText(
                                text = "${option.label}. ${option.text}",
                                mathRenderer = container.mathRenderer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }

                    if (question.answer.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        SectionLabel(stringResource(R.string.edit_answer))
                        RichText(
                            text = question.answer,
                            mathRenderer = container.mathRenderer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (question.analysis.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        SectionLabel(stringResource(R.string.edit_analysis))
                        RichText(
                            text = question.analysis,
                            mathRenderer = container.mathRenderer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (state.knowledgePoints.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        InfoRow(
                            label = stringResource(R.string.detail_knowledge_points),
                            value = state.knowledgePoints.joinToString("、")
                        )
                    }
                    // 辅助图（F-IMG-4）：解析区下方的配图入口。
                    // 精确几何走 SVG（模型会在解析里直接输出），
                    // 这里留给「实物/场景类」示意——细胞结构、实验装置这类。
                    // （question 已在上面 loading/空态提前返回后智能转为非空）
                    Spacer(Modifier.height(12.dp))
                    SectionLabel(stringResource(R.string.detail_aux_image))
                    AuxImageBlock(
                        auxState = auxImageState,
                        onGenerate = viewModel::generateAuxImage,
                        onDismiss = viewModel::dismissAuxImage
                    )
                    // 备注：看题时就能直接改，不用进编辑页
                    Spacer(Modifier.height(12.dp))
                    SectionLabel(stringResource(R.string.detail_note))
                    NoteEditor(
                        note = viewModel.currentNote(),
                        mathRenderer = container.mathRenderer,
                        onChange = viewModel::setNote,
                        onCommit = viewModel::saveNote
                    )

                    Spacer(Modifier.height(20.dp))
                    MasteredSection(
                        mastered = state.isMastered,
                        onChange = viewModel::setMastered
                    )

                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = { onEdit(question.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_edit))
                    }
                }
            }
        }
    }

    if (showFullImage && question != null) {
        FullScreenImageDialog(
            imagePath = fullImagePath ?: question.imagePath,
            contentDescription = stringResource(R.string.edit_original_image),
            onDismiss = {
                showFullImage = false
                fullImagePath = null
            }
        )
    }

    if (showReRecognizeDialog) {
        AlertDialog(
            onDismissRequest = { showReRecognizeDialog = false },
            title = { Text(stringResource(R.string.detail_re_recognize)) },
            text = { Text(stringResource(R.string.detail_re_recognize_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 不再直接提交：先带原图进裁剪页，让用户自己裁切/涂鸦，
                        // 裁完才提交识别。原图裁得好的场景居多，但「涂掉半道题」
                        // 「只框这道题」这类修正只有在裁切页里才做得了。
                        showReRecognizeDialog = false
                        question?.let { onReRecognizeWithCrop(it.imagePath) }
                    }
                ) { Text(stringResource(R.string.detail_re_recognize_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showReRecognizeDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.detail_delete)) },
            text = { Text(stringResource(R.string.detail_delete_confirm)) },
            confirmButton = {
                // 两步确认：删除题目不可恢复，确认键再点一次才执行
                com.mistakebook.ui.common.TwoStepConfirmTextButton(
                    text = stringResource(R.string.action_confirm),
                    confirmText = stringResource(R.string.detail_delete_confirm_action),
                    onConfirm = {
                        showDeleteDialog = false
                        viewModel.delete()
                        onBack()
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 变式题弹层：生成中 / 结果预览 / 失败三种形态由 VariantState 单一驱动
    if (variantState.visible && question != null) {
        when {
            variantState.generating -> AlertDialog(
                onDismissRequest = { /* 生成中没有取消路径，忽略误触 */ },
                title = { Text(stringResource(R.string.detail_variant_title)) },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.detail_variant_generating))
                    }
                },
                confirmButton = {}
            )

            variantState.draft != null -> {
                val draft = variantState.draft
                AlertDialog(
                    onDismissRequest = { viewModel.dismissVariant() },
                    title = { Text(stringResource(R.string.detail_variant_title)) },
                    text = {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = draft!!.title,
                                style = MaterialTheme.typography.titleSmall
                            )
                            if (draft.title.isNotBlank()) Spacer(Modifier.height(8.dp))
                            RichText(
                                text = draft.stem,
                                mathRenderer = container.mathRenderer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            draft.options.forEach { option ->
                                RichText(
                                    text = "${option.label}. ${option.text}",
                                    mathRenderer = container.mathRenderer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                            if (draft.answer.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                SectionLabel(stringResource(R.string.edit_answer))
                                RichText(
                                    text = draft.answer,
                                    mathRenderer = container.mathRenderer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (draft.analysis.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                SectionLabel(stringResource(R.string.edit_analysis))
                                RichText(
                                    text = draft.analysis,
                                    mathRenderer = container.mathRenderer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (draft.knowledge_points.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                InfoRow(
                                    label = stringResource(R.string.detail_knowledge_points),
                                    value = draft.knowledge_points.joinToString("、")
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            viewModel.saveVariant { newId ->
                                onVariantSaved(newId)
                            }
                        }) { Text(stringResource(R.string.detail_variant_save)) }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = { viewModel.generateVariant() }) {
                                Text(stringResource(R.string.detail_variant_regenerate))
                            }
                            TextButton(onClick = { viewModel.dismissVariant() }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    }
                )
            }

            else -> AlertDialog(
                onDismissRequest = { viewModel.dismissVariant() },
                title = { Text(stringResource(R.string.detail_variant_failed)) },
                text = { Text(variantState.error.orEmpty()) },
                confirmButton = {
                    TextButton(onClick = { viewModel.generateVariant() }) {
                        Text(stringResource(R.string.detail_variant_regenerate))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissVariant() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

/**
 * 「是否会了」开关。
 *
 * 原来是「会 / 模糊 / 不会」三按钮 + 艾宾浩斯轮次 + 打卡历史时间线，标签太多反而鸡肋。
 * 现在只留一个开关：打开=会了（不再进待复习队列与每日提醒），关闭=不会（次日重新提醒）。
 */
@Composable
private fun MasteredSection(
    mastered: Boolean,
    onChange: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.detail_mastered_switch),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = mastered, onCheckedChange = onChange)
        }
    }
}

/**
 * 辅助图区块（F-IMG-4）：三态——生成中 / 已有图 / 未生成（或失败）。
 *
 * ## 反馈纪律
 *
 * 生图是 5~15 秒的操作，生成中必须转 spinner：
 * 没有任何反馈的话用户会以为按钮坏了而连点，而连点会真的多烧几次额度
 * （生图 API 按次计费）。
 *
 * 失败态可关闭：错误留在屏幕上会挡住解析正文，
 * 但图本身留在磁盘缓存里——下次进这道题还在。
 */
@Composable
private fun AuxImageBlock(
    auxState: AuxImageState,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    when {
        auxState.generating -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.detail_aux_generating),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        auxState.path != null -> {
            Column {
                AsyncImage(
                    model = auxState.path,
                    contentDescription = stringResource(R.string.detail_aux_image),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                )
                if (!auxState.prompt.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = auxState.prompt,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        auxState.error != null -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = auxState.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.detail_aux_dismiss))
                }
            }
        }

        else -> {
            OutlinedButton(
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.detail_aux_generate))
            }
        }
    }
}
