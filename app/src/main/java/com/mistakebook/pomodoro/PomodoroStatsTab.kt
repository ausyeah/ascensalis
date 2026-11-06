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
internal fun StatsTab(
    state: PomodoroUiState,
    onRangeChange: (StatsRange) -> Unit,
    onShiftDay: (Long) -> Unit,
    onResetStats: () -> Unit,
    onImportCsv: () -> Unit,
    onManualEntry: () -> Unit,
    /**
     * 生成「今日专注」打卡海报。
     *
     * 与词库统计页的「打卡海报」共用同一条流水线（本地渲染 / 背景图生图 /
     * 整图生图），只是数据口径不同。
     *
     * 生图走**后台任务**（WorkManager）：这里只负责「发起」，
     * 进度与结果由 [posterWorkMonitor] 广播（见 [com.mistakebook.poster.rememberPosterWorkHandler]）。
     */
    onGeneratePoster: (mode: com.mistakebook.poster.PosterImageMode) -> Unit = { },
    /** 海报生图后台任务的监视器（界面用它订阅状态）。 */
    posterWorkMonitor: com.mistakebook.poster.PosterWorkMonitor? = null,
    /** 后台任务失败时的提示回调。 */
    onPosterFailed: (String) -> Unit = { }
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
            StatsCard(title = stringResource(R.string.pomodoro_stats_goal_reached, state.statsRange.label)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${(state.completionRate * 100).roundToInt()}%",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.pomodoro_stats_total_summary, state.sessionCount, state.totalCompleted),
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
            StatsCard(title = stringResource(R.string.pomodoro_stats_last7)) {
                Last7Bars(values = state.last7)
            }
        }

        // ---- 近 30 天趋势线 ----
        item {
            StatsCard(title = stringResource(R.string.pomodoro_stats_last30_trend)) {
                DailyTrendLine(values = state.last30)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.pomodoro_stats_last30_total, formatDuration(state.last30.sum())),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- 本月按周柱状图 ----
        if (state.monthWeeks.any { it > 0L }) {
            item {
                StatsCard(title = stringResource(R.string.pomodoro_stats_month_weekly)) {
                    WeekBars(values = state.monthWeeks)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.pomodoro_stats_month_total, formatDuration(state.monthWeeks.sum())),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---- 月度趋势（近 12 个月折线）----
        item {
            StatsCard(title = stringResource(R.string.pomodoro_stats_monthly_trend)) {
                MonthTrendLine(values = state.months12)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.pomodoro_stats_grand_total, formatDuration(state.months12.sum())),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- 季度趋势（近 12 个月按季并）----
        item {
            val quarters = remember(state.months12) { PomodoroStats.quartersOf(state.months12) }
            StatsCard(title = stringResource(R.string.pomodoro_stats_quarter_trend)) {
                QuarterTrendLine(values = quarters)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.pomodoro_stats_quarter_total, formatDuration(quarters.lastOrNull() ?: 0L)),
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
                StatsCard(title = stringResource(R.string.pomodoro_stats_subject_dist, state.statsRange.label)) {
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
                StatsCard(title = stringResource(R.string.pomodoro_stats_task_total, state.statsRange.label)) {
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
                StatsCard(title = stringResource(R.string.pomodoro_stats_recent, state.statsRange.label)) {
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
            StatsCard(title = stringResource(R.string.pomodoro_stats_import_section)) {
                Text(
                    text = stringResource(R.string.pomodoro_stats_manual_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pomodoro_manual_entry_btn))
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.pomodoro_stats_csv_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onImportCsv, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pomodoro_import_csv_btn))
                }
            }
        }

        // ---- 累计数据（总计沉底）----
        //
        // 用户诉求：「统计数据越是接近今日的就往上排，总计这种就
        // 往下丢」。累计是跨全部时间的口径，与任何「最近」都无关，
        // 放最底部——但它仍不受上方时间范围 chips 影响。
        item {
            StatsCard(title = stringResource(R.string.pomodoro_stats_cumulative)) {
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

        // ---- 今日专注打卡海报（v0.0.49）----
        //
        // 与词库统计页的「打卡海报」是同一套东西：本地渲染 / 生图背景 / 整图生图。
        // 这里讲的是**专注**，那边讲的是**背单词**。
        item {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            suspend fun saveAndShare(bitmap: android.graphics.Bitmap) {
                val uri = withContext(Dispatchers.IO) {
                    com.mistakebook.wordbook.ui.savePosterToGallery(
                        context = context,
                        bitmap = bitmap,
                        displayName = "登临专注打卡-${System.currentTimeMillis()}.png"
                    )
                }
                if (uri != null) {
                    com.mistakebook.wordbook.ui.sharePoster(context, uri)
                }
            }

            // 生图走**后台任务**：离开页面照跑，回来能取回结果。
            val posterWork = posterWorkMonitor?.let { monitor ->
                com.mistakebook.poster.rememberPosterWorkHandler(
                    monitor = monitor,
                    onBitmap = { bitmap -> scope.launch { saveAndShare(bitmap) } },
                    onFailed = { message -> onPosterFailed(message) }
                )
            }
            val generating = posterWork?.value is com.mistakebook.poster.PosterWorkState.Running

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // stringResource 只能在组合期取：先拿好文案再进协程。
                val posterStatsHeader = stringResource(R.string.poster_stats_header)
                val posterFooterBrand = stringResource(R.string.poster_footer_brand)
                OutlinedButton(
                    onClick = {
                        if (generating) return@OutlinedButton
                        scope.launch {
                            val bitmap = withContext(Dispatchers.Default) {
                                com.mistakebook.poster.PosterRenderer.render(
                                    buildPomodoroPosterContent(
                                        state,
                                        statsHeader = posterStatsHeader,
                                        footerText = posterFooterBrand
                                    )
                                )
                            }
                            saveAndShare(bitmap)
                        }
                    },
                    enabled = !generating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.poster_checkin_today_btn))
                }

                OutlinedButton(
                    onClick = {
                        if (generating) return@OutlinedButton
                        onGeneratePoster(com.mistakebook.poster.PosterImageMode.BACKGROUND)
                    },
                    enabled = !generating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (generating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.poster_bg_generating))
                    } else {
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.poster_checkin_with_bg))
                    }
                }

                OutlinedButton(
                    onClick = {
                        if (generating) return@OutlinedButton
                        onGeneratePoster(com.mistakebook.poster.PosterImageMode.FULL)
                    },
                    enabled = !generating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (generating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.poster_generating))
                    } else {
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.poster_checkin_btn))
                    }
                }

                if (generating) {
                    Text(
                        text = stringResource(R.string.poster_bg_notice),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.pomodoro_reset_stats_title)) },
            text = { Text(stringResource(R.string.pomodoro_reset_stats_confirm)) },
            confirmButton = {
                // 两步确认：不可恢复操作，确认键也要再点一次才执行
                com.mistakebook.ui.common.TwoStepConfirmTextButton(
                    text = stringResource(R.string.action_reset),
                    confirmText = stringResource(R.string.action_confirm_reset),
                    onConfirm = {
                        confirmReset = false
                        onResetStats()
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
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
    StatsCard(title = stringResource(R.string.pomodoro_stats_single_day)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPrev) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = stringResource(R.string.pomodoro_cd_prev_day))
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
                Icon(Icons.Outlined.ChevronRight, contentDescription = stringResource(R.string.pomodoro_cd_next_day))
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
                    text = stringResource(R.string.pomodoro_week_n, index + 1),
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
        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
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
    // 休息会话由到点自动落库（只为「最近专注」可追溯），统计口径已排除，
    // 这里必须带标签区分——不然用户会奇怪时长怎么对不上
    "BREAK" -> "休息"
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
            text = stringResource(R.string.pomodoro_bar_selected_summary, dayText, bars[selected].first, formatDuration(bars[selected].second)),
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


/**
 * 饼图配色（v0.0.128，issue #48「番茄钟统计数据的颜色选择太丑了，换鲜亮的颜色」）。
 *
 * 旧配色的八个暖灰（赭石/灰绿/麦黄…）是 v0.0.57 为「克制」定的，
 * 实际看是一片土色：扇区之间分不清，整块统计像蒙了层灰。
 * 换成高彩度的一组（色相拉开、明度接近），深浅主题都够亮。
 * 色值放在 Theme 的图表色板里——界面层不写裸色值的契约管组件，
 * 成体系的调色表归主题。
 */
private fun pieColor(index: Int): Color =
    com.mistakebook.ui.theme.chartColor(index)


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
                    text = stringResource(R.string.pomodoro_subject_count, rows.size),
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
            text = stringResource(R.string.pomodoro_slice_summary, (percent * 100).roundToInt(), sessionCount, formatDuration(sec)),
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


private fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}小时${m}分" else "${m}分钟"
}

