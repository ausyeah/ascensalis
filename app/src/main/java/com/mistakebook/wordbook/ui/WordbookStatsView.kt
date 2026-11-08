package com.mistakebook.wordbook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistakebook.di.AppContainer
import com.mistakebook.data.prefs.SettingsStore
import com.mistakebook.ui.common.AppLinearProgressIndicator
import com.mistakebook.ui.common.containerViewModel
import com.mistakebook.ui.theme.LocalDarkTheme
import com.mistakebook.ui.theme.PaperBorderLight
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Share
import com.mistakebook.ui.theme.PaperLv0
import com.mistakebook.ui.theme.PaperLv1
import com.mistakebook.ui.theme.PaperLv2
import com.mistakebook.ui.theme.PaperLv3
import com.mistakebook.ui.theme.PaperOk
import com.mistakebook.ui.theme.PaperOkDark
import com.mistakebook.ui.theme.PaperPrimary
import com.mistakebook.ui.theme.PaperPrimaryTint
import com.mistakebook.wordbook.data.Word
import com.mistakebook.wordbook.data.WordProgress
import com.mistakebook.wordbook.algo.WordbookRules
import androidx.compose.ui.res.stringResource
import com.mistakebook.R

@Composable
internal fun StatsView(
    state: WordbookUiState,
    viewModel: WordbookViewModel
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.wordbook_library_overview),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(12.dp))
                    val isDark = LocalDarkTheme.current
                    val progressRatio = (state.masteredCount.toFloat() / state.totalVocabCount.coerceAtLeast(1)).coerceIn(0f, 1f)
                    AppLinearProgressIndicator(
                        progress = { progressRatio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = if (isDark) Color(0xFF282D42) else PaperPrimaryTint
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.wordbook_library_progress, state.masteredCount, state.totalVocabCount, (progressRatio * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 连续打卡 + 每日目标设置
        item {
            StreakAndGoalCard(
                streakDays = state.streakDays,
                dailyGoal = state.dailyGoal,
                studiedToday = state.studiedTodayCount,
                reminderEnabled = state.reminderEnabled,
                onGoalChange = viewModel::setDailyGoal,
                onReminderChange = viewModel::setReminderEnabled
            )
        }

        // 月度热力图
        item {
            MonthlyHeatmapCard(days = state.monthDayStats)
        }

        item {
            val isDark = LocalDarkTheme.current
            val okColor = if (isDark) com.mistakebook.ui.theme.PaperOkDark else PaperOk
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    title = stringResource(R.string.wordbook_stat_learned),
                    value = "${state.studiedTodayCount}",
                    unit = "词",
                    color = okColor,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = stringResource(R.string.wordbook_stat_due_wrong),
                    value = "${state.wrongBookCount}",
                    unit = "词",
                    color = if (state.wrongBookCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 近 7 天学习量柱状图：一天一柱，没学习的日期留空柱，
        // 一眼看出哪天学了多少、有没有断档。
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline))
            ) {
                WeeklyStudyChart(stats = state.weeklyDayStats)
            }
        }

        // 近 7 天正确率：总量 + 条形 + 百分比，替代原先的 Lv 分级规则卡。
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline))
            ) {
                WeeklyAccuracyCard(stats = state.weeklyDayStats)
            }
        }

        // 打卡海报（F-IMG-2）：本地 Canvas 渲染的一张图，
        // 不经过任何生图 API——数据是本地统计，画也是本地画。
        //
        // v0.0.49 补两个生图入口：
        // - 「背景图生成打卡」：生图模型只出背景，文字仍由本地 Canvas 画
        //   （中文清晰、数字绝对准确）；
        // - 「生图打卡」：文字内容也交给生图模型一起画（风格自由，
        //   但生图模型写中文偶尔会出错）。
        item {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            suspend fun saveAndShare(bitmap: android.graphics.Bitmap) {
                val uri = withContext(Dispatchers.IO) {
                    savePosterToGallery(
                        context = context,
                        bitmap = bitmap,
                        displayName = "登临打卡-${System.currentTimeMillis()}.png"
                    )
                }
                if (uri != null) {
                    sharePoster(context, uri)
                } else {
                    viewModel.showMessage("海报保存失败，请检查相册权限")
                }
            }

            // 生图走**后台任务**（WorkManager）：离开页面照跑，回来能取回结果。
            // 状态挂在 appScope 上，所以这里的订阅在页面重建后仍然接得上。
            val posterWork = com.mistakebook.poster.rememberPosterWorkHandler(
                monitor = viewModel.posterWorkMonitor,
                onBitmap = { bitmap ->
                    scope.launch { saveAndShare(bitmap) }
                },
                onFailed = { message -> viewModel.showMessage(message) }
            )
            val generating = posterWork.value is com.mistakebook.poster.PosterWorkState.Running

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
                                    buildPosterContent(
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
                        Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.poster_generate_btn))
                }

                // ---- 生图入口 1：只生成背景，文字本地叠 ----
                OutlinedButton(
                    onClick = {
                        if (generating) return@OutlinedButton
                        viewModel.generateCheckInPoster(
                            mode = com.mistakebook.poster.PosterImageMode.BACKGROUND
                        )
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
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.poster_checkin_with_bg))
                    }
                }

                // ---- 生图入口 2：文字也交给生图模型 ----
                OutlinedButton(
                    onClick = {
                        if (generating) return@OutlinedButton
                        viewModel.generateCheckInPoster(
                            mode = com.mistakebook.poster.PosterImageMode.FULL
                        )
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
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
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
    }
}


/**
 * 连续打卡 + 每日目标设置（统计页）。
 *
 * 目标用 -/+ 步进而不是输入框：手机上开输入框只为改一个数字
 * 太折腾，步进按钮点几下就到位（5~500 词范围，步长随档位自适应：
 * 100 以下 5 一档、200 以下 10 一档、再往上 25 一档——
 * 否则设到 400 要按 80 下 +）。
 */
@Composable
internal fun StreakAndGoalCard(
    streakDays: Int,
    dailyGoal: Int,
    studiedToday: Int,
    reminderEnabled: Boolean,
    onGoalChange: (Int) -> Unit,
    onReminderChange: (Boolean) -> Unit
) {
    val isDark = LocalDarkTheme.current
    val okColor = if (isDark) com.mistakebook.ui.theme.PaperOkDark else PaperOk

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.wordbook_streak_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "$streakDays",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (streakDays > 0) okColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.wordbook_streak_suffix),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.wordbook_streak_today, studiedToday),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.wordbook_goal_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.wordbook_goal_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onGoalChange(dailyGoal - goalStepFor(dailyGoal)) },
                    enabled = dailyGoal > SettingsStore.MIN_WORDBOOK_DAILY_GOAL
                ) {
                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.wordbook_cd_goal_minus))
                }
                Text(
                    text = "$dailyGoal",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 40.dp)
                )
                IconButton(onClick = { onGoalChange(dailyGoal + goalStepFor(dailyGoal)) }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.wordbook_cd_goal_plus))
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.wordbook_goal_reminder),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.wordbook_goal_reminder_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = reminderEnabled, onCheckedChange = onReminderChange)
            }
        }
    }
}


/**
 * 月度打卡热力图。
 *
 * 一格一天，7 列按「周一到周日」排（与国内日历习惯一致），
 * 当月 1 号不是周一就在第一行前面留空格。
 *
 * ## 色阶为什么固定 0–200 而不是按当月最大值归一
 *
 * 按 max 归一会让「这个月最多才学 30 词」的月份里，第 10 天就已经
 * 是最深档——颜色深浅完全取决于别人，跨月不可比，用户看到的就是
 * 「才 30 个就深得发黑了」。固定刻度后深浅只取决于绝对量：
 * 1~50 一档、51~100、101~150、150+ 满档，跟每日目标同一条尺子。
 *
 * 用 Box 画而不是 Canvas：颜色档位用 MaterialTheme 取，
 * 深浅色主题自动适配，不用处理 Canvas 里的密度换算。
 */
@Composable
internal fun MonthlyHeatmapCard(days: List<DayStat>) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline

    fun heatColor(count: Int): Color {
        if (count <= 0) return outline.copy(alpha = 0.22f)
        return when {
            count <= HEATMAP_SCALE_MAX / 4 -> primary.copy(alpha = 0.28f)
            count <= HEATMAP_SCALE_MAX / 2 -> primary.copy(alpha = 0.50f)
            count <= HEATMAP_SCALE_MAX * 3 / 4 -> primary.copy(alpha = 0.72f)
            else -> primary
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.wordbook_heatmap_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.wordbook_heatmap_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (days.isEmpty()) {
                Text(
                    text = stringResource(R.string.wordbook_heatmap_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            // 星期表头
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { w ->
                    Text(
                        text = w,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            // Calendar.DAY_OF_WEEK：周日=1 … 周六=7；转成「周一=0」的偏移
            val firstWeekday = (java.util.Calendar.getInstance().apply {
                timeInMillis = days.first().dayMs
            }.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
            // 固定色阶：当月学得再多也按 0–200 这条尺子量，
            // 跨月可比、与每日目标同口径（见 MonthlyHeatmapCard 注释）。
            val cells: List<DayStat?> = List(firstWeekday) { null } + days

            cells.chunked(7).forEach { week ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    week.forEach { d ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(heatColor(d?.studiedCount ?: 0))
                        )
                    }
                    // 最后一周不满 7 天时补空格，保证每行都是 7 格（列宽不被拉歪）
                    repeat(7 - week.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.wordbook_heatmap_less),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                // 图例五档：0 / 50 / 100 / 150 / 200+，与色阶边界一一对应
                listOf(0, 50, 100, 150, 200).forEach { count ->
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(heatColor(count))
                    )
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    text = stringResource(R.string.wordbook_heatmap_more),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


/**
 * 近 7 天学习量柱状图。
 *
 * 柱子用 Box 画而不是 Canvas：文字（日期/数量）交给 Text，
 * 免掉 Canvas 里量字号、处理密度的麻烦，且深浅色主题自动适配。
 */
@Composable
internal fun WeeklyStudyChart(stats: List<DayStat>) {
    val dateFormat = remember {
        java.text.SimpleDateFormat("M/d", java.util.Locale.getDefault())
    }
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = stringResource(R.string.wordbook_last7_volume),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.wordbook_last7_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            stats.forEach { stat ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        text = if (stat.studiedCount > 0) "${stat.studiedCount}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    val maxCount = stats.maxOfOrNull { it.studiedCount }?.coerceAtLeast(1) ?: 1
                    val barHeight = (78 * stat.studiedCount / maxCount).coerceAtLeast(2)
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .height(barHeight.dp)
                            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                            .background(
                                if (stat.studiedCount > 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            )
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = dateFormat.format(java.util.Date(stat.dayMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}


/** 近 7 天正确率总览。 */
@Composable
internal fun WeeklyAccuracyCard(stats: List<DayStat>) {
    val isDark = LocalDarkTheme.current
    val total = stats.sumOf { it.totalCount }
    val correct = stats.sumOf { it.correctCount }
    val rate = if (total > 0) correct.toFloat() / total else null
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = stringResource(R.string.wordbook_last7_accuracy),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        if (rate == null) {
            Text(
                text = stringResource(R.string.wordbook_last7_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            AppLinearProgressIndicator(
                progress = { rate },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = if (isDark) com.mistakebook.ui.theme.PaperOkDark else PaperOk,
                trackColor = if (isDark) Color(0xFF282D42) else PaperPrimaryTint
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.wordbook_last7_accuracy_value, (rate * 100).toInt(), correct, total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


@Composable
internal fun StatCard(
    title: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}



/**
 * 每日目标的调整步长（词），随当前档位自适应：
 * 100 以下 5 一档（精细），200 以下 10 一档，200 以上 25 一档。
 *
 * 固定 5 的话，从 200 调到 400 要按 40 下——步进器就成了体力活；
 * 固定大步长又会让小目标用户「点一下跳 25 词」失去微调能力。
 */
internal fun goalStepFor(goal: Int): Int = when {
    goal < 100 -> 5
    goal < 200 -> 10
    else -> 25
}


/**
 * 热力图色阶的固定上限（当日去重学习词数）。
 *
 * 与每日目标共用 200 这条基准线：目标默认 20、封顶 500，
 * 而 200 是「认真刷了一天」的合理刻度——超过 200 一律满档，
 * 颜色只为「今天学没学、学得多不多」服务，不做精确编码。
 */
internal const val HEATMAP_SCALE_MAX = 200

