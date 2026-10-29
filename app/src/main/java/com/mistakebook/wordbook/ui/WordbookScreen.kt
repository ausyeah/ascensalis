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

/**
 * 单词场景图（F-IMG-1）：三态渲染——生成中 / 已有图 / 未生成。
 *
 * ## 为什么按钮常驻而不是藏在菜单里
 *
 * 场景图是「想看就看」的辅助，不是答题的一部分。常驻一颗按钮，
 * 出过图的词切回来直接显示（磁盘缓存命中），没出过图的给入口。
 *
 * ## 反馈纪律
 *
 * 生成中按钮转 spinner + 「生成中…」：生图是 5~15 秒的操作，
 * 没有任何反馈用户会以为按钮坏了而连点（连点会真的多烧几次额度）。
 */
@Composable
private fun WordSceneImage(
    state: WordbookUiState,
    viewModel: WordbookViewModel
) {
    Spacer(Modifier.height(8.dp))
    if (state.sceneImageLoading) {
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
            Text("生成中…")
        }
        return
    }
    val path = state.sceneImagePath
    if (path != null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column {
                AsyncImage(
                    model = path,
                    contentDescription = "场景图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
                // 展示模型写的场景描述：让用户知道这张图是怎么来的，
                // 也便于判断「图不对」是 prompt 的问题还是生图模型的问题。
                if (!state.sceneImagePrompt.isNullOrBlank()) {
                    Text(
                        text = state.sceneImagePrompt,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
        return
    }
    OutlinedButton(
        onClick = viewModel::generateSceneImage,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(
            Icons.Default.Landscape,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text("场景图")
    }
}

@Composable
internal fun StudyView(
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
        // 每日目标进度条已从刷题页撤下：它把首屏占掉一截，而同样的信息
        // （今日已学 / 目标 / 打卡）在「学习统计」页都有完整呈现——
        // 刷题页每一寸都留给题目本身。

        // 模式切换 + 上一题操作栏（熟词/生词标记已并入卡片右上角工具位）
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 「上一题」：常驻圆形撤回按钮。
                //
                // 三个实测反馈一次解决：
                // (a) 形态：原来是带箭头的文字 TextButton，太宽。现在 36dp
                //     正圆 + 一枚撤回（Undo）图标，窄而不突兀；
                // (b) 布局：**永远占位**。过去只有存在历史时才渲染，
                //     它一出现右侧四个模式 chip 就被推着位移；现在灰态
                //     也占住同一个位置，整行布局纹丝不动；
                // (c) 状态：没有上一个词时置灰（底色与图标同时降透明度），
                //     且点击禁用——看得见「有这个东西」，也看得出「现在
                //     还不能用」。
                val hasPrev = state.hasPrevious
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (hasPrev) MaterialTheme.colorScheme.surfaceContainerHighest
                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f)
                        )
                        .clickable(enabled = hasPrev, onClick = viewModel::previousQuestion),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "上一题",
                        tint = if (hasPrev) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 四个模式 chip 等宽铺满整行剩余宽度。
                // 熟词星标已移到卡片右上角工具位（与发音/收藏并排），
                // 不再占这一行的固定右侧位——用户反馈「别占一排的固定位置」。
                //
                // 「不是均衡位置」的根因：原来用 horizontalScroll + 自然宽度，
                // 「四选一」（3 字）比「卡片」「义项」「拼写」（2 字）宽，
                // 四个 chip 挤在左边、右边留一大块空白，看起来就歪。
                // 改成 weight(1f) 等宽 + 文字居中，四个格子严丝合缝，
                // 谁也不比谁宽——切换模式时选中态的移动也更好看。
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = state.studyMode == StudyMode.QUIZ,
                        onClick = { viewModel.setStudyMode(StudyMode.QUIZ) },
                        label = { ChipLabel("四选一") }
                    )
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = state.studyMode == StudyMode.CARD,
                        onClick = { viewModel.setStudyMode(StudyMode.CARD) },
                        label = { ChipLabel("卡片") }
                    )
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = state.studyMode == StudyMode.SENSE,
                        onClick = { viewModel.setStudyMode(StudyMode.SENSE) },
                        label = { ChipLabel("义项") }
                    )
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = state.studyMode == StudyMode.SPELL,
                        onClick = { viewModel.setStudyMode(StudyMode.SPELL) },
                        label = { ChipLabel("拼写") }
                    )
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
                    // 圆角按「外大内小」的层级：容器 28 / 卡片 16 / 卡内元素 8~12。
                    // 原来是 20，与内部 chip 的 12 太接近，两层边界糊在一起。
                    shape = RoundedCornerShape(16.dp),
                    // 不用阴影：深色下阴影几乎不可见，浅色下这点 2dp 只会让卡片
                    // 边缘发灰。层级交给容器色阶与 1dp 描边表达。
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline))
                ) {
                    // 发音 + 收藏固定在卡片右上角：原来单独占一行把卡片
                    // 撑高了一截，而这两个图标一年到头都长在同一个位置，
                    // 右上角是「工具位」的常识摆放，不占正文空间。
                    Box(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
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

                            // 义项辨析模式下**不给发音入口**：发音等于直接
                            // 报答案（听到读音就能选出对应单词），练义项
                            // 辨析的意义就没了。收藏与对错无关，右上角照常给。
                            // 答错后展示原词时再补一个发音按钮（见下方
                            // 揭晓区），让用户对答案建立「音-义」关联。

                            // 作答后展示原词与全部释义。
                            //
                            // **只在答错时展示**：答对后 380ms 就自动切题，
                            // 释义闪现一下没有意义；答错时用户正需要
                            // 「正确答案 + 完整释义」来理解错因。
                            // （学习 github.com/ausyeah/wordbook：答对只出
                            // 「✓ 回答正确！」反馈，答错才给正确答案。）
                            //
                            // 不用 AnimatedVisibility：它的 exit 渐隐期间 content
                            // 仍会组合渲染——切题时新词的释义会先闪一下再消失
                            // （用户实测「还没选就出现了」）。改为显式 alpha：
                            // 隐藏时 snap() 瞬间归零立即停止渲染，显示时才渐显。
                            val senseSelected = state.options.getOrNull(state.selectedOptionIndex ?: -1)
                            val senseAnsweredWrong = state.isAnswered && senseSelected?.isCorrect == false
                            val senseAlpha by animateFloatAsState(
                                targetValue = if (senseAnsweredWrong) 1f else 0f,
                                animationSpec = if (senseAnsweredWrong) tween(220) else snap(),
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = currentWord.word,
                                            fontSize = 26.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Serif,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        // 答错后才出现发音按钮：此时答案已揭晓，
                                        // 听一遍建立「音-义-形」关联不再泄露答案。
                                        IconButton(
                                            onClick = viewModel::speakCurrentWord,
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                                contentDescription = "朗读单词",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
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
                        } else if (state.studyMode == StudyMode.SPELL) {
                            // 拼写模式题干：只给中文释义，不给单词。
                            //
                            // 释义用 full（多义项全文）而不是 meaning（短释义）：
                            // meaning 只有一两个词，猜出英文很容易（一个中文
                            // 词对应一串英文候选）；full 里有词性、多义项、
                            // 近反义，猜的难度才够。
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
                                        text = "拼写模式 · 看释义写单词",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isDark) Color(0xFF91A7FF) else PaperPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Text(
                                text = currentWord.full.ifBlank { currentWord.meaning },
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                lineHeight = 34.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "根据上面的释义，拼写出对应的英文单词：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // 答错后揭晓正确拼写。
                            // 答对时不展示（600ms 后自动切题，闪现没意义），
                            // 答错时用户正需要「正确拼写 + 释义」来记牢。
                            if (state.spellSubmitted && !state.spellCorrect) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Spacer(Modifier.height(18.dp))
                                    HorizontalDivider(
                                        modifier = Modifier.fillMaxWidth(),
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = currentWord.word,
                                            fontSize = 26.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Serif,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        IconButton(
                                            onClick = viewModel::speakCurrentWord,
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                                contentDescription = "朗读单词",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    if (currentWord.pos.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = currentWord.pos,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // 发音/收藏在卡片右上角。这里的喇叭是「听一遍」：
                            // **不自动播放**，由用户自己决定听不听——自动播等于
                            // 直接把答案念出来，拼写练习就退化成听写；用户主动点
                            // 才是听写练习。
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

                            // 发音 + 收藏已移到卡片右上角，不再占据单词正下方的空间。

                            // 作答后或卡片揭晓后展示完整中文释义。
                            // **答对不展示**：答对即退出本轮（380ms 自动切题），
                            // 释义留给答错时理解错因；卡片模式是主动翻面，
                            // isCardRevealed 照常展示。
                            // 同 SENSE 分支：不用 AnimatedVisibility，避免 exit
                            // 渐隐期间新词释义闪现；隐藏即时、显示渐显。
                            val selectedOption = state.options.getOrNull(state.selectedOptionIndex ?: -1)
                            val answeredWrong = state.isAnswered && selectedOption?.isCorrect == false
                            val meaningAlpha by animateFloatAsState(
                                targetValue = if (answeredWrong || state.isCardRevealed) 1f else 0f,
                                animationSpec = if (answeredWrong || state.isCardRevealed) tween(220) else snap(),
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

                    // 右上角工具位：熟词星标 + 发音（义项辨析模式不给，发音=报答案）+ 收藏。
                    // 三个标记/工具按钮并排在一处，不各占一行的固定位。
                    Row(
                        modifier = Modifier.align(Alignment.TopEnd),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isMastered = state.currentProgress?.isMastered == true
                        IconButton(
                            onClick = viewModel::toggleMasterCurrentWord,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isMastered) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = if (isMastered) "取消熟词标记" else "标记熟词",
                                tint = if (isMastered) PaperLv1 else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        if (state.studyMode != StudyMode.SENSE) {
                            IconButton(
                                onClick = viewModel::speakCurrentWord,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "朗读单词",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = viewModel::toggleFavoriteCurrentWord,
                            modifier = Modifier.size(32.dp)
                        ) {
                            val fav = state.currentProgress?.isFavorite == true
                            Icon(
                                imageVector = if (fav) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (fav) "取消收藏" else "收进生词本",
                                tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                    }
                }
            }

            // 场景图（F-IMG-1）：紧跟题目卡之后，v0.0.55 反馈从「LazyColumn 最底部」上移。
            //
            // T7 当初把它下沉到最底部是为了「不占首屏」，但刷题页要滚过题目卡 +
            // 选项 + 打分按钮才到底，用户反馈「最下面的生图没了」——其实就是
            // 「不滚到很下面根本找不到」。辅助功能的入口找不到，等于没有。
            //
            // 现在放在题目卡正下方：未生成时是一颗 46dp 的按钮（首屏多占约 54dp，
            // 题目与选项仍在首屏内）；生成后是 200dp 的图 + 场景描述，此时把选项
            // 推下去是用户主动选择的结果，可接受，滚一下就到。
            if (currentWord != null) {
                item {
                    WordSceneImage(state = state, viewModel = viewModel)
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
                        // 回退/切模式重放回来的题允许改选：
                        // 保留刚才的选中状态（isAnswered 仍为 true，
                        // 于是对错颜色照旧显示），但选项不再被锁死。
                        allowReselect = state.allowReselect,
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
                } else {
                    // **常驻的前进按钮**：答过了叫「下一个词」（抽新题），
                    // 没答过叫「换批释义」（不换词，只重抽当前词的选项）。
                    //
                    // 答错已经有上面那颗「下一题」大按钮，这里就不重复了；
                    // 其余状态一律给出这颗次要按钮，原因是「回退」这条路径
                    // 之前是**死路**：点撤回回到上一个词后，答对的会自动
                    // 跳走，但改选答对、以及回退后重新看一眼的场景不会跳——
                    // 界面上没有任何能往前走的东西，只能去切模式或者杀进程。
                    // 有进有退才是完整的导航：撤得回来，也得走得出去。
                    item {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                // 已答错的大按钮是「下一题」（抽新词）；
                                // 未作答时这颗次要按钮是「换批释义」——
                                // 不换词，只把当前词的选项重抽一批
                                // （用户原话：要的是换释义，不是换词）。
                                if (state.isAnswered) viewModel.prepareNextQuestion()
                                else viewModel.reshuffleOptions()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                if (state.isAnswered) "下一个词" else "换批释义",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            } else if (state.studyMode == StudyMode.SPELL) {
                // 拼写模式：输入框 + 提交 + 结果反馈
                item {
                    SpellAnswerArea(state = state, viewModel = viewModel)
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

/**
 * 模式 chip 里的文字：撑满 chip 宽度并居中。
 *
 * 四个 chip 等宽（weight(1f)）之后，Material3 的 FilterChip 会把
 * label 靠左排——「四选一」三个字顶着左边，「拼写」两个字也顶着左边，
 * 视觉上又变成参差不齐。撑满 + 居中才是真正的均衡。
 */
@Composable
private fun ChipLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
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

/**
 * 拼写模式的作答区：输入框 + 提交 + 对错反馈。
 *
 * 三种状态：
 * - 未提交：输入框可编辑，下面一个「提交答案」大按钮；
 * - 答对：绿色对勾一行，600ms 后自动进入下一题（拼写比四选一多看一眼的时间）；
 * - 答错：红色卡片给出正确拼写（题干卡里也会揭晓），并给「下一题」按钮。
 *
 * 输入框用 IME「完成」动作提交：手机上单手打字时，键盘上的
 * 「完成/换行」键比伸手点屏幕上的按钮顺手得多。
 */
@Composable
private fun SpellAnswerArea(
    state: WordbookUiState,
    viewModel: WordbookViewModel
) {
    val isDark = LocalDarkTheme.current
    val okColor = if (isDark) com.mistakebook.ui.theme.PaperOkDark else PaperOk

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.spellInput,
            onValueChange = viewModel::setSpellInput,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("在这里拼写单词…") },
            singleLine = true,
            enabled = !state.spellSubmitted,
            isError = state.spellSubmitted && !state.spellCorrect,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
                keyboardType = KeyboardType.Text
            ),
            keyboardActions = KeyboardActions(onDone = { viewModel.submitSpell() }),
            trailingIcon = {
                if (!state.spellSubmitted) {
                    IconButton(
                        onClick = viewModel::submitSpell,
                        enabled = state.spellInput.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "提交答案")
                    }
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        Spacer(Modifier.height(12.dp))

        when {
            !state.spellSubmitted -> {
                Button(
                    onClick = viewModel::submitSpell,
                    enabled = state.spellInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        "提交答案",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            state.spellCorrect -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = okColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "拼写正确！",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = okColor
                    )
                }
            }

            else -> {
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
                                text = "拼写不对（已加入错词本待复习）",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFFFFA8A8) else Color(0xFFC92A2A)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "正确拼写：${state.currentWord?.word ?: ""}",
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
    }
}

/**
 * 每日目标进度条（刷题页顶部）。
 *
 * 达标了就把文案换成「今日目标已达成 🎉」并把进度条填满——
 * 明确的正反馈比一个 100% 的数字更让人愿意第二天继续。
 */
@Composable
private fun DailyGoalBar(studiedToday: Int, goal: Int) {
    val isDark = LocalDarkTheme.current
    val okColor = if (isDark) com.mistakebook.ui.theme.PaperOkDark else PaperOk
    val reached = goal > 0 && studiedToday >= goal
    val ratio = if (goal <= 0) 0f else (studiedToday.toFloat() / goal).coerceIn(0f, 1f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline))
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "今日目标",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (reached) "今日目标已达成 🎉" else "$studiedToday / $goal 词",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (reached) okColor else MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (reached) okColor else MaterialTheme.colorScheme.primary,
                trackColor = if (isDark) Color(0xFF282D42) else PaperPrimaryTint
            )
        }
    }
}

@Composable
private fun QuizOptionRow(
    option: QuizOption,
    isSenseMode: Boolean = false,
    isAnswered: Boolean,
    /** 已作答但允许改选（回退/切模式重放回来的题）。 */
    allowReselect: Boolean = false,
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
            // 已作答默认锁死；但 allowReselect 时（回退后重放）仍然可点，
            // 用户能改主意——「保留状态但可以修改」。
            .clickable(enabled = !isAnswered || allowReselect, onClick = onSelect),
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

            // 选项文本区。
            //
            // **固定高度是这个区的核心目的**：实测词库 4356 词——
            // 100% 有词性（最长 34 字符，独立一行放得下）、释义长度
            // p50=13 / p90=25 / p99=33 字符，中文 bodyMedium 一行约
            // 18 字，两行空间（48dp）覆盖 99% 的词。所以：
            //
            // - 释义区写死 48dp（QUIZ）/ 40dp（SENSE 作答后）：
            //   单行释义不塌高度、双行不撑高，四个选项从始至终一样高；
            // - 不写死 Card 总高度：ROM 行高有差异，写死会溢出裁剪，
            //   固定文本区高度则无论行高多少总高都恒定；
            // - 词性独立成行（QUIZ）：有没有词性、词性长短都不影响高度。
            //
            // 改之前的现象：词性有无（有的词没有）、释义一两行、
            // 作答后多一行释义——三种因素叠加，四个选项高矮不齐、
            // 切题时整组跳动，用户答题时要点的位置一直在飘。
            Column(modifier = Modifier.weight(1f)) {
                if (isSenseMode) {
                    // 义项辨析模式：选项呈现英文单词，词性内联小标同行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = option.word.word,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif,
                            color = meaningColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (option.word.pos.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = option.word.pos,
                                style = MaterialTheme.typography.labelSmall,
                                color = posColor,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    // 释义区**恒定占位**：未作答时也留着这 40dp。
                    // 四个选项在作答瞬间一起从 52dp 长到 96dp 也算「位置跳」，
                    // 未作答就预留空间，全程等高，切题/作答都不动。
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (isAnswered) option.word.meaning else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = meaningColor.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.height(OPTION_MEANING_SLOT_SENSE.dp)
                    )
                } else {
                    // 常规四选一模式：词性内联在释义行首（「n. 金属板」），
                    // 不再独立成行。整个文本区固定 40dp 高、内容垂直居中——
                    // 单行不偏上、双行放得下，四个选项从始至终一样高。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(OPTION_MEANING_SLOT_QUIZ.dp)
                    ) {
                        if (option.word.pos.isNotBlank()) {
                            Text(
                                text = option.word.pos,
                                style = MaterialTheme.typography.labelSmall,
                                color = posColor,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                        Text(
                            text = option.word.meaning,
                            style = MaterialTheme.typography.bodyMedium,
                            color = meaningColor,
                            fontWeight = if (isAnswered && (option.isCorrect || option.isSelected)) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
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
        // 四级子标签。数量角标（用户反馈）会随记词进度变化宽度，把整行
        // chip 挤得左右挪位——已去掉；四个 chip **等宽均分**整行且去掉
        // 横向滚动，位置在任何状态下都纹丝不动。「曾错毕业本」改名
        // 「曾错本」（用户口径）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.wrongSubTab == WrongSubTab.WRONG_BOOK,
                onClick = { viewModel.setWrongSubTab(WrongSubTab.WRONG_BOOK) },
                modifier = Modifier.weight(1f),
                label = { Text("错词本", maxLines = 1) }
            )
            FilterChip(
                selected = state.wrongSubTab == WrongSubTab.EVER_WRONG,
                onClick = { viewModel.setWrongSubTab(WrongSubTab.EVER_WRONG) },
                modifier = Modifier.weight(1f),
                label = { Text("曾错本", maxLines = 1) }
            )
            FilterChip(
                selected = state.wrongSubTab == WrongSubTab.MASTERED,
                onClick = { viewModel.setWrongSubTab(WrongSubTab.MASTERED) },
                modifier = Modifier.weight(1f),
                label = { Text("熟词本", maxLines = 1) }
            )
            FilterChip(
                selected = state.wrongSubTab == WrongSubTab.FAVORITE,
                onClick = { viewModel.setWrongSubTab(WrongSubTab.FAVORITE) },
                modifier = Modifier.weight(1f),
                label = { Text("生词本", maxLines = 1) }
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
                        WrongSubTab.FAVORITE -> "生词本空着——刷题或词库检索时点书签图标即可收藏"
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
                        onRemove = { viewModel.removeFromWrongBook(word.word) },
                        onSpeak = { viewModel.speakWord(word.word) },
                        onToggleFavorite = { viewModel.toggleFavorite(word.word) },
                        scenePath = viewModel.scenePathFor(word.word, word.meaning)
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
                    onRemove = {},
                    onSpeak = { viewModel.speakWord(word.word) },
                    onToggleFavorite = { viewModel.toggleFavorite(word.word) }
                )
            }
        }
    }
}

// =========================================================================
// 4. 统计看板 (Stats View)
// =========================================================================

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
                OutlinedButton(
                    onClick = {
                        if (generating) return@OutlinedButton
                        scope.launch {
                            val bitmap = withContext(Dispatchers.Default) {
                                com.mistakebook.poster.PosterRenderer.render(buildPosterContent(state))
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
                    Text("生成打卡海报")
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
                        Text("生图背景中…")
                    } else {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("用背景图生成打卡")
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
                        Text("生图中…")
                    } else {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("生图打卡")
                    }
                }

                if (generating) {
                    Text(
                        text = "生图在后台继续运行，离开这个页面也不会中断。",
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
private fun StreakAndGoalCard(
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
                text = "连续打卡",
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
                    text = "天连续学习",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "今天已学 $studiedToday 词（今天还没学不算断签）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "每日学习目标",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "每日新学去重词数目标",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onGoalChange(dailyGoal - goalStepFor(dailyGoal)) },
                    enabled = dailyGoal > SettingsStore.MIN_WORDBOOK_DAILY_GOAL
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "减少目标")
                }
                Text(
                    text = "$dailyGoal",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 40.dp)
                )
                IconButton(onClick = { onGoalChange(dailyGoal + goalStepFor(dailyGoal)) }) {
                    Icon(Icons.Default.Add, contentDescription = "增加目标")
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "每日目标提醒",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "到点还没达标时通知我（与错题复习提醒同一时刻）",
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
private fun MonthlyHeatmapCard(days: List<DayStat>) {
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
                text = "本月打卡热力图",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "每格一天，颜色越深当天学得越多",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (days.isEmpty()) {
                Text(
                    text = "本月还没有学习记录",
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
                    text = "少",
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
                    text = "多",
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
    onRemove: () -> Unit,
    onSpeak: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    /**
     * 场景图缩略图路径（已生成过才有值）。
     *
     * 错词本列表用它做**视觉锚点**：背单词时那张图比文字更容易被记起来，
     * 列表里露出 40dp 小图，扫一眼就能把词和场景对上。
     */
    scenePath: String? = null
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
                            // 毕业线抽成了 WordbookRules.GRADUATION_REPS：
                            // UI 文案与打分逻辑共用同一个常量，不会出现
                            // 「显示 x/2 实际要对 3 次」的割裂。
                            text = "连对 ${progress.reps}/${WordbookRules.GRADUATION_REPS}",
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

            // 场景图缩略图：出过图才占位置（没出过图的词布局不变）。
            if (scenePath != null) {
                AsyncImage(
                    model = scenePath,
                    contentDescription = "场景图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(6.dp))
            }

            // 列表里的发音与收藏：单词旁的即点即读/即收藏，
            // 不用进详情页（本项目不做单词详情页）。
            IconButton(onClick = onSpeak, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "朗读单词",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (progress.isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = if (progress.isFavorite) "取消收藏" else "收进生词本",
                    tint = if (progress.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
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

/**
 * 四选一选项的释义区高度（dp）。
 *
 * 写死文本区高度而不是 Card 总高度：词库实测释义 p99=33 字符，
 * 中文 bodyMedium 一行约 18 字，48dp（两行空间）覆盖 99% 的词；
 * 单行释义不塌、双行不撑，四个选项从始至终等高。
 * 详见 [QuizOptionRow] 里的注释。
 */
private const val OPTION_MEANING_SLOT_QUIZ = 40


/**
 * 义项辨析选项的释义区高度（dp）。
 *
 * bodySmall 一行约 22 字，40dp 两行覆盖 44 字——词库最长释义
 * 43 字符，刚好全覆盖。未作答时也恒定占位，作答不改变选项高度。
 */
private const val OPTION_MEANING_SLOT_SENSE = 36

/**
 * 每日目标的调整步长（词），随当前档位自适应：
 * 100 以下 5 一档（精细），200 以下 10 一档，200 以上 25 一档。
 *
 * 固定 5 的话，从 200 调到 400 要按 40 下——步进器就成了体力活；
 * 固定大步长又会让小目标用户「点一下跳 25 词」失去微调能力。
 */
private fun goalStepFor(goal: Int): Int = when {
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
private const val HEATMAP_SCALE_MAX = 200
