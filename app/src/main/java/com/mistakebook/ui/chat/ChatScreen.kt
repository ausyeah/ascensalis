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
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.History
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
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import com.mistakebook.R
import com.mistakebook.ui.theme.LocalDarkTheme
import com.mistakebook.data.chat.ChatToolPrompts
import com.mistakebook.domain.AttachmentStatus
import com.mistakebook.data.chat.ChatContextAssembler
import com.mistakebook.data.chat.ChatSearchProvider
import com.mistakebook.data.local.entities.ChatMessage
import com.mistakebook.data.prefs.ReasoningEffort
import com.mistakebook.di.AppContainer
import com.mistakebook.di.PendingTool
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * 会话相册一次最多选几张（v0.0.52 多选）。
 *
 * 取 9 与旧 CHAT 连拍上限一致：附件要逐张压缩/转 data URL，
 * 一次十几张既慢也没意义，真需要更多分几次选。
 */
private const val CHAT_GALLERY_MAX = 9

/** 选文档。PDF 单独列出，因为部分文件管理应用不会把 .pdf 归到 text 类型。 */

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
    onOpenCamera: () -> Unit = {},
    /** 抽屉里点了另一条历史会话（v0.0.96）：由 NavHost 导航过去。 */
    onOpenSession: (sessionId: Long, questionId: Long?) -> Unit = { _, _ -> },
    /** 右上角：开一条临时对话，退出即删（v0.0.96）。 */
    onNewTemporaryConversation: () -> Unit = {}
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
            titleGenerator = c.chatTitleGenerator,
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

    // 「翻上去别动」（v0.0.96，学习 NovelForge）：用户离开底部看历史时，
    // 冻结流式预览——reverseLayout 下最后一条长高会把上面所有内容顶走，
    // 用户没动，页面自己在抽。回到触底自动解冻并补上攒下的内容。
    LaunchedEffect(coordinator.following) {
        viewModel.setStreamFrozen(!coordinator.following)
    }

    var showKeyGate by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf(0L) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }

    previewImagePath?.let { path ->
        com.mistakebook.ui.common.FullScreenImageDialog(
            imagePath = path,
            contentDescription = stringResource(R.string.chat_cd_image_preview),
            onDismiss = { previewImagePath = null }
        )
    }
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
    // 功能入口的交接包按 sessionId 认领（v0.0.98 审查修正）：屏幕上可能
    // 还挂着上一条会话，不认 id 的话指令会注错地方。
    val pendingTool = container.pendingTool

    // ① 隐藏指令：会话就绪且一条消息都没有时落成 SYSTEM 消息。
    //    只消费指令本身，拍照/自动发送两个意图留着——它们在拍摄回来后才用得上。
    LaunchedEffect(pendingTool, state.sessionId, state.loading) {
        val mine = pendingTool?.takeIf { it.sessionId == state.sessionId } ?: return@LaunchedEffect
        if (state.loading || state.messages.isNotEmpty()) return@LaunchedEffect
        if (mine.instruction.isNullOrBlank()) return@LaunchedEffect
        container.pendingTool = mine.copy(instruction = null)
        viewModel.installInstruction(mine.instruction)
    }

    // ② 拍题 / 翻译 / 作文批改要先拍照：会话就绪后自动拉起相机。
    //    **开不开相机由入口本身决定**（mine.tool.needsCamera），不是某个抄过来的开关。
    //    「拉起相机」必须**用掉即焚**（launchedCamera）：这条 effect 的 key 含
    //    state.loading / sessionId，从相机返回时页面重建，条件一成立就会再拉一次——
    //    用户点关闭后相机立刻又盖回来，看起来就是「关闭按钮坏了」。同理也会
    //    叠出两份取景器互相 unbindAll，预览全黑。
    LaunchedEffect(pendingTool, state.sessionId, state.loading) {
        val mine = pendingTool?.takeIf { it.sessionId == state.sessionId } ?: return@LaunchedEffect
        if (!mine.tool.needsCamera || state.loading || mine.launchedCamera) return@LaunchedEffect
        container.pendingTool = mine.copy(launchedCamera = true)
        if (state.pendingAttachments.isNotEmpty()) return@LaunchedEffect
        onOpenCamera()
    }

    // ③ 拍完裁切回来、附件就绪即自动发送——「拍完直接发送」就该一步到位。
    //    两种情况必须撤防：附件准备失败（all{READY} 永不成立）、
    //    用户自己按了发送——否则之后随手选一张图就会被静默发出去。
    LaunchedEffect(pendingTool, state.pendingAttachments) {
        val mine = pendingTool?.takeIf { it.sessionId == state.sessionId } ?: return@LaunchedEffect
        // 自动发送只属于「拍完就发」的入口（拍题 / 作文批改）
        if (!mine.tool.needsCamera) return@LaunchedEffect
        val pending = state.pendingAttachments
        if (pending.isEmpty()) return@LaunchedEffect
        when {
            pending.any { it.status == AttachmentStatus.FAILED } ->
                container.pendingTool = null
            pending.all { it.status == AttachmentStatus.READY } -> {
                container.pendingTool = null
                // 题目批改（v0.0.121，issue #36）：这一轮按批改发——
                // OCR 手写过程 + 注入批改指令都走 T13 那条路，只作用于本轮。
                viewModel.send(forceGrading = mine.tool == ChatTool.GRADE)
            }
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

    // 收键盘用：点左上角开抽屉时先把输入法收掉（用户反馈「键盘不会自动消失」）
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // 历史抽屉（v0.0.96）：右滑边缘或点左上角滑出，盖住大半屏。
    // ModalNavigationDrawer 自带边缘手势，不用自己写拖拽判定。
    val drawerState = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)

    // 退出会话统一走 onBack：NavHost 在那里判断「是不是临时对话」并决定删不删。
    // 不接住系统返回键的话，系统自己 pop 栈，删临时对话的那一步就被跳过了。
    // 抽屉开着时返回键优先关抽屉——否则一按返回直接退出了会话，
    // 用户从历史里点进来又按一下返回，就莫名其妙回到了上一条对话。
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = !drawerState.isOpen) { onBack() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ChatHistorySheet(
                    container = container,
                    drawerState = drawerState,
                    snackbarHostState = snackbarHost,
                    onOpenSession = onOpenSession
                )
            }
        }
    ) {
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
                    // 左上角进历史（v0.0.96，用户指定的 ChatGPT 手机端布局）。
                    // 退出会话改由系统返回键/手势承担——顶栏这一格让给了更高频的「翻历史」。
                    IconButton(
                        onClick = {
                            // 抽屉是「离开输入」的动作：先收键盘再拉开。
                            // 不收的话键盘会留在抽屉下面，用户以为界面卡住了。
                            keyboard?.hide()
                            focusManager.clearFocus()
                            scope.launch { drawerState.open() }
                        }
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = stringResource(R.string.chat_list_title)
                        )
                    }
                },
                actions = {
                    // 右上角：临时对话（v0.0.96）。退出就删，用来问那种
                    // 「不想留在历史里」的问题。普通新对话的入口在首页与历史页。
                    IconButton(onClick = onNewTemporaryConversation) {
                        Icon(
                            painter = painterResource(R.drawable.ic_temp_chat),
                            contentDescription = stringResource(R.string.chat_temporary_new),
                            // 灰：临时对话是「次要动作」，用色重了会和正式对话抢
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
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
            // 临时对话横幅（v0.0.112）：临时对话退出不再删除，而是归档；
            // 界面必须把「现在在临时对话里、退出后会去哪」讲清楚，
            // 否则用户以为聊的内容凭空消失了。
            if (state.temporary) {
                TemporaryChatBanner()
            }
            Box(modifier = Modifier.weight(1f)) {
                if (state.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.chat_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (showChatEmptyState(state.loading, state.messages.size)) {
                    // v0.0.121（issue #34）：传全部消息数而不是 visibleMessageCount。
                    // 题目上下文是注入消息、不计入可见数——原来一进题目会话
                    // 空状态就盖住整页，题目卡要等模型回应后才「冒出来」。
                    // 注入消息也算内容， MessageList 里的题目卡第一时间可见。
                    // 空会话中央原来是纯白一块：用户进来第一眼不知道这页能干嘛。
                    // 标志 + 签名把「这是哪儿」说清楚，比任何引导浮层都轻，也不占状态。
                    ChatEmptyState(
                        onSnapAsk = {
                            container.pendingTool = PendingTool(
                                sessionId = state.sessionId,
                                tool = ChatTool.PHOTO,
                                instruction = ChatToolPrompts.ASK
                            )
                        }
                    )
                } else {
                    MessageList(
                        state = state,
                        listState = listState,
                        mathRenderer = container.mathRenderer,
                        bubbleImages = state.bubbleImages,
                        bubbleFiles = state.bubbleFiles,
                        truncatedMessageId = state.truncatedMessageId,
                        onImageClick = { previewImagePath = it },
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
                        onRetry = { id -> viewModel.retry(id) },
                        onContinue = { id -> viewModel.continueGeneration(id) },
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

                // 空会话不显示「回到底部」：没有消息可滚，那颗浮钮是纯噪音
                if (coordinator.showJumpButton && state.visibleMessageCount > 0) {
                    JumpToBottomButton(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        onClick = { scope.launch { coordinator.jumpToBottom(listState) } }
                    )
                }
            }

            // 上下文状态条：摘要生效提示 + 上次请求 token 数 + 手动压缩入口。
            // 压缩按钮在上下文到线（见 MANUAL_COMPRESS_TOKENS）或**服务端报上下文超限**
            // （contextOverflow：超限会话 token 数永远拿不到，只按 token 数显示的话
            // 死会话一次都不会出现——v0.0.62 长会话死区修复）后出现。
            if (state.summaryActive || state.lastPromptTokens > 0 || state.contextOverflow) {
                ContextStatusRow(
                    summaryActive = state.summaryActive,
                    compressing = state.compressing,
                    lastPromptTokens = state.lastPromptTokens,
                    showCompress = state.lastPromptTokens >= ChatViewModel.MANUAL_COMPRESS_TOKENS ||
                        state.contextOverflow,
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
                onSend = {
                    // 手动发送 = 自动发送意图作废（否则稍后选图会被静默发出）。
                    // 没有「只关自动发送」这个开关可关了——那件事由入口本身决定，
                    // 所以这里直接把整包交接清掉：入口是它自己带来的。
                    val mine = container.pendingTool
                    if (mine != null && mine.sessionId == state.sessionId) {
                        container.pendingTool = null
                    }
                    viewModel.send()
                },
                onStop = viewModel::stop,
                // 流式中打了字才出现的「递上去」：停当前生成 + 发这一轮
                onSteer = viewModel::steer,
                onAttach = { showAttachSheet = true },
                onPickModel = { showModelSheet = true },
                onRemoveAttachment = viewModel::removePendingAttachment
            )
        }
    }
    }

    if (showModelSheet) {
        ModelPickerSheet(
            models = state.modelOptions,
            selected = state.selectedModel,
            providers = state.providerOptions,
            activeProviderId = state.activeProviderId,
            currentEffort = state.reasoningEffort,
            // 生成中也能换模型：换的只是下一轮，正在跑的这一轮不受影响
            enabled = true,
            searchEnabled = state.chatSearchEnabled,
            searchProvider = state.chatSearchProvider,
            onSelect = viewModel::selectModel,
            onSelectProvider = viewModel::selectProvider,
            onEffortChange = viewModel::setReasoningEffort,
            onSearchEnabledChange = viewModel::setChatSearchEnabled,
            onSearchProviderChange = viewModel::setChatSearchProvider,
            onOpenSettings = {
                showModelSheet = false
                onOpenSettings()
            },
            onDismiss = { showModelSheet = false }
        )
    }
}




/**
 * 中央空状态什么时候出场：会话读完了，且**用户看得见的**消息一条都没有。
 *
 * 「看得见」这个限定是 v0.0.99 加的：功能入口的隐藏指令是以 injected 消息
 * 入库的，它一进会话 [state.messages] 就非空了——空状态（标志 + 签名）不出现，
 * 用户点完「英语翻译」看到的是一片空白，连「这条会话是空的」都不知道。
 * 题目上下文那条注入消息同理。
 *
 * 纯函数（单测见 ChatEmptyStateTest）。`loading` 必须挡在前面——
 * 进会话要先读数据库，这几百毫秒里消息数也是 0，
 * 不挡的话用户会先看见鲸鱼闪一下再跳回加载中。
 */
internal fun showChatEmptyState(loading: Boolean, messageCount: Int): Boolean =
    !loading && messageCount == 0


/**
 * 空会话的中央状态：应用标志 + 签名。
 *
 * ## 为什么换掉那条蓝色金鱼（v0.0.96 用户拍板）
 * 蓝色金鱼是「有颜色的插画」，而空状态是用户见到这个产品的第一眼：
 * - 颜色跟强调色走，用户一换主题色（设置里有 8 档），首页标志也跟着变，
 *   品牌就没了固定形象；
 * - 深色主题下那条亮蓝尤其扎眼，和整页「中性锌灰」的克制完全不搭。
 *
 * 现在只做**黑白**：矢量标志 + 一行签名。颜色取 `onSurface`，
 * 浅色主题下是墨黑、深色主题下是纸白——同一枚标志，两种纸。
 *
 * 签名用 Serif + Italic + 宽字距：系统自带字体里最接近「行楷英语字样」的
 * 组合，而且不打包字体文件（仓库硬性规则：不加依赖、不涨包体）。
 * 这里**刻意不跟随全局字体设置**——签名是品牌标识，不该被用户的正文偏好改写。
 */
@Composable
private fun ChatEmptyState(onSnapAsk: () -> Unit, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 点标志直接进拍题（v0.0.98）：空会话里唯一能干的事就是开始一问，
        // 而这一页最常见的问法就是「拍一道题」。标志本身就是最大的可点区域。
        //
        // v0.0.121：中央标志换成用户定稿的「登临」山岳徽章（明/暗两版，
        // 用户认真手作）。选哪一版由 [LocalDarkTheme] 决定——它拿的是
        // 应用**实际渲染**的主题（设置里可强制浅色/深色），不是系统夜间
        // 开关：用户强制浅色时若跟系统走，就会在米白纸面上贴炭黑徽章。
        // 浅版米白底贴浅色纸面、深版炭黑底贴深色纸面，随背景自适应。
        // v0.0.114 的 Canvas 相机随之退役。
        Image(
            painter = painterResource(
                if (LocalDarkTheme.current) R.drawable.brand_emblem_dark
                else R.drawable.brand_emblem_light
            ),
            contentDescription = stringResource(R.string.home_snap_ask),
            modifier = Modifier
                .size(128.dp)
                // 不再加 clip：徽章 PNG 自带与外框对齐的透明圆角，
                // 再叠一层 clip 会在圆角处切掉外框（用户截图指出的裁切伤）。
                .clickable(role = Role.Button, onClick = onSnapAsk)
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.app_signature),
            color = ink,
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Normal,
                letterSpacing = 2.5.sp
            )
        )
    }
}


@Composable
private fun OcrProgressBar(stage: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
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
/**
 * 临时对话横幅：常驻在消息区上方的一条细提示。
 *
 * v0.0.112 起临时对话退出是「归档」而不是「删除」——行为变了，
 * 界面必须跟上：用户得知道当前在临时状态、退出后内容去了哪、
 * 刚退出想找回该去哪里。常驻而不做成一次性 snackbar：
 * 聊到一半才想起来「这是临时对话」已经晚了。
 */
@Composable
private fun TemporaryChatBanner() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_temp_chat),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.chat_temporary_banner),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OcrNoticeBar(notice: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
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
