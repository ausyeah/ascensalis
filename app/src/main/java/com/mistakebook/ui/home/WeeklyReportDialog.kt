package com.mistakebook.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.mistakebook.data.local.entities.WeeklyReport
import com.mistakebook.di.AppContainer
import com.mistakebook.ui.common.RichText
import com.mistakebook.ui.common.containerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 周报弹层（F2 AI 错因诊断周报）。
 *
 * 为什么是弹层而不是路由页：周报是"看一眼、周一生成一次"的轻场景，
 * 配不上导航栈里的一个位置；错题本页的入口图标点开即看、关了即走。
 */
@Composable
fun WeeklyReportDialog(
    container: AppContainer,
    onDismiss: () -> Unit
) {
    val viewModel: WeeklyReportViewModel =
        containerViewModel(container) { WeeklyReportViewModel(it) }
    val state by viewModel.uiState.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.weekly_report_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp)) {
                when {
                    state.generating -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.weekly_report_generating))
                    }

                    state.error != null -> Column {
                        Text(
                            text = state.error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.generate() }) {
                            Text(stringResource(R.string.weekly_report_retry))
                        }
                    }

                    else -> {
                        // 历史周：横向 chip 切换。本周永远在最左（列表按周倒序）。
                        if (state.reports.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(state.reports) { report ->
                                    FilterChip(
                                        selected = report.id == state.selected?.id,
                                        onClick = { viewModel.select(report) },
                                        label = { Text(weekLabel(report)) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        if (state.selected == null) {
                            Text(stringResource(R.string.weekly_report_empty))
                        } else {
                            Column(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .weight(1f, fill = false)
                            ) {
                                RichText(
                                    text = state.selected!!.markdown,
                                    mathRenderer = container.mathRenderer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.generating) {
                // 生成中不给确认键：关闭只能走 onDismissRequest 之外的路（这里同样禁止），
                // 用户唯一的等待出口就是让它跑完——LLM 调用没有取消语义
                Text(stringResource(R.string.weekly_report_wait))
            } else {
                Button(onClick = { viewModel.generate() }) {
                    Text(
                        if (state.hasCurrentWeek) stringResource(R.string.weekly_report_regenerate)
                        else stringResource(R.string.weekly_report_generate)
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

/** chip 文案："本周" / "10-13"（该周周一的月-日）。 */
private fun weekLabel(report: WeeklyReport): String {
    val today = LocalDate.now()
    val monday = today.minusDays(today.dayOfWeek.value - 1L)
    return if (report.weekStartEpochDay == monday.toEpochDay()) "本周"
    else {
        val date = LocalDate.ofEpochDay(report.weekStartEpochDay)
        "%02d-%02d".format(date.monthValue, date.dayOfMonth)
    }
}

data class WeeklyReportUiState(
    val generating: Boolean = false,
    val error: String? = null,
    val reports: List<WeeklyReport> = emptyList(),
    val selected: WeeklyReport? = null
) {
    val hasCurrentWeek: Boolean
        get() = selected?.let {
            val monday = LocalDate.now().let { t -> t.minusDays(t.dayOfWeek.value - 1L) }
            it.weekStartEpochDay == monday.toEpochDay()
        } == true
}

class WeeklyReportViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(WeeklyReportUiState())
    val uiState: StateFlow<WeeklyReportUiState> = _uiState.asStateFlow()

    /** 全部周报（新→旧）。生成成功后自动选中新生成的那份。 */
    val reports: StateFlow<List<WeeklyReport>> = container.weeklyReportDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            reports.collect { list ->
                _uiState.value = _uiState.value.copy(
                    reports = list,
                    selected = _uiState.value.selected?.let { sel -> list.firstOrNull { it.id == sel.id } }
                        // 没有手动选择过就默认最新一份（REPLACE 换 id 后按周定位回新行）
                        ?: list.firstOrNull { it.weekStartEpochDay == (_uiState.value.selected?.weekStartEpochDay) }
                        ?: list.firstOrNull()
                )
            }
        }
    }

    fun select(report: WeeklyReport) {
        _uiState.value = _uiState.value.copy(selected = report)
    }

    fun generate() {
        if (_uiState.value.generating) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(generating = true, error = null)
            when (val result = container.weeklyReportGenerator.generate()) {
                is com.mistakebook.report.WeeklyReportGenerator.Result.Done -> {
                    // reports Flow 会带出更新；这里先选中，避免闪回旧报告
                    _uiState.value = _uiState.value.copy(generating = false, selected = result.report)
                }
                is com.mistakebook.report.WeeklyReportGenerator.Result.Failed -> {
                    _uiState.value = _uiState.value.copy(generating = false, error = result.reason)
                }
            }
        }
    }
}
