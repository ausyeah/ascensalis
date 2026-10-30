package com.mistakebook.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clickable { if (state.examDate != null) showPlan = true else showDateEditor = true }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
    val applying: Boolean = false
)

class ExamCountdownViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(ExamCountdownUiState())
    val uiState = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** 读设置里的考试日期 + 到期队列，重算计划。改日期/应用排期后都调它。 */
    fun refresh() {
        viewModelScope.launch {
            val snapshot = container.settingsStore.snapshotNow()
            val examDate = snapshot.examDateEpochDay?.let(LocalDate::ofEpochDay)
            if (examDate == null) {
                _uiState.value = ExamCountdownUiState()
                return@launch
            }
            val today = LocalDate.now()
            val due = container.questionRepository.dueQuestions(today, limit = 500)
                .filter { it.status != com.mistakebook.domain.MasteryStatus.MASTERED }
            val plan = ExamPlanner.plan(
                questions = due.map { PlanQuestion(it.id, it.reviewStage) },
                today = today,
                examDate = examDate
            )
            _uiState.value = ExamCountdownUiState(
                examDate = examDate,
                daysLeft = plan.daysLeft,
                plan = plan
            )
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
