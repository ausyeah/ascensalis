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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    // 单条会话删除：删除不可恢复，先弹窗再两步确认
    var pendingDeleteSession by remember { mutableStateOf<Long?>(null) }
    val untitled = stringResource(R.string.chat_untitled)

    pendingDeleteSession?.let { sessionId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSession = null },
            title = { Text(stringResource(R.string.chat_delete_message)) },
            text = { Text(stringResource(R.string.chat_delete_session_confirm_body)) },
            confirmButton = {
                com.mistakebook.ui.common.TwoStepConfirmTextButton(
                    text = stringResource(R.string.chat_confirm),
                    confirmText = stringResource(R.string.chat_delete_confirm_action),
                    onConfirm = {
                        pendingDeleteSession = null
                        viewModel.delete(sessionId)
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSession = null }) {
                    Text(stringResource(R.string.chat_cancel))
                }
            }
        )
    }

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
                    onDelete = { pendingDeleteSession = item.session.id }
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * 右滑操作行：**蓝色重命名 + 红色删除**，复用主页错题本的滑动形式。
 *
 * ## 交互（与 [com.mistakebook.ui.home.HomeScreen] 的划开删除同构）
 *
 * 1. 右滑 → 左侧露出蓝色「重命名」+ 红色「删除」两块，**卡片停住不收回**
 * 2. 再点一下色块 → 才真正执行（重命名弹窗 / 删除两步确认）
 * 3. 点卡片其余部分 → 收回色块，不进会话
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

    val dragState = rememberDraggableState { delta ->
        scope.launch {
            offsetX.snapTo((offsetX.value + delta).coerceIn(0f, revealWidthPx))
        }
    }

    fun close() {
        revealed = false
        scope.launch { offsetX.animateTo(0f) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // 底层：操作区。右滑后从左侧露出，**靠左对齐**——
        // 卡片往右移，露出的正是最左边这两块。
        Row(modifier = Modifier.matchParentSize()) {
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
                color = MaterialTheme.colorScheme.error,
                icon = Icons.Default.Delete,
                label = stringResource(R.string.swipe_delete_hint),
                enabled = revealed,
                onClick = {
                    close()
                    onDelete()
                }
            )
        }

        // 上层：卡片内容，跟随手势位移
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        val opened = offsetX.value > revealWidthPx * 0.35f
                        revealed = opened
                        scope.launch {
                            offsetX.animateTo(
                                targetValue = if (opened) revealWidthPx else 0f,
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
            val pattern = if (sameYear) "M月d日" else "yyyy年M月d日"
            date.format(DateTimeFormatter.ofPattern(pattern))
        }
    }
}
