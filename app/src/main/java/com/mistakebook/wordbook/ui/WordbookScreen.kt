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
import androidx.compose.material3.LinearProgressIndicator
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

/**
 * 单词书主界面（纯本地化纸质书卷质感设计）。
 */
@Composable
fun WordbookScreen(
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    val viewModel: WordbookViewModel = containerViewModel(container) { WordbookViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部状态与打卡摘要
                WordbookHeaderSummary(
                    studiedToday = state.studiedTodayCount,
                    wrongCount = state.wrongBookCount,
                    masteredCount = state.masteredCount,
                    dueToday = state.dueTodayCount
                )

                val tabs = remember {
                    listOf(
                        WordbookTab.STUDY to "学习刷题",
                        WordbookTab.WRONG_BOOK to "错题本",
                        WordbookTab.LIBRARY to "词库检索",
                        WordbookTab.STATS to "学习统计"
                    )
                }
                val pagerState = rememberPagerState(initialPage = state.currentTab.ordinal) { tabs.size }
                val scope = rememberCoroutineScope()

                // 子 Tab 导航拦截：当用户处于「错词本」、「词库检索」或「学习统计」子页时，按返回键退回「学习刷题」主 Tab
                BackHandler(enabled = pagerState.currentPage != 0) {
                    scope.launch {
                        pagerState.animateScrollToPage(0)
                    }
                }

                // 外部变更 tab 时驱动 pager 滚动。
                // 护栏（v0.0.84 修「左右滑动卡在一半」）：用户正在拖页或页面还在
                // 惯性滑动时绝不回写——早先拖页过半触发 setTab，异步落地的
                // state.currentTab 撞上手势中段，animateScrollToPage 会把拖到
                // 一半的页面拽走，表现就是页面卡在中间/被拽回。
                LaunchedEffect(state.currentTab) {
                    if (!pagerState.isScrollInProgress &&
                        pagerState.targetPage != state.currentTab.ordinal
                    ) {
                        pagerState.animateScrollToPage(state.currentTab.ordinal)
                    }
                }

                // 用户滑动手势切页时同步 viewModel
                LaunchedEffect(pagerState.currentPage) {
                    val targetTab = tabs[pagerState.currentPage].first
                    if (state.currentTab != targetTab) {
                        viewModel.setTab(targetTab)
                    }
                }

                // 核心功能标签栏
                WordbookTabBar(
                    selectedTabIndex = pagerState.currentPage,
                    onSelectTabIndex = { index ->
                        scope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    }
                )

                // 各子视图水平滑动切换
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    when (tabs[page].first) {
                        WordbookTab.STUDY -> StudyView(
                            state = state,
                            viewModel = viewModel
                        )
                        WordbookTab.WRONG_BOOK -> WrongBookView(
                            state = state,
                            viewModel = viewModel
                        )
                        WordbookTab.LIBRARY -> LibraryView(
                            state = state,
                            viewModel = viewModel
                        )
                        WordbookTab.STATS -> StatsView(
                            state = state,
                            viewModel = viewModel
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            )
        }
    }
}

// =========================================================================
// 顶部状态栏与 Tab 切换
// =========================================================================


@Composable
private fun WordbookHeaderSummary(
    studiedToday: Int,
    wrongCount: Int,
    masteredCount: Int,
    dueToday: Int = 0
) {
    val isDark = LocalDarkTheme.current
    val okColor = if (isDark) com.mistakebook.ui.theme.PaperOkDark else PaperOk
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SummaryStatBadge(
                label = stringResource(R.string.wordbook_stat_learned),
                value = "$studiedToday",
                accentColor = okColor,
                bgColor = okColor.copy(alpha = if (isDark) 0.16f else 0.10f),
                modifier = Modifier.weight(1f)
            )
            SummaryStatBadge(
                label = stringResource(R.string.wordbook_stat_due_wrong),
                value = "$wrongCount",
                accentColor = if (wrongCount > 0) errorColor else MaterialTheme.colorScheme.onSurfaceVariant,
                bgColor = if (wrongCount > 0) errorColor.copy(alpha = if (isDark) 0.16f else 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.weight(1f)
            )
            // 今日到期（v0.0.64 SRS 接入）：间隔复习到点的词数。
            // 与「待复习错词」口径不同——那是错词本库存，这是排期到期。
            SummaryStatBadge(
                label = stringResource(R.string.wordbook_stat_due_today),
                value = "$dueToday",
                accentColor = if (dueToday > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                bgColor = if (dueToday > 0) {
                    MaterialTheme.colorScheme.tertiary.copy(alpha = if (isDark) 0.16f else 0.10f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
                modifier = Modifier.weight(1f)
            )
            SummaryStatBadge(
                label = stringResource(R.string.wordbook_stat_mastered),
                value = "$masteredCount",
                accentColor = primaryColor,
                bgColor = primaryColor.copy(alpha = if (isDark) 0.16f else 0.10f),
                modifier = Modifier.weight(1f)
            )
        }
    }
}


@Composable
private fun SummaryStatBadge(
    label: String,
    value: String,
    accentColor: Color,
    bgColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}


@Composable
private fun WordbookTabBar(
    selectedTabIndex: Int,
    onSelectTabIndex: (Int) -> Unit
) {
    val tabs = listOf(
        WordbookTab.STUDY to "学习刷题",
        WordbookTab.WRONG_BOOK to "错题本",
        WordbookTab.LIBRARY to "词库检索",
        WordbookTab.STATS to "学习统计"
    )

    TabRow(
        selectedTabIndex = selectedTabIndex,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = {},
        indicator = { tabPositions ->
            if (selectedTabIndex in tabPositions.indices) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                    color = MaterialTheme.colorScheme.primary,
                    height = 3.dp
                )
            }
        }
    ) {
        tabs.forEachIndexed { index, (_, title) ->
            val isSelected = selectedTabIndex == index
            Tab(
                selected = isSelected,
                onClick = { onSelectTabIndex(index) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            )
        }
    }
}

// =========================================================================
// 1. 刷题页面 (Study View)
// =========================================================================

