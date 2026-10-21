package com.mistakebook.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.mistakebook.R
import com.mistakebook.data.local.entities.ChatMessage
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.ChatRole
import com.mistakebook.domain.MessageStatus
import com.mistakebook.ui.common.ApiKeyRequiredDialog
import com.mistakebook.ui.common.RichText
import com.mistakebook.ui.common.containerViewModel
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.ExpandLess
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import android.content.Intent
import android.provider.MediaStore
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
            questionId = questionId,
            sessionId = sessionId
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
    LaunchedEffect(state.sessionId, state.loading) {
        if (state.sessionId != 0L && !state.loading && state.messages.isEmpty() && questionId != null) {
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
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteMessage(pendingDeleteId)
                }) { Text(stringResource(R.string.chat_confirm)) }
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
                    // 没有 app 响应 ACTION_IMAGE_CAPTURE（相机被禁用/卸载/魔改 ROM）时
                    // launch 会抛 ActivityNotFoundException 直接闪退。
                    // manifest 里已声明 <queries>，resolveActivity 才能看见相机 app。
                    val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    val hasCamera = capture.resolveActivity(context.packageManager) != null
                    if (!hasCamera) {
                        tempCameraUri = null
                        tempCameraFile = null
                        Toast.makeText(
                            context,
                            R.string.chat_attach_no_camera,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
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

            ChatInputBar(
                text = state.input,
                streaming = state.isStreaming,
                attachments = state.pendingAttachments,
                onTextChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onAttach = { showAttachSheet = true },
                onRemoveAttachment = viewModel::removePendingAttachment
            )
        }
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
                        streaming = isStreaming
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
                    // 光标：流式时末尾的小圆点
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
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
    val bitmap = remember(dataUrl) {
        runCatching {
            val bytes = Base64.decode(dataUrl.substringAfter("base64,"), Base64.NO_WRAP)
            decodeSampled(bytes, BUBBLE_IMAGE_MAX_PX)
        }.getOrNull()
    }
    if (bitmap == null) {
        Text(
            text = stringResource(R.string.chat_image_unreadable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
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
 */
@Composable
private fun ThinkingBlock(text: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(true) }
    LaunchedEffect(streaming) {
        if (!streaming) {
            expanded = false
        }
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
        if (expanded && text.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                // 思考过程同样加深：onSurfaceVariant 在浅色主题下对比度不足
                color = MaterialTheme.colorScheme.onSurface
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
private fun ChatInputBar(
    text: String,
    streaming: Boolean,
    attachments: List<PendingAttachment>,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
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

            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onAttach, enabled = !streaming, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = stringResource(R.string.chat_attach)
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                    maxLines = 5,
                    shape = RoundedCornerShape(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { if (streaming) onStop() else onSend() },
                    enabled = streaming ||
                        text.isNotBlank() ||
                        attachments.any { it.status == com.mistakebook.domain.AttachmentStatus.READY },
                    modifier = Modifier.size(48.dp)
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
