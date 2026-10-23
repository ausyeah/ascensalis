@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.mistakebook.pomodoro

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import com.mistakebook.data.local.entities.PomodoroTask
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.di.AppContainer
import com.mistakebook.pomodoro.stats.StatsRange
import com.mistakebook.ui.common.containerViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 番茄钟页面。
 *
 * ## 三个 Tab 的分工
 *
 * 番茄钟 / 正向计时是**两种心智模型**（有终点 vs 没有终点），各占一页；
 * 统计数据单独一页。旧版的「定时」模式与番茄钟完全是同一个东西
 * （都是倒计时，只是默认时长不同），已合并——想定时 45 分钟，
 * 在番茄钟页把轮盘拨到 45 即可。
 *
 * ## 布局顺序是按「一次专注的动作顺序」排的
 *
 * 看时间 → 选任务 → 按开始。统计被挪到独立 Tab：它是**事后回看**
 * 的东西，混在计时页里会把主链路挤碎。
 *
 * ## 计时控件为什么在表盘里
 *
 * 「暂停 / 结束并记录」是计时途中最高频的两个操作，放在屏幕最底部
 * 等于让用户每次暂停都多伸一次拇指。它们贴在居中时间的正下方，
 * 「放弃」另起一行——常用的一步够到，破坏性的远一步。
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
            subjectDao = c.subjectDao,
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

    // Tab 状态：0 番茄钟 / 1 正向计时 / 2 统计。
    // 与 FocusMode 同步——切 Tab 就是切模式（运行中被 VM 拦下并提示）。
    var tab by remember { mutableIntStateOf(if (state.mode == FocusMode.STOPWATCH) 1 else 0) }

    LaunchedEffect(state.mode) {
        // 长按主按钮循环模式时，Tab 指示器跟着走
        tab = if (state.mode == FocusMode.STOPWATCH) 1 else 0
    }

    // CSV 导入：系统文件选择器，选完交给 VM 解析落库
    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importCsv(uri)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = tab == 0,
                    onClick = { viewModel.setMode(FocusMode.POMODORO) },
                    enabled = state.status != RunStatus.RUNNING,
                    text = { Text("番茄钟") }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { viewModel.setMode(FocusMode.STOPWATCH) },
                    enabled = state.status != RunStatus.RUNNING,
                    text = { Text("正向计时") }
                )
                Tab(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    text = { Text("统计数据") }
                )
            }

            when (tab) {
                0, 1 -> TimerTab(
                    state = state,
                    viewModel = viewModel,
                    elapsedFlow = viewModel.tick
                )
                else -> StatsTab(
                    state = state,
                    onRangeChange = viewModel::setStatsRange,
                    onResetStats = viewModel::resetStats,
                    onImportCsv = {
                        csvLauncher.launch(
                            arrayOf(
                                "text/comma-separated-values",
                                "text/csv",
                                "text/plain",
                                "*/*"
                            )
                        )
                    }
                )
            }
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

// =========================================================================
// Tab 1 / Tab 2：计时页
// =========================================================================

@Composable
private fun TimerTab(
    state: PomodoroUiState,
    viewModel: PomodoroViewModel,
    elapsedFlow: StateFlow<Long>
) {
    // 轮盘对话框：点表盘数字才开
    var showWheel by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TimerDial(
            mode = state.mode,
            status = state.status,
            targetMs = state.targetMs,
            // 只有表盘订阅秒读数：整棵树的重组范围因此不随时间跳动
            elapsedFlow = elapsedFlow,
            initialElapsed = state.elapsedMs,
            onPickTime = { showWheel = true },
            onStart = viewModel::start,
            onPause = viewModel::pause,
            onResume = viewModel::resume,
            onCycleMode = viewModel::cycleMode,
            onStopSave = { viewModel.stop(save = true) }
        )

        // 「放弃」另起一行：与表盘里的暂停/结束拉开距离，
        // 误触扔掉的是一次专注，不是一点滑动。
        if (state.status != RunStatus.IDLE) {
            TextButton(onClick = { viewModel.stop(save = false) }) {
                Text("放弃本次计时", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(16.dp))

        SubjectTaskBoard(
            tasks = state.tasks,
            subjects = state.subjects,
            selectedTaskId = state.selectedTaskId,
            running = state.status == RunStatus.RUNNING,
            onSelectTask = viewModel::selectTask,
            onStartTask = { id ->
                viewModel.selectTask(id)
                viewModel.start()
            },
            onEditTask = viewModel::editTask,
            onNewTask = { subjectId ->
                viewModel.onNewTaskSubject(subjectId)
                viewModel.openNewTask(true)
            },
            onNewSubject = { viewModel.openNewSubject(true) },
            showNewSubject = state.showNewSubject,
            newSubjectName = state.newSubjectName,
            onNewSubjectName = viewModel::onNewSubjectName,
            onCreateSubject = viewModel::createSubject,
            onCancelNewSubject = { viewModel.openNewSubject(false) },
            onEditSubject = viewModel::editSubject
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
    }

    if (showWheel) {
        DurationWheelDialog(
            initialMinutes = (state.targetMs / 60_000L).toInt().coerceAtLeast(1),
            onDismiss = { showWheel = false },
            onConfirm = { minutes ->
                viewModel.setTargetMinutes(minutes)
                showWheel = false
            }
        )
    }

    // 长按任务弹出的编辑面板
    state.editingTask?.let { task ->
        EditTaskDialog(
            task = task,
            subjects = state.subjects,
            onName = viewModel::onEditTaskName,
            onSubject = viewModel::onEditTaskSubject,
            onSave = viewModel::saveEditedTask,
            onArchive = { viewModel.archiveTask(task.id) },
            onDelete = { viewModel.deleteTask(task.id) },
            onDismiss = viewModel::closeEditTask
        )
    }

    // 长按学科弹出的编辑面板
    state.editingSubject?.let { subject ->
        EditSubjectDialog(
            subject = subject,
            onName = viewModel::onEditSubjectName,
            onSave = viewModel::saveEditedSubject,
            onDelete = { viewModel.deleteSubject(subject.id) },
            onDismiss = viewModel::closeEditSubject
        )
    }
}

// =========================================================================
// 计时表盘（含主操作：暂停/结束在时间正下方）
// =========================================================================

@Composable
private fun TimerDial(
    mode: FocusMode,
    status: RunStatus,
    targetMs: Long,
    elapsedFlow: StateFlow<Long>,
    initialElapsed: Long,
    onPickTime: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCycleMode: () -> Unit,
    onStopSave: () -> Unit
) {
    val elapsedMs by elapsedFlow.collectAsStateWithLifecycle(initialValue = initialElapsed)
    val progress = if (targetMs > 0L) (elapsedMs.toFloat() / targetMs).coerceIn(0f, 1f) else 0f
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val progressColor = MaterialTheme.colorScheme.primary
    val remaining = if (targetMs > 0L) (targetMs - elapsedMs).coerceAtLeast(0L) else 0L
    val mainText = if (targetMs > 0L) formatClock(remaining) else formatClock(elapsedMs)

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(264.dp)) {
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
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(inset, inset),
                    size = Size(diameter, diameter)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // 倒计时且空闲：点数字拨轮盘自定义时长（用户点名要的交互）。
            // 正计时没有目标可设，点了也没反应，所以不加点击热区——
            // 点了没反应比不能点更糟。
            modifier = if (mode.hasTarget && status == RunStatus.IDLE) {
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .combinedClickable(onClick = onPickTime)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            } else {
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            }
        ) {
            Text(
                text = mainText,
                // 等宽数字：秒数每跳一次不再整串抖动，这是计时器的基本功
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = if (mainText.length > 5) 40.sp else 50.sp,
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
                    status == RunStatus.IDLE && mode.hasTarget -> "目标 ${targetMs / 60_000L} 分钟 · 点此调整"
                    status == RunStatus.PAUSED -> "已暂停"
                    mode.hasTarget -> "剩余"
                    else -> "已专注"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))

            // ---- 主操作：时间正下方 ----
            when (status) {
                RunStatus.IDLE -> {
                    // 开始：长按循环切换模式（隐藏手势，顶部另有可见 Tab）
                    Surface(
                        modifier = Modifier
                            .height(44.dp)
                            .widthIn(min = 132.dp)
                            .combinedClickable(onClick = onStart, onLongClick = onCycleMode),
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("开始", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(
                            onClick = if (status == RunStatus.RUNNING) onPause else onResume,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Icon(
                                if (status == RunStatus.RUNNING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (status == RunStatus.RUNNING) "暂停" else "继续", fontSize = 13.sp)
                        }
                        FilledTonalButton(
                            onClick = onStopSave,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier.height(38.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("结束并记录", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// 时长滚轮（竖向、无限循环，苹果闹钟手感）
// =========================================================================

/**
 * 时长轮盘对话框：小时 0–12、分钟 0–59，两列各自**无限循环**竖向滑动。
 *
 * ## 为什么是竖向
 *
 * 时间是「从上往下拨」的——闹钟、计时器、飞机表盘全是竖向滚轮。
 * 横向拨轮拨的是「挑选」，竖向拨轮拨的是「设定」，手感不能反。
 *
 * ## 无限循环怎么做的
 *
 * 真实取值范围各重复 [WHEEL_REPEAT] 份，初始位置停在正中间那一份。
 * 滑到接近边缘时用户早就滑回来了——2600 / 12000 个 item 的 LazyColumn
 * 只组合可见的那几个，成本与 13 / 60 个 item 完全一样。真要滑到天边
 * 之外，把 [WHEEL_REPEAT] 调大即可，交互语义不变。
 *
 * 选中值 = 距轮盘中线最近的 item 的下标 % 真实数量，与滚动位置天然同步，
 * 不需要额外记「当前选中第几项」的状态。
 */
@Composable
private fun DurationWheelDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val hours = remember { (0..12).toList() }
    val minutes = remember { (0..59).toList() }
    val initH = initialMinutes / 60
    val initM = initialMinutes % 60

    val hourState = rememberLazyListState(
        initialFirstVisibleItemIndex = WHEEL_REPEAT / 2 * hours.size + initH
    )
    val minuteState = rememberLazyListState(
        initialFirstVisibleItemIndex = WHEEL_REPEAT / 2 * minutes.size + initM
    )

    // 当前选中的真实值：每帧重算太浪费，只在滚动中/停下时变——
    // derivedStateOf 依赖的是 layoutInfo，滚动停了对它求值也不重组。
    val selectedHour by remember {
        derivedStateOf { centeredWheelValue(hourState, hours.size) }
    }
    val selectedMinute by remember {
        derivedStateOf { centeredWheelValue(minuteState, minutes.size) }
    }
    val totalMinutes = selectedHour * 60 + selectedMinute

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置时长") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 预览行：把两列的选择合成一句人话
                Text(
                    text = if (totalMinutes >= 60) {
                        "${selectedHour} 小时 ${selectedMinute} 分钟"
                    } else {
                        "${selectedMinute} 分钟"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WheelColumn(
                        values = hours,
                        state = hourState,
                        label = "小时",
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = ":",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    WheelColumn(
                        values = minutes,
                        state = minuteState,
                        label = "分钟",
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "上下滑动，循环选择",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(totalMinutes) },
                // 0 小时 0 分钟不是时长：拨到全零时确定不可点，
                // 比让用户点完才弹一个「时长不能为 0」好。
                enabled = totalMinutes >= 1
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 单列竖向滚轮。 */
@Composable
private fun WheelColumn(
    values: List<Int>,
    state: androidx.compose.foundation.lazy.LazyListState,
    label: String,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = modifier.height(180.dp), contentAlignment = Alignment.Center) {
        // 选中带的底：一条居中横带，把「选中项」这件事画出来
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .height(48.dp)
                .fillMaxWidth()
        ) {}

        // 上下渐隐：让轮盘有「圆柱」的纵深感，而不是一列平板数字
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            Color.Transparent,
                            Color.Transparent,
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
        )

        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            items(
                count = values.size * WHEEL_REPEAT,
                key = { index -> index }
            ) { index ->
                val value = values[index % values.size]
                val selected = centeredWheelValue(state, values.size) == value &&
                    isItemCentered(state, index)
                // 选中项放大、主色、加粗；其余按距中线距离淡出
                val centerOffsetPx = wheelCenterOffset(state, index)
                val distance = abs(centerOffsetPx)
                val scale = (1f - (distance / 500f)).coerceIn(0.7f, 1.2f)
                val alpha = (1f - (distance / 700f)).coerceIn(0.25f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = String.format("%02d", value),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontSize = 22.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (selected) primary else onSurfaceVariant.copy(alpha = alpha),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        }
                    )
                }
            }
        }

        // 单位标签贴在轮盘右上角，不占轮盘高度
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = onSurface.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
        )
    }
}

/** 轮盘数据重复份数：够滑几千屏，又不会让下标溢出 Int。 */
private const val WHEEL_REPEAT = 200

/** 当前居中 item 的真实值（下标 % 真实数量）。 */
private fun centeredWheelValue(
    state: androidx.compose.foundation.lazy.LazyListState,
    realCount: Int
): Int {
    val center = (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2
    var bestIndex = state.firstVisibleItemIndex
    var bestDistance = Int.MAX_VALUE
    state.layoutInfo.visibleItemsInfo.forEach { item ->
        val itemCenter = item.offset + item.size / 2
        val distance = abs(itemCenter - center)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = item.index
        }
    }
    return bestIndex % realCount
}

/** 指定下标是否就是居中那一个（用于加粗放大）。 */
private fun isItemCentered(
    state: androidx.compose.foundation.lazy.LazyListState,
    index: Int
): Boolean {
    val center = (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2
    var bestIndex = state.firstVisibleItemIndex
    var bestDistance = Int.MAX_VALUE
    state.layoutInfo.visibleItemsInfo.forEach { item ->
        val itemCenter = item.offset + item.size / 2
        val distance = abs(itemCenter - center)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = item.index
        }
    }
    return bestIndex == index
}

/** item 中心相对轮盘中线的偏移（px）。 */
private fun wheelCenterOffset(
    state: androidx.compose.foundation.lazy.LazyListState,
    index: Int
): Int {
    val center = (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2
    val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return Int.MAX_VALUE
    return item.offset + item.size / 2 - center
}

// =========================================================================
// 学科 → 任务看板
// =========================================================================

/**
 * 学科横条 + 展开的任务列表。
 *
 * 交互（按用户原话）：
 * - 每个学科一条横条，点它展开下面的细分任务；
 * - 任务行最右边一个「开始」按钮，点了直接拿这个任务开计时；
 * - 长按任务弹编辑（改名 / 换学科 / 归档 / 删除）；
 * - 长按学科弹编辑（改名 / 删除）——学科不是只能建不能改；
 * - 「新建学科」点了之后，输入框**就地展开在学科列表末尾**，
 *   不再甩到页面最底部：学科多的时候，输入框跟着列表走，
 *   建完立刻就能在旁边的列表里看见它。
 *
 * 未分类任务归在「未分类」横条下——允许任务不属于任何学科，
 * 「随手计个时」不该被逼先建一个学科。
 */
@Composable
private fun SubjectTaskBoard(
    tasks: List<PomodoroTask>,
    subjects: List<Subject>,
    selectedTaskId: Long?,
    running: Boolean,
    onSelectTask: (Long?) -> Unit,
    onStartTask: (Long) -> Unit,
    onEditTask: (PomodoroTask) -> Unit,
    onNewTask: (Long?) -> Unit,
    onNewSubject: () -> Unit,
    showNewSubject: Boolean,
    newSubjectName: String,
    onNewSubjectName: (String) -> Unit,
    onCreateSubject: () -> Unit,
    onCancelNewSubject: () -> Unit,
    onEditSubject: (Subject) -> Unit
) {
    // 学科累计时长（按学科聚合的统计里有），任务数实时数
    val taskTotals = remember(tasks) {
        tasks.groupBy { it.subjectId }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 学科横条：先固定学科，再「未分类」（有未分类任务时才显示）
        val groups: List<Triple<String, Long?, Subject?>> =
            subjects.map { Triple(it.name, it.id, it) } +
                if (tasks.any { it.subjectId == null }) listOf(Triple("未分类", null, null)) else emptyList()

        var expanded by remember { mutableStateOf<String?>(null) }

        groups.forEach { (name, subjectId, subject) ->
            val subjectTasks = taskTotals[subjectId].orEmpty()
            val isExpanded = expanded == name
            SubjectBar(
                name = name,
                taskCount = subjectTasks.size,
                expanded = isExpanded,
                // 未分类是虚拟分组，没有实体可编辑
                onToggle = { expanded = if (isExpanded) null else name },
                onLongClick = subject?.let { s -> { onEditSubject(s) } }
            )
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp)) {
                    if (subjectTasks.isEmpty()) {
                        Text(
                            text = "这个学科下还没有任务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        subjectTasks.forEach { task ->
                            TaskRow(
                                task = task,
                                selected = task.id == selectedTaskId,
                                running = running,
                                onClick = { onSelectTask(task.id) },
                                onStart = { onStartTask(task.id) },
                                onEdit = { onEditTask(task) }
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    TextButton(onClick = { onNewTask(subjectId) }) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新建任务")
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // 新建学科：点了之后按钮原地变成输入行（展开在列表末尾），
        // 取消或建成后恢复按钮。绝不再出现「甩到页面最底部」的输入卡。
        if (showNewSubject) {
            InlineSubjectEditor(
                name = newSubjectName,
                onName = onNewSubjectName,
                onConfirm = onCreateSubject,
                onCancel = onCancelNewSubject
            )
        } else {
            TextButton(onClick = onNewSubject) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建学科")
            }
        }
    }
}

/**
 * 就地展开的「新建学科」输入行。
 *
 * 自动获焦并弹键盘：用户点「新建学科」的意图就是打字，
 * 让他再点一次输入框是多一步纯浪费。回车直接创建。
 */
@Composable
private fun InlineSubjectEditor(
    name: String,
    onName: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                label = { Text("新学科名") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onCancel) { Text("取消") }
            Button(onClick = onConfirm, enabled = name.isNotBlank()) { Text("创建") }
        }
    }
}

@Composable
private fun SubjectBar(
    name: String,
    taskCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .combinedClickable(onClick = onToggle, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$taskCount 个任务",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun TaskRow(
    task: PomodoroTask,
    selected: Boolean,
    running: Boolean,
    onClick: () -> Unit,
    onStart: () -> Unit,
    onEdit: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = if (selected) {
            ButtonDefaults.outlinedButtonBorder
        } else {
            ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outlineVariant))
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onEdit
            )
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = task.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )
            // 最右边的「开始」：选中这个任务并立刻开计。
            // 运行中禁用——同时跑两个番茄钟没有意义。
            FilledTonalButton(
                onClick = onStart,
                enabled = !running,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = "开始",
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(3.dp))
                Text("开始", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun NewTaskCard(
    name: String,
    subjects: List<Subject>,
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "归到哪个学科（可不选）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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

/**
 * 长按任务的编辑面板。
 *
 * 四个动作按破坏力排：改名/换学科（保存）、归档（保留历史、退出列表）、
 * 删除（连同历史记录一起没）。删除是红色且要二次确认——
 * 归档记录还在，删除连记录都没了。
 */
@Composable
private fun EditTaskDialog(
    task: PomodoroTask,
    subjects: List<Subject>,
    onName: (String) -> Unit,
    onSubject: (Long?) -> Unit,
    onSave: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑任务") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = task.name,
                    onValueChange = onName,
                    label = { Text("任务名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "所属学科",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SubjectChip(name = "不分类", selected = task.subjectId == null, onClick = { onSubject(null) })
                    subjects.forEach { s ->
                        SubjectChip(
                            name = s.name,
                            selected = task.subjectId == s.id,
                            onClick = { onSubject(s.id) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onArchive) {
                    Text("归档", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除")
                }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除任务") },
            text = { Text("「${task.name}」及其全部专注记录将一并删除，且无法恢复。确认删除？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 长按学科的编辑面板：改名 / 删除。
 *
 * 删除只删学科本身：它下面的任务滑到「未分类」，历史记录一条不丢
 * （外键 SET_NULL）。删一个分类不该连带抹掉几个月的专注——
 * 这是破坏力可控的删法，确认文案要说清楚。
 */
@Composable
private fun EditSubjectDialog(
    subject: Subject,
    onName: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑学科") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = subject.name,
                    onValueChange = onName,
                    label = { Text("学科名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "学科与错题本共用一套：改名后录题页同步生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text("保存") }
        },
        dismissButton = {
            TextButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("删除")
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除学科") },
            text = { Text("「${subject.name}」将被删除，其下的任务移至「未分类」，专注记录保留。确认删除？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SubjectChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            Color.Transparent
        },
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

// =========================================================================
// Tab 3：统计
// =========================================================================

/**
 * 统计页。
 *
 * 内容按「从粗到细」排：总览四宫格 → 完成率 → 近 7 天 → 30 天趋势线 →
 * 学科分布（饼图）→ 任务排行 → 最近记录 → 外部导入。
 * 每个模块回答一个问题，不堆无意义的装饰图。
 *
 * 顶部的时间范围 chips 决定**除柱状/折线窗口外**的所有数字：
 * 柱状与折线是「最近动态」窗口，固定以今天为终点，选「全部」时
 * 也看得到最近一个月的节奏。
 */
@Composable
private fun StatsTab(
    state: PomodoroUiState,
    onRangeChange: (StatsRange) -> Unit,
    onResetStats: () -> Unit,
    onImportCsv: () -> Unit
) {
    var confirmReset by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---- 时间范围 + 重置 ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatsRange.entries.forEach { range ->
                        FilterChip(
                            selected = state.statsRange == range,
                            onClick = { onRangeChange(range) },
                            label = { Text(range.label) }
                        )
                    }
                }
                TextButton(onClick = { confirmReset = true }) {
                    Text(
                        "重置",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // ---- 总览四宫格（随范围变）----
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCell("${state.statsRange.label}专注", formatDuration(state.totalSec), Modifier.weight(1f))
                StatCell("完成番茄", "${state.todayPomodoros} 个", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCell("连续天数", "${state.streakDays} 天", Modifier.weight(1f))
                StatCell("专注次数", "${state.sessionCount} 次", Modifier.weight(1f))
            }
        }

        // ---- 完成率 ----
        item {
            StatsCard(title = "目标达成（${state.statsRange.label}）") {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${(state.completionRate * 100).roundToInt()}%",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "共 ${state.sessionCount} 次专注，完成 ${state.totalCompleted} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 一条完成率进度条：百分比是结果，这条是「完成 / 总数」的直观形态
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(state.completionRate.coerceIn(0f, 1f))
                            .height(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
            }
        }

        // ---- 近 7 天 ----
        item {
            StatsCard(title = "近 7 天") {
                Last7Bars(values = state.last7)
            }
        }

        // ---- 近 30 天趋势线 ----
        item {
            StatsCard(title = "近 30 天趋势") {
                DailyTrendLine(values = state.last30)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "30 天累计 ${formatDuration(state.last30.sum())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- 学科分布（饼图 + 图例）----
        val subjectRows = state.subjectTotals.filter { it.sec > 0 }
        if (subjectRows.isNotEmpty()) {
            item {
                StatsCard(title = "学科分布（${state.statsRange.label}）") {
                    val named = subjectRows.map { row ->
                        val name = state.subjects.firstOrNull { it.id == row.subjectId }?.name
                            ?: "未分类"
                        Triple(name, row.sec, row.sessionCount)
                    }
                    SubjectPieChart(rows = named)
                    Spacer(Modifier.height(12.dp))
                    val totalSec = named.sumOf { it.second }.coerceAtLeast(1L)
                    named.forEachIndexed { index, (name, sec, count) ->
                        PieLegendRow(
                            color = pieColor(index),
                            name = name,
                            sec = sec,
                            sessionCount = count,
                            percent = sec.toFloat() / totalSec
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        // ---- 任务排行 ----
        if (state.taskTotals.any { it.sec > 0 }) {
            item {
                StatsCard(title = "任务累计（${state.statsRange.label}）") {
                    val maxSec = state.taskTotals.first().sec.coerceAtLeast(1L)
                    state.taskTotals.filter { it.sec > 0 }.take(5).forEach { row ->
                        TaskBar(name = row.taskName, sec = row.sec, max = maxSec)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }

        // ---- 最近记录 ----
        if (state.recent.isNotEmpty()) {
            item {
                StatsCard(title = "最近专注（${state.statsRange.label}）") {
                    state.recent.forEach { session ->
                        RecentRow(session = session)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        // ---- 外部数据导入 ----
        item {
            StatsCard(title = "导入外部记录") {
                Text(
                    text = "从其他计时软件导出的 CSV 导入：识别「任务分类 / 任务名 / 时长」列，" +
                        "日期列认不出就归到今天。学科与任务不存在会自动创建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onImportCsv, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("选择 CSV 文件导入")
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("重置统计数据") },
            text = { Text("将清空全部专注记录，所有统计图表归零。任务与学科保留。此操作无法恢复，确认重置？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        onResetStats()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("重置")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun StatsCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
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
private fun RecentRow(session: com.mistakebook.data.local.SessionWithTask) {
    val time = remember(session.startedAt) {
        java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(session.startedAt))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.taskName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$time · ${modeLabel(session.mode)}" +
                    if (session.completed) " · 已完成" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = formatDuration(session.durationSec),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (session.completed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private fun modeLabel(mode: String): String = when (mode) {
    "POMODORO" -> "番茄"
    "STOPWATCH" -> "正计时"
    else -> "定时"
}

@Composable
private fun Last7Bars(values: List<Long>) {
    // 下标 0 是今天，画图从左到右要 oldest → newest
    val days = values.asReversed()
    val max = days.maxOrNull()?.coerceAtLeast(60L) ?: 60L
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val today = LocalDate.now()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        days.forEachIndexed { index, sec ->
            val ratio = (sec.toFloat() / max.toFloat()).coerceIn(0f, 1f)
            val label = today.minusDays((6 - index).toLong())
                .format(DateTimeFormatter.ofPattern("E"))
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = if (sec > 0L) "${sec / 60}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((4 + ratio * 56).dp)
                        .background(
                            color = if (sec > 0L) color else track,
                            shape = RoundedCornerShape(4.dp)
                        )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 近 30 天每日专注时长折线图。
 *
 * 折线比 30 根细柱更适合看「趋势」：柱状图回答「每天多少」，
 * 折线回答「在变好还是变差」。线下填一层 12% 透明的主色，
 * 让趋势面有体积感又不抢线。
 */
@Composable
private fun DailyTrendLine(values: List<Long>) {
    val days = values.asReversed() // oldest → newest
    val max = (days.maxOrNull() ?: 0L).coerceAtLeast(60L)
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = Modifier.fillMaxWidth()) {
        // 顶部标尺：最大值 / 0
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("峰值 ${max / 60} 分钟", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text("0", style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
        ) {
            val w = size.width
            val h = size.height
            val stepX = if (days.size <= 1) w else w / (days.size - 1).toFloat()

            // 底部基线
            drawLine(
                color = gridColor,
                start = Offset(0f, h),
                end = Offset(w, h),
                strokeWidth = 1.dp.toPx()
            )

            val points = days.mapIndexed { index, sec ->
                Offset(
                    x = index * stepX,
                    y = h - (sec.toFloat() / max.toFloat()).coerceIn(0f, 1f) * (h - 6.dp.toPx())
                )
            }

            // 趋势面
            if (points.size >= 2) {
                val fill = Path().apply {
                    moveTo(points.first().x, h)
                    points.forEach { lineTo(it.x, it.y) }
                    lineTo(points.last().x, h)
                    close()
                }
                drawPath(fill, color = lineColor.copy(alpha = 0.12f))

                // 趋势线
                val line = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    line,
                    color = lineColor,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // 有记录的日期打点
            points.forEachIndexed { index, p ->
                if (days[index] > 0L) {
                    drawCircle(color = dotColor, radius = 2.5.dp.toPx(), center = p)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "${days.size} 天前",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor
            )
            Text(
                text = "今天",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor
            )
        }
    }
}

/** 饼图配色：同一强调色系里拉开色相，保证相邻扇区不同色。 */
private val PIE_COLORS = listOf(
    Color(0xFF4F7DF3),
    Color(0xFF12A594),
    Color(0xFFE8A33D),
    Color(0xFF8B5CF6),
    Color(0xFFEC5B5B),
    Color(0xFF3AA8E8),
    Color(0xFF7CB342),
    Color(0xFFD81B60)
)

private fun pieColor(index: Int): Color = PIE_COLORS[index % PIE_COLORS.size]

/**
 * 学科分布饼图（环形）。
 *
 * 用环形而不是实心 Pie：中间能放总时长，信息密度比实心高，
 * 也不会被大扇区挡住图例的视线。
 */
@Composable
private fun SubjectPieChart(rows: List<Triple<String, Long, Int>>) {
    val total = rows.sumOf { it.second }.coerceAtLeast(1L)
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(180.dp)) {
            val stroke = Stroke(width = 34.dp.toPx())
            val inset = 34.dp.toPx() / 2f
            val diameter = size.minDimension - 34.dp.toPx()
            var startAngle = -90f
            rows.forEachIndexed { index, (_, sec, _) ->
                val sweep = 360f * (sec.toFloat() / total.toFloat())
                drawArc(
                    color = pieColor(index),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(inset, inset),
                    size = Size(diameter, diameter)
                )
                startAngle += sweep
            }
        }
        // 环心：总时长 + 科目数
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatDuration(total),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${rows.size} 个学科",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PieLegendRow(
    color: Color,
    name: String,
    sec: Long,
    sessionCount: Int,
    percent: Float
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${(percent * 100).roundToInt()}% · $sessionCount 次 · ${formatDuration(sec)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

private fun formatClock(ms: Long): String = PomodoroService.formatClock(ms)

private fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}小时${m}分" else "${m}分钟"
}
