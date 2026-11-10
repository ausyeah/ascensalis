package com.mistakebook.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
// positionChange() 是扩展函数，必须显式导入：PointerInputChange 上有一个
// 同名的 internal 成员（消费标记），不导入会解析到它而编译不过。
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BarChart
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.data.local.displayTitle
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.local.printImagePath
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.MasteryStatus
import com.mistakebook.ui.common.DifficultyStars
import com.mistakebook.ui.common.ApiKeyRequiredDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.mistakebook.ui.common.EmptyState
import com.mistakebook.ui.common.ErrorReasonChip
import com.mistakebook.ui.common.Format
import com.mistakebook.ui.common.QuestionThumb
import com.mistakebook.ui.common.SubjectDot
import com.mistakebook.ui.common.ReorderDragHandle
import com.mistakebook.ui.common.moveItem
import com.mistakebook.ui.common.orderByIds
import com.mistakebook.ui.common.containerViewModel
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.mistakebook.ui.common.SwipeActionBlock
import com.mistakebook.ui.common.SwipeActionGap
import com.mistakebook.ui.common.swipeActionTileShape
import com.mistakebook.ui.common.SwipeRevealCoordinator
import com.mistakebook.ui.common.SwipeActionBlockWidth
import com.mistakebook.ui.common.SwipeActionJunctionRadius
import com.mistakebook.ui.common.SwipeRevealSpring
import com.mistakebook.ui.common.swipeRevealContentAlpha
import com.mistakebook.ui.common.swipeRevealProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    filterDue: Boolean = false,
    filterDueRequest: Int = 0,
    pickedNotebookId: Long? = null,
    onNotebookPicked: (Long?) -> Unit = {},
    onAddByPhoto: () -> Unit,
    /**
     * 相册导入的单张图进裁剪页。
     * 相册照片和拍照照片走**完全相同**的流程：框选、旋转 90°、涂鸦遮蔽，
     * 全部走完才提交识别。之前相册是导入后直接提交，裁剪页被整个跳过，
     * 所以用户没法框选题目、也没法涂掉红笔批注。
     *
     * 原来的 `onImported: (List<Long>) -> Unit` 随之移除——
     * 相册不再直连提交，没有任务 id 需要回传给首页了。
     */
    onCropImage: (String) -> Unit,
    onImportPdf: () -> Unit,
    /** 手动录入：完全不依赖 API，Key 没配 / 识别连不上时的兜底入口。 */
    onAddManual: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPrint: () -> Unit,
    onOpenNotebooks: () -> Unit = {},
    onOpenQuestion: (Long) -> Unit,
    /** AI 对话：直接开一条新对话（v0.0.96）。历史会话改从对话页左上角进。 */
    onNewChat: () -> Unit = {},
    /** 今日有到期题时横幅点击 → 进独立复习页（T1）。 */
    onStartReview: () -> Unit = {}
) {
    val viewModel: HomeViewModel = containerViewModel(container) { HomeViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val questionListState = rememberLazyListState()
    var arrangedQuestionIds by remember { mutableStateOf<List<Long>?>(null) }
    var draggedQuestionId by remember { mutableStateOf<Long?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val displayQuestions = remember(state.questions, arrangedQuestionIds) {
        arrangedQuestionIds?.let { orderByIds(state.questions, it) { question -> question.id } }
            ?: state.questions
    }
    // 学科名查表：列表每一项都要显示学科名，原来是每项都对 subjects 做一次
    // firstOrNull 线性扫描，几十项 × 每次重组。建一次 Map，之后 O(1)。
    val subjectNames = remember(state.subjects) {
        state.subjects.associate { it.id to it.name }
    }

    fun beginQuestionDrag(id: Long) {
        draggedQuestionId = id
        dragOffsetPx = 0f
        arrangedQuestionIds = displayQuestions.map { it.id }
    }

    fun updateQuestionDrag(delta: Float) {
        val draggedId = draggedQuestionId ?: return
        dragOffsetPx += delta
        val layout = questionListState.layoutInfo
        val draggedItem = layout.visibleItemsInfo.firstOrNull { it.key == draggedId } ?: return
        val center = draggedItem.offset + draggedItem.size / 2f + dragOffsetPx
        val target = layout.visibleItemsInfo.firstOrNull { item ->
            val top = item.offset.toFloat()
            val key = item.key as? Long
            key != null && center >= top && center < top + item.size
        } ?: return
        val targetId = target.key as? Long ?: return
        val order = arrangedQuestionIds ?: displayQuestions.map { it.id }
        val fromIndex = order.indexOf(draggedId)
        val toIndex = order.indexOf(targetId)
        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
            arrangedQuestionIds = moveItem(order, fromIndex, toIndex)
            dragOffsetPx = 0f
        }
    }

    fun finishQuestionDrag() {
        arrangedQuestionIds?.let(viewModel::saveVisibleQuestionOrder)
        draggedQuestionId = null
        dragOffsetPx = 0f
    }


    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshLocalDate()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(filterDue, filterDueRequest) {
        if (filterDue || filterDueRequest > 0) viewModel.setDueOnly(true)
    }
    // 错题本页选完回来时应用筛选
    // 错题本页选完回来时应用筛选。
    //
    // **顺序不能颠倒，且必须先清 source 再设 target。**
    //
    // 原来写成 `setNotebook(id)` 然后 `onNotebookPicked(null)`：
    // 两次都是 state 写入，同一帧内都会触发重组。重组后 effect 因为 key 变成 null
    // 而**重跑一次**，于是 `setNotebook(null)` 把刚设好的筛选又清掉——
    // 用户点「某个错题本」，界面闪一下就变回「全部」，功能实际不可用。
    //
    // 改成先清 source（key 变 null → effect 重跑，此时读到的已是 null，
    // 于是 setNotebook(null) 幂等的），再在**下一个 key 值**上设 target。
    LaunchedEffect(pickedNotebookId) {
        val picked = pickedNotebookId
        if (picked == null) return@LaunchedEffect
        onNotebookPicked(null)
        viewModel.setNotebook(picked)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    // 单开互斥（v0.0.132）：错题卡与对话历史/归档页同一行为——同时只有一张卡展开
    val swipeCoordinator = remember { SwipeRevealCoordinator() }
    val scope = rememberCoroutineScope()
    var showAddSheet by remember { mutableStateOf(false) }
    // 右滑题图 → 全屏看图（v0.0.142）
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    var importing by remember { mutableStateOf(false) }
    var showKeyGate by remember { mutableStateOf(false) }
    var mineruMissing by remember { mutableStateOf(false) }
    var llmMissing by remember { mutableStateOf(false) }
    // AI 学习周报（F2）：错题本搜索栏右侧的入口弹层
    var showWeeklyReport by remember { mutableStateOf(false) }
    // 思维导图（F4）：同上，轻量弹层入口
    var showMindmap by remember { mutableStateOf(false) }
    // 错题统计（F5）：分布图表弹层
    var showStats by remember { mutableStateOf(false) }

    // 相册入口直接拉起系统选择器：原先中间还夹一个「从相册选择」页，
    // 整页只有一个按钮，等于多点一次屏幕才能到真正的选择器，已删除。
    //
    // 用**单选**而不是多选：现在每张图都要走完整的裁剪流程
    // （框选 → 旋转 → 涂鸦遮蔽 → 提交），多选会让用户在同一个页面里反复进出。
    // 需要一次处理很多张的场景走「导入 PDF」，那边有分页预览。
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val files = container.imageImporter.importUris(listOf(uri))
            importing = false
            if (files.isEmpty()) {
                snackbarHostState.showSnackbar(context.getString(R.string.gallery_copy_failed, ""))
            } else {
                // 交给裁剪页：框选 → 旋转 → 涂鸦遮蔽 → 提交，与拍照完全同一条路
                onCropImage(files.first().absolutePath)
            }
        }
    }

    fun pickFromGallery() {
        scope.launch {
            val snapshot = container.settingsStore.snapshotNow()
            if (!snapshot.mineruConfigured || !snapshot.llmConfigured) {
                mineruMissing = !snapshot.mineruConfigured
                llmMissing = !snapshot.llmConfigured
                showKeyGate = true
                return@launch
            }
            galleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            // v0.0.121：两颗都是等长胶囊（用户拍板「保证长度一致」）。
            //
            // v0.0.118 只统一了高度与配色，上面那颗仍是圆钮——图标悬在
            // 胶囊旁边还是像两个组件。现在两颗同为 ExtendedFloatingActionButton，
            // 同底色、同内容色、同结构（图标 + 三个字），宽度天然一致，
            // 右缘对齐后是一对真正的双胞胎。
            Column(horizontalAlignment = Alignment.End) {
                ExtendedFloatingActionButton(
                    onClick = onNewChat,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            contentDescription = stringResource(R.string.home_open_chat)
                        )
                    },
                    text = { Text(stringResource(R.string.home_open_chat)) },
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                ExtendedFloatingActionButton(
                    onClick = { showAddSheet = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.home_add_by_photo)) }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // 搜索栏 + 错题本专属操作（打印/周报/导图/统计）同一行：
            // 这些入口只服务错题本，从顶栏挪到搜索栏右侧，顶栏四个 Tab
            // 从此位置恒定，翻页不抖动；搜索栏让出部分宽度给它们。
            // 对话与拍题不再放这里：右下角一对胶囊就是它们的唯一快捷入口。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OutlinedTextField(
                    value = state.keyword,
                    onValueChange = viewModel::setKeyword,
                    modifier = Modifier.weight(1f),
                    // v0.0.84 曾以「提示字会被裁掉」为由去掉；v0.0.98 用户点名要回
                    // 「搜索」二字。这次只放两个字，远比原来那句短，不存在裁切问题。
                    placeholder = {
                        // maxLines=1 + softWrap=false 是硬约束：不加的话
                        // 「搜索」两个字会被挤成上下两行（竖排），看着像渲染坏了。
                        Text(
                            text = stringResource(R.string.home_search_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (state.keyword.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.setKeyword("") },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.home_search_clear),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
                IconButton(onClick = onOpenPrint, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = stringResource(R.string.home_action_print),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                // AI 学习周报：诊断 + 建议，与打印/对话并列的轻量入口
                IconButton(onClick = { showWeeklyReport = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Insights,
                        contentDescription = stringResource(R.string.weekly_report_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                // 思维导图（F4）：知识体系 + 薄弱分支标红
                IconButton(onClick = { showMindmap = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = stringResource(R.string.mindmap_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                // 错题统计（F5）：错因/难度/学科/知识点分布
                IconButton(onClick = { showStats = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.BarChart,
                        contentDescription = stringResource(R.string.stats_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // 筛选条永远钉在搜索栏正下方（v0.0.121「筛选最高级」）：
            // 复习横幅与倒计时卡都降级成了列表内容，题一多往下翻就被盖住，
            // 只有筛选是用户随时可能要动的，必须一步够到、绝不被卷走。
            FilterRow(
                state = state,
                onStatusChange = viewModel::setStatus,
                onDueChange = viewModel::setDueOnly,
                onSubjectChange = viewModel::setSubject,
                onNotebookChange = viewModel::setNotebook,
                onOpenNotebooks = onOpenNotebooks
            )
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                else -> LazyColumn(
                    state = questionListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 当天日期在**整个列表范围内**算一次即可。
                    // 注意这里不能用 remember：LazyColumn 的 content 是
                    // LazyListScope，不是 @Composable 上下文。
                    val todayEpochDay = java.time.LocalDate.now().toEpochDay()

                    // T1 复习横幅：今天有到期题才出现（N=0 不显示，不做弹窗）。
                    // 样式与列表卡片同族，点击进独立复习页。
                    // v0.0.121 起横幅是列表的第一个条目（「复习第二」）：
                    // 随题目滚动被盖住，把屏幕留给筛选条。
                    if (state.dueCount > 0) {
                        item(key = "review-banner", contentType = "banner") {
                            Surface(
                                onClick = onStartReview,
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.review_banner_due, state.dueCount),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }

                    // 考前倒计时（F3）：未设日期时是设置入口。
                    // v0.0.121 起是列表条目（「倒计时第三」），随题目滚动被盖住；
                    // 横向留白交给 contentPadding，和题目卡对齐。
                    item(key = "exam-countdown", contentType = "banner") {
                        ExamCountdownCard(container = container)
                    }

                    if (state.questions.isEmpty() && state.totalCount == 0) {
                        item(key = "empty-add", contentType = "empty") {
                            Box(Modifier.fillParentMaxSize()) {
                                EmptyStateWithActions(
                                    onAddByPhoto = onAddByPhoto,
                                    onAddFromGallery = { pickFromGallery() },
                                    onImportPdf = onImportPdf,
                                    onAddManual = onAddManual
                                )
                            }
                        }
                    } else if (state.questions.isEmpty()) {
                        item(key = "empty-filter", contentType = "empty") {
                            Box(Modifier.fillParentMaxSize()) {
                                EmptyState(
                                    title = stringResource(R.string.home_empty_title),
                                    subtitle = stringResource(R.string.home_empty_filter_hint)
                                )
                            }
                        }
                    }

                    items(
                        items = displayQuestions,
                        key = { it.id },
                        contentType = { "question" }
                    ) { question ->
                        // 原来这里对每道题都 firstOrNull 线性扫一遍 subjects。
                        // 列表几十项、每次重组都扫，改成外面建好的 Map 直接查。
                        val subjectName = subjectNames[question.subjectId]
                        SwipeableQuestionCard(
                            // animateItem：拖动换位时其他卡片平滑滑到新位置，
                            // 没有它就是「瞬移」，排序体感很生硬
                            modifier = Modifier.animateItem(),
                            coordinator = swipeCoordinator,
                            myId = question.id,
                            question = question,
                            subjectName = subjectName,
                            dueToday = state.dueCount > 0 && isDue(question, todayEpochDay),
                            dragging = draggedQuestionId == question.id,
                            onReorderStart = { beginQuestionDrag(question.id) },
                            onReorder = ::updateQuestionDrag,
                            onReorderEnd = ::finishQuestionDrag,
                            onClick = { onOpenQuestion(question.id) },
                            onPreviewImage = { path -> previewImagePath = path },
                            onPin = { viewModel.setPinned(question.id, !question.pinned) },
                            onDelete = {
                                viewModel.archive(question)
                                // 文案在组合期取好：showSnackbar 的回调不是组合上下文
                                val doneText = context.getString(R.string.archive_done)
                                val undoText = context.getString(R.string.action_undo)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = doneText,
                                        actionLabel = undoText,
                                        duration = androidx.compose.material3.SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.unarchive(question)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 全屏看图：与对话/详情同一套（双指缩放、拖动、长按存图、点一下退出）
    previewImagePath?.let { path ->
        com.mistakebook.ui.common.FullScreenImageDialog(
            imagePath = path,
            onDismiss = { previewImagePath = null }
        )
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = stringResource(R.string.home_add_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                AddSheetItem(
                    icon = Icons.Default.PhotoCamera,
                    text = stringResource(R.string.home_add_by_photo),
                    onClick = {
                        showAddSheet = false
                        onAddByPhoto()
                    }
                )
                AddSheetItem(
                    icon = Icons.Default.PhotoLibrary,
                    text = stringResource(R.string.home_add_from_gallery),
                    enabled = !importing,
                    onClick = {
                        showAddSheet = false
                        pickFromGallery()
                    }
                )
                AddSheetItem(
                    icon = Icons.Default.PictureAsPdf,
                    text = stringResource(R.string.home_import_pdf),
                    onClick = {
                        showAddSheet = false
                        onImportPdf()
                    }
                )
                // 手动录入：不依赖任何 API，最坏情况下（Key 没配、MinerU 连不上）
                // 这是唯一还能往错题本里加题的路
                AddSheetItem(
                    icon = Icons.Default.Edit,
                    text = stringResource(R.string.home_add_manual),
                    onClick = {
                        showAddSheet = false
                        onAddManual()
                    }
                )
            }
        }
    }

    if (importing) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.gallery_copying))
                }
            }
        }
    }

    if (showKeyGate) {
        ApiKeyRequiredDialog(
            mineruMissing = mineruMissing,
            llmMissing = llmMissing,
            onOpenSettings = onOpenSettings,
            onManualEntry = onAddManual,
            onDismiss = { showKeyGate = false }
        )
    }

    if (showWeeklyReport) {
        WeeklyReportDialog(
            container = container,
            onDismiss = { showWeeklyReport = false }
        )
    }

    if (showMindmap) {
        MindmapDialog(
            container = container,
            onDismiss = { showMindmap = false }
        )
    }

    if (showStats) {
        QuestionStatsDialog(
            container = container,
            onDismiss = { showStats = false }
        )
    }
}

/**
 * 是否到期。
 *
 * `todayEpochDay` **必须由调用方传入**：原来这里直接调 `LocalDate.now()`，
 * 而它在**每个列表项每次重组**时都会跑一遍时区与日历计算——
 * 一屏 8 条、滚动时每帧都算 8 次，纯属浪费。
 */
private fun isDue(question: Question, todayEpochDay: Long): Boolean {
    val next = question.nextReviewAt ?: return false
    return question.status != com.mistakebook.domain.MasteryStatus.MASTERED &&
        next <= todayEpochDay
}

@Composable
private fun EmptyStateWithActions(
    onAddByPhoto: () -> Unit,
    onImportPdf: () -> Unit,
    onAddFromGallery: () -> Unit,
    onAddManual: () -> Unit
) {
    EmptyState(
        title = stringResource(R.string.home_empty_title),
        // Mihon 空态配方：一个低对比字形给视线一个落点，纯文字太飘
        glyph = "◌",
        action = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 主操作等宽撑满（Row + weight）：次级按钮两枚也各占一半，
                // 触控目标一致宽，间距节奏才不会歪
                androidx.compose.material3.Button(
                    onClick = onAddByPhoto,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_add_by_photo))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = onAddFromGallery,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.home_add_from_gallery), maxLines = 1)
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = onImportPdf,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.home_import_pdf), maxLines = 1)
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 空状态里也留着手动录入：新用户没有题，第一个键还可能没配，
                // 这时如果只有「拍照/相册/PDF」三条路，他会以为自己用不了
                androidx.compose.material3.TextButton(
                    onClick = onAddManual,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.home_add_manual))
                }
            }
        }
    )
}

@Composable
private fun AddSheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * 划开删除：滑动只**露出删除按钮**，真删要点一下。
 *
 *
 * 也就是**划一下立即删除**。实测太容易误触：列表滑动时手一抖就删掉一道题，
 * 只能靠撤销救回来。用户反馈「删除太容易误触」。
 *
 * ## 交互
 *
 * 1. 左滑 → 露出深红色色块 + 垃圾桶图标，**卡片停在那里不收回**
 * 2. 再点一下垃圾桶 → 才真删，Snackbar「撤销」保留
 * 3. 点卡片其余部分 → 收回色块，不进详情
 *
 * ## 为什么不再用 SwipeToDismissBox
 *
 * 原来用 SwipeToDismissBox，在 confirmValueChange 里直接调 onDelete()，
 * 也就是**划一下立即删除**。实测太容易误触：列表滑动时手一抖就删掉一道题，
 * 只能靠撤销救回来。
 * ## 为什么第 1 步必须「停住」
 *
 * 如果松手就回弹，用户根本没机会点垃圾桶——手势还在进行中是不能点击的。
 * 所以滑到位就吸附展开，把「删除」变成一次**独立的、看得见的**点击。
 *
 * 安全边界因此从「会不会误划」转移到「会不会误点」：
 * 后者需要一次明确的点击，而不是一次滑动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableQuestionCard(
    modifier: Modifier = Modifier,
    coordinator: SwipeRevealCoordinator,
    myId: Long,
    question: Question,
    subjectName: String?,
    dueToday: Boolean,
    dragging: Boolean,
    onReorderStart: () -> Unit,
    onReorder: (Float) -> Unit,
    onReorderEnd: () -> Unit,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    /** 点右滑露出的题图 → 全屏看图（v0.0.142）。 */
    onPreviewImage: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val revealWidthPx = with(density) { (SwipeDeleteRevealWidth + SwipeActionGap).toPx() }

    // v0.0.142：从 Animatable 换成普通 float 状态。
    //
    // 旧写法每个指针事件都 `scope.launch { offsetX.snapTo(...) }`——
    // 协程调度不保证顺序，慢速微动时多次 snapTo 会乱序落地，
    // 表现就是「拉住滑块慢慢动一直闪」；而且松手读 offsetX.value 可能
    // 读到滞后的旧值，把「从预览态往回拖」的起点算成 0，于是拖多少都
    // 判成「继续展开」——「拉不回来」的真因。
    // 现在手势里**同步**赋值（非挂起调用，受限作用域允许），只有松手
    // 时的吸附动画走协程，且用 animate() 单目标驱动。
    var cardOffset by remember { mutableFloatStateOf(0f) }
    // 右滑露出题目图片的行程（v0.0.134 用户：「不够大，尽可能只留一点点
    // 滑块在外面」）：行程 = 整卡宽 - 24dp 残留边。列表横向 padding 12dp×2，
    // 所以卡宽 = screenWidth - 24dp；再留 24dp 卡身在外面，手指有落点。
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidthDp = config.screenWidthDp.dp
    val imageRevealWidth = (screenWidthDp - 48.dp).coerceAtLeast(220.dp)
    val imageRevealPx = with(density) { imageRevealWidth.toPx() }

    var previewing by remember { mutableStateOf(false) }
    // 「是否已划开」是**离散状态**，只在松手时翻转。
    //
    // 早先写成 `val isRevealed = offsetX.value <= ...`，那是在组合期读 Animatable，
    // 滑动时**每帧都会重组**一次。列表里几十张卡同时在手势里，就是几十次重组/帧。
    // 位移本身走 `Modifier.offset { }`（只跑布局阶段，不重组），这是正确写法。
    var revealed by remember { mutableStateOf(false) }

    // 松手/收回的吸附动画：唯一一处协程驱动，目标单一，不会打架。
    fun settleTo(target: Float) {
        scope.launch {
            androidx.compose.animation.core.animate(
                initialValue = cardOffset,
                targetValue = target,
                animationSpec = SwipeRevealSpring
            ) { value, _ -> cardOffset = value }
        }
    }

    fun close() {
        revealed = false
        previewing = false
        coordinator.revealedId = null
        settleTo(0f)
    }

    // 归档两步确认：4 秒无操作自动回落，不让「确认归档」悬着等人来点
    var confirmingArchive by remember { mutableStateOf(false) }
    LaunchedEffect(confirmingArchive) {
        if (confirmingArchive) {
            delay(4_000)
            confirmingArchive = false
        }
    }

    // 互斥（v0.0.132）：别的卡滑开时，已开的自己（左滑操作或右滑预览）自动收回
    LaunchedEffect(coordinator.revealedId) {
        if ((revealed || previewing) && coordinator.revealedId != myId) {
            revealed = false
            previewing = false
            confirmingArchive = false
            settleTo(0f)
        }
    }

    Box(
        modifier = modifier
            // v0.0.142（用户：「条状右滑后仅在自己原来那个区域显示图片」）：
            // 不再撑高卡片——图就铺在卡片原本的矩形里（右侧只留一条
            // 窄边当拉手），高度变化会触发 LazyColumn 重排，也是手势
            // 打架的来源之一。
            .clip(MaterialTheme.shapes.medium)
            // 底层背景由各操作块自己铺（置顶 primaryContainer、删除 errorContainer），
            // 外层保持透明——两块颜色不同，铺在同一层就分不开了。
    ) {
        // 底层：操作区。
        //
        // **必须限定在露出来的那一条宽度内、并靠右对齐。**
        // v0.0.5 用的是 `fillMaxSize()` + `Alignment.Center`，
        // 图标被放在**整张卡的正中**——而滑开只露出最右边一条，
        // 于是图标压根没露出来，用户看到「一片红、什么都没有」，
        // 自然也没有东西可点。这是 v0.0.5 的实际回归。
        //
        // v0.0.121 圆润化（用户：「蓝色左边缘没有做圆润化处理，
        // 红蓝连接的方式也没有做圆润化处理」）：两层叠画——
        //   下层红块向左多铺一段衔接带；上层蓝块左缘随卡片圆角、
        //   右缘以 [SwipeActionJunctionRadius] 的圆弧压在红色上。
        // 蓝红交界从此是一段圆弧，蓝块左缘与卡片轮廓同一条曲线。
        Box(
            modifier = Modifier
                // v0.0.116：从 fillMaxHeight() 改成 matchParentSize()。
                //
                // 错题卡躺在 LazyColumn 里，纵向约束是无限高，fillMaxHeight()
                // 在这种约束下等于「保持自身内容高度」——色块只有图标加一行字
                // 那么高、上下留白，看着是一小块飘在卡片中间。对话历史的抽屉
                // 用 matchParentSize() 恰好等于卡片高度，色块才饱满；三处
                // 左滑共用同一套观感，这里跟着对齐。
                .matchParentSize()
                // 透明度跟着露出进度走，且只在绘制期读 offsetX（graphicsLayer 的
                // lambda 是 draw 阶段，不重组）：图标像被手指带出来，而不是
                // 一开始就静止贴在底层、被卡片切成一半。
                .graphicsLayer {
                    val progress = swipeRevealProgress(cardOffset, revealWidthPx)
                    alpha = swipeRevealContentAlpha(progress)
                }
        ) {
            // 右滑露出层（v0.0.132 用户：「右滑展示题目，尽可能大的展示，
            // 不允许裁切」）：固定占 imageReveal 宽、贴左缘——Fit 图在这个
            // 完整矩形内显示；铺满整卡宽的话 Fit 居中的图大部分被卡片盖住。
            // v0.0.134（issue #52「做好隔离，不要左滑右滑内容打架」）：
            // alpha 只跟正向位移——左滑（offset<0）时本层全透明。
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(imageRevealWidth)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .graphicsLayer {
                        val progress = if (cardOffset >= 0f) {
                            swipeRevealProgress(cardOffset, imageRevealPx)
                        } else 0f
                        alpha = swipeRevealContentAlpha(progress)
                    }
                    // 点图看大图（v0.0.142 用户：「单击放大这个图片全屏看，
                    // 跟对话图片一样，点一下退出，可以放大」）——复用
                    // FullScreenImageDialog（双指缩放/拖动/长按存图/点一下退出）。
                    // 只在预览态可点：收起时本层被卡片盖住，无需抢触摸。
                    .clickable(enabled = previewing) { onPreviewImage(question.printImagePath) }
            ) {
                if (com.mistakebook.util.CoverFallback.isLikelyImage(question.printImagePath)) {
                    AsyncImage(
                        model = question.printImagePath,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = question.stem,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    )
                }
            }
            // 左滑操作层：完整图形拼贴——置顶在左、归档在右。
            // v0.0.134（issue #52）：alpha 只跟负向位移——此前
            // swipeRevealProgress 取 abs(offset)，右滑看图时操作块会按
            // 正向位移半透明浮现叠在图上（「内容打架」）。offset 在右
            // 半程时本层强制全透明。
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val progress = if (cardOffset <= 0f) {
                            swipeRevealProgress(cardOffset, revealWidthPx)
                        } else 0f
                        alpha = swipeRevealContentAlpha(progress)
                    },
                horizontalArrangement = Arrangement.spacedBy(SwipeActionGap, Alignment.End)
            ) {
                SwipeActionBlock(
                    width = SwipeActionBlockWidth,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = Icons.Default.PushPin,
                    label = stringResource(
                        if (question.pinned) R.string.swipe_unpin_action else R.string.swipe_pin_action
                    ),
                    enabled = revealed,
                    shape = swipeActionTileShape(),
                    onClick = { close(); onPin() }
                )
                SwipeActionBlock(
                    width = SwipeActionBlockWidth,
                    // 两步确认（v0.0.132 用户：「杜绝一次滑动连跳两级直接从
                    // 错题到删除」）：第一击只翻确认态（4 秒无操作自动回落），
                    // 第二击才真归档——与对话历史的两步删除同一模式。
                    color = if (confirmingArchive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.errorContainer,
                    contentColor = if (confirmingArchive) MaterialTheme.colorScheme.onError
                    else MaterialTheme.colorScheme.onErrorContainer,
                    icon = Icons.Default.Delete,
                    label = stringResource(
                        if (confirmingArchive) R.string.swipe_archive_confirm
                        else R.string.archive_action
                    ),
                    enabled = revealed,
                    shape = swipeActionTileShape(),
                    onClick = {
                        if (confirmingArchive) {
                            close()
                            onDelete()
                        } else {
                            confirmingArchive = true
                        }
                    }
                )
            }
        }

        // 上层：卡片，跟随手势位移。
        //
        // 横向手势（v0.0.89 修回）：**横向占优就由卡片认领**，纵向占优才交给
        // 列表滚动。规则只有这一条，没有速度分叉。
        //
        // v0.0.84~0.0.88 曾按「落指到越过 slop 的用时」给门户 Pager 让道
        // （阈值 slop/90ms ≈ 270 px/s）。那条线画在了一个不可能成立的位置：
        // 常人一次明确的左滑删除速度普遍在 500 px/s 以上，于是**每一次左滑都
        // 被判成翻页**，卡片再也不认领——用户连红色色块都没见过，等于
        // 「左滑删除没了」。这比「卡片上翻不了页」严重得多：翻页有 Tab 行，
        // 删除没有第二入口。
        //
        // Compose 的 Main 指针分发是子先于父：卡片一旦消费移动事件，
        // 门户 Pager 的同轴检测器就收不到，自然不会翻页。翻页请从 Tab 行
        // 或卡片以外的空白处起手。
        Box(
            modifier = Modifier
                .offset { IntOffset(cardOffset.roundToInt(), 0) }
                .pointerInput(revealWidthPx) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var accX = 0f
                        var accY = 0f
                        var claimed = false
                        val slop = viewConfiguration.touchSlop
                        // 手势起始位移（v0.0.142）：松手判定必须相对**起点**
                        // 而不是相对 0——预览态 offset 已是满程，用绝对阈值
                        // 的话往回拖大半程仍判「开」，永远拉不回来。
                        val startOffset = cardOffset
                        // 本手势累计到的位移：松手判定用本地累计值，不读
                        // cardOffset（吸附动画可能正在改写它）。
                        var curOffset = cardOffset
                        val rightOpenSlopPx = with(density) { 80.dp.toPx() }

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break

                                // 列表滚动 / 门户 Pager 已接手：立即让位
                                if (event.changes.any { it.isConsumed }) break

                                val change = pressed.first()
                                // **必须用 positionChange()，不能手工算
                                // position - lastX**（v0.0.142 修「拉住滑块
                                // 慢慢动一直闪」与「拖到底也拉不回来」的真因）。
                                //
                                // 本节点自己正被 Modifier.offset 位移，而
                                // change.position 是**节点本地**坐标：卡片每
                                // 往右挪一帧，下一帧读到的本地位移就被扣掉
                                // 同等量，形成反馈回路。实测一次 800px 的手工
                                // 累加只走出 379.8px，且相邻事件正负交替
                                // （+44.8 / −18.6 / +48.6 / −21.8 …）——位移
                                // 来回抖就是「闪烁」，净行程只剩一半就是
                                // 「拉不回来」。
                                // positionChange() 由框架把前后两点放在
                                // **同一帧的变换**下相减，天然不含节点自身位移。
                                val dx = change.positionChange().x
                                val dy = change.positionChange().y
                                accX += dx
                                accY += dy

                                // 首次越过 slop 时定向：横向占优 → 认领为展开删除；
                                // 纵向占优 → 直接退出，让位给 LazyColumn 的滚动。
                                // 让位后不再反悔认领——半路抢回来会把列表的滚动
                                // 拽停，是最难查的一类抖动。
                                if (!claimed && (abs(accX) > slop || abs(accY) > slop)) {
                                    if (shouldClaimCardSwipe(accX, accY)) {
                                        claimed = true
                                        // 起手就收回别的卡（互斥）：登记权留给滑到位的自己
                                        if (coordinator.revealedId != myId) coordinator.revealedId = null
                                    } else {
                                        break
                                    }
                                }

                                if (claimed) {
                                    change.consume()
                                    // snapTo 是挂起函数，awaitEachGesture 的受限作用域
                                    // 不允许直接调（编译错误）——与旧 rememberDraggableState
                                    // 一致，经外层 scope.launch 落盘，帧内顺序由互斥锁保证。
                                    curOffset = (curOffset + dx).coerceIn(-revealWidthPx, imageRevealPx)
                                    // 同步赋值：跟手且不会乱序（旧写法每次 launch 一个
                                    // snapTo，慢速微动时闪烁的来源）
                                    cardOffset = curOffset
                                }
                            }
                        } finally {
                            if (claimed) {
                                // 三态吸附，阈值**相对手势起点**（v0.0.142 修
                                // 「拉不回来」）：
                                // - 从 0 起手：左滑过半程 → 操作块；右滑过 80dp
                                //   （轻推即看图）→ 预览；
                                // - 从预览态起手（startOffset>0）：往回拖过预览
                                //   行程的一半才收，否则保持预览——绝对阈值下
                                //   往回拖多少都判「开」，就是拉不回来的根因；
                                // - 从展开态起手（startOffset<0）：往回拖过操作
                                //   区一半才收，否则保持展开（与左滑原逻辑同构）。
                                val settle = resolveCardSwipeSettle(
                                    startOffset = startOffset,
                                    curOffset = curOffset,
                                    revealWidthPx = revealWidthPx,
                                    imageRevealPx = imageRevealPx,
                                    rightOpenSlopPx = rightOpenSlopPx
                                )
                                revealed = settle.revealActions
                                previewing = settle.revealImage
                                coordinator.revealedId =
                                    if (settle.revealActions || settle.revealImage) myId else null
                                settleTo(settle.target)
                            }
                        }
                    }
                }
        ) {
            QuestionCard(
                question = question,
                subjectName = subjectName,
                dueToday = dueToday,
                dragging = dragging,
                onReorderStart = onReorderStart,
                onReorder = onReorder,
                onReorderEnd = onReorderEnd,
                onClick = {
                    // 预览态点卡片（右侧那条窄拉手就在卡片上）= 收回，
                    // 而不是进详情——「拉不回来」的另一半根因（v0.0.142）。
                    if (revealed || previewing) close() else onClick()
                }
            )
        }
    }
}

@Composable
private fun QuestionCard(
    question: Question,
    subjectName: String?,
    dueToday: Boolean,
    dragging: Boolean,
    onReorderStart: () -> Unit,
    onReorder: (Float) -> Unit,
    onReorderEnd: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        // 卡 = 面浮底一级（surfaceContainerLow）+ 1dp 描边 + 零阴影：
        // 浅色底 #FCFCFC 上白卡靠描边划界，深色近黑上 111 卡同理
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            QuestionThumb(
                imagePath = question.printImagePath,
                contentDescription = stringResource(R.string.edit_original_image),
                // 列表小图保持固定方块 + 居中裁剪，行列整齐
                modifier = Modifier.size(72.dp),
                keepAspect = false,
                // PDF 文本路线的题没有位图（photoPath 是 .md），
                // 兜底成文字封面，列表里不至于是一整列空白格。
                fallbackText = question.stem
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SubjectDot(subjectName = subjectName)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = subjectName ?: "未分类",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (dueToday) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 标题优先（用户可自定义 / 大模型生成），没有标题才回退显示题干摘要
                Text(
                    text = question.displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(42.dp)
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ErrorReasonChip(reason = question.errorReason)
                    Spacer(Modifier.width(8.dp))
                    DifficultyStars(difficulty = question.difficulty)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = Format.relativeDay(question.nextReviewAt),
                        style = MaterialTheme.typography.labelSmall,
                        // outline 是描边槽不是文字槽： auxiliary 文字走 onSurfaceVariant
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            ReorderDragHandle(
                dragging = dragging,
                onDragStart = onReorderStart,
                onDrag = onReorder,
                onDragEnd = onReorderEnd
            )
        }
    }
}

/**
 * 三个筛选下拉：学科 / 掌握程度 / 错题本。
 *
 * 需求要求「做成三个可以展开的菜单」，所以这里不再混用 FilterChip——
 * 三个维度统一成同一种按钮外观，一眼看出是同一族控件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(
    state: HomeUiState,
    onStatusChange: (MasteryStatus?) -> Unit,
    onDueChange: (Boolean) -> Unit,
    onSubjectChange: (Long?) -> Unit,
    onNotebookChange: (Long?) -> Unit,
    onOpenNotebooks: () -> Unit
) {
    FilterMenuRow {
        SubjectFilterMenu(
            subjects = state.subjects,
            selectedId = state.subjectId,
            count = state.filteredCount.takeIf { state.subjectId != null },
            onSelect = onSubjectChange,
            modifier = Modifier.weight(1f)
        )
        MasteryFilterMenu(
            status = state.status,
            dueOnly = state.dueOnly,
            count = state.filteredCount.takeIf { state.status != null || state.dueOnly },
            onSelect = { status, due ->
                onStatusChange(status)
                if (due != state.dueOnly) onDueChange(due)
            },
            modifier = Modifier.weight(1f)
        )
        NotebookFilterMenu(
            notebooks = state.notebooks,
            selectedId = state.notebookId,
            count = state.filteredCount.takeIf { state.notebookId != null },
            onSelect = onNotebookChange,
            onManage = onOpenNotebooks,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 划开删除时露出的宽度：错题卡只有一块操作区，就是公共块宽。
 *
 * 要放得下垃圾桶图标（24dp）+ 一行小字 + 左右留白，88dp 是个舒服的值：
 * 拇指能点中，又不至于把整张卡都推走（推太多用户会失去「这是同一张卡」的感觉）。
 */
private val SwipeDeleteRevealWidth = SwipeActionBlockWidth * 2

/** 右滑露出题目图片的宽度（v0.0.132 用户：「尽可能大的展示，不允许裁切」）。 */
private val SwipeImageRevealWidth = 260.dp

/**
 * 横向手势定向判定（v0.0.89 重写；单测见 HomeScreenSwipeClaimTest）。
 *
 * 输入是手指从落指到「首次越过触摸 slop」那一刻的累计位移：
 * **只看方向，不看速度**。横向占优 → 卡片认领为「展开删除」；
 * 纵向占优 → 交给列表滚动。竖横相等时算纵向（取等号让给滚动）。
 *
 * 为什么去掉 v0.0.84~0.0.88 的速度分叉：那条规则的阈值是 slop/90ms
 * ≈ 270 px/s，低于常人一次明确左滑的速度，于是删除永远划不出来。
 * 翻页与删除在同一根轴上抢同一根手指，只能有一个赢家——而删除没有
 * 第二入口（翻页有 Tab 行），所以赢的必须是删除。
 */
internal fun shouldClaimCardSwipe(accX: Float, accY: Float): Boolean {
    return abs(accX) > abs(accY)
}

/**
 * 松手后的落点判定（纯函数，单测见 HomeScreenSwipeClaimTest）。
 *
 * 阈值**相对手势起点**，这是「拉不回来」的正解：预览态下起点已是满程
 * [imageRevealPx]，若拿绝对阈值判「curOffset 还大于半程」，往回拖多少
 * 都算「继续展开」——手指松开就弹回去。
 *
 * - 左滑过半程 → 操作块（-revealWidthPx）；
 * - 从 0 起手右滑过 80dp → 看题图（轻推即预览）；
 * - 从预览态起手（startOffset > 0）→ 往回拖过预览行程一半才收。
 *
 * [revealActions] 与 [revealImage] 互斥：同一个 offset 不可能既负又
 * 大于 80dp，所以不会出现两层同时点亮。
 */
internal data class CardSwipeSettle(
    val revealActions: Boolean,
    val revealImage: Boolean,
    val target: Float
)

internal fun resolveCardSwipeSettle(
    startOffset: Float,
    curOffset: Float,
    revealWidthPx: Float,
    imageRevealPx: Float,
    rightOpenSlopPx: Float
): CardSwipeSettle {
    val revealActions = curOffset < -revealWidthPx * 0.5f
    val revealImage = when {
        startOffset > 0f -> curOffset > imageRevealPx * 0.5f
        else -> curOffset > rightOpenSlopPx
    }
    val target = when {
        revealActions -> -revealWidthPx
        revealImage -> imageRevealPx
        else -> 0f
    }
    return CardSwipeSettle(revealActions, revealImage, target)
}

