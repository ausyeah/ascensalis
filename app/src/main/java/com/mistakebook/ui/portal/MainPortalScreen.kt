package com.mistakebook.ui.portal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistakebook.R
import com.mistakebook.di.AppContainer
import com.mistakebook.ui.home.HomeScreen
import com.mistakebook.ui.settings.SettingsScreen
import com.mistakebook.ui.theme.PaperPrimary
import com.mistakebook.wordbook.ui.WordbookScreen
import kotlinx.coroutines.launch

/**
 * 主门户界面：四核切换（0: 错题本, 1: 背单词, 2: 番茄钟, 3: 设置），支持左右滑动手势。
 *
 * ## 设置为什么是第 4 页而不是右上角图标
 *
 * 旧版设置藏在右上角齿轮里，用户找不到（「顶部连设置都没有」）。
 * 但它又不该和三个内容核平级占一个 Tab——设置是「用完就走」的
 * 功能性页面，放在 Tab 栏最右侧、不配图标，既保证一眼可见，
 * 又不喧宾夺主。设置页以内嵌模式挂在 pager 里，没有自己的顶栏，
 * 顶部 Tab 行始终可见，从任何一页都能一步跳到设置、再一步跳回来。
 */
@Composable
fun MainPortalScreen(
    container: AppContainer,
    filterDue: Boolean = false,
    filterDueRequest: Int = 0,
    pickedNotebookId: Long? = null,
    onNotebookPicked: (Long?) -> Unit = {},
    onAddByPhoto: () -> Unit,
    onCropImage: (String) -> Unit,
    onImportPdf: () -> Unit,
    onAddManual: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPrint: () -> Unit,
    onOpenNotebooks: () -> Unit = {},
    onOpenQuestion: (Long) -> Unit,
    onOpenChatList: () -> Unit = {}
) {
    val pagerState = rememberPagerState(initialPage = 0) { 4 }
    val scope = rememberCoroutineScope()
    // 翻页过程中 currentPage 会连续变化，直接读它会让**整个顶栏逐帧重组**
    // （Tab 行 + 右侧图标）。用 derivedStateOf 收敛成「真正变了才发」。
    val currentPage = remember { derivedStateOf { pagerState.currentPage } }.value

    // 返回键：不在首页时平滑回首页（防止误触直接退出应用）。
    // 停在设置页（第 4 页）时同样先回首页——设置的所有修改都是即时生效的，
    // 不存在「没保存」状态，不需要二次确认。
    BackHandler(enabled = currentPage != 0) {
        scope.launch {
            pagerState.animateScrollToPage(0)
        }
    }

    LaunchedEffect(filterDueRequest) {
        if (filterDueRequest > 0) {
            pagerState.animateScrollToPage(0)
        }
    }

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PortalTabRow(
                        selectedIndex = currentPage,
                        onSelectIndex = { index ->
                            scope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )

                    // 右侧图标只在错题本页出现：打印与对话列表是错题本的能力，
                    // 挂在其他页上是纯干扰。设置已升级为第 4 个 Tab，齿轮图标撤销。
                    if (currentPage == 0) {
                        IconButton(onClick = onOpenPrint, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = Icons.Outlined.Print,
                                contentDescription = stringResource(R.string.home_action_print),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = onOpenChatList, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = Icons.Outlined.Forum,
                                contentDescription = stringResource(R.string.chat_list_title),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) { page ->
            when (page) {
                0 -> {
                    HomeScreen(
                        container = container,
                        filterDue = filterDue,
                        filterDueRequest = filterDueRequest,
                        pickedNotebookId = pickedNotebookId,
                        onNotebookPicked = onNotebookPicked,
                        onAddByPhoto = onAddByPhoto,
                        onCropImage = onCropImage,
                        onImportPdf = onImportPdf,
                        onAddManual = onAddManual,
                        onOpenSettings = onOpenSettings,
                        onOpenPrint = onOpenPrint,
                        onOpenNotebooks = onOpenNotebooks,
                        onOpenQuestion = onOpenQuestion,
                        onOpenChatList = onOpenChatList
                    )
                }
                1 -> {
                    WordbookScreen(
                        container = container
                    )
                }
                2 -> {
                    com.mistakebook.pomodoro.PomodoroScreen(
                        container = container
                    )
                }
                3 -> {
                    // 内嵌设置：不显示自己的顶栏与返回键，门户 Tab 行即它的导航。
                    // onBack 不会以任何路径被调用（没有返回箭头可点），传空实现。
                    SettingsScreen(
                        container = container,
                        onBack = {},
                        embedded = true
                    )
                }
            }
        }
    }
}

/**
 * 门户顶部 Tab 行：错题本 / 英语单词 / 番茄钟 / 设置。
 *
 * 用「文字 + 底部指示条」而不是图标+文字的胶囊分段控件：
 * 四个 Tab 在 360dp 小屏上放图标必然拥挤或裁字，而文字本身
 * （错题本/英语单词/番茄钟/设置）表意已经完备，图标只是装饰。
 * 四个 Tab 等分宽度（weight(1f)），任何屏宽下都不会挤成一团。
 */
@Composable
private fun PortalTabRow(
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val titles = listOf(
        stringResource(R.string.portal_mistake_book),
        stringResource(R.string.portal_word_book),
        stringResource(R.string.portal_pomodoro),
        stringResource(R.string.settings_title)
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        titles.forEachIndexed { index, title ->
            val selected = selectedIndex == index
            val contentColor = if (selected) {
                PaperPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelectIndex(index) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = contentColor,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                // 选中指示条：未选中时留同等高度的透明占位，文字不跳动
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(3.dp)
                        .background(
                            color = if (selected) PaperPrimary else Color.Transparent,
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }
        }
    }
}
