package com.mistakebook.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import android.content.ClipData
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import com.mistakebook.R
import com.mistakebook.data.chat.ChatContextAssembler
import com.mistakebook.data.chat.ChatSearchProvider
import com.mistakebook.data.local.entities.ChatMessage
import com.mistakebook.data.prefs.ReasoningEffort
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.ChatRole
import com.mistakebook.domain.MessageStatus
import com.mistakebook.ui.common.ApiKeyRequiredDialog
import com.mistakebook.ui.common.RichText
import com.mistakebook.ui.common.rememberStreamTextDebounced
import com.mistakebook.ui.common.containerViewModel
import kotlinx.coroutines.Dispatchers
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.ExpandLess
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.gestures.scrollBy

/**
 * 会话相册一次最多选几张（v0.0.52 多选）。
 *
 * 取 9 与旧 CHAT 连拍上限一致：附件要逐张压缩/转 data URL，
 * 一次十几张既慢也没意义，真需要更多分几次选。
 */
private const val CHAT_GALLERY_MAX = 9

/** 选文档。PDF 单独列出，因为部分文件管理应用不会把 .pdf 归到 text 类型。 */

@Composable
internal fun MessageList(
    state: ChatUiState,
    listState: LazyListState,
    mathRenderer: com.mistakebook.math.MathRenderer,
    bubbleImages: Map<Long, List<String>>,
    bubbleFiles: Map<Long, List<BubbleFile>>,
    truncatedMessageId: Long?,
    onImageClick: (String) -> Unit = {},
    onCopy: (String) -> Unit,
    // 重试必须带上被点消息的 id：重试按钮挂在每条失败消息上，
    // 无 id 的「重试最后一轮」在历史里有旧失败消息时会删错目标。
    onRetry: (Long) -> Unit,
    /** 停止后的「继续」：接着写，不重新开头。 */
    onContinue: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    /** T13：批改结果「存入题目备注」入口；自由会话传 null 不展示。 */
    onSaveToNote: ((Long) -> Unit)? = null,
    /**
     * 折叠窗口开关（v0.0.144）：思考块展开/收起期间，增长锚定（path ④）让路，
     * 由折叠块自己按残差补 —— 两个补偿者同时动手就是 2Δ。
     */
    onThinkingToggleWindow: (Boolean) -> Unit = {},
    /** 手指按下（不等 slop）：自动滚动立刻交出滚动权（issue #39）。 */
    onTouchDown: () -> Unit = {},
    /** 手指全部抬起：把控制权交还位置判定（issue #39）。 */
    onTouchUp: () -> Unit = {}
) {
    // 思考块展开/收起后按钮被反向列表顶走的补偿：
    // 列表按残差反着滚同样的量，按钮就留在手指底下，内容只往下长。
    //
    // 归属只认**量出来的残差**，不做位置预测：增长锚定（path ④）会不会补这一次
    // 变化取决于它自己的分支（跟随/贴底、锚点有没有换条、手势标志……），
    // 靠「贴底？」「被折叠的是不是最上面那条？」去猜，真机上就被打过脸——
    // 思考块在视口中部、下面还有别的消息时锚定其实没补（锚点换条后它只重取
    // 基线），按预测让路的结果是没人补、按钮被顶上去 293px。
    // 现在的规则：折叠块每轮重新量「按钮离原位还差多少」，锚定补过残差就≈0
    // （这里不补，不会出现第二个补偿者），没补残差还在就由这里原样滚回去。
    val anchorScope = rememberCoroutineScope()
    // pointerInput 里跨事件记「当前是否有手指按着」：只在按下/抬起两个
    // 边沿各回调一次，不随每次 ENTER/MOVE 重复通知。
    var touchActive by remember { mutableStateOf(false) }
    // 倒序提供数据配合 reverseLayout：最新消息固定在底边，流式气泡增长无需反复重启滚动动画。
    //
    // 流式预览（streamingContent）在**item 作用域内**读取，不在外层：
    // 快照每 220ms 一帧，读在外层会让整个 MessageList（连同 LazyColumn 的
    // item provider）每帧重组一遍——长会话里这是白烧的主线程时间。
    // 读在 item 里，只有正在生成的那条气泡重组。
    val messages = state.messages
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier
            .fillMaxSize()
            // v0.0.126（issue #39「手势的优先级不够」）：在 **Initial pass**
            // 里只观察、不消费，按下瞬间就通知外部交出滚动权。
            //
            // 为什么不用 collectIsDraggedAsState：它要等越过触摸 slop
            // （8~16dp）才翻真，那之前的位移里列表仍在自动跟随、气泡仍在
            // 长高，用户体感是「一抽一抽、不跟手」。Initial pass 拿到的
            // 是「手指刚落下」那一刻，比 slop 早十几 dp。
            // 不消费事件 = 滚动、点击照常，只是把「我在操作」这个事实
            // 提前告诉自动滚动编排。
            .pointerInput(onTouchDown, onTouchUp) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val anyPressed = event.changes.any { it.pressed }
                        if (anyPressed && !touchActive) {
                            touchActive = true
                            onTouchDown()
                        } else if (!anyPressed && touchActive) {
                            touchActive = false
                            onTouchUp()
                        }
                    }
                }
            },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 「此前省略 N 轮」提示条声明在消息**之后**：reverseLayout 下最后
        // 声明的 item 在屏幕顶部（对话开头）——省略的正是开头那些轮次，
        // 放顶部语义才对。更关键的是几何契约：AutoScrollCoordinator 的
        // 贴底判定/回底/增长补偿全部假定 **index 0 = 最新一条消息**；
        // 早先声明在前面时它是 index 0，只要会话出现过省略提示，
        // 整套触底几何就都在盯着这条小提示条而不是正在流式的那条消息。
        itemsIndexed(
            items = messages.asReversed(),
            // key 用消息 id：内容更新（流式 / 落库）时按 id 对齐，
            // 不因为实例变化而丢掉滚动位置与已组合的 item。
            key = { _, item -> item.id },
            // contentType 让 Compose 按类型分池复用：不写的话滚动时
            // 每条消息都可能拿到上一条不同类型的容器重新组合。
            contentType = { _, _ -> "message" }
        ) { index, message ->
            // 连发清角（Element X 模式）：同角色相邻气泡相接的角收窄，
            // 一屏内的连续消息黏成一列。reverseLayout 下 index-1 是屏幕上方（更新）。
            val reversedMessages = messages.asReversed()
            val aboveSameRole = index > 0 &&
                reversedMessages[index - 1].role == message.role
            val belowSameRole = index < reversedMessages.lastIndex &&
                reversedMessages[index + 1].role == message.role
            val preview = if (message.id == state.streamingMessageId) {
                state.streamingContent
            } else {
                null
            }
            val visibleMessage = if (preview != null) message.copy(content = preview) else message
            MessageBubble(
                message = visibleMessage,
                // reverseLayout：index 0 = 对话最后一条（最新）
                isLastMessage = index == 0,
                aboveSameRole = aboveSameRole,
                belowSameRole = belowSameRole,
                images = bubbleImages[message.id].orEmpty(),
                files = bubbleFiles[message.id].orEmpty(),
                stage = state.generationStage.takeIf {
                    message.id == state.streamingMessageId && state.isStreaming
                },
                truncated = truncatedMessageId == message.id,
                mathRenderer = mathRenderer,
                onImageClick = onImageClick,
                onCopy = onCopy,
                onRetry = onRetry,
                onContinue = onContinue,
                onDelete = onDelete,
                onSaveToNote = onSaveToNote,
                onThinkingToggleWindow = onThinkingToggleWindow,
                onThinkingAnchor = { residualPx ->
                    // 锚定先补，它没补掉的残差由我们接手。
                    //
                    // 「列表正忙」只认**用户的手指**（touchActive），不认
                    // `isScrollInProgress`：程序化滚动（增长锚定 path ④ 自己那次）
                    // 恰恰就是我们要抵消的那一份——把它当「忙」挡掉，结果是
                    // 锚定补完没人收尾，按钮被顶离手指（真机实测中部那条消息
                    // 展开后表头跑出视口）。用户真在拖动时仍然不抢：那是手势。
                    val shiftPx = thinkingAnchorCompensation(
                        residualPx = residualPx,
                        listBusy = touchActive
                    )
                    if (shiftPx != 0f) {
                        anchorScope.launch {
                            listState.scrollBy(shiftPx)
                        }
                    }
                }
            )
        }
        if (state.omittedCount > 0) {
            item(key = "omitted-notice", contentType = "notice") {
                OmittedNoticeRow(state.omittedCount)
            }
        }
    }
}


/** 折叠键位移在 1px 以内当作渲染误差：再补只会来回抖。 */
internal const val THINKING_ANCHOR_TOLERANCE_PX = 1f

/**
 * 折叠/展开后最多追几轮残差。
 *
 * 首轮补布局位移，之后几轮追公式位图/流式尾帧带来的后续增高。轮数必须封顶——
 * 每轮都是一次 `scrollBy`，停不下来就会跟用户抢滚动。
 */
internal const val THINKING_ANCHOR_MAX_ROUNDS = 4

/**
 * 这一轮该滚多少像素（纯函数，可单测）：**锚定先补，它没补掉的那部分由折叠块接手**。
 *
 * ## 为什么判据是「残差」而不是位置启发式
 *
 * 同一次布局变化可能有两个补偿者：列表自己的增长锚定（[rememberAutoScrollEffect]
 * path ④）与本函数的调用方；两个都动手就是 2Δ。但锚定**会不会**补这一段，取决于
 * 它自己的分支（是否跟随/贴底、锚点 key 有没有换条、手势标志……）——想靠
 * 「贴底？」「被折叠的是不是视口最上面那条？」去*预测*它在真机上就被打脸过：
 * 思考块在视口中部、下面还有别的消息时，锚定其实**没补**（增长把锚点那条推走后
 * 它换了 key、只重取基线），折叠块却按启发式让路 ⇒ 没人补 ⇒ 按钮被顶上去 293px。
 *
 * 所以这里只认**量出来的残差**（折叠块每轮重新测「按钮离原位还差多少」）：
 * - 锚定补过 → 残差 ≈ 0 → 返回 0（不会出现第二个补偿者）；
 * - 锚定没补 → 残差还在 → 原样滚回去（这就是「它不接手就我们接手」）。
 *
 * ## 方向
 *
 * 本仓实测（见 [newestGapOf] 注释）：反向布局下 `scrollBy(+d)` 把内容**往下**推
 * d 像素。气泡长高时内容整体往上长，块顶部的折叠键被顶**上**去（窗口 Y 变小），
 * 所以「点下去那一刻记下的 Y − 现在量到的 Y」正是要补的滚动量：正值 = 按钮被
 * 顶上去 = 内容往下滚同样的量。按钮因此留在原处，新内容从它下方长出来。
 *
 * ## 两种「这一轮不补」
 *
 * - [listBusy]：用户正拖着列表 / 惯性还在跑，这一轮不插手（下一轮重新量残差，
 *   自愈；判据在列表里，见 `MessageList` 的 `isScrollInProgress || touchActive`）；
 * - 残差已落在 [THINKING_ANCHOR_TOLERANCE_PX] 以内：那是渲染误差。
 *
 * 返回 0 = 这一轮不补。
 *
 * ## 已知边界：列表自己滚不动的时候
 *
 * 反向列表的滚动量有下界（贴底 = 0）：展开要内容往下滚（量变大，上面还有内容就能
 * 补上），收起要内容往上滚（量变小，贴底时到顶了）。所以贴底那一下**收起**补不
 * 回来——这不是符号写错，是反向列表的几何事实，没有补偿者能绕过。
 */
internal fun thinkingAnchorCompensation(
    /** 目标 Y − 现在量到的 Y（正数 = 按钮被顶上去，内容要往下滚同样的量）。 */
    residualPx: Float,
    /** 列表正被用户占着 / 惯性在跑：这一轮不插手。 */
    listBusy: Boolean
): Float = when {
    listBusy -> 0f
    kotlin.math.abs(residualPx) < THINKING_ANCHOR_TOLERANCE_PX -> 0f
    else -> residualPx
}


@Composable
internal fun OmittedNoticeRow(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = stringResource(R.string.chat_omitted_notice, count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}


/**
 * 输入栏上方的上下文状态条。
 *
 * 显示两件事：**摘要是否生效**、**上次请求的输入 token 数**。
 * 后者是用户唯一能看到的上下文占用口径——不显示的话，
 * 「AI 开始失忆」和「请求里有 60K token」之间用户没有任何感知手段。
 * 压缩按钮只在 [showCompress]（上下文 ≥ 200k）时出现，由用户决定压不压；
 * 自动压缩进行中（compressing）按钮照常显示——「压缩中…」的反馈不能消失。
 */
@Composable
internal fun ContextStatusRow(
    summaryActive: Boolean,
    compressing: Boolean,
    lastPromptTokens: Int,
    showCompress: Boolean,
    onCompress: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (summaryActive) {
                Text(
                    text = stringResource(R.string.chat_summary_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (lastPromptTokens > 0) {
                Text(
                    text = stringResource(R.string.chat_last_prompt_tokens, lastPromptTokens),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (compressing) {
            // 进行中状态直接长在按钮上（用户反馈：角落里 16dp 小圈根本看不见，
            // 「不知道是正在压缩还是没反应」）。按钮换成「压缩中…」并禁用防连点。
            // 自动压缩触发时 showCompress 可能为 false，这个分支必须在显隐判断之外。
            TextButton(onClick = onCompress, enabled = false) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.chat_compressing),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        } else if (showCompress) {
            TextButton(onClick = onCompress) {
                Icon(
                    Icons.Default.Compress,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.chat_compress),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}


@Composable
internal fun JumpToBottomButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        IconButton(onClick = onClick) {
            Icon(
                Icons.Default.ArrowDownward,
                contentDescription = stringResource(R.string.chat_jump_to_bottom)
            )
        }
    }
}

