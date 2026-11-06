package com.mistakebook.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.ui.common.SwipeActionBlock
import com.mistakebook.ui.common.SwipeActionBlockWidth
import com.mistakebook.ui.common.swipeRevealContentAlpha
import com.mistakebook.ui.common.swipeRevealProgress
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.local.displayTitle
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.MasteryStatus
import com.mistakebook.ui.common.EmptyState
import com.mistakebook.ui.print.SubjectDropdown
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 已归档内容的入口（v0.0.126 加入番茄钟任务，issue #37）。
 *
 * 三者的语义完全一致：**归档 ≠ 删除**，都能在这里找回。
 */
enum class ArchivedKind { QUESTIONS, SESSIONS, POMODORO_TASKS }

/**
 * 已归档内容（v0.0.104）。
 *
 * ## 归档 ≠ 删除
 * 删除是「扔掉」，进回收站、30 天后物理清理；归档是「我收起来了」，
 * 随时能拿回来。这一轮把主列表里的删除**换成**归档，所以用户再也不必
 * 「为了不让某道题消失而不敢删」。
 *
 * - 已归档题目：能按学科（标签）、掌握程度、关键词筛——「上次那个数学的
 *   错题在哪」是最常见的找法；
 * - 已归档会话：什么都不用做，按归档时间倒序，最近归档的排最前；
 * - 两个列表的行都左滑：**恢复**与**删除**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedItemsScreen(container: AppContainer, kind: ArchivedKind, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (kind) {
                                ArchivedKind.QUESTIONS -> R.string.archived_questions_title
                                ArchivedKind.SESSIONS -> R.string.archived_sessions_title
                                ArchivedKind.POMODORO_TASKS -> R.string.archived_tasks_title
                            }
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        when (kind) {
            ArchivedKind.QUESTIONS -> ArchivedQuestionsList(container, padding)
            ArchivedKind.SESSIONS -> ArchivedSessionsList(container, padding)
            ArchivedKind.POMODORO_TASKS -> ArchivedPomodoroTasksList(container, padding)
        }
    }
}

@Composable
private fun ArchivedQuestionsList(container: AppContainer, padding: PaddingValues) {
    var subjectId by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf<MasteryStatus?>(null) }
    var keyword by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val subjects by container.subjectRepository.observeAll()
        .collectAsState(initial = emptyList<com.mistakebook.data.local.entities.Subject>())

    val questions by container.questionRepository.observeArchived(
        statusName = status?.name.orEmpty(),
        subjectFilter = subjectId ?: 0L,
        reasonName = "",
        keyword = keyword.trim()
    ).collectAsState(initial = emptyList<Question>())

    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        // 筛选：学科（标签）+ 掌握程度 + 关键词
        SubjectDropdown(
            subjects = subjects,
            selectedId = subjectId,
            onSelect = { subjectId = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = status == null,
                onClick = { status = null },
                label = { Text(stringResource(R.string.archived_filter_all)) }
            )
            MasteryStatus.entries.forEach { value ->
                FilterChip(
                    selected = status == value,
                    onClick = { status = if (status == value) null else value },
                    label = { Text(value.label) }
                )
            }
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))

        if (questions.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.archived_empty_questions),
                subtitle = ""
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(questions, key = { it.id }) { question ->
                ArchiveSwipeRow(
                    onRestore = {
                        scope.launch { container.questionRepository.unarchive(question.id) }
                    },
                    onDelete = {
                        scope.launch { container.questionRepository.softDelete(question.id) }
                    }
                ) {
                    QuestionArchiveCard(question = question)
                }
            }
        }
    }
}

@Composable
private fun ArchivedSessionsList(container: AppContainer, padding: PaddingValues) {
    val scope = rememberCoroutineScope()
    val sessions by container.chatRepository.observeArchivedSessions()
        .collectAsState(initial = emptyList<com.mistakebook.data.local.entities.ChatSession>())

    if (sessions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            EmptyState(
                title = stringResource(R.string.archived_empty_sessions),
                subtitle = ""
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sessions, key = { it.id }) { session ->
            ArchiveSwipeRow(
                onRestore = { scope.launch { container.chatRepository.unarchiveSession(session.id) } },
                onDelete = { scope.launch { container.chatRepository.softDeleteSession(session.id) } }
            ) {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            text = session.title.ifBlank { stringResource(R.string.chat_untitled) },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = session.lastPreview.ifBlank { stringResource(R.string.chat_list_empty) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.archived_at, formatTime(session.archivedAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 已归档的番茄钟任务（v0.0.126，issue #37）。
 *
 * 用户：「任务归档后没有地方能找到啊，不能恢复了」。归档在设计上只是
 * 「从选择器里收起来、历史记录保留」，但从没有任何界面能看到它——
 * 等于单向删除。这里按 [ArchiveSwipeRow] 的同一套交互给出恢复与删除：
 * - **恢复**：`archived = 0`，任务回到学科列表，历史记录原样还在；
 * - **删除**：连同该任务的全部专注记录一起物理删除（弹窗两步确认，
 *   与编辑面板里的删除同口径——归档可以后悔，删除不能）。
 */
@Composable
private fun ArchivedPomodoroTasksList(container: AppContainer, padding: PaddingValues) {
    val scope = rememberCoroutineScope()
    val tasks by container.pomodoroDao.observeArchivedTasks()
        .collectAsState(initial = emptyList<com.mistakebook.data.local.entities.PomodoroTask>())
    var pendingDelete by remember { mutableStateOf<com.mistakebook.data.local.entities.PomodoroTask?>(null) }

    if (tasks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            EmptyState(
                title = stringResource(R.string.archived_empty_tasks),
                subtitle = ""
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tasks, key = { it.id }) { task ->
            ArchiveSwipeRow(
                onRestore = { scope.launch { container.pomodoroDao.unarchiveTask(task.id) } },
                onDelete = { pendingDelete = task }
            ) {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            text = task.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.archived_task_keep_history),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.archived_task_delete_title)) },
            text = {
                Text(stringResource(R.string.archived_task_delete_body, task.name))
            },
            confirmButton = {
                com.mistakebook.ui.common.TwoStepConfirmTextButton(
                    text = stringResource(R.string.action_delete),
                    confirmText = stringResource(R.string.archived_task_delete_confirm),
                    onConfirm = {
                        pendingDelete = null
                        scope.launch { container.pomodoroDao.deleteTask(task.id) }
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun QuestionArchiveCard(question: Question) {
    Card(
        shape = MaterialTheme.shapes.medium,
        // 与首页题目卡同一套面：surfaceContainerLow + 1dp 描边。
        // 原先的 surface 无描边在浅色底上等于一张看不见边界的白卡，
        // 用户滑开归档页的第一眼会以为列表是空的。
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                // 用 displayTitle 而不是 stem：stem 里带 $...$ 的公式原样摊出来
                // 就是「$f(x)=x^2+2x$」这种源码，和首页 / 详情页的观感对不上。
                text = question.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.archived_at, formatTime(question.archivedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 可左滑揭示两个操作的内容行（恢复 / 删除）。
 *
 * 为什么单独抽出来：归档题目与归档会话两个列表都要「左滑出操作」，
 * 各自抄一份滑动手势就是两份同样的代码；放在这里，两边共用一套。
 */
@Composable
private fun ArchiveSwipeRow(
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val revealWidthPx = with(density) { (SwipeActionBlockWidth * 2).toPx() }
    // 用纯 State + 横向手势，不用 Animatable：
    // 松手直接归位（开或关），少一层动画状态机，也就少一类「手势与动画抢状态」的坑。
    var offset by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    // 外层的 clip + surface 底与对话历史抽屉同一口径：色块要「长」在卡片里，
    // 由父级圆角统一裁外沿，越界的方块才不会是直角的。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            // v0.0.116：原来是「按内容高度居中」，色块只有图标加一行字那么高，
            // 滑开是一小块飘在卡片中间；对话历史用 matchParentSize() 恰好等于
            // 卡片高度，饱满得多。归档页跟着对齐——三处左滑必须一套观感。
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    val progress = swipeRevealProgress(offset, revealWidthPx)
                    alpha = swipeRevealContentAlpha(progress)
                },
            horizontalArrangement = Arrangement.End
        ) {
            SwipeActionBlock(
                width = SwipeActionBlockWidth,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = Icons.Default.Restore,
                label = stringResource(R.string.archived_restore),
                // 滑开前点不到（被卡片挡住），显式收紧与另两处一致
                enabled = offset < -revealWidthPx * 0.35f,
                onClick = {
                    offset = 0f
                    scope.launch { onRestore() }
                }
            )
            SwipeActionBlock(
                width = SwipeActionBlockWidth,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                icon = Icons.Default.Delete,
                label = stringResource(R.string.archived_delete),
                enabled = offset < -revealWidthPx * 0.35f,
                onClick = {
                    offset = 0f
                    scope.launch { onDelete() }
                }
            )
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .pointerInput(revealWidthPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            offset = if (offset < -revealWidthPx * 0.35f) -revealWidthPx else 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            offset = (offset + dragAmount).coerceIn(-revealWidthPx, 0f)
                        }
                    )
                }
        ) {
            content()
        }
    }
}

private val ARCHIVE_TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")

private fun formatTime(millis: Long?): String {
    if (millis == null) return "—"
    return ARCHIVE_TIME_FMT.format(
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    )
}
