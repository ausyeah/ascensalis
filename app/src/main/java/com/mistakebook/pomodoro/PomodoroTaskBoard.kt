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
internal fun ManualEntryDialog(
    state: PomodoroUiState,
    onDismiss: () -> Unit,
    onTask: (Long?) -> Unit,
    onDay: (String) -> Unit,
    onTimeText: (String) -> Unit,
    onMinutes: (String) -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pomodoro_backfill_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 任务：从已有任务里选。补记不该顺手新建任务——
                // 新建任务带出学科选择，那是另一件事（CSV 导入已经在做）。
                Text(
                    text = stringResource(R.string.pomodoro_backfill_task_label),
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
                    label = { Text(stringResource(R.string.pomodoro_backfill_date_hint)) },
                    supportingText = {
                        Text(stringResource(R.string.pomodoro_backfill_date_format))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // v0.0.129（issue #51「手动添加记录时间不能完全去掉然后填写，
                // 而且 UI 异常都换行了」）：
                // - 时/分两个数字框塞一行，标签在窄屏挤成两行——改成一个
                //   「HH:mm」整行输入框，占位即格式示例；
                // - 输入过程零拦截（旧实现清空就被旧值顶回来，等于不能删），
                //   合法性挪到提交时校验。
                OutlinedTextField(
                    value = state.manualTimeText,
                    onValueChange = onTimeText,
                    label = { Text(stringResource(R.string.pomodoro_backfill_time_hint)) },
                    supportingText = {
                        Text(stringResource(R.string.pomodoro_backfill_time_format))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.manualMinutes,
                    onValueChange = onMinutes,
                    label = { Text(stringResource(R.string.pomodoro_backfill_duration)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.pomodoro_backfill_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.pomodoro_backfill_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

// =========================================================================
// Tab 1 / Tab 2：计时页
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
internal fun SubjectTaskBoard(
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
                            text = stringResource(R.string.pomodoro_no_tasks_in_subject),
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
                        Text(stringResource(R.string.pomodoro_new_task))
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
                Text(stringResource(R.string.pomodoro_new_subject))
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
                label = { Text(stringResource(R.string.pomodoro_new_subject_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = onConfirm, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_create)) }
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
                text = stringResource(R.string.pomodoro_new_task_into_subject, subjectName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                label = { Text(stringResource(R.string.pomodoro_task_name_label)) },
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
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConfirm, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_create)) }
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
                text = stringResource(R.string.pomodoro_task_count_badge, taskCount),
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
            ButtonDefaults.outlinedButtonBorder(enabled = true)
        } else {
            ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outlineVariant))
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
                    contentDescription = stringResource(R.string.pomodoro_cd_start),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(3.dp))
                Text(stringResource(R.string.pomodoro_cd_start), fontSize = 13.sp)
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
internal fun EditTaskDialog(
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
        title = { Text(stringResource(R.string.pomodoro_edit_task_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = task.name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.pomodoro_task_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.pomodoro_task_subject_label),
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
            TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onArchive) {
                    Text(stringResource(R.string.pomodoro_archive), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.pomodoro_delete_task_title)) },
            text = { Text(stringResource(R.string.pomodoro_delete_task_confirm, task.name)) },
            confirmButton = {
                // 两步确认：任务及其专注记录不可恢复，确认键再点一次才执行
                com.mistakebook.ui.common.TwoStepConfirmTextButton(
                    text = stringResource(R.string.action_delete),
                    confirmText = stringResource(R.string.action_confirm_delete),
                    onConfirm = {
                        confirmDelete = false
                        onDelete()
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
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
internal fun EditSubjectDialog(
    subject: Subject,
    onName: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pomodoro_edit_subject_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = subject.name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.pomodoro_subject_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.pomodoro_edit_subject_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.action_delete))
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.pomodoro_delete_subject_title)) },
            text = { Text(stringResource(R.string.pomodoro_delete_subject_confirm, subject.name)) },
            confirmButton = {
                // 两步确认：学科删除不可恢复，确认键再点一次才执行
                com.mistakebook.ui.common.TwoStepConfirmTextButton(
                    text = stringResource(R.string.action_delete),
                    confirmText = stringResource(R.string.action_confirm_delete),
                    onConfirm = {
                        confirmDelete = false
                        onDelete()
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}


@Composable
internal fun SubjectChip(name: String, selected: Boolean, onClick: () -> Unit) {
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

