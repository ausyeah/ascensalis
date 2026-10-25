package com.mistakebook.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.di.AppContainer
import com.mistakebook.ui.common.containerViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenSession: (sessionId: Long, questionId: Long?) -> Unit
) {
    val viewModel = containerViewModel(container) { c ->
        ChatListViewModel(c.chatRepository, c.database.questionDao())
    }
    val state by viewModel.state.collectAsState()

    var showClearConfirm by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ChatListItemUi?>(null) }
    var renameText by remember { mutableStateOf("") }
    val untitled = stringResource(R.string.chat_untitled)

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.chat_list_clear_confirm_title)) },
            text = { Text(stringResource(R.string.chat_list_clear_confirm_body)) },
            confirmButton = {
                // 两步确认：清空全部对话不可恢复，确认键再点一次才执行
                com.mistakebook.ui.common.TwoStepConfirmTextButton(
                    text = stringResource(R.string.chat_confirm),
                    confirmText = stringResource(R.string.chat_list_clear_confirm_action),
                    onConfirm = {
                        showClearConfirm = false
                        viewModel.clearAll()
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.chat_cancel))
                }
            }
        )
    }

    renaming?.let { target ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.chat_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.chat_rename_hint)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(target.session.id, renameText)
                    renaming = null
                }) { Text(stringResource(R.string.chat_rename_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.chat_cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_list_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_cancel)
                        )
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_list_clear_all))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_list_clear_all)) },
                                onClick = {
                                    menuOpen = false
                                    showClearConfirm = true
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                // 新对话：先拿到（或建好）会话 id 再进。
                // 传 0 是不行的——ViewModel 会当成「没指定」而重建一条，
                // 这里建的这条就成了孤儿。
                viewModel.startFreeSession { sessionId -> onOpenSession(sessionId, null) }
            }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chat_list_new))
            }
        }
    ) { padding ->
        if (state.items.isEmpty() && !state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.chat_list_empty),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.chat_list_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            // `padding` 是 Scaffold 的 innerPadding，含 TopAppBar 高度与状态栏。
            // 漏掉它的话列表从 y=0 开始画，第一行正好被顶栏压掉上半截——
            // 而空状态分支用了 `.padding(padding)`，所以「有数据时错、空时对」，
            // 是个很容易骗过自己的 bug。
            // 这里只取顶部：底部要留给 FAB 的悬浮高度，见 contentPadding。
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            items(state.items, key = { it.session.id }) { item ->
                SwipeableChatRow(
                    item = item,
                    // 必须传 sessionId，不只是 questionId：
                    // 自由会话 questionId 是 null，光传它等于没传——
                    // ViewModel 会去找「那条空的自由会话」，找不到就**新建一条空的**，
                    // 于是点历史记录进去是一片空白，而且每点一次多插一条孤儿会话。
                    onOpen = { onOpenSession(item.session.id, item.session.questionId) },
                    onRename = {
                        renameText = item.session.title
                        renaming = item
                    },
                    onDelete = { viewModel.delete(item.session.id) }
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * 左滑操作行：**红色删除（原地两步确认）**，可选蓝色重命名。
 *
 * ## 交互（与 [com.mistakebook.ui.home.HomeScreen] 的划开删除同构）
 *
 * 1. **左滑** → 右侧露出操作色块，卡片停住不收回
 * 2. 点「删除」→ **原地**变成更深红的「确认删除」，位置不变、不弹窗
 *    （用户诉求：二次确认就像错题本那样在原来的位置点一下，再点一下才删）
 * 3. 再点一次「确认删除」→ 真删；4 秒不点或划回去 → 自动收起
 * 4. 点卡片其余部分 → 收回色块，不进会话
 *
 * 滑到位必须吸附展开：松手就回弹的话，用户根本没机会点色块——
 * 手势进行中是不能点击的。安全边界因此从「会不会误划」转移到
 * 「会不会误点」，后者需要一次明确的点击。
 */
@Composable
private fun SwipeableChatRow(
    item: ChatListItemUi,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val revealWidthPx = with(density) { SwipeActionRevealWidth.toPx() }

    val offsetX = remember { Animatable(0f) }
    // 「是否已划开」是**离散状态**，只在松手时翻转。
    // 位移本身走 `Modifier.offset { }`（只跑布局阶段，不重组）。
    var revealed by remember { mutableStateOf(false) }
    // 删除的原地两步确认：第一击只是把色块换成更深的红，
    // 第二击才真的删。收起（划回 / 点别处 / 超时）时复位。
    var confirmingDelete by remember { mutableStateOf(false) }

    // 确认态 4 秒无操作自动收起：停在这个界面上干别的，
    // 回来时不该还悬着一颗「再点就删」的按钮。
    LaunchedEffect(confirmingDelete) {
        if (confirmingDelete) {
            delay(4_000)
            confirmingDelete = false
            revealed = false
            scope.launch { offsetX.animateTo(0f) }
        }
    }

    val dragState = rememberDraggableState { delta ->
        scope.launch {
            offsetX.snapTo((offsetX.value + delta).coerceIn(-revealWidthPx, 0f))
        }
    }

    fun close() {
        revealed = false
        confirmingDelete = false
        scope.launch { offsetX.animateTo(0f) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // 底层：操作区。左滑后从右侧露出，**靠右对齐**——
        // 卡片往左移，露出的正是最右边这两块。
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End
        ) {
            SwipeActionBlock(
                width = SwipeActionWidth,
                color = MaterialTheme.colorScheme.primary,
                icon = Icons.Default.Edit,
                label = stringResource(R.string.chat_rename),
                enabled = revealed,
                onClick = {
                    close()
                    onRename()
                }
            )
            SwipeActionBlock(
                width = SwipeActionWidth,
                // 确认态换成更深的一档红：不用弹窗、不挪位置，
                // 「在原来位置再点一次」的语义全在这块颜色里
                color = if (confirmingDelete) SwipeDeleteConfirmRed
                else MaterialTheme.colorScheme.error,
                icon = Icons.Default.Delete,
                label = if (confirmingDelete) stringResource(R.string.swipe_delete_confirm)
                else stringResource(R.string.swipe_delete_hint),
                enabled = revealed,
                onClick = {
                    if (confirmingDelete) {
                        close()
                        onDelete()
                    } else {
                        confirmingDelete = true
                    }
                }
            )
        }

        // 上层：卡片内容，跟随手势位移（向左为负）
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        val opened = offsetX.value < -revealWidthPx * 0.35f
                        revealed = opened
                        if (!opened) confirmingDelete = false
                        scope.launch {
                            offsetX.animateTo(
                                targetValue = if (opened) -revealWidthPx else 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    }
                )
        ) {
            ChatRowContent(
                item = item,
                onClick = {
                    if (revealed) close() else onOpen()
                }
            )
        }
    }
}

/** 右滑露出的一块操作（图标 + 文字）。 */
@Composable
private fun SwipeActionBlock(
    width: androidx.compose.ui.unit.Dp,
    color: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(color)
            // 滑开前点不到这里（被卡片挡住），显式禁用更保险
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = label,
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

/** 对话记录一行的内容（标题 + 预览 + 时间）。 */
@Composable
private fun ChatRowContent(
    item: ChatListItemUi,
    onClick: () -> Unit
) {
    val untitled = stringResource(R.string.chat_untitled)
    // 时间标签里的 DateTimeFormatter 提到顶层缓存（原每次重组都编译一遍），
    // kindOf 是廉价纯计算，直接调用即可——remember 的计算 lambda 不可组合，
    // 包住 stringResource 会直接编译失败。
    val time = timeLabel(item.session.updatedAt)

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.session.title.ifBlank { untitled },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (item.questionTitle != null) {
                        Spacer(Modifier.width(8.dp))
                        QuestionTag(item.questionTitle)
                    } else {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = stringResource(R.string.chat_list_free_tag),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.session.lastPreview.ifBlank { stringResource(R.string.chat_input_hint) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 右滑露出的总宽度：两块操作各 [SwipeActionWidth]。 */
private val SwipeActionRevealWidth = 168.dp
private val SwipeActionWidth = 84.dp

/**
 * 删除的「确认态」红色：比 error 更深一档。
 *
 * 语义上要能和「普通删除」一眼分开——同一位置、不弹窗、不移动，
 * 颜色是唯一的状态信号，必须比第一击的 error 明显更深。
 * 深色/浅色主题下都用固定深红，不跟主题色走（error 在深色主题下
 * 会变亮，那时「更深」就反过来了）。
 */
private val SwipeDeleteConfirmRed = Color(0xFF8B0000)

@Composable
private fun QuestionTag(title: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

/**
 * 时间标签。
 *
 * 判据（今天/昨天/N 天前/日期）在 [ChatTimeLabel] 里，可单测；
 * 这里只负责把判据映射成文案——**文案必须走 strings.xml**，不硬编码。
 */
@Composable
private fun timeLabel(updatedAt: Long): String {
    val now = System.currentTimeMillis()
    return when (val kind = ChatTimeLabel.kindOf(updatedAt, now)) {
        ChatTimeLabel.Kind.TODAY -> stringResource(R.string.chat_time_today)
        ChatTimeLabel.Kind.YESTERDAY -> stringResource(R.string.chat_time_yesterday)
        ChatTimeLabel.Kind.BEFORE_YESTERDAY -> stringResource(R.string.chat_time_before_yesterday)
        ChatTimeLabel.Kind.DAYS_AGO -> stringResource(
            R.string.chat_time_within_week,
            ChatTimeLabel.daysBetween(updatedAt, now)
        )

        ChatTimeLabel.Kind.DATE -> {
            val zone = ZoneId.systemDefault()
            val date = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
            // 同年只显示月日，跨年才带年份——省掉噪音
            val sameYear = date.year == Instant.ofEpochMilli(now).atZone(zone).year
            // pattern 编译结果缓存：行重组（滚动、其他行状态变化）会反复走到
            // 这里，每次 ofPattern 都要重新解析一遍模式串。
            date.format(if (sameYear) DATE_FMT_SAME_YEAR else DATE_FMT_WITH_YEAR)
        }
    }
}

/** 同年日期格式：「8月15日」。 */
private val DATE_FMT_SAME_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

/** 跨年日期格式：「2024年8月15日」。 */
private val DATE_FMT_WITH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")
