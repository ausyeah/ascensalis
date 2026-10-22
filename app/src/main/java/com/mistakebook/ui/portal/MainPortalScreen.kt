package com.mistakebook.ui.portal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
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
import androidx.compose.runtime.derivedStateOf
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
import com.mistakebook.ui.theme.PaperPrimary
import com.mistakebook.wordbook.ui.WordbookScreen
import kotlinx.coroutines.launch

/**
 * 主门户界面：三核切换（0: 错题本, 1: 背单词, 2: 番茄钟），支持左右滑动手势。
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
    val pagerState = rememberPagerState(initialPage = 0) { 3 }
    val scope = rememberCoroutineScope()
    // 翻页过程中 currentPage 会连续变化，直接读它会让**整个顶栏逐帧重组**
    // （标题 + 分段控件 + 三个图标）。用 derivedStateOf 收敛成「真正变了才发」。
    val currentPage = remember { derivedStateOf { pagerState.currentPage } }.value

    // 当用户处于背单词页 (page 1) 时，拦截返回键平滑回到错题本首页 (page 0)，防止误触直接退出应用
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
                    Text(
                        text = when (currentPage) {
                            0 -> stringResource(R.string.portal_mistake_book)
                            1 -> stringResource(R.string.portal_word_book)
                            else -> stringResource(R.string.portal_pomodoro)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(72.dp)
                    )

                    PortalSegmentedControl(
                        selectedIndex = currentPage,
                        onSelectIndex = { index ->
                            scope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        }
                    )

                    Row(
                        modifier = Modifier.width(108.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                        IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = stringResource(R.string.home_action_settings),
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
            }
        }
    }
}

/**
 * 纸质化双选项胶囊分段组件。
 */
@Composable
private fun PortalSegmentedControl(
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PortalTabItem(
                title = stringResource(R.string.portal_mistake_book),
                icon = Icons.Outlined.Book,
                isSelected = selectedIndex == 0,
                onClick = { onSelectIndex(0) }
            )
            Spacer(Modifier.width(4.dp))
            PortalTabItem(
                title = stringResource(R.string.portal_word_book),
                icon = Icons.Outlined.Translate,
                isSelected = selectedIndex == 1,
                onClick = { onSelectIndex(1) }
            )
            Spacer(Modifier.width(4.dp))
            PortalTabItem(
                title = stringResource(R.string.portal_pomodoro),
                icon = Icons.Outlined.Timer,
                isSelected = selectedIndex == 2,
                onClick = { onSelectIndex(2) }
            )
        }
    }
}

@Composable
private fun PortalTabItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent
    val contentColor = if (isSelected) PaperPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            fontWeight = fontWeight,
            fontSize = 14.sp
        )
    }
}
