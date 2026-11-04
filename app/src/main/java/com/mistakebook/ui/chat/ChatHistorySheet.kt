package com.mistakebook.ui.chat

import androidx.compose.animation.core.Animatable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.di.AppContainer
import com.mistakebook.di.PendingTool
import com.mistakebook.ui.common.containerViewModel
import com.mistakebook.ui.common.SwipeActionBlock
import com.mistakebook.ui.common.SwipeActionBlockWidth
import com.mistakebook.ui.common.SwipeRevealSpring
import com.mistakebook.ui.common.swipeRevealContentAlpha
import com.mistakebook.ui.common.swipeRevealProgress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatHistorySheet(
    container: AppContainer,
    /** 由 ChatScreen 持有：选完工具/会话后要在这里合上抽屉。 */
    drawerState: androidx.compose.material3.DrawerState,
    /** 复用对话页的 Snackbar：删除撤销的提示直接落在对话页底部。 */
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
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

    // 能力入口的可用状态（v0.0.96）。只在进页面时读一次：用户在设置里配好
    // 再回来，这一页会重建（导航回退），不需要在这里挂着订阅。
    var llmConfigured by remember { mutableStateOf(true) }
    var ocrConfigured by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        runCatching { container.settingsStore.snapshotNow() }.getOrNull()?.let {
            llmConfigured = it.llmConfigured
            ocrConfigured = it.mineruConfigured
        }
    }

    // 删除撤销（v0.0.66）：软删不再删附件，恢复即完整还原。
    val scope = rememberCoroutineScope()

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

    // 抽屉是一整列：头部签名 → 功能行 → 历史记录 → 底部新对话胶囊。
    // 不是 Scaffold——抽屉没有顶栏/底栏之分，Scaffold 的 padding 语义在这里只会碍事。
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // 头部：左上角应用签名。抽屉的关闭交给遮罩点击与边缘右滑，
        // 不另设关闭键——ChatGPT 的抽屉也是这么做的。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.app_signature),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 2.sp
                ),
                modifier = Modifier.weight(1f)
            )
            if (state.items.isNotEmpty()) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
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
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "tools") {
                // 功能入口是一行行的（v0.0.96 用户指定，照 ChatGPT 抽屉的样式），
                // 不再是九宫格——抽屉是竖向空间，行比格子读得快。
                ChatToolRows(
                    llmConfigured = llmConfigured,
                    ocrConfigured = ocrConfigured,
                    onPick = { tool ->
                        // 先合抽屉再干活：不关的话新会话已经在底下加载，
                        // 抽屉还盖在上面，用户会以为点了没反应。
                        scope.launch { drawerState.close() }
                        // 指令与「要不要先拍照」经容器交接（与相机拍照同一套惯例）；
                        // 需要拍照的入口由对话页自动拉起相机，拍完自动发送。
                        viewModel.startFreeSession { sessionId ->
                            container.pendingTool = PendingTool(
                                sessionId = sessionId,
                                tool = tool,
                                instruction = tool.instruction
                            )
                            onOpenSession(sessionId, null)
                        }
                    }
                )
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.chat_history_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            if (state.items.isEmpty() && !state.loading) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
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
            }

            items(state.items, key = { it.session.id }) { item ->
                // 文案在组合期取好：showSnackbar 的回调不是组合上下文
                val deletedText = stringResource(R.string.chat_session_deleted)
                val undoText = stringResource(R.string.action_undo)
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
                    onTogglePin = { viewModel.setPinned(item.session.id, !item.session.pinned) },
                    onDelete = {
                        // 删除可撤销（v0.0.66）：软删保留附件，撤销即完整还原
                        val sessionId = item.session.id
                        viewModel.archive(sessionId)
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = deletedText,
                                actionLabel = undoText,
                                duration = androidx.compose.material3.SnackbarDuration.Short
                            )
                            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                viewModel.unarchive(sessionId)
                            }
                        }
                    }
                )
                HorizontalDivider()
            }
        }

        // 底部新对话胶囊：抽屉里最常用的动作固定在最顺手的位置（拇指区）。
        Surface(
            onClick = {
                scope.launch { drawerState.close() }
                viewModel.startFreeSession { sessionId -> onOpenSession(sessionId, null) }
            },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.chat_list_new),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
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
    onDelete: () -> Unit,
    onTogglePin: () -> Unit
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
            modifier = Modifier
                .matchParentSize()
                // 与错题卡同一口径：露出内容随进度淡入，绘制期读 offsetX 不重组
                .graphicsLayer {
                    val progress = swipeRevealProgress(offsetX.value, revealWidthPx)
                    alpha = swipeRevealContentAlpha(progress)
                },
            horizontalArrangement = Arrangement.End
        ) {
            // v0.0.98 用户拍板：置顶从长按菜单挪进左滑，三个动作一排。
            // 最左是最温和的动作，越往右越破坏性——删除永远在最外面。
            SwipeActionBlock(
                width = SwipeActionBlockWidth,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = Icons.Default.PushPin,
                label = stringResource(
                    if (item.session.pinned) R.string.swipe_unpin_action else R.string.swipe_pin_action
                ),
                enabled = revealed,
                onClick = {
                    close()
                    onTogglePin()
                }
            )
            SwipeActionBlock(
                width = SwipeActionBlockWidth,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = Icons.Default.Edit,
                label = stringResource(R.string.chat_rename),
                enabled = revealed,
                onClick = {
                    close()
                    onRename()
                }
            )
            SwipeActionBlock(
                width = SwipeActionBlockWidth,
                // 两步确认全在这块颜色里：第一击是浅一档的 errorContainer，
                // 第二击换成饱和的 error。不弹窗、不挪位置，「再点一次」
                // 的语义靠对比度表达——深浅两档都从 colorScheme 取，
                // 亮暗主题下方向一致（亮色：浅粉→深红；暗色：深红→亮红）。
                color = if (confirmingDelete) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.errorContainer,
                contentColor = if (confirmingDelete) MaterialTheme.colorScheme.onError
                else MaterialTheme.colorScheme.onErrorContainer,
                icon = Icons.Default.Delete,
                label = if (confirmingDelete) stringResource(R.string.swipe_delete_confirm)
                else stringResource(R.string.archive_action),
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
                                animationSpec = SwipeRevealSpring
                            )
                        }
                    }
                )
        ) {
            ChatRowContent(
                item = item,
                onClick = {
                    if (revealed) close() else onOpen()
                },
                onRename = onRename,
                onDelete = onDelete,
                onTogglePin = onTogglePin
            )
        }
    }
}

/** 对话记录一行的内容（标题 + 预览 + 时间）。 */
@Composable
private fun ChatRowContent(
    item: ChatListItemUi,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit
) {
    val untitled = stringResource(R.string.chat_untitled)
    // 时间标签里的 DateTimeFormatter 提到顶层缓存（原每次重组都编译一遍），
    // kindOf 是廉价纯计算，直接调用即可——remember 的计算 lambda 不可组合，
    // 包住 stringResource 会直接编译失败。
    val time = timeLabel(item.session.updatedAt)

    Surface(color = MaterialTheme.colorScheme.surface) {
        // v0.0.98 用户拍板：去掉长按菜单，三个动作全部走左滑——
        // 长按和左滑是同一组动作的两套入口，留一套才不会让人想「该用哪个」。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.session.pinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = stringResource(R.string.chat_pinned_badge),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
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
                    text = item.session.lastPreview.ifBlank { stringResource(R.string.chat_list_empty) },
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

/**
 * 右滑露出的总宽度：重命名 + 删除两块，各 [SwipeActionBlockWidth]。
 *
 * 块宽与错题卡的删除块取同一个公共值（ui/common/SwipeReveal.kt）：
 * 两个列表的删除块必须一样大，用户才不会觉得「一边顺手一边别扭」。
 */
private val SwipeActionRevealWidth = SwipeActionBlockWidth * 3

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
