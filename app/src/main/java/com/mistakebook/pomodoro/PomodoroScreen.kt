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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.outlined.ChevronLeft
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import com.mistakebook.data.local.entities.PomodoroTask
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.di.AppContainer
import com.mistakebook.pomodoro.stats.PomodoroStats
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
 * （都是倒计时，只是默认时长不同），已合并——默认就是 45 分钟，
 * 想换时长在番茄钟页把轮盘拨一下即可。
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
                    // **同时写 tab**：早先只调 setMode()，靠 LaunchedEffect(state.mode)
                    // 反过来改 tab。于是「已在番茄钟模式再点番茄钟」时 mode 不变、
                    // effect 不重跑，tab 卡在统计页——点「番茄钟」没反应。
                    // 而且每次切 Tab 都要绕一圈 ViewModel 状态，切换明显发顿。
                    onClick = {
                        tab = 0
                        viewModel.setMode(FocusMode.POMODORO)
                    },
                    enabled = state.status != RunStatus.RUNNING,
                    text = { Text("番茄钟") }
                )
                Tab(
                    selected = tab == 1,
                    onClick = {
                        tab = 1
                        viewModel.setMode(FocusMode.STOPWATCH)
                    },
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
                    onShiftDay = viewModel::shiftSingleDay,
                    onResetStats = viewModel::resetStats,
                    onManualEntry = { viewModel.openManualEntry() },
                    onImportCsv = {
                        csvLauncher.launch(
                            arrayOf(
                                "text/comma-separated-values",
                                "text/csv",
                                "text/plain",
                                "application/vnd.ms-excel",
                                "*/*"
                            )
                        )
                    }
                )
            }
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (state.showManualEntry) {
        ManualEntryDialog(
            state = state,
            onDismiss = viewModel::closeManualEntry,
            onTask = viewModel::onManualTask,
            onDay = viewModel::onManualDay,
            onTime = viewModel::onManualTime,
            onMinutes = viewModel::onManualMinutes,
            onConfirm = viewModel::submitManualEntry
        )
    }
}

/**
 * 手动补记对话框。
 *
 * 只收四样东西：任务、日期、开始时刻、时长。刻意不给「结束时刻」——
 * 补记的用户场景是「我记不清具体什么时候开始的」，让他填两个时刻
 * 只会逼他瞎猜，猜出来的数据比没有更糟。开始时刻 + 时长已经足够
 * 还原时段分布。
 *
 * 时长用「分钟 + 小数」而不是「时分秒」：补记最多是「一小时二十分」
 * 这种量级，秒级精度是幻觉。
 */
@Composable
private fun ManualEntryDialog(
    state: PomodoroUiState,
    onDismiss: () -> Unit,
    onTask: (Long?) -> Unit,
    onDay: (String) -> Unit,
    onTime: (Int, Int) -> Unit,
    onMinutes: (String) -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("补记一次专注") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 任务：从已有任务里选。补记不该顺手新建任务——
                // 新建任务带出学科选择，那是另一件事（CSV 导入已经在做）。
                Text(
                    text = "任务",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                ) {
                    state.tasks.filter { !it.archived }.forEach { task ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .combinedClickable { onTask(task.id) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = state.manualTaskId == task.id,
                                onClick = { onTask(task.id) }
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = task.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = state.manualDay,
                    onValueChange = onDay,
                    label = { Text("日期（2026-03-08）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = state.manualHour.toString(),
                        onValueChange = { onTime(it.toIntOrNull() ?: state.manualHour, state.manualMinute) },
                        label = { Text("开始时") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = state.manualMinute.toString(),
                        onValueChange = { onTime(state.manualHour, it.toIntOrNull() ?: state.manualMinute) },
                        label = { Text("开始分") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = state.manualMinutes,
                        onValueChange = onMinutes,
                        label = { Text("时长（分钟）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.weight(1.4f)
                    )
                }
                Text(
                    text = "补记只计入专注时长，不计番茄数。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("补记") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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
            onEditSubject = viewModel::editSubject,
            // 新建任务表单跟着学科走：内联展开在所点学科的任务列表下方，
            // 不再是页面最底部那张孤零零的卡（见 SubjectTaskBoard）。
            showNewTask = state.showNewTask,
            newTaskSubjectId = state.newTaskSubjectId,
            newTaskName = state.newTaskName,
            onNewTaskName = viewModel::onNewTaskName,
            onCreateTask = viewModel::createTask,
            onCancelNewTask = { viewModel.openNewTask(false) }
        )

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

            // ---- 主操作：时间正下方，纯图标、零底色 ----
            // 早期是一颗 primary 大胶囊「开始」，用户原话：「占整个圆的大
            // 部分，甚至还要超出去，实在是没有审美」。计时器的控件应该
            // 像手表一样安静——图形本身即语义，不需要文字 explain：
            // 三角形 = 开始，竖等号 = 暂停，红方块 = 停止。
            when (status) {
                RunStatus.IDLE -> {
                    // 开始：长按循环切换模式（隐藏手势，顶部另有可见 Tab）
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .combinedClickable(onClick = onStart, onLongClick = onCycleMode),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.PlayArrow,
                            contentDescription = "开始",
                            modifier = Modifier.size(30.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = if (status == RunStatus.RUNNING) onPause else onResume
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (status == RunStatus.RUNNING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = if (status == RunStatus.RUNNING) "暂停" else "继续",
                                modifier = Modifier.size(30.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        // 停止：唯一允许出现的彩色，且只给破坏性操作
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .combinedClickable(onClick = onStopSave),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Stop,
                                contentDescription = "结束并记录",
                                modifier = Modifier.size(26.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
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
    onEditSubject: (Subject) -> Unit,
    showNewTask: Boolean = false,
    newTaskSubjectId: Long? = null,
    newTaskName: String = "",
    onNewTaskName: (String) -> Unit = {},
    onCreateTask: () -> Unit = {},
    onCancelNewTask: () -> Unit = {}
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
                    // 新建任务表单：就地长在这个学科的任务列表下面。
                    // 早期它被甩到整个页面最底部——「点数学的新建任务，
                    // 输入框出现在英语下面」，包含关系断裂，学科一多
                    // 就得翻到底。现在表单跟着学科走，位置即归属。
                    if (showNewTask && newTaskSubjectId == subjectId) {
                        Spacer(Modifier.height(8.dp))
                        InlineTaskEditor(
                            subjectName = name,
                            name = newTaskName,
                            onName = onNewTaskName,
                            onConfirm = onCreateTask,
                            onCancel = onCancelNewTask
                        )
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

/**
 * 就地展开的「新建任务」输入行——长在所属学科的任务列表下面。
 *
 * 与 [InlineSubjectEditor] 同一套手势：自动获焦、弹键盘、回车创建。
 * 学科由**所在位置**决定（点哪个学科的新建任务就归哪个学科），
 * 所以不再放学科筛选 chips——位置本身就是归属，再选一遍是重复提问。
 */
@Composable
private fun InlineTaskEditor(
    subjectName: String,
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
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "新建到「$subjectName」",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                label = { Text("任务名") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCancel) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConfirm, enabled = name.isNotBlank()) { Text("创建") }
            }
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
                // 两步确认：任务及其专注记录不可恢复，确认键再点一次才执行
                com.mistakebook.ui.common.TwoStepConfirmTextButton(
                    text = "删除",
                    confirmText = "确认删除",
                    onConfirm = {
                        confirmDelete = false
                        onDelete()
                    }
                )
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
                // 两步确认：学科删除不可恢复，确认键再点一次才执行
                com.mistakebook.ui.common.TwoStepConfirmTextButton(
                    text = "删除",
                    confirmText = "确认删除",
                    onConfirm = {
                        confirmDelete = false
                        onDelete()
                    }
                )
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
    onShiftDay: (Long) -> Unit,
    onResetStats: () -> Unit,
    onImportCsv: () -> Unit,
    onManualEntry: () -> Unit
) {
    var confirmReset by remember { mutableStateOf(false) }
    // 翻页位置留在 LazyColumn 的 state 里：切去计时页再回来，
    // 用户不该被弹回统计页顶部重新往下划。
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
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

        // ---- 单日专注卡（可翻页，默认今天）----
        //
        // 排序规则（用户诉求）：「统计数据越是接近今日的就往上排，
        // 总计这种就往下丢」。单日卡是离今天最近的一张，排最前。
        item {
            SingleDayCard(
                day = state.singleDay,
                sec = state.singleDaySec,
                pomodoros = state.singleDayPomodoros,
                tasks = state.singleDayTasks,
                // 翻到今天就挡住「后一天」：统计是已发生的事实，
                // 翻到未来只会看到空白，还会让人以为数据丢了
                nextEnabled = state.singleDay < LocalDate.now().toString(),
                onPrev = { onShiftDay(-1L) },
                onNext = { onShiftDay(1L) }
            )
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

        // ---- 近 7 天（离今天最近的动态窗口，排在月/季之前）----
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

        // ---- 本月按周柱状图 ----
        if (state.monthWeeks.any { it > 0L }) {
            item {
                StatsCard(title = "本月每周专注") {
                    WeekBars(values = state.monthWeeks)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "本月累计 ${formatDuration(state.monthWeeks.sum())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---- 月度趋势（近 12 个月折线）----
        item {
            StatsCard(title = "月度趋势（近 12 个月）") {
                MonthTrendLine(values = state.months12)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "合计 ${formatDuration(state.months12.sum())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- 季度趋势（近 12 个月按季并）----
        item {
            val quarters = remember(state.months12) { PomodoroStats.quartersOf(state.months12) }
            StatsCard(title = "季度趋势") {
                QuarterTrendLine(values = quarters)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "最近一个季度 ${formatDuration(quarters.lastOrNull() ?: 0L)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- 学科分布（饼图 + 图例）----
        // 注意 remember 必须写在 item {} **内部**：item 之前那段是
        // LazyListScope，不是 @Composable 上下文。
        val hasSubject = state.subjectTotals.any { it.sec > 0 }
        if (hasSubject) {
            item {
                val subjectRows = remember(state.subjectTotals) {
                    state.subjectTotals.filter { it.sec > 0 }
                }
                StatsCard(title = "学科分布（${state.statsRange.label}）") {
                    val named = remember(subjectRows, state.subjects) {
                        subjectRows.map { row ->
                            val name = state.subjects.firstOrNull { it.id == row.subjectId }?.name
                                ?: "未分类"
                            Triple(name, row.sec, row.sessionCount)
                        }
                    }
                    SubjectPieChart(rows = named)
                    Spacer(Modifier.height(12.dp))
                    val totalSec = remember(named) { named.sumOf { it.second }.coerceAtLeast(1L) }
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
                    val top5 = remember(state.taskTotals) {
                        state.taskTotals.filter { it.sec > 0 }.take(5)
                    }
                    val maxSec = remember(top5) { top5.firstOrNull()?.sec ?: 1L }
                    top5.forEach { row ->
                        TaskBar(name = row.taskName, sec = row.sec, max = maxSec)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }

        // ---- 最近记录 ----
        //
        // 固定高度 + 内部滚动（用户诉求：「这个栏目应该要做成固定长度，
        // 然后可以内部滑动，不然太多了容易爆」）。直接 forEach 铺开的话，
        // 20 条记录能把统计页拉成无限长列表，翻到底全是它。
        if (state.recent.isNotEmpty()) {
            item {
                StatsCard(title = "最近专注（${state.statsRange.label}）") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    ) {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        ) {
                            state.recent.forEach { session ->
                                RecentRow(session = session)
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }

        // ---- 手动补记 + 外部数据导入 ----
        item {
            StatsCard(title = "补记与导入") {
                Text(
                    text = "手动补记：用别的方式学过的时长补进来（比如纸质笔记、上机自习）。" +
                        "补记只加时长，不计番茄数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("手动补记一次专注")
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "从其他计时软件导出的表格导入（CSV / .xls）：识别「任务分类 / 任务名 / 时长」列，" +
                        "日期列认不出就归到今天。学科与任务不存在会自动创建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onImportCsv, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("选择 CSV / XLS 文件导入")
                }
            }
        }

        // ---- 累计数据（总计沉底）----
        //
        // 用户诉求：「统计数据越是接近今日的就往上排，总计这种就
        // 往下丢」。累计是跨全部时间的口径，与任何「最近」都无关，
        // 放最底部——但它仍不受上方时间范围 chips 影响。
        item {
            StatsCard(title = "累计数据") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCell("累计专注", formatDuration(state.totalAllSec), Modifier.weight(1f))
                    StatCell("累计番茄", "${state.totalAllCompleted} 个", Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCell("坚持天数", "${state.activeDays} 天", Modifier.weight(1f))
                    StatCell("跨度", "${state.activeMonths} 个月", Modifier.weight(1f))
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
                // 两步确认：不可恢复操作，确认键也要再点一次才执行
                com.mistakebook.ui.common.TwoStepConfirmTextButton(
                    text = "重置",
                    confirmText = "确认重置",
                    onConfirm = {
                        confirmReset = false
                        onResetStats()
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 单日专注卡（可翻页）。
 *
 * 为什么单独给一天一张卡：统计页回答的是「一段时间的规律」，
 * 但用户最常问的其实是「我昨天到底学了多久」——一个具体数字。
 * 左右箭头翻页时中间那几天也会顺带加载，不跳日期，所以不会闪空白。
 */
@Composable
private fun SingleDayCard(
    day: String,
    sec: Long,
    pomodoros: Int,
    tasks: List<PomodoroStats.TaskRow>,
    nextEnabled: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    StatsCard(title = "单日专注") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPrev) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = "前一天")
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = dayLabel(day),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (sec > 0L) "$pomodoros 个番茄" else "这天没有记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onNext, enabled = nextEnabled) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = "后一天")
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = formatDuration(sec),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        if (tasks.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val max = remember(tasks) { tasks.first().sec.coerceAtLeast(1L) }
            tasks.take(4).forEach { row ->
                TaskBar(name = row.taskName, sec = row.sec, max = max)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun dayLabel(day: String): String {
    val d = runCatching { LocalDate.parse(day) }.getOrNull() ?: return day
    return "${d.monthValue} 月 ${d.dayOfMonth} 日 " +
        d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
}

/**
 * 本月按周柱状图。
 *
 * 折线看趋势、柱状看「每周到底多少」——月里有 4–5 周，
 * 每周一根柱比 30 根日柱更容易看出「这周是不是掉队了」。
 *
 * 手指停留 / 点击某根柱 → 顶部读数行换成那一周的简略数据
 * （用户诉求：「这些图，手指停留或者点击应该要显示具体的简略数据」）。
 */
@Composable
private fun WeekBars(values: List<Long>) {
    if (values.isEmpty()) return
    val max = remember(values) { values.maxOrNull()?.coerceAtLeast(60L) ?: 60L }
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    // -1 = 没选中；选中时柱体换主色、读数行换成那一周的时长
    var selected by remember { mutableStateOf(-1) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        values.forEachIndexed { index, sec ->
            val ratio = (sec.toFloat() / max.toFloat()).coerceIn(0f, 1f)
            Column(
                modifier = Modifier
                    .weight(1f)
                    // 整列可点：柱子细，点柱顶不如点整列好按
                    .pointerInput(index) {
                        detectTapGestures(
                            onPress = {
                                selected = if (selected == index) -1 else index
                            }
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = if (sec > 0L) "${sec / 60}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index == selected) color else labelColor,
                    fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((4 + ratio * 58).dp)
                        .background(
                            color = when {
                                index == selected -> color
                                sec > 0L -> color
                                else -> track
                            },
                            shape = RoundedCornerShape(4.dp)
                        )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "第${index + 1}周",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index == selected) color else labelColor,
                    fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
    // 读数行：选中某一周时显示「第 N 周 · 时长」，否则显示本月峰值
    Spacer(Modifier.height(4.dp))
    Text(
        text = if (selected in values.indices) {
            "第 ${selected + 1} 周 · ${formatDuration(values[selected])}"
        } else {
            "峰值 ${max / 60} 分钟"
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (selected in values.indices) color else labelColor,
        fontWeight = if (selected in values.indices) FontWeight.Bold else FontWeight.Normal
    )
}

/**
 * 月度折线（近 12 个月）。
 *
 * 与 [DailyTrendLine] 同一套画法，只是 x 轴粒度从「天」变成「月」：
 * 数据点个数从 30 降到 12，点间距大了，趋势一眼就看得出来。
 * 不用柱：跨 12 个月看的是走向，不是每月的绝对值。
 */
@Composable
private fun MonthTrendLine(values: List<Long>) {
    TrendLine(
        values = values,
        startLabel = if (values.isEmpty()) "" else "${values.size} 个月前",
        pointLabeler = { index ->
            if (index == values.size - 1) "本月" else "${values.size - 1 - index} 个月前"
        }
    )
}

/** 季度折线：同样 12 个月并成 4 个点，折线更陡，看「一个季度的量级」。 */
@Composable
private fun QuarterTrendLine(values: List<Long>) {
    TrendLine(
        values = values,
        startLabel = if (values.isEmpty()) "" else "近 ${values.size} 个季度",
        pointLabeler = { index -> "第 ${index + 1} 个季度" }
    )
}

/**
 * 通用折线图（带 12% 主色填充的趋势面 + 有值打点）。
 *
 * 抽出来是因为月度与季度两条线的画法完全一样，只有 x 轴粒度不同——
 * 复制两份意味着以后改样式要改两处，早晚漏一处。
 *
 * **所有数据计算都在 remember 里、Canvas 之外**：
 * 点位依赖 size（Canvas 尺寸），但「归一化后的比值」不依赖，
 * 所以在 draw lambda 里只做一次乘法。这样布局变化时不会重算
 * 整条折线的点位，也不会因为外层任何无关重组而重画路径。
 */
@Composable
private fun TrendLine(
    values: List<Long>,
    startLabel: String,
    endLabel: String = "最近",
    /** 每个数据点的可读名称（触摸时显示用，如「6 天前」「本月」）。 */
    pointLabeler: (Int) -> String = { index -> "第 ${index + 1} 个" }
) {
    val ratios = remember(values) {
        val max = (values.maxOrNull() ?: 0L).coerceAtLeast(60L)
        values.map { (it.toFloat() / max.toFloat()).coerceIn(0f, 1f) }
    }
    val max = remember(values) { (values.maxOrNull() ?: 0L).coerceAtLeast(60L) }
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    // 手指停留 / 点击选中的点；-1 = 没选。松手不清空——
    // 用户要的就是「按住看清楚」，抬手立刻消失反而看不到。
    var selected by remember { mutableStateOf(-1) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                // 选中时这一行变成选中点的读数：简略数据（名称 + 时长）
                text = if (selected in values.indices) {
                    "${pointLabeler(selected)} · ${formatDuration(values[selected])}"
                } else {
                    "峰值 ${max / 60} 分钟"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (selected in values.indices) lineColor else labelColor,
                fontWeight = if (selected in values.indices) FontWeight.Bold else FontWeight.Normal
            )
            Text("0", style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                // 手指停留或点击 → 按 x 坐标找最近的数据点。
                // 不消费事件：纵向滚动（外层 LazyColumn）照旧能抢走手势。
                .pointerInput(values) {
                    detectTapGestures(
                        onPress = { offset ->
                            if (values.isNotEmpty()) {
                                val stepX = size.width / (values.size - 1).coerceAtLeast(1)
                                selected = (offset.x / stepX).roundToInt()
                                    .coerceIn(0, values.size - 1)
                            }
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val topPad = 6.dp.toPx()
            val stepX = if (ratios.size <= 1) w else w / (ratios.size - 1).toFloat()

            drawLine(
                color = gridColor,
                start = Offset(0f, h),
                end = Offset(w, h),
                strokeWidth = 1.dp.toPx()
            )

            val points = ratios.mapIndexed { index, r ->
                Offset(index * stepX, h - r * (h - topPad))
            }

            // 选中点：先画一条竖向参考线，点本身描大一点
            if (selected in points.indices) {
                val p = points[selected]
                drawLine(
                    color = lineColor.copy(alpha = 0.35f),
                    start = Offset(p.x, 0f),
                    end = Offset(p.x, h),
                    strokeWidth = 1.dp.toPx()
                )
            }

            if (points.size >= 2) {
                val fill = Path().apply {
                    moveTo(points.first().x, h)
                    points.forEach { lineTo(it.x, it.y) }
                    lineTo(points.last().x, h)
                    close()
                }
                drawPath(fill, color = lineColor.copy(alpha = 0.12f))

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

            points.forEachIndexed { index, p ->
                if (values.getOrNull(index) != null && values[index] > 0L) {
                    val isSel = index == selected
                    drawCircle(
                        color = lineColor,
                        radius = if (isSel) 4.5.dp.toPx() else 2.5.dp.toPx(),
                        center = p
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(startLabel, style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(endLabel, style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
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

/**
 * 近 7 天柱状图。
 *
 * 柱高、星期标签都跟重组无关却要每次重算：label 还得过一次
 * DateTimeFormatter（一天 7 个 formatter 实例）。一次性在
 * remember 里算好，外层随便怎么重组都不会重跑。
 *
 * 与 [WeekBars] 同样支持手指停留 / 点击读数。
 */
@Composable
private fun Last7Bars(values: List<Long>) {
    val bars = remember(values) {
        val days = values.asReversed()
        val max = days.maxOrNull()?.coerceAtLeast(60L) ?: 60L
        val today = LocalDate.now()
        days.mapIndexed { index, sec ->
            Triple(
                today.minusDays((6 - index).toLong())
                    .format(DateTimeFormatter.ofPattern("E")),
                sec,
                (sec.toFloat() / max.toFloat()).coerceIn(0f, 1f)
            )
        }
    }
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    var selected by remember { mutableStateOf(-1) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        bars.forEachIndexed { index, (label, sec, ratio) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(index) {
                        detectTapGestures(
                            onPress = {
                                selected = if (selected == index) -1 else index
                            }
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = if (sec > 0L) "${sec / 60}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index == selected) color else labelColor,
                    fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((4 + ratio * 56).dp)
                        .background(
                            color = when {
                                index == selected -> color
                                sec > 0L -> color
                                else -> track
                            },
                            shape = RoundedCornerShape(4.dp)
                        )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index == selected) color else labelColor,
                    fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
    // 读数行：选中某天时显示「周三 · 时长」（连同日期更实用）
    if (selected in bars.indices) {
        Spacer(Modifier.height(4.dp))
        val dayText = remember(selected) {
            LocalDate.now().minusDays((6 - selected).toLong())
                .format(DateTimeFormatter.ofPattern("M月d日"))
        }
        Text(
            text = "$dayText 周${bars[selected].first} · ${formatDuration(bars[selected].second)}",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 近 30 天每日专注时长折线图。
 *
 * 折线比 30 根细柱更适合看「趋势」：柱状图回答「每天多少」，
 * 折线回答「在变好还是变差」。线下填一层 12% 透明的主色，
 * 让趋势面有体积感又不抢线。
 */
/**
 * 近 30 天每日专注时长折线图。
 *
 * 折线比 30 根细柱更适合看「趋势」：柱状图回答「每天多少」，
 * 折线回答「在变好还是变差」。线下填一层 12% 透明的主色，
 * 让趋势面有体积感又不抢线。画法与月度/季度折线完全一致，
 * 所以直接复用 [TrendLine]，只是 x 轴粒度从「月」换成「天」。
 */
@Composable
private fun DailyTrendLine(values: List<Long>) {
    // last7/last30 的下标 0 是**今天**，折线要从左往右画就得倒过来。
    // 倒序结果用 remember 缓存：asReversed() 每次都新建 List，
    // 直接当 remember 的 key 会让缓存永远命中不了。
    val ordered = remember(values) { values.asReversed() }
    TrendLine(
        values = ordered,
        startLabel = if (ordered.isEmpty()) "" else "${ordered.size} 天前",
        endLabel = "今天",
        pointLabeler = { index ->
            val daysAgo = ordered.size - 1 - index
            when (daysAgo) {
                0 -> "今天"
                1 -> "昨天"
                else -> "$daysAgo 天前"
            }
        }
    )
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
 *
 * 手指停留 / 点击某个扇区 → 环心换成那个学科的简略数据
 * （名称 + 时长 + 占比），再点空白处回到总时长。
 */
@Composable
private fun SubjectPieChart(rows: List<Triple<String, Long, Int>>) {
    val total = remember(rows) { rows.sumOf { it.second }.coerceAtLeast(1L) }
    // 扇区起止角只依赖 rows：提到 Canvas 之外算，
    // 免得每次绘制阶段都重跑一遍三角函数与除法。
    val arcs = remember(rows, total) {
        var start = -90f
        rows.mapIndexed { index, (_, sec, _) ->
            val sweep = 360f * (sec.toFloat() / total.toFloat())
            Triple(index, start, sweep).also { start += sweep }
        }
    }
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val lineColor = MaterialTheme.colorScheme.primary
    // -1 = 没选中扇区（环心显示总计）
    var selected by remember { mutableStateOf(-1) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(180.dp)
                .pointerInput(arcs) {
                    detectTapGestures { offset ->
                        // PointerInputScope 的 size 是 IntSize，没有 minDimension
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val dx = offset.x - cx
                        val dy = offset.y - cy
                        val dist = kotlin.math.hypot(dx, dy)
                        val stroke = 34.dp.toPx()
                        val outer = minOf(size.width, size.height) / 2f
                        val inner = outer - stroke
                        selected = if (dist in inner..outer) {
                            // 屏幕坐标 y 向下、角度逆时针为正，与 Canvas 的
                            // drawArc 口径（0° = 3 点钟、顺时针）一致：
                            // atan2(dy, dx) 直接给出相对 3 点钟的顺时针角。
                            var angle = Math.toDegrees(
                                kotlin.math.atan2(dy.toDouble(), dx.toDouble())
                            ).toFloat()
                            if (angle < 0f) angle += 360f
                            arcs.firstOrNull { (_, start, sweep) ->
                                angle >= start && angle < start + sweep
                            }?.first ?: -1
                        } else {
                            -1
                        }
                    }
                }
        ) {
            val stroke = Stroke(width = 34.dp.toPx())
            val inset = 34.dp.toPx() / 2f
            val diameter = size.minDimension - 34.dp.toPx()
            arcs.forEach { (index, startAngle, sweep) ->
                drawArc(
                    color = pieColor(index),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(inset, inset),
                    size = Size(diameter, diameter)
                )
            }
            // 选中扇区：同色再描一圈略粗的弧，指出「现在看的是这块」
            if (selected in arcs.indices) {
                val (_, startAngle, sweep) = arcs[selected]
                drawArc(
                    color = lineColor,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = 40.dp.toPx()),
                    topLeft = Offset(inset - 3.dp.toPx(), inset - 3.dp.toPx()),
                    size = Size(diameter + 6.dp.toPx(), diameter + 6.dp.toPx())
                )
            }
        }
        // 环心：默认总时长 + 学科数；选中扇区时换成该学科简略数据
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (selected in rows.indices) {
                val (name, sec, _) = rows[selected]
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatDuration(sec),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${(sec.toFloat() / total * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
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
