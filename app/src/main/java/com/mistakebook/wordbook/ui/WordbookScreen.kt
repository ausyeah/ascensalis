package com.mistakebook.wordbook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistakebook.di.AppContainer
import com.mistakebook.ui.common.containerViewModel
import com.mistakebook.ui.theme.LocalDarkTheme
import com.mistakebook.ui.theme.PaperBorderLight
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
                    masteredCount = state.masteredCount
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

                // 外部变更 tab 时驱动 pager 滚动
                LaunchedEffect(state.currentTab) {
                    if (pagerState.currentPage != state.currentTab.ordinal) {
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
                            state = state
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
    masteredCount: Int
) {
    val isDark = LocalDarkTheme.current
    val okColor = if (isDark) com.mistakebook.ui.theme.PaperOkDark else PaperOk
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
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
                label = "今日已学",
                value = "$studiedToday",
                accentColor = okColor,
                bgColor = okColor.copy(alpha = if (isDark) 0.16f else 0.10f),
                modifier = Modifier.weight(1f)
            )
            SummaryStatBadge(
                label = "待复习错词",
                value = "$wrongCount",
                accentColor = if (wrongCount > 0) errorColor else MaterialTheme.colorScheme.onSurfaceVariant,
                bgColor = if (wrongCount > 0) errorColor.copy(alpha = if (isDark) 0.16f else 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.weight(1f)
            )
            SummaryStatBadge(
                label = "已掌握",
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

@Composable
private fun StudyView(
    state: WordbookUiState,
    viewModel: WordbookViewModel
) {
    val isDark = LocalDarkTheme.current
    val currentWord = state.currentWord

    if (currentWord == null && !state.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "🎉 太棒了，当前词库学习已告一段落！",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "错词本与待复习列表已清空，可去词库检索更多生词",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = viewModel::prepareNextQuestion) {
                    Text("抽取练习题")
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 模式切换与熟词标记操作栏
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = state.studyMode == StudyMode.QUIZ,
                        onClick = { viewModel.setStudyMode(StudyMode.QUIZ) },
                        label = { Text("四选一模式") }
                    )
                    FilterChip(
                        selected = state.studyMode == StudyMode.CARD,
                        onClick = { viewModel.setStudyMode(StudyMode.CARD) },
                        label = { Text("卡片自测") }
                    )
                    FilterChip(
                        selected = state.studyMode == StudyMode.SENSE,
                        onClick = { viewModel.setStudyMode(StudyMode.SENSE) },
                        label = { Text("义项辨析") }
                    )
                }

                // 熟词标记按钮
                val isMastered = state.currentProgress?.isMastered == true
                IconButton(onClick = viewModel::toggleMasterCurrentWord) {
                    Icon(
                        imageVector = if (isMastered) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "熟词标记",
                        tint = if (isMastered) PaperLv1 else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 「上一题」：回看刚做过的词。历史栈为空时置灰。
        // 放在题干卡片上方——先看到入口，再看到内容，符合回看的操作顺序。
        if (state.hasPrevious) {
            item(key = "prev_question") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    TextButton(onClick = viewModel::previousQuestion) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("上一题", fontSize = 14.sp)
                    }
                }
            }
        }

        // 单词主纸质卡片
        if (currentWord != null) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (state.studyMode == StudyMode.SENSE) {
                            // 义项辨析专属题干
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isDark) Color(0xFF232A46) else PaperPrimaryTint,
                                modifier = Modifier.padding(bottom = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isDark) Color(0xFF91A7FF) else PaperPrimary
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "义项辨析 · 熟词僻义自测",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isDark) Color(0xFF91A7FF) else PaperPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Text(
                                text = state.currentSense ?: currentWord.meaning,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                lineHeight = 34.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "请在下方选出与上述义项匹配的英文单词：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // 作答后展示原词与全部释义。
                            //
                            // 不用 AnimatedVisibility：它的 exit 渐隐期间 content
                            // 仍会组合渲染——切题时新词的释义会先闪一下再消失
                            // （用户实测「还没选就出现了」）。改为显式 alpha：
                            // 隐藏时 snap() 瞬间归零立即停止渲染，显示时才渐显。
                            val senseAlpha by animateFloatAsState(
                                targetValue = if (state.isAnswered) 1f else 0f,
                                animationSpec = if (state.isAnswered) tween(220) else snap(),
                                label = "senseAnswerAlpha"
                            )
                            if (senseAlpha > 0.01f) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .alpha(senseAlpha),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Spacer(Modifier.height(18.dp))
                                    HorizontalDivider(
                                        modifier = Modifier.fillMaxWidth(),
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    Text(
                                        text = currentWord.word,
                                        fontSize = 26.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Serif,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (currentWord.pos.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = currentWord.pos,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = currentWord.full.ifBlank { currentWord.meaning },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 22.sp
                                    )
                                }
                            }
                        } else {
                            // 常规四选一 / 卡片模式题干
                            Text(
                                text = currentWord.word,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )

                            if (currentWord.pos.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = currentWord.pos,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // 作答后或卡片揭晓后展示完整中文释义。
                            // 同 SENSE 分支：不用 AnimatedVisibility，避免 exit
                            // 渐隐期间新词释义闪现；隐藏即时、显示渐显。
                            val meaningAlpha by animateFloatAsState(
                                targetValue = if (state.isAnswered || state.isCardRevealed) 1f else 0f,
                                animationSpec = if (state.isAnswered || state.isCardRevealed) tween(220) else snap(),
                                label = "meaningAlpha"
                            )
                            if (meaningAlpha > 0.01f) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .alpha(meaningAlpha)
                                ) {
                                    Spacer(Modifier.height(18.dp))
                                    HorizontalDivider(
                                        modifier = Modifier.fillMaxWidth(),
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        text = currentWord.full.ifBlank { currentWord.meaning },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 24.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 模式分支交互
            if (state.studyMode == StudyMode.QUIZ || state.studyMode == StudyMode.SENSE) {
                // 单选题列表（QUIZ 或 SENSE）
                // key 用单词本身：选项集合变化时 Compose 才能正确复用/重排
                // item，而不是把四个选项整组扔掉重建（滚动卡顿的来源之一）。
                items(state.options, key = { it.word.word }) { option ->
                    QuizOptionRow(
                        option = option,
                        isSenseMode = state.studyMode == StudyMode.SENSE,
                        isAnswered = state.isAnswered,
                        onSelect = {
                            val idx = state.options.indexOf(option)
                            if (idx >= 0) viewModel.selectOption(idx)
                        }
                    )
                }

                // 答错时展示「下一题」大按钮供看清错因并手动推进；
                // 答对时由系统 380ms 跟手自动切题，无需手动点击。
                val selectedOption = state.options.getOrNull(state.selectedOptionIndex ?: -1)
                val isWrong = state.isAnswered && (selectedOption?.isCorrect == false)
                if (isWrong) {
                    item {
                        Spacer(Modifier.height(14.dp))
                        val correctOption = state.options.firstOrNull { it.isCorrect }
                        val wrongExplanation = if (state.studyMode == StudyMode.SENSE && correctOption != null) {
                            "正确答案：${correctOption.word.word}（${correctOption.word.meaning}）"
                        } else {
                            "正确答案：${correctOption?.word?.meaning ?: ""}"
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDark) Color(0xFF381518) else Color(0xFFFFF0F0)
                            ),
                            border = CardDefaults.outlinedCardBorder().copy(
                                brush = androidx.compose.ui.graphics.SolidColor(
                                    if (isDark) Color(0xFFFF6B6B) else Color(0xFFC92A2A)
                                )
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFFFF6B6B) else Color(0xFFC92A2A),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "答错了（已加入错题本待复习）",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDark) Color(0xFFFFA8A8) else Color(0xFFC92A2A)
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = wrongExplanation,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isDark) Color(0xFFFFE8E8) else Color(0xFF821818)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = viewModel::prepareNextQuestion,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("下一题", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            } else {
                // 卡片模式：翻面揭晓与四档打分
                item {
                    if (!state.isCardRevealed) {
                        Button(
                            onClick = viewModel::revealCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("点击揭晓完整释义", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    } else {
                        // 四档掌握度打分按钮
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RateButton("忘记了", MaterialTheme.colorScheme.error, Modifier.weight(1f)) { viewModel.rateCard(0) }
                                RateButton("模糊", PaperLv1, Modifier.weight(1f)) { viewModel.rateCard(1) }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RateButton("认识", PaperLv2, Modifier.weight(1f)) { viewModel.rateCard(2) }
                                RateButton("熟练", PaperLv3, Modifier.weight(1f)) { viewModel.rateCard(3) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RateButton(
    title: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color)
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun QuizOptionRow(
    option: QuizOption,
    isSenseMode: Boolean = false,
    isAnswered: Boolean,
    onSelect: () -> Unit
) {
    val isDark = LocalDarkTheme.current

    // 背景色搭配：深浅色自适应，彻底解决深色模式下冒出惨白底与白字冲突的问题
    val bgColor = when {
        !isAnswered -> MaterialTheme.colorScheme.surface
        option.isCorrect -> {
            if (isDark) Color(0xFF133221) else Color(0xFFEDFBF1)
        }
        option.isSelected && !option.isCorrect -> {
            if (isDark) Color(0xFF381518) else Color(0xFFFFF0F0)
        }
        else -> {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        }
    }

    // 边框色
    val borderColor = when {
        !isAnswered -> MaterialTheme.colorScheme.outline
        option.isCorrect -> {
            if (isDark) Color(0xFF40C057) else Color(0xFF2B8A3E)
        }
        option.isSelected && !option.isCorrect -> {
            if (isDark) Color(0xFFFF6B6B) else Color(0xFFC92A2A)
        }
        else -> {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        }
    }

    // 选项字母圆形徽标背景色与文字色
    val badgeBgColor = when {
        isAnswered && option.isCorrect -> {
            if (isDark) Color(0xFF2B8A3E) else Color(0xFF2B8A3E)
        }
        isAnswered && option.isSelected -> {
            if (isDark) Color(0xFFC92A2A) else Color(0xFFC92A2A)
        }
        else -> {
            if (isDark) Color(0xFF2B3356) else PaperPrimaryTint
        }
    }

    val badgeTextColor = when {
        isAnswered && (option.isCorrect || option.isSelected) -> Color.White
        else -> {
            if (isDark) Color(0xFF91A7FF) else PaperPrimary
        }
    }

    // 词性标签颜色
    val posColor = when {
        isAnswered && option.isCorrect -> {
            if (isDark) Color(0xFF69DB7C) else Color(0xFF2B8A3E)
        }
        isAnswered && option.isSelected && !option.isCorrect -> {
            if (isDark) Color(0xFFFFA8A8) else Color(0xFFC92A2A)
        }
        isAnswered -> {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        }
        else -> {
            MaterialTheme.colorScheme.primary
        }
    }

    // 释义正文颜色：确保在深色或浅色卡片上对比度均在 10:1 以上清晰可读
    val meaningColor = when {
        isAnswered && option.isCorrect -> {
            if (isDark) Color(0xFFE6FCED) else Color(0xFF144D29)
        }
        isAnswered && option.isSelected && !option.isCorrect -> {
            if (isDark) Color(0xFFFFE8E8) else Color(0xFF821818)
        }
        isAnswered -> {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        }
        else -> {
            MaterialTheme.colorScheme.onSurface
        }
    }

    // 反馈图标颜色
    val iconColor = when {
        option.isCorrect -> if (isDark) Color(0xFF51CF66) else Color(0xFF2B8A3E)
        else -> if (isDark) Color(0xFFFF6B6B) else Color(0xFFC92A2A)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(enabled = !isAnswered, onClick = onSelect),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(borderColor))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(badgeBgColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option.label,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = badgeTextColor
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (isSenseMode) {
                    // 义项辨析模式：选项呈现英文单词
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = option.word.word,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif,
                            color = meaningColor
                        )
                        if (option.word.pos.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = option.word.pos,
                                style = MaterialTheme.typography.labelSmall,
                                color = posColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (isAnswered) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = option.word.meaning,
                            style = MaterialTheme.typography.bodySmall,
                            color = meaningColor.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    // 常规四选一模式：选项呈现中文释义
                    if (option.word.pos.isNotBlank()) {
                        Text(
                            text = option.word.pos,
                            style = MaterialTheme.typography.labelSmall,
                            color = posColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = option.word.meaning,
                        style = MaterialTheme.typography.bodyMedium,
                        color = meaningColor,
                        fontWeight = if (isAnswered && (option.isCorrect || option.isSelected)) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }

            if (isAnswered) {
                if (option.isCorrect) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = iconColor)
                } else if (option.isSelected) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = iconColor)
                }
            }
        }
    }
}

// =========================================================================
// 2. 错题本体系 (WrongBook View)
// =========================================================================

@Composable
private fun WrongBookView(
    state: WordbookUiState,
    viewModel: WordbookViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 三级子标签
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.wrongSubTab == WrongSubTab.WRONG_BOOK,
                onClick = { viewModel.setWrongSubTab(WrongSubTab.WRONG_BOOK) },
                label = { Text("错词本 (${state.wrongBookCount})") }
            )
            FilterChip(
                selected = state.wrongSubTab == WrongSubTab.EVER_WRONG,
                onClick = { viewModel.setWrongSubTab(WrongSubTab.EVER_WRONG) },
                label = { Text("曾错毕业本") }
            )
            FilterChip(
                selected = state.wrongSubTab == WrongSubTab.MASTERED,
                onClick = { viewModel.setWrongSubTab(WrongSubTab.MASTERED) },
                label = { Text("熟词本 (${state.masteredCount})") }
            )
        }

        if (state.wrongBookList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (state.wrongSubTab) {
                        WrongSubTab.WRONG_BOOK -> "错词本干干净净，暂无待复习错词 🎉"
                        WrongSubTab.EVER_WRONG -> "暂无曾错毕业词汇"
                        WrongSubTab.MASTERED -> "暂无手动标记的熟词"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.wrongBookList, key = { it.first.word }) { (word, progress) ->
                    WordItemCard(
                        word = word,
                        progress = progress,
                        showRemove = state.wrongSubTab == WrongSubTab.WRONG_BOOK,
                        onRemove = { viewModel.removeFromWrongBook(word.word) }
                    )
                }
            }
        }
    }
}

// =========================================================================
// 3. 词库检索 (Library View)
// =========================================================================

@Composable
private fun LibraryView(
    state: WordbookUiState,
    viewModel: WordbookViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = state.libraryKeyword,
            onValueChange = viewModel::setLibraryKeyword,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索 4356 个考研单词或中文释义…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        Spacer(Modifier.height(8.dp))

        // 等级筛选 Chip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                null to "全部",
                0 to "生词",
                1 to "模糊",
                2 to "认识",
                3 to "掌握"
            ).forEach { (lv, label) ->
                FilterChip(
                    selected = state.libraryFilterLevel == lv,
                    onClick = { viewModel.setLibraryFilterLevel(lv) },
                    label = { Text(label) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.libraryWords, key = { it.first.word }) { (word, progress) ->
                WordItemCard(
                    word = word,
                    progress = progress ?: WordProgress(word = word.word),
                    showRemove = false,
                    onRemove = {}
                )
            }
        }
    }
}

// =========================================================================
// 4. 统计看板 (Stats View)
// =========================================================================

@Composable
private fun StatsView(
    state: WordbookUiState
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
                        text = "词库总览与进度",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(12.dp))
                    val isDark = LocalDarkTheme.current
                    val progressRatio = (state.masteredCount.toFloat() / state.totalVocabCount.coerceAtLeast(1)).coerceIn(0f, 1f)
                    LinearProgressIndicator(
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
                        text = "已掌握 ${state.masteredCount} / ${state.totalVocabCount} 词 · ${(progressRatio * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            val isDark = LocalDarkTheme.current
            val okColor = if (isDark) com.mistakebook.ui.theme.PaperOkDark else PaperOk
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    title = "今日已学",
                    value = "${state.studiedTodayCount}",
                    unit = "词",
                    color = okColor,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "待复习错词",
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
    }
}

/**
 * 近 7 天学习量柱状图。
 *
 * 柱子用 Box 画而不是 Canvas：文字（日期/数量）交给 Text，
 * 免掉 Canvas 里量字号、处理密度的麻烦，且深浅色主题自动适配。
 */
@Composable
private fun WeeklyStudyChart(stats: List<DayStat>) {
    val dateFormat = remember {
        java.text.SimpleDateFormat("M/d", java.util.Locale.getDefault())
    }
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = "近 7 天学习量",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "柱高为当天去重学习词数，空档日期表示当天没有学习",
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
private fun WeeklyAccuracyCard(stats: List<DayStat>) {
    val isDark = LocalDarkTheme.current
    val total = stats.sumOf { it.totalCount }
    val correct = stats.sumOf { it.correctCount }
    val rate = if (total > 0) correct.toFloat() / total else null
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = "近 7 天正确率",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        if (rate == null) {
            Text(
                text = "近 7 天还没有作答记录，学几个单词再来看看吧",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LinearProgressIndicator(
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
                text = "${(rate * 100).toInt()}% · 答对 $correct / 共 $total 次",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatCard(
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


@Composable
private fun WordItemCard(
    word: Word,
    progress: WordProgress,
    showRemove: Boolean,
    onRemove: () -> Unit
) {
    val levelColor = when (progress.level) {
        1 -> PaperLv1
        2 -> PaperLv2
        3 -> PaperLv3
        else -> PaperLv0
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 掌握度竖条
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(levelColor)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = word.word,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif
                    )
                    if (progress.isMastered) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Star, contentDescription = null, tint = PaperLv1, modifier = Modifier.size(14.dp))
                    }
                    if (progress.isWrongBook) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "连对 ${progress.reps}/3",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = word.meaning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (showRemove) {
                TextButton(onClick = onRemove) {
                    Text("移出", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        }
    }
}
