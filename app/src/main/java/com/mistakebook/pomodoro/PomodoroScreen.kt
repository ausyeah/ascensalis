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

fun PomodoroScreen(container: AppContainer) {
    val viewModel: PomodoroViewModel = containerViewModel(container) { c ->
        PomodoroViewModel(
            app = c.appContext as android.app.Application,
            dao = c.pomodoroDao,
            subjectDao = c.subjectDao,
            timer = c.pomodoroTimer,
            subjectsFlow = c.subjectsFlow,
            posterImageService = c.posterImageService,
            posterWorkMonitorRef = c.pomodoroPosterMonitor
        )
    }
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.consumeMessage()
    }

    // Tab 与左右滑动手势（v0.0.80）：使用 HorizontalPager 支持流畅左右滑屏。
    val initialTab = if (state.mode == FocusMode.STOPWATCH) 1 else 0
    val pagerState = rememberPagerState(initialPage = initialTab) { 3 }
    val scope = rememberCoroutineScope()

    // 页面滑动与模式联动
    LaunchedEffect(pagerState.currentPage) {
        if (state.status != RunStatus.RUNNING) {
            if (pagerState.currentPage == 0 && state.mode != FocusMode.POMODORO) {
                viewModel.setMode(FocusMode.POMODORO)
            } else if (pagerState.currentPage == 1 && state.mode != FocusMode.STOPWATCH) {
                viewModel.setMode(FocusMode.STOPWATCH)
            }
        }
    }

    LaunchedEffect(state.mode) {
        // 长按主按钮循环模式时，Tab 指示器与 Pager 跟着切。
        // 护栏（v0.0.84 修「左右滑动卡在一半」）：拖页/惯性滑动中绝不回写——
        // 早先 setMode 异步落地撞上手势中段，scrollToPage 会把拖到一半的
        // 页面瞬间拽走。顺带改成 animate，跳页不再生硬。
        val targetPage = if (state.mode == FocusMode.STOPWATCH) 1 else 0
        if (!pagerState.isScrollInProgress &&
            pagerState.currentPage != targetPage &&
            pagerState.currentPage != 2
        ) {
            pagerState.animateScrollToPage(targetPage)
        }
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
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(0) }
                        if (state.status != RunStatus.RUNNING) {
                            viewModel.setMode(FocusMode.POMODORO)
                        }
                    },
                    enabled = state.status != RunStatus.RUNNING,
                    text = { Text(stringResource(R.string.pomodoro_tab_focus)) }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(1) }
                        if (state.status != RunStatus.RUNNING) {
                            viewModel.setMode(FocusMode.STOPWATCH)
                        }
                    },
                    enabled = state.status != RunStatus.RUNNING,
                    text = { Text(stringResource(R.string.pomodoro_tab_stopwatch)) }
                )
                Tab(
                    selected = pagerState.currentPage == 2,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(2) }
                    },
                    text = { Text(stringResource(R.string.pomodoro_tab_stats)) }
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                when (page) {
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
                        onGeneratePoster = { mode ->
                            viewModel.generateFocusPoster(mode)
                        },
                        posterWorkMonitor = viewModel.posterWorkMonitor,
                        onPosterFailed = { message -> viewModel.showPosterError(message) },
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

