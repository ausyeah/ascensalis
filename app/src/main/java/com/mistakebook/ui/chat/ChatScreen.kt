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
import android.util.Base64
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
private val DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    // Excel（v0.0.57）：不加进来，文件管理器里根本选不中 xlsx/xls。
    // 用户反馈的「xls 不支持」很大一部分就卡在这一层。
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
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
    onOpenSettings: () -> Unit,
    /** 打开 App 内置相机（v0.0.57：替代系统相机；返回的图经容器交接）。 */
    onOpenCamera: () -> Unit = {}
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
            questionRepository = c.questionRepository,
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

    // AI 流式输出期间保持屏幕常亮（用户反馈：会话正在输出时因「长时间无操作」被息屏）。
    // 一次回答经常几十秒，用户全程在读、不需要碰屏幕，系统却按无操作掐屏，阅读被打断。
    // FLAG_KEEP_SCREEN_ON 是窗口级标志：只在 streaming 期间置位，结束/离开页面即清除，
    // 不引入 WakeLock 权限，也不影响其它页面的息屏策略。
    val chatView = LocalView.current
    val streamingNow = state.isStreaming
    DisposableEffect(chatView, streamingNow) {
        val window = chatView.context.findActivity()?.window
        if (streamingNow) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

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
    var showLibraryPicker by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // 选文件：OpenDocument 拿的是可长期读取的 Uri（不依赖临时授权），
    // 比 GetContent 好——GetContent 在进程重启后就读不到了。
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::onAttachmentPicked) }

    // 会话相册：照片多选（v0.0.52）。一次多张进待发区，聊天发多图不再需要
    // 借用错题的连拍批次——附件管线本身就是多图（气泡内网格渲染）。
    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(CHAT_GALLERY_MAX)
    ) { uris ->
        uris.forEach { uri -> viewModel.onAttachmentPicked(uri) }
    }

    // 会话拍照（v0.0.57）：改为跳转内置相机页，交回的图经
    // [AppContainer.pendingChatAttachmentPaths] 进入当轮附件。
    // 系统 TakePicture 路径已移除（见 onTakePhoto 注释）。

    // 裁切页交回的聊天附件（T12）：扫描相机 + 裁切确认后经容器标志交接。
    // 快照状态驱动重组 → LaunchedEffect 重跑；取走即清，防旧图误入下一轮。
    // 进程死亡不持久化（与旧 TakePicture 方案的记忆边界一致）。
    // 相机/裁切页交回的待发附件：单张（T12）与多张（T2 对话连拍）两条交接口。
    // 状态驱动 → LaunchedEffect 消费：取逻辑单一，附件准备异步转 placeholder。
    val pendingChatAttachment = container.pendingChatAttachmentPath
    val pendingChatAttachments = container.pendingChatAttachmentPaths
    LaunchedEffect(pendingChatAttachment) {
        if (!pendingChatAttachment.isNullOrBlank()) {
            container.pendingChatAttachmentPath = null
            viewModel.onAttachmentPicked(android.net.Uri.fromFile(File(pendingChatAttachment)))
        }
    }
    LaunchedEffect(pendingChatAttachments) {
        val paths = pendingChatAttachments
        if (!paths.isNullOrEmpty()) {
            container.pendingChatAttachmentPaths = null
            paths.forEach { path ->
                viewModel.onAttachmentPicked(android.net.Uri.fromFile(File(path)))
            }
        }
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
                is ChatEvent.Info -> snackbarHost.showSnackbar(event.text)
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

    // T6 降级：资料库文件选择弹层（列出 files/library/ 下的文本文件，多选注入）
    if (showLibraryPicker) {
        LibraryPickerDialog(
            filesDir = container.files.rootDir,
            selectedNames = state.libraryInjectionNames,
            onPick = { file -> viewModel.addLibraryInjection(file) },
            onRemove = { file -> viewModel.removeLibraryInjection(file) },
            onDismiss = { showLibraryPicker = false }
        )
    }

    if (showAttachSheet) {
        AttachmentSourceSheet(
            onPickImage = {
                // v0.0.52：相册多选——聊天发多图走附件管线自己搞定
                galleryPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onTakePhoto = {
                // v0.0.57：改走 App 内的相机页，不再拉系统相机。
                // 系统 TakePicture 在部分 ROM 上不落盘，也没有跟随主题的
                // 取景/增强体验；内置相机与错题扫描共用一套（见 NavHost）。
                showAttachSheet = false
                onOpenCamera()
            },
            onPickFile = { filePicker.launch(DOCUMENT_MIME_TYPES) },
            // T6 降级：资料库文件选择 → 加入待注入列表（发送时前置为当轮上下文）
            onPickLibrary = { showLibraryPicker = true },
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
        // 输入法 inset **只作用在底部输入栏**上（见 ChatInputBar 内的
        // `imePadding`），不挂在根 Column 上：
        // 挂在根上时整个内容区（含顶端 UI 的视觉位置）都会被键盘顶上去
        // 再回弹；挂底部则只有输入栏与对话列表随键盘上移，顶栏纹丝不动。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                        bubbleFiles = state.bubbleFiles,
                        truncatedMessageId = state.truncatedMessageId,
                        onCopy = { text ->
                            // 直接走 Android 原生剪贴板：Compose 的 clipboard API
                            // 一分为二（旧 ClipboardManager.setText 非 suspend 已弃用、
                            // 新 Clipboard.setClipEntry 要 ClipData），原生 API 在
                            // minSdk 26 上稳定且零弃用警告，行为与原来一致。
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("copied", text))
                            viewModel.notifyCopied()
                        },
                        onRetry = viewModel::retry,
                        onDelete = { id ->
                            pendingDeleteId = id
                            showDeleteDialog = true
                        },
                        // T13：批改结果「存入题目备注」。**只有题目会话才显示按钮**：
                        // 早先无条件传回调，自由会话里图标照画、点下去 saveToNote
                        // 第一行 questionId==null 静默退出——用户看到的就是
                        // 「第二个图标点了没反应」。
                        onSaveToNote = questionId?.let { viewModel::saveToNote }
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
            // 压缩按钮只在上下文到线（200k，见 MANUAL_COMPRESS_TOKENS）后出现：
            // 短会话没有压缩价值，常驻按钮只会诱导人去按。
            if (state.summaryActive || state.lastPromptTokens > 0) {
                ContextStatusRow(
                    summaryActive = state.summaryActive,
                    compressing = state.compressing,
                    lastPromptTokens = state.lastPromptTokens,
                    showCompress = state.lastPromptTokens >= ChatViewModel.MANUAL_COMPRESS_TOKENS,
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
                libraryInjectionNames = state.libraryInjectionNames,
                libraryFiles = remember { com.mistakebook.data.library.LibraryStore.list(
                    com.mistakebook.data.library.LibraryStore.ensureDir(container.files.rootDir)
                ) },
                onRemoveLibraryInjection = viewModel::removeLibraryInjection,
                onTextChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onAttach = { showAttachSheet = true },
                onPickModel = { showModelSheet = true },
                onRemoveAttachment = viewModel::removePendingAttachment,
                // T13 批改开关：仅题目会话显示（自由会话 questionId == null）
                gradingMode = state.gradingMode,
                gradingVisible = questionId != null,
                onToggleGrading = viewModel::toggleGrading
            )
        }
    }

    if (showModelSheet) {
        ModelPickerSheet(
            models = state.modelOptions,
            selected = state.selectedModel,
            providers = state.providerOptions,
            activeProviderId = state.activeProviderId,
            currentEffort = state.reasoningEffort,
            enabled = !state.isStreaming,
            searchEnabled = state.chatSearchEnabled,
            searchProvider = state.chatSearchProvider,
            onSelect = viewModel::selectModel,
            onSelectProvider = viewModel::selectProvider,
            onEffortChange = viewModel::setReasoningEffort,
            onSearchEnabledChange = viewModel::setChatSearchEnabled,
            onSearchProviderChange = viewModel::setChatSearchProvider,
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
    bubbleFiles: Map<Long, List<BubbleFile>>,
    truncatedMessageId: Long?,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: (Long) -> Unit,
    /** T13：批改结果「存入题目备注」入口；自由会话传 null 不展示。 */
    onSaveToNote: ((Long) -> Unit)? = null
) {
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
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 「此前省略 N 轮」提示条声明在消息**之后**：reverseLayout 下最后
        // 声明的 item 在屏幕顶部（对话开头）——省略的正是开头那些轮次，
        // 放顶部语义才对。更关键的是几何契约：AutoScrollCoordinator 的
        // 贴底判定/回底/增长补偿全部假定 **index 0 = 最新一条消息**；
        // 早先声明在前面时它是 index 0，只要会话出现过省略提示，
        // 整套触底几何就都在盯着这条小提示条而不是正在流式的那条消息。
        items(
            items = messages.asReversed(),
            // key 用消息 id：内容更新（流式 / 落库）时按 id 对齐，
            // 不因为实例变化而丢掉滚动位置与已组合的 item。
            key = { it.id },
            // contentType 让 Compose 按类型分池复用：不写的话滚动时
            // 每条消息都可能拿到上一条不同类型的容器重新组合。
            contentType = { "message" }
        ) { message ->
            val preview = if (message.id == state.streamingMessageId) {
                state.streamingContent
            } else {
                null
            }
            val visibleMessage = if (preview != null) message.copy(content = preview) else message
            MessageBubble(
                message = visibleMessage,
                images = bubbleImages[message.id].orEmpty(),
                files = bubbleFiles[message.id].orEmpty(),
                stage = state.generationStage.takeIf {
                    message.id == state.streamingMessageId && state.isStreaming
                },
                truncated = truncatedMessageId == message.id,
                mathRenderer = mathRenderer,
                onCopy = onCopy,
                onRetry = onRetry,
                onDelete = onDelete,
                onSaveToNote = onSaveToNote
            )
        }
        if (state.omittedCount > 0) {
            item(key = "omitted-notice", contentType = "notice") {
                OmittedNoticeRow(state.omittedCount)
            }
        }
    }
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
 * 压缩按钮只在 [showCompress]（上下文 ≥ 200k）时出现，由用户决定压不压；
 * 自动压缩进行中（compressing）按钮照常显示——「压缩中…」的反馈不能消失。
 */
@Composable
private fun ContextStatusRow(
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
private fun MessageBubble(
    message: ChatMessage,
    images: List<String>,
    /** 非图片附件（PDF/文档等）。点击交给系统打开，本应用不做阅读器。 */
    files: List<BubbleFile>,
    /** 首个 token 之前的阶段提示（如「正在联网搜索…」），仅流式中传。 */
    stage: String?,
    truncated: Boolean,
    mathRenderer: com.mistakebook.math.MathRenderer,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: (Long) -> Unit,
    /** T13：把助手回复存入题目备注。null = 当前会话不展示该入口（自由会话）。 */
    onSaveToNote: ((Long) -> Unit)? = null
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
            // v0.0.58：**只有用户消息有气泡**，AI 回复全宽度铺开
            //（对齐 ChatGPT 移动端：用户消息框起来，助手消息通栏）。
            // 原来助手也用 surfaceVariant 气泡 + 320dp 限宽，长公式/表格/HTML
            // 全被挤在窄列里——既割裂阅读，也让宽公式更容易被右侧裁掉。
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            },
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 14.dp
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
                        BubbleImage(dataUrl = images.first())
                    } else {
                        images.chunked(2).forEach { rowImages ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowImages.forEach { dataUrl ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        BubbleImage(dataUrl = dataUrl)
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
                            AttachmentFileChip(file = file)
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
                        streaming = isStreaming,
                        mathRenderer = mathRenderer,
                        // 思考块与正文同属一条消息：共用消息 id 隔离防抖
                        debounceKey = message.id
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
    mathRenderer: com.mistakebook.math.MathRenderer,
    debounceKey: Any? = null
) {
    // 初始展开态直接取 streaming：历史消息（streaming=false）首次组合就是
    // 收起态，不会先把整段思考渲染一遍再折叠——那笔开销在滚动到每条
    // 带思考的历史消息时都要付一次，是「新 item 进视口就卡一下」的一员。
    // 流式中为 true；流式结束由下面的 LaunchedEffect 收起。
    var expanded by remember { mutableStateOf(streaming) }
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
                streaming = streaming,
                showFailedMathSource = false
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
    /** T6 降级：待注入的资料库文件名与文件列表（chips 移除用）。 */
    libraryInjectionNames: List<String> = emptyList(),
    libraryFiles: List<java.io.File> = emptyList(),
    onRemoveLibraryInjection: (java.io.File) -> Unit = {},
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onPickModel: () -> Unit,
    onRemoveAttachment: (Long) -> Unit,
    /** 批改模式开闭（T13）。 */
    gradingMode: Boolean = false,
    /** 是否展示批改开关：仅题目会话。 */
    gradingVisible: Boolean = false,
    onToggleGrading: () -> Unit = {}
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 键盘弹出时输入栏坐到键盘上；键盘收起时贴齐手势导航条。
                // 用 union 而不是两个 padding 叠加：ime 的底边距本身就
                // 含导航栏区域，再叠一层 navigationBarsPadding 会让输入栏
                // 浮在键盘上方一条导航栏的高度。
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (libraryInjectionNames.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    libraryInjectionNames.forEach { name ->
                        LibraryInjectionChip(
                            fileName = name,
                            onRemove = {
                                libraryFiles.firstOrNull { it.name == name }
                                    ?.let(onRemoveLibraryInjection)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
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
                placeholder = {
                    Text(
                        stringResource(
                            if (gradingMode) R.string.chat_input_hint_grading
                            else R.string.chat_input_hint
                        )
                    )
                },
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
                // v0.0.58：生成中也能加附件（用户反馈「可以打字但不能添加图片」）。
                // 附件只是排队成 pendingAttachments，等这一轮结束后随下一条消息发出，
                // 与「打字」完全同权——没有理由只禁这一项。
                IconButton(onClick = onAttach, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.chat_attach)
                    )
                }

                Spacer(Modifier.width(6.dp))

                // 批改开关（T13）：选中态高亮 + contentDescription 说明。
                // 只对题目会话显示——自由会话没有题干/答案可对照，批改无从谈起。
                if (gradingVisible) {
                    val gradingTint =
                        if (gradingMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    IconButton(
                        onClick = onToggleGrading,
                        enabled = !streaming,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Filled.RateReview,
                            contentDescription = stringResource(R.string.chat_grading_toggle),
                            tint = gradingTint
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }

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
    providers: List<ChatProviderOption>,
    activeProviderId: String?,
    currentEffort: ReasoningEffort,
    enabled: Boolean,
    searchEnabled: Boolean,
    searchProvider: ChatSearchProvider,
    onSelect: (String) -> Unit,
    onSelectProvider: (String) -> Unit,
    onEffortChange: (ReasoningEffort) -> Unit,
    onSearchEnabledChange: (Boolean) -> Unit,
    onSearchProviderChange: (ChatSearchProvider) -> Unit,
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
                // ---- 供应商切换（扁平列表，点选即换，不展开不成表单）----
                //
                // 用户诉求：「切换模型不能跳转切换供应商……点开设置里面
                // 就是几个供应商列表，但是不能展开，可以自己选择哪个
                // 供应商来切换模型」。所以这里只做「选一个」：
                // 写全局 activeProfileId，模型列表随设置流自动换成
                // 新供应商的；编辑配置仍然回设置页。
                if (providers.isNotEmpty()) {
                    Text(
                        text = "供应商",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 6.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        providers.forEach { provider ->
                            FilterChip(
                                selected = provider.id == activeProviderId,
                                onClick = { onSelectProvider(provider.id) },
                                enabled = enabled,
                                label = { Text(provider.name, maxLines = 1) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
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

            // ---- 联网搜索（v0.0.43）：只留「选择 + 开启」 ----
            //
            // v0.0.43 起改走 function calling：开关只决定**要不要给模型挂
            // web_search 工具**，用不用、什么时候用由模型自己决定——
            // 不再是「回答前先盲目搜一次」。API Key 等具体配置已移到
            // 设置页（SearchProviderSection），这里不再堆表单。
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSearchEnabledChange(!searchEnabled) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("联网搜索", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "需要时由模型自主检索最新网页（DeepSeek / Tavily）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = searchEnabled, onCheckedChange = onSearchEnabledChange)
            }
            if (searchEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = searchProvider == ChatSearchProvider.DEEPSEEK,
                        onClick = { onSearchProviderChange(ChatSearchProvider.DEEPSEEK) },
                        label = { Text("DeepSeek") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = searchProvider == ChatSearchProvider.TAVILY,
                        onClick = { onSearchProviderChange(ChatSearchProvider.TAVILY) },
                        label = { Text("Tavily") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    when (searchProvider) {
                        ChatSearchProvider.DEEPSEEK ->
                            "调用 DeepSeek 官方搜索服务，与对话模型无关：用别家大模型时也能获得搜索能力。API Key 在「设置 → 联网搜索」配置，留空则复用 DeepSeek 模型 Key。"

                        ChatSearchProvider.TAVILY ->
                            "使用 Tavily 独立搜索服务，API Key 请在「设置 → 识别与大模型 → 联网搜索」里配置。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
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
    onPickLibrary: () -> Unit = {},
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
            ListItem(
                headlineContent = { Text(stringResource(R.string.chat_attach_library)) },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                modifier = Modifier.clickable {
                    onDismiss()
                    onPickLibrary()
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
/**
 * 气泡里的非图片附件芯片（PDF/文档/其它）。
 *
 * 展示图标 + 文件名 + 大小/提取字数；点击经 FileProvider 授权后
 * 交给**系统**按类型打开（PDF 阅读器这类能力不进本应用——系统里
 * 装了什么就用什么）。文件副本丢失或没有可接手的应用时给 toast，
 * 不抛异常也不静默。
 */
@Composable
private fun AttachmentFileChip(file: BubbleFile) {
    val context = LocalContext.current
    val failed = file.errorMessage != null
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (failed) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openChatAttachment(context, file) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = when (file.kind) {
                    com.mistakebook.domain.AttachmentKind.PDF -> Icons.Default.PictureAsPdf
                    com.mistakebook.domain.AttachmentKind.IMAGE -> Icons.Default.Image
                    else -> Icons.Default.Description
                },
                contentDescription = null,
                tint = if (failed) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.fileName.ifBlank { stringResource(R.string.chat_attach_file) },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = file.errorMessage ?: run {
                    // stringResource 是 @Composable，只能在本层调用，
                    // buildString 的 lambda 里拿不到。
                    val extracted = stringResource(
                        R.string.chat_attachment_extracted, file.extractedChars
                    )
                    buildString {
                        file.sizeBytes.takeIf { it > 0 }?.let { append(formatSize(it)) }
                        file.extractedChars.takeIf { it > 0 }?.let {
                            if (isNotEmpty()) append(" · ")
                            append(extracted)
                        }
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = stringResource(R.string.chat_attachment_open),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 用系统应用打开一条聊天附件。
 *
 * FileProvider 授权（附件在 filesDir/chats 下，`file_paths.xml` 的
 * `internal_files` 已覆盖）→ ACTION_VIEW 带类型；系统没有能接手的应用、
 * 或本地副本已被清掉时 toast 说明，绝不静默失败。
 */
private fun openChatAttachment(context: Context, file: BubbleFile) {
    val local = File(file.localPath)
    if (!local.exists()) {
        Toast.makeText(context, R.string.chat_attachment_open_missing, Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(
        context, context.packageName + ".fileprovider", local
    )
    val mime = file.mimeType.ifBlank {
        when (file.kind) {
            com.mistakebook.domain.AttachmentKind.PDF -> "application/pdf"
            com.mistakebook.domain.AttachmentKind.TEXT -> "text/plain"
            else -> "application/octet-stream"
        }
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, R.string.chat_attachment_no_viewer, Toast.LENGTH_SHORT).show()
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}

/** 从 View.context 一路剥 ContextWrapper 找到宿主 Activity；找不到返回 null（如 Preview）。 */
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * 流式期间裁掉未闭合的行内/块级公式尾巴；静态文本原样返回。
 * 纯函数抽出便于与防抖组合。
 */
private fun trimIncomplete(text: String, streaming: Boolean): String =
    if (streaming) trimIncompleteTrailingFormula(text) else text
