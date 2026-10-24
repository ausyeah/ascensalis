package com.mistakebook.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.R
import com.mistakebook.data.local.knowledgePoints
import com.mistakebook.data.local.options
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.ErrorReason
import com.mistakebook.export.AnkiCsvBuilder
import com.mistakebook.report.WeeklyStatsBuilder
import com.mistakebook.ui.common.SectionLabel
import com.mistakebook.ui.common.containerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * 错题统计弹层（F5）：错因 / 难度 / 学科 / 薄弱知识点分布。
 *
 * 对齐番茄钟统计的完成度——错题侧此前只有列表角标级的数量，
 * 而"错因分布、知识点分布"恰恰是决定下一步学什么的依据。
 * 图表用横向条（纯布局实现，不引图表库、不画 Canvas）：
 * 分布类数据横向条在小屏上的可读性远好于饼图。
 */
@Composable
fun QuestionStatsDialog(
    container: AppContainer,
    onDismiss: () -> Unit
) {
    val viewModel: QuestionStatsViewModel = containerViewModel(container) { QuestionStatsViewModel(it) }
    val state by viewModel.uiState.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stats_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SummaryNumber(stringResource(R.string.stats_total), state.total)
                    SummaryNumber(stringResource(R.string.stats_mastered), state.mastered)
                    SummaryNumber(stringResource(R.string.stats_new_week), state.newThisWeek)
                }
                state.groups.forEachIndexed { index, group ->
                    Spacer(Modifier.height(16.dp))
                    SectionLabel(group.title)
                    Spacer(Modifier.height(6.dp))
                    if (group.bars.isEmpty()) {
                        Text(
                            text = stringResource(R.string.stats_group_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        BarGroup(rows = group.bars, total = state.total)
                    }
                }

                // Anki 导出（F7）：统计页是"数据出口"的自然位置
                state.exportMessage?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.exportAnki() },
                enabled = !state.exporting && state.total > 0
            ) {
                Text(
                    if (state.exporting) stringResource(R.string.stats_exporting)
                    else stringResource(R.string.stats_export_anki)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

@Composable
private fun SummaryNumber(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 一组横向条：label 固定宽 + 按占比填色的条 + 计数与百分比。 */
@Composable
private fun BarGroup(rows: List<StatBar>, total: Int) {
    val max = rows.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
    // 主题色必须先在 Composable 上下文取值，再进 remember——
    // remember 的 lambda 不是 Composable 作用域，里面取 colorScheme 编译不过
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    val palette = remember(primary, tertiary, secondary) { listOf(primary, tertiary, secondary) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { index, bar ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = bar.label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(88.dp),
                    maxLines = 1
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(bar.value.toFloat() / max)
                            .height(12.dp)
                            .background(
                                palette[index % palette.size].copy(alpha = 0.85f),
                                RoundedCornerShape(3.dp)
                            )
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (total > 0) {
                        stringResource(R.string.stats_bar_count, bar.value, bar.value * 100 / total)
                    } else {
                        bar.value.toString()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(68.dp),
                    maxLines = 1
                )
            }
        }
    }
}

data class StatBar(val label: String, val value: Int)

data class StatGroup(val title: String, val bars: List<StatBar>)

data class QuestionStatsUiState(
    val total: Int = 0,
    val mastered: Int = 0,
    val newThisWeek: Int = 0,
    val groups: List<StatGroup> = emptyList(),
    val exporting: Boolean = false,
    val exportMessage: String? = null
)

class QuestionStatsViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(QuestionStatsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                val dao = container.database.questionDao()
                val subjects = container.subjectRepository.observeAll().first()
                val subjectNames = subjects.associate { (it.id as Long?) to it.name }
                val ctx = container.appContext
                fun res(id: Int) = ctx.getString(id)

                val total = dao.observeTotalCount().first()
                val weekStart = LocalDate.now().let { it.minusDays(it.dayOfWeek.value - 1L) }
                val sinceMs = weekStart
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

                val reasons = dao.reasonDistribution()
                    .map { StatBar(labelReason(it.name), it.count) }
                    .sortedByDescending { it.value }
                val difficulty = dao.difficultyDistribution()
                    .map { StatBar(ctx.getString(R.string.stats_difficulty_star, it.name), it.count) }
                val bySubject = dao.subjectDistribution()
                    .map {
                        StatBar(
                            it.subjectId?.let { id -> subjectNames[id] } ?: res(R.string.stats_unclassified),
                            it.count
                        )
                    }
                    .sortedByDescending { it.value }
                val topKnowledge = WeeklyStatsBuilder
                    .topKnowledgePoints(dao.allKnowledgePointsJson(), 10)
                    .map { StatBar(it.name, it.count) }

                QuestionStatsUiState(
                    total = total,
                    mastered = dao.countMastered(),
                    newThisWeek = dao.countCreatedSince(sinceMs),
                    groups = listOf(
                        StatGroup(res(R.string.stats_group_reason), reasons),
                        StatGroup(res(R.string.stats_group_difficulty), difficulty),
                        StatGroup(res(R.string.stats_group_subject), bySubject),
                        StatGroup(res(R.string.stats_group_knowledge), topKnowledge)
                    )
                )
            }
            _uiState.value = state
        }
    }

    private fun labelReason(name: String): String =
        ErrorReason.entries.firstOrNull { it.name == name }?.label ?: name

    /**
     * 导出 Anki CSV（F7）。落盘走 MediaStore.Downloads（Q+）/应用目录（旧机），
     * 与 BackupManager 同一模式；文件名带时间戳防覆盖。
     */
    fun exportAnki() {
        if (_uiState.value.exporting) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(exporting = true, exportMessage = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ctx = container.appContext
                    val dao = container.database.questionDao()
                    val subjects = container.subjectRepository.observeAll().first()
                    val subjectNames = subjects.associate { (it.id as Long?) to it.name }
                    val questions = dao.allActive()
                    if (questions.isEmpty()) return@runCatching ctx.getString(R.string.stats_export_empty)

                    val rows = questions.map { q ->
                        AnkiCsvBuilder.cardRow(
                            stem = q.stem,
                            options = q.options.map { it.label to it.text },
                            answer = q.answer,
                            analysis = q.analysis,
                            tags = buildList {
                                add(ctx.getString(R.string.app_name))
                                q.subjectId?.let { subjectNames[it] }?.let { add(it) }
                                add(labelReason(q.errorReason.name))
                                addAll(q.knowledgePoints)
                            }
                        )
                    }
                    val csv = AnkiCsvBuilder.build(rows)
                    val name = "anki_${java.text.SimpleDateFormat(
                        "yyyyMMdd_HHmmss", java.util.Locale.CHINA
                    ).format(java.util.Date())}.csv"
                    val path = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/csv")
                            put(
                                android.provider.MediaStore.Downloads.RELATIVE_PATH,
                                android.os.Environment.DIRECTORY_DOWNLOADS + "/错题本"
                            )
                        }
                        val uri = ctx.contentResolver.insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                        ) ?: error("无法写入下载目录")
                        ctx.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(csv.toByteArray(Charsets.UTF_8))
                        } ?: error("打开输出流失败")
                        "Download/错题本/$name"
                    } else {
                        val dir = container.files.exportDir.apply { mkdirs() }
                        val target = java.io.File(dir, name)
                        target.writeText(csv, Charsets.UTF_8)
                        target.absolutePath
                    }
                    ctx.getString(R.string.stats_export_done, path)
                }.getOrElse { e -> e.message ?: "导出失败" }
            }
            _uiState.value = _uiState.value.copy(exporting = false, exportMessage = result)
        }
    }
}
