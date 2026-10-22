@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.mistakebook.pomodoro

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import com.mistakebook.di.AppContainer
import com.mistakebook.ui.common.containerViewModel

/**
 * 番茄钟页面。
 *
 * ## 布局顺序是按「一次专注的动作顺序」排的
 *
 * 选任务 → 选模式 → 看时间 → 按开始 → 看统计。
 * 统计放在最下面：它是**事后回看**的东西，抢在计时器前面会把主链路挤碎。
 *
 * ## 审美上刻意做的克制
 *
 * - 全屏只有一个强调色（主题 primary），其余全走中性灰阶；
 * - 大数字用等宽字形（`tnum`），否则秒数每跳一次整串字都在抖；
 * - 进度环是**一根线**，不加渐变、不加呼吸动效——计时器不是装饰品；
 * - 层次靠容器色阶（surfaceContainerLow/High），不用阴影。
 */
@Composable
fun PomodoroScreen(container: AppContainer) {
    val viewModel: PomodoroViewModel = containerViewModel(container) { c ->
        PomodoroViewModel(
            app = c.appContext as android.app.Application,
            dao = c.pomodoroDao,
            timer = c.pomodoroTimer,
            subjectsFlow = c.subjectsFlow
        )
    }
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.consumeMessage()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ModeSegmented(
                current = state.mode,
                running = state.status == RunStatus.RUNNING,
                onSelect = viewModel::setMode
            )

            Spacer(Modifier.height(20.dp))

            TimerDial(
                mode = state.mode,
                status = state.status,
                targetMs = state.targetMs,
                // 只有表盘订阅秒读数：整棵树的重组范围因此不随时间跳动
                elapsedFlow = viewModel.tick,
                initialElapsed = state.elapsedMs,
                onAdjust = viewModel::adjustTarget
            )

            Spacer(Modifier.height(20.dp))

            TaskPicker(
                tasks = state.tasks,
                subjects = state.subjects,
                subjectFilter = state.subjectFilter,
                selectedTaskId = state.selectedTaskId,
                running = state.status == RunStatus.RUNNING,
                onSelectSubject = viewModel::selectSubject,
                onSelectTask = viewModel::selectTask,
                onNewTask = { viewModel.openNewTask(true) }
            )

            if (state.showNewTask) {
                Spacer(Modifier.height(12.dp))
                NewTaskCard(
                    name = state.newTaskName,
                    subjects = state.subjects,
                    subjectId = state.newTaskSubjectId,
                    onName = viewModel::onNewTaskName,
                    onSubject = viewModel::onNewTaskSubject,
                    onConfirm = viewModel::createTask,
                    onCancel = { viewModel.openNewTask(false) }
                )
            }

            Spacer(Modifier.height(24.dp))

            PrimaryAction(
                status = state.status,
                finished = state.finished,
                onStart = viewModel::start,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onCycleMode = viewModel::cycleMode
            )

            if (state.status != RunStatus.IDLE) {
                Spacer(Modifier.height(12.dp))
                Row {
                    TextButton(onClick = { viewModel.stop(save = true) }) {
                        Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("结束并记录")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { viewModel.stop(save = false) }) {
                        Text("放弃", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            StatsSection(state = state)

            Spacer(Modifier.height(24.dp))
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

// =========================================================================
// 模式选择
// =========================================================================

@Composable
private fun ModeSegmented(
    current: FocusMode,
    running: Boolean,
    onSelect: (FocusMode) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FocusMode.entries.forEach { mode ->
                val selected = mode == current
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest
                            else Color.Transparent,
                            shape = RoundedCornerShape(20.dp)
                        )
                        .combinedClickable(
                            enabled = !running,
                            onClick = { onSelect(mode) }
                        )
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// =========================================================================
// 计时表盘
// =========================================================================

@Composable
private fun TimerDial(
    mode: FocusMode,
    status: RunStatus,
    targetMs: Long,
    elapsedFlow: StateFlow<Long>,
    initialElapsed: Long,
    onAdjust: (Int) -> Unit
) {
    val elapsedMs by elapsedFlow.collectAsStateWithLifecycle(initialValue = initialElapsed)
    val progress = if (targetMs > 0L) (elapsedMs.toFloat() / targetMs).coerceIn(0f, 1f) else 0f
    // 进度每秒才跳一次，400ms 的补间只会让这一秒内持续逐帧重组——
    // 计时数字就该**直接跳**，补间在这里是纯粹的浪费。
    val animated = progress
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val progressColor = MaterialTheme.colorScheme.primary
    val remaining = if (targetMs > 0L) (targetMs - elapsedMs).coerceAtLeast(0L) else 0L
    val mainText = if (targetMs > 0L) formatClock(remaining) else formatClock(elapsedMs)

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(252.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
            val inset = 10.dp.toPx() / 2f
            val diameter = size.minDimension - 10.dp.toPx()
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
                topLeft = Offset(inset, inset),
                size = Size(diameter, diameter)
            )
            if (mode.hasTarget) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(inset, inset),
                    size = Size(diameter, diameter)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = mainText,
                // 等宽数字：秒数每跳一次不再整串抖动，这是计时器的基本功
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = if (mainText.length > 5) 44.sp else 54.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                    letterSpacing = (-1).sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = when {
                    status == RunStatus.IDLE && mode.hasTarget -> "目标 ${targetMs / 60_000L} 分钟"
                    status == RunStatus.PAUSED -> "已暂停"
                    mode.hasTarget -> "剩余"
                    else -> "已专注"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (mode.hasTarget && status == RunStatus.IDLE) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stepper("-5") { onAdjust(-5) }
            Spacer(Modifier.width(10.dp))
            Stepper("+5") { onAdjust(5) }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "长按下方按钮也可换模式",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Stepper(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.combinedClickable(onClick = onClick)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// =========================================================================
// 任务选择：学科 → 任务
// =========================================================================

@Composable
private fun TaskPicker(
    tasks: List<com.mistakebook.data.local.entities.PomodoroTask>,
    subjects: List<com.mistakebook.data.local.entities.Subject>,
    subjectFilter: Long?,
    selectedTaskId: Long?,
    running: Boolean,
    onSelectSubject: (Long?) -> Unit,
    onSelectTask: (Long?) -> Unit,
    onNewTask: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 一级：学科（含「全部」）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SubjectChip(
                name = "全部",
                selected = subjectFilter == null,
                onClick = { onSelectSubject(null) }
            )
            subjects.forEach { subject ->
                SubjectChip(
                    name = subject.name,
                    selected = subjectFilter == subject.id,
                    onClick = { onSelectSubject(subject.id) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 二级：该学科下的任务
        val visible = tasks.filter { subjectFilter == null || it.subjectId == subjectFilter }
        if (visible.isEmpty()) {
            Text(
                text = "这个学科下还没有任务",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                visible.forEach { task ->
                    FilterChip(
                        selected = task.id == selectedTaskId,
                        onClick = { onSelectTask(task.id) },
                        enabled = !running,
                        label = { Text(task.name) },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onNewTask) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("新建任务")
        }
    }
}

@Composable
private fun SubjectChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
        modifier = Modifier.combinedClickable(onClick = onClick)
    ) {
        Text(
            text = name,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NewTaskCard(
    name: String,
    subjects: List<com.mistakebook.data.local.entities.Subject>,
    subjectId: Long?,
    onName: (String) -> Unit,
    onSubject: (Long?) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                label = { Text("任务名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "归到哪个学科（可不选）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubjectChip(name = "不分类", selected = subjectId == null, onClick = { onSubject(null) })
                subjects.forEach { s ->
                    SubjectChip(
                        name = s.name,
                        selected = subjectId == s.id,
                        onClick = { onSubject(s.id) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConfirm) { Text("创建") }
            }
        }
    }
}

// =========================================================================
// 主按钮
// =========================================================================

@Composable
private fun PrimaryAction(
    status: RunStatus,
    finished: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCycleMode: () -> Unit
) {
    val label = when {
        status == RunStatus.RUNNING -> "暂停"
        status == RunStatus.PAUSED -> "继续"
        finished -> "再来一次"
        else -> "开始"
    }
    val icon = when {
        status == RunStatus.RUNNING -> Icons.Outlined.Pause
        else -> Icons.Outlined.PlayArrow
    }
    val onClick = when {
        status == RunStatus.RUNNING -> onPause
        status == RunStatus.PAUSED -> onResume
        else -> onStart
    }

    // 不用 Button 再叠一层 combinedClickable：那样点击会走两次（外层 onClick
    // 与内层手势重叠），长按与单击的边界也不干净。直接用 Surface 承载手势。
    Surface(
        // 长按切换计时模式：用户点名要的快捷手势。
        // 只在非运行态生效（运行中切换会打断这一次计时，ViewModel 会拦下），
        // 顶部另有一组可见的模式按钮，不依赖这个隐藏手势。
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .combinedClickable(onClick = onClick, onLongClick = onCycleMode),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

// =========================================================================
// 统计
// =========================================================================

@Composable
private fun StatsSection(state: PomodoroUiState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "统计",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCell("今日专注", formatDuration(state.todaySec), Modifier.weight(1f))
            StatCell("今日番茄", "${state.todayPomodoros} 个", Modifier.weight(1f))
            StatCell("连续天数", "${state.streakDays} 天", Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "近 7 天",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Last7Bars(values = state.last7)

        Spacer(Modifier.height(16.dp))

        if (state.taskTotals.isNotEmpty()) {
            Text(
                text = "任务累计",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            state.taskTotals.filter { it.sec > 0 }.take(5).forEach { row ->
                TaskBar(name = row.taskName, sec = row.sec, max = state.taskTotals.first().sec)
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "累计专注 ${formatDuration(state.totalSec)} · 完成 ${state.totalCompleted} 次",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Last7Bars(values: List<Long>) {
    val max = values.maxOrNull()?.coerceAtLeast(60L) ?: 60L
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        values.asReversed().forEach { sec ->
            val ratio = (sec.toFloat() / max.toFloat()).coerceIn(0f, 1f)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((4 + ratio * 60).dp)
                        .background(
                            color = if (sec > 0L) color else track,
                            shape = RoundedCornerShape(4.dp)
                        )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (sec > 0L) "${(sec / 60).coerceAtLeast(1)}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TaskBar(name: String, sec: Long, max: Long) {
    val ratio = if (max <= 0L) 0f else (sec.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatDuration(sec),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

// =========================================================================
// 格式化
// =========================================================================

private fun formatClock(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format("%d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}

private fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}小时${m}分" else "${m}分钟"
}
