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
import androidx.compose.ui.semantics.Role
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

/**
 * 会话相册一次最多选几张（v0.0.52 多选）。
 *
 * 取 9 与旧 CHAT 连拍上限一致：附件要逐张压缩/转 data URL，
 * 一次十几张既慢也没意义，真需要更多分几次选。
 */
private const val CHAT_GALLERY_MAX = 9

/** 选文档。PDF 单独列出，因为部分文件管理应用不会把 .pdf 归到 text 类型。 */

@Composable
internal fun MessageBubble(
    message: ChatMessage,
    /** 屏幕上方相邻消息与本条同角色：相接角收窄（连发黏列）。 */
    aboveSameRole: Boolean = false,
    /** 屏幕下方相邻消息同角色。 */
    belowSameRole: Boolean = false,
    images: List<String>,
    /** 非图片附件（PDF/文档等）。点击交给系统打开，本应用不做阅读器。 */
    files: List<BubbleFile>,
    /** 首个 token 之前的阶段提示（如「正在联网搜索…」），仅流式中传。 */
    stage: String?,
    truncated: Boolean,
    mathRenderer: com.mistakebook.math.MathRenderer,
    onImageClick: (String) -> Unit = {},
    onCopy: (String) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    /** T13：把助手回复存入题目备注。null = 当前会话不展示该入口（自由会话）。 */
    onSaveToNote: ((Long) -> Unit)? = null,
    onToggleThinking: () -> Unit = {}
) {
    val isUser = message.role == ChatRole.USER
    val isInjected = message.injected

    // 上下文压缩的摘要：渲染成居中的低调提示条。
    // 压缩不是幕后动作——用户有权知道模型现在「记得」的是什么。
    val isSummary = message.role == ChatRole.SYSTEM &&
        message.content.startsWith(ChatContextAssembler.SUMMARY_PREFIX)
    if (isSummary) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.chat_summary_bubble_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = message.content.removePrefix(ChatContextAssembler.SUMMARY_PREFIX).trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        return
    }

    // 题目上下文那条是给模型看的：渲染成独立的「题目卡片」，
    // 免得用户以为 AI 莫名其妙先说了一段，也免得整块原文铺在对话最前面
    // ——每次进会话都要先滑过几十行才看到自己的问题。
    if (isInjected) {
        QuestionContextCard(content = message.content, mathRenderer = mathRenderer)
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // 连发清角：与同角色相邻的一侧收 4dp（保一丝间隙感不焊死），
        // 悬空侧保持 14dp 大圆角。助手通栏无气泡轮廓，收角只影响 user 分支。
        val bubbleCorner = 14.dp
        val joinedCorner = 4.dp
        Surface(
            // v0.0.58：**只有用户消息有气泡**，AI 回复全宽度铺开
            //（对齐 ChatGPT 移动端：用户消息框起来，助手消息通栏）。
            // 原来助手也用 surfaceVariant 气泡 + 320dp 限宽，长公式/表格/HTML
            // 全被挤在窄列里——既割裂阅读，也让宽公式更容易被右侧裁掉。
            color = if (isUser) {
                // 用户气泡走中性面（T3 messageSurface 口径）而不是 primaryContainer：
                // 强调色只给主操作，气泡靠对齐方向区分归属，蓝底蓝字的克制感更足
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                Color.Transparent
            },
            shape = RoundedCornerShape(
                topStart = if (isUser && aboveSameRole) joinedCorner else bubbleCorner,
                topEnd = if (isUser && aboveSameRole) joinedCorner else bubbleCorner,
                bottomStart = if (!isUser || belowSameRole) joinedCorner else bubbleCorner,
                bottomEnd = if (belowSameRole) joinedCorner else if (isUser) bubbleCorner else joinedCorner
            ),
            modifier = if (isUser) Modifier.widthIn(max = 320.dp) else Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = if (isUser) 12.dp else 0.dp,
                    vertical = 8.dp
                )
            ) {
                // 用户自己发的图片。**早先气泡只渲染 message.content，
                // 附件从来没进过界面**——模型看得到图，用户这边看不见，
                // 表现成「图片发不出去」。
                if (images.isNotEmpty()) {
                    // 多图（T2 对话连拍）两两一行；单图保持全宽。
                    if (images.size == 1) {
                        BubbleImage(images.first(), onImageClick = onImageClick)
                    } else {
                        images.chunked(2).forEach { rowImages ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowImages.forEach { imagePath ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        BubbleImage(imagePath, onImageClick = onImageClick)
                                    }
                                }
                                if (rowImages.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    if (message.content.isNotBlank()) Spacer(Modifier.height(8.dp))
                }

                // 非图片附件（PDF/文档等）：之前只抽文本喂模型、气泡里什么都不渲染，
                // 用户看到「发出去了却是空的」。以文件芯片呈现，点击交给系统打开。
                if (files.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        files.forEach { file ->
                            AttachmentFileChip(file = file, onImageClick = onImageClick)
                        }
                    }
                    if (message.content.isNotBlank()) Spacer(Modifier.height(8.dp))
                }

                // 思考过程与正文用 `<think>` 标记存在同一列里，界面拆开渲染。
                // 正文为空但有思考时也要渲染思考块，否则就是一个空气泡。
                // streaming 传进去：流式时未闭合的标记还是思考，
                // 结束后就是模型漏写了结束标记（后面是答案、不是思考）。
                val isStreaming = message.status == MessageStatus.STREAMING
                val (thinking, answer) = remember(message.content, isStreaming) {
                    ChatThinking.split(message.content, isStreaming)
                }
                // 流式时把尾巴上未闭合的公式先藏起来：半截公式一旦进 RichText
                // 就会先显示源码、拼完再跳成公式。等配对符到齐再整段渲染滑入。
                val displayAnswer = rememberStreamTextDebounced(
                    key = message.id,
                    // 防抖只作用于流式中间帧；trim/清洗语义不变
                    text = run {
                        val base = if (isStreaming) trimIncompleteTrailingFormula(answer) else answer
                        // v0.0.46 生图旧版本曾把 `![](本地路径)` 写进正文，
                        // 老会话残留会被清洗（无引用的图片路径直接删）
                        LegacyImageRefs.strip(base)
                    },
                    streaming = isStreaming
                )
                // 阶段提示：联网搜索要几秒到十几秒，没这行就是又一例「点了没反应」。
                // 不要求内容为空——工具循环里搜索期间气泡**带着已生成的内容**，
                // 阶段行显示在内容上方，用户知道「还在搜，但没断」。
                if (isStreaming && stage != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (thinking.isNotBlank()) {
                    ThinkingBlock(
                        text = thinking,
                        // 「正在思考」= 还在流式**且答案尚未开始**（v0.0.83 简化折叠规则）。
                        streaming = isStreaming && answer.isBlank(),
                        // 生成全程（思考+作答）禁手动折叠：思考中不可折叠；
                        // 结束输出正文时自动折叠；只有生成结束后才可自由展开/收起。
                        locked = isStreaming,
                        mathRenderer = mathRenderer,
                        // 思考块与正文同属一条消息：共用消息 id 隔离防抖
                        debounceKey = message.id,
                        onToggle = { onToggleThinking() }
                    )
                    if (answer.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (answer.isNotBlank() || thinking.isBlank()) {
                    RichText(
                        text = displayAnswer,
                        mathRenderer = mathRenderer,
                        modifier = if (isStreaming) Modifier.animateContentSize() else Modifier,
                        streaming = isStreaming,
                        // 聊天里绝不回退 LaTeX 源码（v0.0.57）：用户反馈
                        // 「流式正常、结束后显示原始源码」。给「公式无法渲染」提示。
                        showFailedMathSource = false,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            // AI 回复正文用 onSurface：气泡底是 surfaceVariant（浅灰），
                            // 再用 onSurfaceVariant 会糊成一片，用户实测「字太淡看不清」
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
                // 常驻的截断标记。弹窗只在生成当时弹一次，
                // 用户往回翻时看到的那条残缺回答在界面上和完整回答一模一样，
                // 他不知道下面少了东西。
                if (truncated) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.chat_truncated_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (message.status == MessageStatus.STREAMING) {
                    Spacer(Modifier.height(6.dp))
                    // 流式输出指示器：三点弹性跳动 + 整体呼吸（原来是 6dp 静态蓝点）
                    StreamingDotsIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        StatusRow(
            message = message,
            isUser = isUser,
            onCopy = onCopy,
            onRetry = onRetry,
            onDelete = onDelete,
            onSaveToNote = onSaveToNote
        )
    }
}

/**
 * 气泡图片的最大边长（像素）。
 *
 * 气泡的显示宽度撑死就是屏幕宽，而用户发的是原图——
 * 一张 4000×3000 的照片解码出来占 48MB 堆内存，对话里挂三四张，
 * 列表滚动时必然被 GC 拖住。降到 1280 上限，肉眼无损，内存差一个数量级。
 */
private const val BUBBLE_IMAGE_MAX_PX = 1280


/**
 * 流式输出指示器：三点从左到右依次弹跳 + 整体呼吸。
 *
 * 用户原话「加载的时候的三个点只显示了下半，跳起来的时候被挡住了，
 * 从左到右跳比较好」。三个问题分别对应三处修法：
 *
 * 1. **只显示下半 / 被挡住**：原来三个点直接放在 Row 里，Row 的高度
 *    被点的 6dp 撑死，而点要向上跳 3dp、还要放大到 1.1 倍——超出
 *    自身 bounds 的部分被父容器裁掉，看起来只剩一条下沿。
 *    修法：给每个点套一个 16dp 高、`contentAlignment = Center` 的
 *    容器，跳动用 `graphicsLayer` 的 `translationY`（不参与布局，
 *    不会被裁），容器本身留足上下空间。
 * 2. **从左到右**：相位差从 1/6 周期改成 1/3 周期，三个点依次
 *    达到波峰，形成清晰的「左→中→右」接力。
 * 3. **呼吸**：容器整体 alpha 走一条 1800ms 的往复曲线。
 *
 * 位移与缩放共用同一条 sin 曲线——「弹性」感来自二者同源，
 * 相邻点错峰形成追上去又落下来的波浪。
 */
@Composable
internal fun StreamingDotsIndicator(color: Color) {
    val transition = rememberInfiniteTransition()
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing)
        )
    )
    val breathe by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    Row(
        modifier = Modifier
            .alpha(breathe)
            .height(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (i in 0 until 3) {
            // 相位差 1/3 周期：左点先跳，中点接力，右点再接力
            val t = (phase + i / 3f) % 1f
            // sin 取正半周 → 只在「上抛后落下」时有量
            val bounce = sin(t * 2f * PI.toFloat()).coerceAtLeast(0f)
            val scale = 0.65f + 0.45f * bounce
            Box(
                modifier = Modifier.size(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        // translationY 走 graphicsLayer：不参与测量，
                        // 向上跳出去也不会被父容器裁掉
                        .graphicsLayer {
                            translationY = -3f * bounce * density
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(RoundedCornerShape(3.dp))
                        .background(color)
                )
            }
        }
    }
}


/**
 * 按目标边长降采样解码。
 *
 * 关键在**先只解边界**（inJustDecodeBounds 不分配像素内存），
 * 拿到尺寸算出 2 的幂次采样率后再真正解码。直接 decodeByteArray
 * 会把整张原图完整分配一遍，省不掉。
 */
internal fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    var sample = 1
    while (maxOf(w, h) / sample > maxPx) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }
    )
}


/**
 * 气泡里的图片。
 *
 * 入参是**本地文件路径**（v0.0.72 起不再是 data URL——base64 文本每张
 * 几百 KB 且随会话常驻 UiState，路径只有几十字节）。直接读文件采样解码，
 * 连 base64 那步都省了。
 * `remember(path)` 是必须的：LazyColumn 滚动时每一帧都会重组，
 * 不缓存的话每帧都重新解码一张几百 KB 的图，滑不动。
 */
@Composable
internal fun BubbleImage(path: String, onImageClick: (String) -> Unit = {}) {
    // **解码不能在组合期做。** 读文件 + 两遍 BitmapFactory.decodeByteArray
    // 是几十毫秒的重活，放在 remember 里就是卡在合成这一帧上——带图的气泡
    // 每滑进屏幕一次就掉一次帧。produceState 让它在 IO 线程跑、完成后再回填。
    val bitmap by produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = java.io.File(path).readBytes()
                decodeSampled(bytes, BUBBLE_IMAGE_MAX_PX)
            }.getOrNull()
        }
    }
    val decoded = bitmap
    if (decoded == null) {
        Text(
            text = stringResource(R.string.chat_image_unreadable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Image(
        bitmap = decoded.asImageBitmap(),
        contentDescription = stringResource(R.string.chat_image_content_description),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onImageClick(path) }
    )
}


@Composable
internal fun StatusRow(
    message: ChatMessage,
    isUser: Boolean,
    onCopy: (String) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    /** T13：非空时对完成态助手消息展示「存入题目备注」入口。 */
    onSaveToNote: ((Long) -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        when {
            message.status == MessageStatus.FAILED -> {
                Text(
                    text = stringResource(R.string.chat_failed_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(
                    onClick = { onRetry(message.id) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text(stringResource(R.string.chat_retry), style = MaterialTheme.typography.labelSmall)
                }
            }

            message.status == MessageStatus.CANCELED -> {
                Text(
                    text = stringResource(R.string.chat_stopped_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            message.status == MessageStatus.DONE && !isUser -> {
                IconButton(
                    onClick = {
                        val textToCopy = ChatThinking.answerOf(message.content).trim().ifEmpty { message.content }
                        onCopy(textToCopy)
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.chat_copy),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // T13：批改结果一键存进题目备注（题目会话专属，自由会话不传该回调）
                if (onSaveToNote != null) {
                    IconButton(
                        onClick = { onSaveToNote(message.id) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Note,
                            contentDescription = stringResource(R.string.chat_save_to_note),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        IconButton(onClick = { onDelete(message.id) }, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.chat_delete_message),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


/**
 * 思考过程的可折叠段落。
 *
 * ## 为什么默认收起
 *
 * 思考过程可能很长而信息量不大，展开占的正是答案的位置。
 * 用户想看的是结论，想查推导时再展开。
 *
 * ## 折叠规则（v0.0.83 定稿，用户拍板）
 *
 * - **思考中**：保持展开且**不可折叠**（[locked]=true，点击无效）——
 *   用户要看到「它在想什么」；
 * - **答案开始输出**：自动收起一次（`streaming` 翻假触发 LaunchedEffect），
 *   把位置让给正文；作答期间同样不可点；
 * - **生成结束后**：才可以自由折叠/展开。
 * 早先错传整条消息的流式状态，思考块在作答阶段一直开着、消息结束才猛收——
 * 就是「会跳转但不折叠」的根因。
 *
 * ## 思考过程同样走 [RichText]
 *
 * 推理模型的思考里**全是公式**（推导过程），用纯 Text 渲染就是满屏
 * LaTeX 源码（用户反馈「思考过程中的 latex 没有渲染」）。
 *
 * 流式两条规则与正文一致（`trimIncompleteTrailingFormula` +
 * `streaming = true`）：
 * 1. 未闭合的 `$`/`$$` 先隐藏，配对符到齐再整段渲染滑入——
 *    即「先渲染后输出」，不会先闪源码再跳公式；
 * 2. 位图未就绪的公式只留占位，不回退显示源码。
 */
@Composable
internal fun ThinkingBlock(
    text: String,
    streaming: Boolean,
    mathRenderer: com.mistakebook.math.MathRenderer,
    debounceKey: Any? = null,
    /** 生成进行中（思考+作答全程）：折叠/展开点击一律无效。 */
    locked: Boolean = false,
    onToggle: (Boolean) -> Unit = {}
) {
    // 初始展开态直接取 streaming：历史消息（streaming=false）首次组合就是
    // 收起态，不会先把整段思考渲染一遍再折叠——那笔开销在滚动到每条
    // 带思考的历史消息时都要付一次，是「新 item 进视口就卡一下」的一员。
    // 流式中为 true；流式结束由下面的 LaunchedEffect 收起。
    var expanded by remember(debounceKey) { mutableStateOf(streaming) }
    LaunchedEffect(streaming) {
        if (!streaming) {
            expanded = false
        }
    }

    // 尾巴上未闭合的公式先藏起来：半截公式一旦进 RichText 就会先显示
    // 源码、拼完再跳成公式。与正文同一套处理（见 MessageBubble）。
    val displayText = remember(text, streaming) {
        if (streaming) trimIncompleteTrailingFormula(text) else text
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            // 整块不再可点（v0.0.96）：折叠收在一颗固定按钮里。
            // 之前整块都能点，用户想选中/复制思考里的文字却总是把块折叠了。
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Psychology,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.chat_thinking_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (streaming) {
                    stringResource(R.string.chat_thinking_ongoing)
                } else {
                    stringResource(R.string.chat_thinking_chars, text.length)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))

            // 折叠/展开是一颗**固定位置**的按钮（v0.0.96 用户要求）：
            // - 两种状态下它都在行末同一个位置，不再因为「复制键出现/消失」而挪动；
            // - 箭头恒指向下——展开时内容本来就在按钮下方长出来，
            //   「向下」说的正是这个动作，翻上去反而让人多想一步；
            // - 复制键挪到了内容末尾，与折叠键隔开整段思考，不再误触。
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(
                        enabled = !locked,
                        role = Role.Button,
                        onClick = {
                            val next = !expanded
                            expanded = next
                            onToggle(next)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.chat_thinking_collapse else R.string.chat_thinking_expand
                    ),
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (locked) 0.4f else 1f)
                )
            }
        }
        if (expanded && displayText.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            RichText(
                text = displayText,
                mathRenderer = mathRenderer,
                style = MaterialTheme.typography.bodySmall,
                // 思考过程同样加深：onSurfaceVariant 在浅色主题下对比度不足
                color = MaterialTheme.colorScheme.onSurface,
                streaming = streaming,
                showFailedMathSource = false
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                val context = androidx.compose.ui.platform.LocalContext.current
                // 复制放在内容**末尾**而不是标题行：标题行那颗是折叠键，
                // 两颗小图标挨在一起，想折叠的时候按到的总是复制。
                // 顺带把原来硬编码的「思考过程已复制」收进了 strings.xml。
                androidx.compose.material3.IconButton(
                    onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.chat_thinking_copied),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.chat_cd_copy_thinking),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

