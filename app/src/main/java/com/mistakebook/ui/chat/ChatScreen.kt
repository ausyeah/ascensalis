package com.mistakebook.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.mistakebook.R
import com.mistakebook.data.chat.ChatContextAssembler
import com.mistakebook.data.local.entities.ChatMessage
import com.mistakebook.data.prefs.ReasoningEffort
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.ChatRole
import com.mistakebook.domain.MessageStatus
import com.mistakebook.ui.common.ApiKeyRequiredDialog
import com.mistakebook.ui.common.RichText
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
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import android.widget.Toast

/**
 * 选图片的 MIME 过滤。
 *
 * 不用通配 image 类型：部分文档管理应用会把 HEIC 归到 image 类型、
 * 但也有把不相关文件归到这里的，让用户自己挑下文件不能最终判定。
 */
private val IMAGE_MIME_TYPES = arrayOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/bmp")

/** 选文档。PDF 单独列出，因为部分文件管理应用不会把 .pdf 归到 text 类型。 */
private val DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/plain",
    "text/markdown",
    "text/csv"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    questionId: Long?,
    sessionId: Long? = null,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val viewModel = containerViewModel(
        container = container,
        // 每题一个固定会话：key 带上标识，切换时才不会复用上一个的 ViewModel。
        //
        // **sessionId 必须进 key**：从对话记录先后打开两个自由会话时，
        // 两者的 questionId 都是 null，key 会撞成同一个 → 显示的还是上一次的会话。
        key = "chat-${sessionId ?: "q$questionId"}"
    ) { c ->
        ChatViewModel(
            repository = c.chatRepository,
            stream = c.chatCompletionStream,
            settingsStore = c.settingsStore,
            usageStatsStore = c.usageStatsStore,
            preparer = c.chatAttachmentPreparer,
            compressor = c.chatContextCompressor,
            imageOcr = c.chatImageOcr,
            questionId = questionId,
            sessionId = sessionId,
            appContext = c.appContext
        )
    }

    // 用 collectAsState 而不是 collectAsStateWithLifecycle：
    // 后者在 lifecycle-runtime-compose 里，本项目没引这个依赖（零新增依赖）。
    val state by viewModel.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val copiedText = stringResource(R.string.chat_copied)
    val title = stringResource(
        if (questionId != null) R.string.chat_title_question else R.string.chat_title_free
    )

    val latestMessageKey = state.messages.lastOrNull()?.id
    val coordinator = rememberAutoScrollEffect(listState, latestMessageKey)

    var showKeyGate by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf(0L) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showAttachSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }

    // 选文件：OpenDocument 拿的是可长期读取的 Uri（不依赖临时授权），
    // 比 GetContent 好——GetContent 在进程重启后就读不到了。
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::onAttachmentPicked) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::onAttachmentPicked) }

    val context = LocalContext.current
    var tempCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var tempCameraFile by remember { mutableStateOf<File?>(null) }

    // 拍照结果回调。三个加固点：
    //  1. success=true 也可能什么都没写——部分 ROM 的相机返回成功但不落
    //     EXTRA_OUTPUT 的文件，不校验的话附件会永远停在「准备中」
    //  2. 进程被系统回收后 remember 状态丢失，tempCameraUri 会是 null
    //  3. 用完立刻清空，防止同一张旧图被误当成新一轮拍照的结果
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = tempCameraUri
        val file = tempCameraFile
        tempCameraUri = null
        tempCameraFile = null
        if (!success || uri == null || file == null) return@rememberLauncherForActivityResult
        if (!file.exists() || file.length() <= 0L) {
            Toast.makeText(
                context,
                R.string.chat_attach_photo_empty,
                Toast.LENGTH_SHORT
            ).show()
            return@rememberLauncherForActivityResult
        }
        viewModel.onAttachmentPicked(uri)
    }

    // 首次进入题目会话时预填首问，**不自动发送**。
    //
    // 必须同时等 !loading：早先只判 state.messages.isEmpty()，
    // 而 Flow 还没吐出第一帧时 messages 同样是空的——
    // 于是**打开一个已有对话时也会弹出一段预填草稿**，
    // 用户以为自己之前打的字没了。
    //
    // 还要判 input 为空：恢复了历史草稿的会话不能再被预填覆盖，
    // 否则用户上次打的字照样会丢（只是丢的方式换了）。
    LaunchedEffect(state.sessionId, state.loading) {
        if (state.sessionId != 0L && !state.loading && state.messages.isEmpty() &&
            questionId != null && state.input.isBlank()
        ) {
            viewModel.onInputChange(ChatViewModel.PREFILL_QUESTION)
        }
    }

    LaunchedEffect(state.needsApiKey) { if (state.needsApiKey) showKeyGate = true }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ChatEvent.Copied -> snackbarHost.showSnackbar(copiedText)
                is ChatEvent.Error -> snackbarHost.showSnackbar(event.text)
            }
        }
    }

    if (state.truncated) {
        AlertDialog(
            onDismissRequest = viewModel::dismissTruncatedNotice,
            title = { Text(stringResource(R.string.chat_truncated_title)) },
            text = { Text(stringResource(R.string.chat_truncated_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissTruncatedNotice) {
                    Text(stringResource(R.string.chat_dismiss))
                }
            }
        )
    }

    if (showKeyGate) {
        ApiKeyRequiredDialog(
            mineruMissing = false,
            llmMissing = true,
            onOpenSettings = {
                showKeyGate = false
                viewModel.dismissApiKeyGate()
                onOpenSettings()
            },
            onDismiss = {
                showKeyGate = false
                viewModel.dismissApiKeyGate()
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.chat_delete_confirm_title)) },
            text = { Text(stringResource(R.string.chat_delete_confirm_body)) },
            confirmButton = {
                // 两步确认：删除消息不可恢复，确认键再点一次才执行
                com.mistakebook.ui.common.TwoStepConfirmTextButton(
                    text = stringResource(R.string.chat_confirm),
                    confirmText = stringResource(R.string.chat_delete_confirm_action),
                    onConfirm = {
                        showDeleteDialog = false
                        viewModel.deleteMessage(pendingDeleteId)
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.chat_cancel))
                }
            }
        )
    }

    if (showRenameDialog) {        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
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
                    showRenameDialog = false
                    viewModel.renameSession(renameText)
                }) { Text(stringResource(R.string.chat_rename_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.chat_cancel))
                }
            }
        )
    }

    if (showAttachSheet) {
        AttachmentSourceSheet(
            onPickImage = { imagePicker.launch(IMAGE_MIME_TYPES) },
            onTakePhoto = {
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                val file = File(dir, "chat_photo_${System.currentTimeMillis()}.jpg")
                // FileProvider.getUriForFile 在路径未声明 / authority 不匹配时
                // 抛 IllegalArgumentException，兜住并提示而不是闪退
                val uri = runCatching {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                }.getOrNull()
                if (uri == null) {
                    Toast.makeText(
                        context,
                        R.string.chat_attach_photo_unavailable,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    tempCameraUri = uri
                    tempCameraFile = file
                    // **不要用 resolveActivity 预检**。
                    //
                    // v0.0.11 在这里加了预检，真机实测直接误判：设备上明明有相机，
                    // resolveActivity 却返回 null，于是走了「没有相机」分支，
                    // 拍照功能整个不可用。Android 11+ 的包可见性对
                    // ACTION_IMAGE_CAPTURE 本来就不完全可靠——部分 ROM 的相机
                    // 没有按规范暴露 intent-filter，<queries> 也救不回来。
                    //
                    // 改成直接 launch + try-catch：真有相机就一定拉得起来；
                    // 真的一个都没有时，launch 抛 ActivityNotFoundException，
                    // 由 runCatching 兜住并提示。预检唯一的价值是「别闪退」，
                    // 而 try-catch 已经做到了，且不会误伤。
                    //
                    // 注意这里传的是 uri 而不是自己拼的 Intent：
                    // cameraLauncher 用的是 ActivityResultContracts.TakePicture，
                    // 它的入参就是「照片写到哪个 Uri」，intent 由 contract 自己构造。
                    val launched = runCatching { cameraLauncher.launch(uri) }.isSuccess
                    if (launched) {
                        // 相机拉起成功后才关面板——万一启动失败，面板留着，
                        // 用户还能改选「从相册选择」
                        showAttachSheet = false
                    } else {
                        tempCameraUri = null
                        tempCameraFile = null
                        Toast.makeText(
                            context,
                            R.string.chat_attach_no_camera,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onPickFile = { filePicker.launch(DOCUMENT_MIME_TYPES) },
            onDismiss = { showAttachSheet = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title.ifBlank { title },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_cancel)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        renameText = state.title
                        showRenameDialog = true
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.chat_rename))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (state.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.chat_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    MessageList(
                        state = state,
                        listState = listState,
                        mathRenderer = container.mathRenderer,
                        bubbleImages = state.bubbleImages,
                        truncatedMessageId = state.truncatedMessageId,
                        onCopy = { text ->
                            clipboard.setText(AnnotatedString(text))
                            viewModel.notifyCopied()
                        },
                        onRetry = viewModel::retry,
                        onDelete = { id ->
                            pendingDeleteId = id
                            showDeleteDialog = true
                        }
                    )
                }

                if (coordinator.showJumpButton) {
                    JumpToBottomButton(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        onClick = { scope.launch { coordinator.jumpToBottom(listState) } }
                    )
                }
            }

            // 上下文状态条：摘要生效提示 + 上次请求 token 数 + 手动压缩入口。
            // 只在「有摘要」或「问过一轮」后出现，空会话不占地方。
            if (state.summaryActive || state.lastPromptTokens > 0) {
                ContextStatusRow(
                    summaryActive = state.summaryActive,
                    compressing = state.compressing,
                    lastPromptTokens = state.lastPromptTokens,
                    onCompress = viewModel::compressContext
                )
                Spacer(Modifier.height(4.dp))
            }

            // 后台 OCR 进度：不识图的模型收到图片时，先由 MinerU 把图里的字认出来。
            // 整段流程在协程里跑，界面不卡；这里只显示一行进度提示。
            state.ocrStage?.let { stage ->
                OcrProgressBar(stage = stage)
                Spacer(Modifier.height(4.dp))
            }

            // OCR 失败通知：常驻，带原因和「重试」。
            // 进度条会消失，失败原因不能跟着消失——否则用户只看到模型答非所问。
            state.ocrNotice?.let { notice ->
                OcrNoticeBar(notice = notice, onRetry = viewModel::retryOcr)
                Spacer(Modifier.height(4.dp))
            }

            // 模型切换改为输入栏里的选择入口 + 底部弹层（见 ChatInputBar）。
            // 旧的横向 chip 一排在模型多的时候要滚很久才能找到目标，
            // 弹层一屏列表能看到全部，还能就地去设置里调思考强度。

            ChatInputBar(
                text = state.input,
                streaming = state.isStreaming,
                attachments = state.pendingAttachments,
                modelName = state.currentModel,
                onTextChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onAttach = { showAttachSheet = true },
                onPickModel = { showModelSheet = true },
                onRemoveAttachment = viewModel::removePendingAttachment
            )
        }
    }

    if (showModelSheet) {
        ModelPickerSheet(
            models = state.modelOptions,
            selected = state.selectedModel,
            currentEffort = state.reasoningEffort,
            enabled = !state.isStreaming,
            onSelect = viewModel::selectModel,
            onEffortChange = viewModel::setReasoningEffort,
            onDismiss = { showModelSheet = false }
        )
    }
}

@Composable
private fun MessageList(
    state: ChatUiState,
    listState: LazyListState,
    mathRenderer: com.mistakebook.math.MathRenderer,
    bubbleImages: Map<Long, List<String>>,
    truncatedMessageId: Long?,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: (Long) -> Unit
) {
    // 倒序提供数据配合 reverseLayout：最新消息固定在底边，流式气泡增长无需反复重启滚动动画。
    val items: List<ChatListItem> = buildList {
        if (state.omittedCount > 0) add(ChatListItem.OmittedNotice(state.omittedCount))
        state.messages.forEach { message ->
            val preview = state.streamingContent
            val visibleMessage = if (message.id == state.streamingMessageId && preview != null) {
                message.copy(content = preview)
            } else {
                message
            }
            add(ChatListItem.Message(visibleMessage))
        }
    }
    val displayItems = remember(items) { items.asReversed() }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(
            items = displayItems,
            key = { item ->
                when (item) {
                    is ChatListItem.OmittedNotice -> "omitted-notice"
                    is ChatListItem.Message -> item.message.id
                }
            },
            // contentType 让 Compose 按类型分池复用：不写的话滚动时
            // 每条消息都可能拿到上一条不同类型的容器重新组合。
            contentType = { item ->
                when (item) {
                    is ChatListItem.OmittedNotice -> "notice"
                    is ChatListItem.Message -> "message"
                }
            }
        ) { item ->
            when (item) {
                is ChatListItem.OmittedNotice -> OmittedNoticeRow(item.count)
                is ChatListItem.Message -> MessageBubble(
                    message = item.message,
                    images = bubbleImages[item.message.id].orEmpty(),
                    truncated = truncatedMessageId == item.message.id,
                    mathRenderer = mathRenderer,
                    onCopy = onCopy,
                    onRetry = onRetry,
                    onDelete = onDelete
                )
            }
        }
    }
}

private sealed interface ChatListItem {
    data class OmittedNotice(val count: Int) : ChatListItem
    data class Message(val message: ChatMessage) : ChatListItem
}

@Composable
private fun OmittedNoticeRow(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp)
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
 * 压缩按钮常驻：自动压缩是安全网，用户想主动整理时随时可以按。
 */
@Composable
private fun ContextStatusRow(
    summaryActive: Boolean,
    compressing: Boolean,
    lastPromptTokens: Int,
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
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        TextButton(onClick = onCompress, enabled = !compressing) {
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

@Composable
private fun MessageBubble(
    message: ChatMessage,
    images: List<String>,
    truncated: Boolean,
    mathRenderer: com.mistakebook.math.MathRenderer,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: (Long) -> Unit
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
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp),
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

    // 题目上下文那条是给模型看的，用一条低调的分隔样式展示，
    // 免得用户以为 AI 莫名其妙先说了一段。
    if (isInjected) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.chat_context_header),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            RichText(
                text = message.content,
                mathRenderer = mathRenderer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 14.dp
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // 用户自己发的图片。**早先气泡只渲染 message.content，
                // 附件从来没进过界面**——模型看得到图，用户这边看不见，
                // 表现成「图片发不出去」。
                if (images.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        images.forEach { dataUrl ->
                            BubbleImage(dataUrl = dataUrl)
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
                val displayAnswer = remember(answer, isStreaming) {
                    if (isStreaming) trimIncompleteTrailingFormula(answer) else answer
                }
                if (thinking.isNotBlank()) {
                    ThinkingBlock(
                        text = thinking,
                        streaming = isStreaming,
                        mathRenderer = mathRenderer
                    )
                    if (answer.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (answer.isNotBlank() || thinking.isBlank()) {
                    RichText(
                        text = displayAnswer,
                        mathRenderer = mathRenderer,
                        modifier = if (isStreaming) Modifier.animateContentSize() else Modifier,
                        streaming = isStreaming,
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
            onDelete = onDelete
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
private fun StreamingDotsIndicator(color: Color) {
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
private fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? {
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
 * 直接把 data URL 喂给 `Image`，不引任何图片库（项目锁零新增依赖）。
 * `remember(dataUrl)` 是必须的：LazyColumn 滚动时每一帧都会重组，
 * 不缓存的话每帧都重新 base64 解码一张几百 KB 的图，滑不动。
 */
@Composable
private fun BubbleImage(dataUrl: String) {
    // **解码不能在组合期做。** base64 解码 + 两遍 BitmapFactory.decodeByteArray
    // 是几十毫秒的重活，放在 remember 里就是卡在合成这一帧上——带图的气泡
    // 每滑进屏幕一次就掉一次帧。produceState 让它在 IO 线程跑、完成后再回填。
    val bitmap by produceState<android.graphics.Bitmap?>(null, dataUrl) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = Base64.decode(dataUrl.substringAfter("base64,"), Base64.NO_WRAP)
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
    )
}

@Composable
private fun StatusRow(
    message: ChatMessage,
    isUser: Boolean,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: (Long) -> Unit
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
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
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
                    onClick = { onCopy(message.content) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.chat_copy),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
 * ## 正在思考时保持展开，结束后自动收起
 *
 * 模型还在输出时自动收起，页面会不停跳而且看不到「它在想什么」；
 * 一直展开着，正文一出来就被推到屏幕外。两种做法各错一半，
 * 所以按状态切换：思考中展开、结束收起，各一次，不反复横跳。
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
private fun ThinkingBlock(
    text: String,
    streaming: Boolean,
    mathRenderer: com.mistakebook.math.MathRenderer
) {
    var expanded by remember { mutableStateOf(true) }
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
            .animateContentSize()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .clickable { expanded = !expanded }
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
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.chat_thinking_collapse else R.string.chat_thinking_expand
                ),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded && displayText.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            RichText(
                text = displayText,
                mathRenderer = mathRenderer,
                style = MaterialTheme.typography.bodySmall,
                // 思考过程同样加深：onSurfaceVariant 在浅色主题下对比度不足
                color = MaterialTheme.colorScheme.onSurface,
                streaming = streaming
            )
        }
    }
}

@Composable
private fun JumpToBottomButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 4.dp,
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

@Composable
private fun OcrProgressBar(stage: String) {
    Surface(
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stage,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * 图片识别失败通知条。
 *
 * 与 [OcrProgressBar] 的分工：进度条报告「正在做什么」，跑了就换；
 * 本条目报告「结果不行，为什么」。失败不会自己好转，
 * 所以它常驻在输入栏上方，直到重试成功或离开会话。
 *
 * 右侧「重试」：清掉失败说明重新排队——网断了、额度没了
 * 这类原因恢复后，用户不用退出会话再进来。
 */
@Composable
private fun OcrNoticeBar(notice: String, onRetry: () -> Unit) {
    Surface(
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
        ) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = notice,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) {
                Text(
                    text = stringResource(R.string.chat_ocr_retry),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    streaming: Boolean,
    attachments: List<PendingAttachment>,
    modelName: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onPickModel: () -> Unit,
    onRemoveAttachment: (Long) -> Unit
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (attachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    attachments.forEach { attachment ->
                        PendingAttachmentChip(attachment = attachment, onRemove = { onRemoveAttachment(attachment.id) })
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // 上段：输入区。去掉了描边——两段式布局里容器本身就是边界，
            // 再画一圈边框就是双重框，又挤又旧。
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                maxLines = 5,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    errorBorderColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            )

            // 下段：操作条
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 附件在最左（用户点名：最左边是 +，右边那个加号不要了）
                IconButton(onClick = onAttach, enabled = !streaming, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.chat_attach)
                    )
                }

                Spacer(Modifier.width(6.dp))

                // 模型选择入口：圆形图标 + 当前模型名 + 下拉箭头。
                // 一个 provider 常供应几十个模型，点这里弹出完整列表。
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(enabled = !streaming, onClick = onPickModel)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = modelName.firstOrNull()?.uppercase() ?: "?",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = modelName.ifBlank { stringResource(R.string.chat_model_unset) },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = stringResource(R.string.chat_pick_model),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledIconButton(
                    onClick = { if (streaming) onStop() else onSend() },
                    enabled = streaming ||
                        text.isNotBlank() ||
                        attachments.any { it.status == com.mistakebook.domain.AttachmentStatus.READY },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = if (streaming) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(if (streaming) R.string.chat_stop else R.string.chat_send)
                    )
                }
            }
        }
    }
}

/**
 * 模型选择弹层（效仿主流 AI 应用的模型列表）。
 *
 * 两页内容共用一个弹层：
 * - **模型列表**：每行圆形首字母徽标 + 模型名 + 选中对勾；
 * - **设置**（右上角齿轮）：思考强度五档单选，写全局设置，
 *   与设置页同一数据源，下一次发送即生效。
 *
 * 为什么把思考强度塞在这里：它是「换模型时最常一起动的参数」——
 * 切到推理模型想拉满思考、切到快模型想关掉思考省额度，
 * 藏到设置页里每次都要跳出去改，打断对话节奏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    models: List<String>,
    selected: String,
    currentEffort: ReasoningEffort,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onEffortChange: (ReasoningEffort) -> Unit,
    onDismiss: () -> Unit
) {
    var page by remember { mutableStateOf(0) } // 0 模型列表 / 1 思考强度
    ModalBottomSheet(onDismissRequest = {
        page = 0
        onDismiss()
    }) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (page == 1) {
                    // 设置页左上角是「返回模型列表」
                    IconButton(onClick = { page = 0 }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回模型列表")
                    }
                }
                Text(
                    text = if (page == 0) stringResource(R.string.chat_pick_model)
                    else stringResource(R.string.settings_reasoning_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (page == 0) {
                    IconButton(onClick = { page = 1 }, enabled = enabled) {
                        Icon(Icons.Default.Settings, contentDescription = "思考强度设置")
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }

            if (page == 0) {
                if (models.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_model_unset),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
                models.forEach { model ->
                    val isSelected = selected == model ||
                        (selected.isBlank() && model == models.firstOrNull())
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) {
                                onSelect(model)
                                page = 0
                                onDismiss()
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = model.firstOrNull()?.uppercase() ?: "?",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = model,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "已选择",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            } else {
                // 思考强度：五档单选。当场生效打勾，不关弹层——
                // 用户往往要「调完强度继续换模型」。
                ReasoningEffort.entries.forEach { effort ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEffortChange(effort) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = currentEffort == effort, onClick = { onEffortChange(effort) })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = effortLabel(effort),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = effortHint(effort),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun effortLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.OFF -> "关闭"
    ReasoningEffort.LOW -> "低"
    ReasoningEffort.MEDIUM -> "中"
    ReasoningEffort.HIGH -> "高"
    ReasoningEffort.MAX -> "最高"
}

private fun effortHint(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.OFF -> "不带思考字段，服务端按默认来"
    ReasoningEffort.LOW -> "浅想一下，最快"
    ReasoningEffort.MEDIUM -> "平衡思考与速度"
    ReasoningEffort.HIGH -> "想清楚再答"
    ReasoningEffort.MAX -> "思考拉满（部分中转站支持）"
}

/** 附件来源选择。相册 / 拍照 / 文件三选一。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentSourceSheet(
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.chat_attach_camera)) },
                leadingContent = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                modifier = Modifier.clickable {
                    // onDismiss 在 onTakePhoto 内部相机拉起后才调：
                    // 启动失败时面板留着，用户可以直接改选相册
                    onTakePhoto()
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.chat_attach_gallery)) },
                leadingContent = { Icon(Icons.Default.Image, contentDescription = null) },
                modifier = Modifier.clickable {
                    onDismiss()
                    onPickImage()
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.chat_attach_files)) },
                leadingContent = { Icon(Icons.Default.Description, contentDescription = null) },
                modifier = Modifier.clickable {
                    onDismiss()
                    onPickFile()
                }
            )
        }
    }
}

@Composable
private fun PendingAttachmentChip(
    attachment: PendingAttachment,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (attachment.status == com.mistakebook.domain.AttachmentStatus.FAILED) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            if (attachment.status == com.mistakebook.domain.AttachmentStatus.PREPARING) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.chat_attachment_preparing),
                    style = MaterialTheme.typography.labelSmall
                )
            } else {
                Icon(
                    imageVector = when (attachment.kind) {
                        com.mistakebook.domain.AttachmentKind.IMAGE -> Icons.Default.Image
                        com.mistakebook.domain.AttachmentKind.PDF -> Icons.Default.PictureAsPdf
                        else -> Icons.Default.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = attachment.fileName.ifBlank { stringResource(R.string.chat_attach_file) },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 130.dp)
                    )
                    val subtitle = when (attachment.status) {
                        com.mistakebook.domain.AttachmentStatus.FAILED ->
                            attachment.errorMessage ?: stringResource(R.string.chat_attachment_failed)

                        else -> if (attachment.extractedChars > 0) {
                            stringResource(R.string.chat_attachment_extracted, attachment.extractedChars)
                        } else {
                            formatSize(attachment.sizeBytes)
                        }
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (attachment.status == com.mistakebook.domain.AttachmentStatus.FAILED) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 130.dp)
                    )
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.chat_attachment_remove),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

/** 字节数 -> 人类可读。 */
private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
