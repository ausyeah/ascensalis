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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.res.stringResource
import com.mistakebook.R

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
internal fun TimerTab(
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
                Text(stringResource(R.string.pomodoro_give_up), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            // v0.0.84 视觉微调：环细一档（10→8dp）更轻盈；进度末端加一颗
            // 圆头小点（手表指针感），剩余时间一眼可读——纯描边在进度小
            // 的时候几乎看不出起点在哪。
            val strokeW = 8.dp.toPx()
            val stroke = Stroke(width = strokeW, cap = StrokeCap.Round)
            val inset = strokeW / 2f
            val diameter = size.minDimension - strokeW
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
                topLeft = Offset(inset, inset),
                size = Size(diameter, diameter)
            )
            if (mode.hasTarget && progress > 0f) {
                val sweep = 360f * progress
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(inset, inset),
                    size = Size(diameter, diameter)
                )
                // 进度末端圆点：圆心落在弧线终点上，半径略大于线宽一半
                val endAngle = (-90f + sweep) * (Math.PI.toFloat() / 180f)
                val radius = diameter / 2f
                val cx = size.minDimension / 2f + radius * kotlin.math.cos(endAngle)
                val cy = size.minDimension / 2f + radius * kotlin.math.sin(endAngle)
                drawCircle(
                    color = progressColor,
                    radius = strokeW * 0.9f,
                    center = Offset(cx, cy)
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
            // v0.0.84：裸图标看着太秃（用户「显示有点丑」），给每颗按钮补
            // 一层安静的圆底——开始用 primaryContainer 呼应主题色，暂停用
            // 中性容器色，停止仍是唯一的彩色（errorContainer）。尺寸克制，
            // 不回到早期那颗大胶囊。
            when (status) {
                RunStatus.IDLE -> {
                    // 开始：长按循环切换模式（隐藏手势，顶部另有可见 Tab）
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .combinedClickable(onClick = onStart, onLongClick = onCycleMode),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.pomodoro_cd_start),
                            modifier = Modifier.size(30.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .combinedClickable(
                                    onClick = if (status == RunStatus.RUNNING) onPause else onResume
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (status == RunStatus.RUNNING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (status == RunStatus.RUNNING) "暂停" else "继续",
                                modifier = Modifier.size(26.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        // 停止：唯一允许出现的彩色，且只给破坏性操作
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .combinedClickable(onClick = onStopSave),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = stringResource(R.string.pomodoro_cd_stop_record),
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
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


private fun formatClock(ms: Long): String = PomodoroService.formatClock(ms)

