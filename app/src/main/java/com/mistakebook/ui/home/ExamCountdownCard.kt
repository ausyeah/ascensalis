package com.mistakebook.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.R
import com.mistakebook.di.AppContainer
import com.mistakebook.plan.ExamPlan
import com.mistakebook.data.prefs.CustomEvent
import com.mistakebook.plan.ExamPlanner
import com.mistakebook.plan.PlanQuestion
import com.mistakebook.ui.common.TwoStepConfirmTextButton
import com.mistakebook.ui.common.containerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 考前倒计时卡片（F3）：错题本页搜索栏下方的常驻入口。
 *
 * 未设考试日期时是"设置"入口；设了之后显示倒计时+还能过几轮，
 * 点开是完整排期（摊开计划 + 一键应用到复习排期）。
 */
@Composable
fun ExamCountdownCard(container: AppContainer) {
    val viewModel: ExamCountdownViewModel =
        containerViewModel(container) { ExamCountdownViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    var showPlan by remember { mutableStateOf(false) }
    var showDateEditor by remember { mutableStateOf(false) }
    var showEventManager by remember { mutableStateOf(false) }

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            // v0.0.121 起这张卡是列表条目：横向留白由 LazyColumn 的
            // contentPadding 统一给（与题目卡对齐），这里只留上下呼吸。
            .padding(vertical = 4.dp)
    ) {
        // v0.0.134（issue #56「没有自定义未来事件」）：卡片改成纵向多行——
        // 考试倒计时行（点击进排期）+ 自定义事件行们（逐条倒计时，可删）
        // + 添加入口。clickable 也从整卡移到考试行：点自定义事件行不该
        // 弹出考试排期对话框。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (state.examDate != null) showPlan = true else showDateEditor = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🎯", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            val examDate = state.examDate
            if (examDate == null) {
                Text(
                    text = stringResource(R.string.exam_card_set_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.exam_card_countdown, state.daysLeft),
                        style = MaterialTheme.typography.titleSmall
                    )
                    val plan = state.plan
                    if (plan != null && plan.dueCount > 0) {
                        Text(
                            text = stringResource(
                                R.string.exam_card_summary,
                                plan.dueCount,
                                plan.fullPassesPossible,
                                plan.dailyLoad
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Text(
                text = examDate?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) ?: "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 自定义未来事件行（v0.0.134，issue #56）：逐条倒计时，行尾删除。
        state.customEvents.forEach { event ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📅", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = event.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                val days = event.dateEpochDay - LocalDate.now().toEpochDay()
                Text(
                    text = stringResource(
                        if (days == 0L) R.string.custom_event_today
                        else R.string.custom_event_days_left, days
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_delete),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { viewModel.removeCustomEvent(event) }
                )
            }
        }
    }

    if (showEventManager) {
        CustomEventManagerDialog(
            events = state.customEvents,
            onAdd = { name, date -> viewModel.addCustomEvent(name, date) },
            onRemove = { viewModel.removeCustomEvent(it) },
            onDismiss = { showEventManager = false }
        )
    }

    if (showPlan) {
        ExamPlanDialog(
            state = state,
            onApply = { viewModel.applyPlan() },
            onEditDate = { showPlan = false; showDateEditor = true },
            onDismiss = { showPlan = false }
        )
    }
    if (showDateEditor) {
        ExamDateEditorDialog(
            current = state.examDate,
            onSave = { viewModel.saveExamDate(it); showDateEditor = false },
            onClear = { viewModel.saveExamDate(null); showDateEditor = false },
            onOpenEvents = {
                // 二级页面：关掉日期对话框，进事件管理（v0.0.135）
                showDateEditor = false
                showEventManager = true
            },
            onDismiss = { showDateEditor = false }
        )
    }
}

@Composable
private fun ExamPlanDialog(
    state: ExamCountdownUiState,
    onApply: () -> Unit,
    onEditDate: () -> Unit,
    onDismiss: () -> Unit
) {
    val plan = state.plan
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.exam_plan_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp)) {
                if (plan == null || plan.dueCount == 0) {
                    Text(stringResource(R.string.exam_plan_no_due))
                } else {
                    Text(stringResource(R.string.exam_plan_days_left, plan.daysLeft))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.exam_plan_passes, plan.fullPassesPossible))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.exam_plan_daily_load, plan.dailyLoad))
                    if (plan.compressed) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.exam_plan_compressed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.exam_plan_apply_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (plan != null && plan.dueCount > 0 && !state.applying) {
                TwoStepConfirmTextButton(
                    text = stringResource(R.string.exam_plan_apply),
                    confirmText = stringResource(R.string.exam_plan_apply_confirm),
                    onConfirm = onApply
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onEditDate) {
                    Text(stringResource(R.string.exam_plan_change_date))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    )
}

@Composable
private fun ExamDateEditorDialog(
    current: LocalDate?,
    onSave: (LocalDate) -> Unit,
    onClear: () -> Unit,
    onOpenEvents: () -> Unit,
    onDismiss: () -> Unit
) {
    val today = remember { LocalDate.now() }
    var yearText by remember { mutableStateOf(current?.year?.toString() ?: "") }
    var monthText by remember { mutableStateOf(current?.monthValue?.toString() ?: "") }
    var dayText by remember { mutableStateOf(current?.dayOfMonth?.toString() ?: "") }
    var error by remember { mutableStateOf(false) }

    fun parseOrNull(): LocalDate? = runCatching {
        LocalDate.of(yearText.toInt(), monthText.toInt(), dayText.toInt())
    }.getOrNull()?.takeIf { it.isAfter(today) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.exam_date_title)) },
        text = {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { onSave(ExamPlanner.nextGaokao(today)) },
                        label = { Text(stringResource(R.string.exam_date_gaokao)) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { onSave(ExamPlanner.nextKaoyan(today)) },
                        label = { Text(stringResource(R.string.exam_date_kaoyan)) }
                    )
                    // v0.0.135（用户：「添加自定义事件放在考研高考这两个右边，
                    // 点进去的二级页面里自己输入」）：入口从首页卡片挪到这里，
                    // 与两个预设考期同一行。
                    FilterChip(
                        selected = false,
                        onClick = onOpenEvents,
                        label = { Text(stringResource(R.string.custom_event_manager)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.exam_date_custom_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = yearText, onValueChange = { yearText = it; error = false },
                        label = { Text(stringResource(R.string.exam_date_year)) },
                        isError = error, modifier = Modifier.weight(1f), singleLine = true
                    )
                    OutlinedTextField(
                        value = monthText, onValueChange = { monthText = it; error = false },
                        label = { Text(stringResource(R.string.exam_date_month)) },
                        isError = error, modifier = Modifier.weight(1f), singleLine = true
                    )
                    OutlinedTextField(
                        value = dayText, onValueChange = { dayText = it; error = false },
                        label = { Text(stringResource(R.string.exam_date_day)) },
                        isError = error, modifier = Modifier.weight(1f), singleLine = true
                    )
                }
                if (error) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.exam_date_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsed = parseOrNull()
                if (parsed == null) error = true else onSave(parsed)
            }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.exam_date_clear))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

data class ExamCountdownUiState(
    val examDate: LocalDate? = null,
    val daysLeft: Int = 0,
    val plan: ExamPlan? = null,
    val applying: Boolean = false,
    /** 自定义未来事件（v0.0.134，issue #56）：按日期升序、只保留今天及以后。 */
    val customEvents: List<CustomEvent> = emptyList()
)

class ExamCountdownViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(ExamCountdownUiState())
    val uiState = _uiState.asStateFlow()

    init {
        refresh()
    }

    /**
     * 读设置里的考试日期 + 到期队列，重算计划。改日期/应用排期后都调它。
     *
     * v0.0.128（issue #44/#45「复习完了但首页还显示今天有题目没复习」）：
     * 原来这里用 `dueQuestions(...)` **一次性查**，查完就存成快照 —— 用户去复习页
     * 把题过完再回来，卡片上的「待复习 N 道」还是进页面时的旧数字。
     * 改成订阅 [QuestionRepository.observeDue] 的 Flow：复习落库（nextReviewAt
     * 推到明天/清空）会让 Room 失效这张表，计划与到期数自动跟着重算。
     */
    private var dueJob: kotlinx.coroutines.Job? = null

    fun refresh() {
        viewModelScope.launch {
            val snapshot = container.settingsStore.snapshotNow()
            val examDate = snapshot.examDateEpochDay?.let(LocalDate::ofEpochDay)
            val events = upcomingCustomEvents(snapshot.customEvents, LocalDate.now())
            dueJob?.cancel()
            if (examDate == null) {
                _uiState.value = ExamCountdownUiState(customEvents = events)
                return@launch
            }
            val today = LocalDate.now()
            dueJob = viewModelScope.launch {
                container.questionRepository.observeDue(today).collect { due ->
                    val plan = ExamPlanner.plan(
                        questions = due
                            .filter { it.status != com.mistakebook.domain.MasteryStatus.MASTERED }
                            .map { PlanQuestion(it.id, it.reviewStage) },
                        today = today,
                        examDate = examDate
                    )
                    _uiState.value = ExamCountdownUiState(
                        examDate = examDate,
                        daysLeft = plan.daysLeft,
                        plan = plan,
                        customEvents = events
                    )
                }
            }
        }
    }

    fun saveExamDate(date: LocalDate?) {
        viewModelScope.launch {
            container.settingsStore.setExamDate(date?.toEpochDay())
            refresh()
            // 小组件有「考试倒计时」一格（v0.0.74）：改了日期立刻反映，
            // 旧实现只在番茄钟结束/复习完成时刷新，改完考试日最迟 30 分钟
            // 才更新——用户以为没保存成功。
            runCatching {
                com.mistakebook.widget.TodayWidgetProvider.refreshAll(container.appContext)
            }
        }
    }

    /** 添加自定义事件（v0.0.134，issue #56）：名称必填、日期必须晚于今天。 */
    fun addCustomEvent(name: String, date: LocalDate) {
        if (name.isBlank()) return
        if (!date.isAfter(LocalDate.now())) return
        viewModelScope.launch {
            val snapshot = container.settingsStore.snapshotNow()
            val updated = snapshot.customEvents +
                CustomEvent(name = name.trim(), dateEpochDay = date.toEpochDay())
            container.settingsStore.saveCustomEvents(updated)
            refresh()
        }
    }

    /** 删除自定义事件。 */
    fun removeCustomEvent(event: CustomEvent) {
        viewModelScope.launch {
            val snapshot = container.settingsStore.snapshotNow()
            container.settingsStore.saveCustomEvents(snapshot.customEvents.filterNot { it == event })
            refresh()
        }
    }

    /** 把规划好的下一复习日写进库。写完刷新计划（schedule 变空，防止重复应用）。 */
    fun applyPlan() {
        val plan = _uiState.value.plan ?: return
        if (plan.newSchedule.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(applying = true)
            container.questionRepository.applyExamSchedule(plan.newSchedule)
            refresh()
        }
    }
}

/**
 * 自定义事件管理（二级页面，v0.0.135）。
 *
 * 用户：「添加自定义事件放在考研高考这两个的右边，可以自己输入，是在点进去
 * 的二级页面」。所以它不再是卡片上的一行入口，而是从「考试日期」对话框
 * 进入的独立一页：上半是已有事件（名称 / 日期 / 还有几天 / 删除），
 * 下半是名称 + 年/月/日 + 添加。
 *
 * 用全宽 Dialog 而不是 NavHost 路由：它只服务于这张卡片，为它加一条路由
 * 与回调链不划算；全宽 Dialog 的交互层级与二级页面一致（返回键关闭）。
 */
@Composable
private fun CustomEventManagerDialog(
    events: List<CustomEvent>,
    onAdd: (name: String, date: LocalDate) -> Unit,
    onRemove: (CustomEvent) -> Unit,
    onDismiss: () -> Unit
) {
    val today = remember { LocalDate.now() }
    var name by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var month by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }

    fun parseOrNull(): LocalDate? = runCatching {
        LocalDate.of(year.toInt(), month.toInt(), day.toInt())
    }.getOrNull()

    val date = parseOrNull()
    val valid = name.isNotBlank() && date != null && date.isAfter(today)

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .heightIn(max = 560.dp)
            ) {
                Text(
                    text = stringResource(R.string.custom_event_manager),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(12.dp))
                if (events.isEmpty()) {
                    Text(
                        text = stringResource(R.string.custom_event_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        events.forEach { event ->
                            val days = event.dateEpochDay - today.toEpochDay()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(event.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = LocalDate.ofEpochDay(event.dateEpochDay).toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = stringResource(
                                        if (days == 0L) R.string.custom_event_today
                                        else R.string.custom_event_days_left, days
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.custom_event_remove),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.clickable { onRemove(event) }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.custom_event_date_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.custom_event_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = year,
                        onValueChange = { year = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.exam_date_year)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = month,
                        onValueChange = { month = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.exam_date_month)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = day,
                        onValueChange = { day = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.exam_date_day)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_close))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = valid,
                        onClick = {
                            onAdd(name.trim(), date!!)
                            name = ""
                            year = ""
                            month = ""
                            day = ""
                        }
                    ) {
                        Text(stringResource(R.string.custom_event_add_action))
                    }
                }
            }
        }
    }
}

/**
 * 自定义事件的展示过滤与排序（v0.0.134，issue #56）：只保留今天及
 * 以后（过去的倒计时没有意义），按日期升序——最近的最先看到。
 * 纯函数，单测见 [CustomEventsTest]。
 */
internal fun upcomingCustomEvents(
    events: List<CustomEvent>,
    today: LocalDate
): List<CustomEvent> = events
    .filter { it.dateEpochDay >= today.toEpochDay() }
    .sortedBy { it.dateEpochDay }
