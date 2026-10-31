package com.mistakebook.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BarChart
import kotlin.math.abs
import kotlin.math.roundToInt
import com.mistakebook.ui.theme.DeleteReveal
import androidx.compose.material.icons.Icons
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
import com.mistakebook.ui.theme.Danger
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
    /** AI 对话：进历史会话列表（规格锁定只放顶栏，不加底部按钮）。 */
    onOpenChatList: () -> Unit = {},
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
    val scope = rememberCoroutineScope()
    var showAddSheet by remember { mutableStateOf(false) }
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
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.home_add_by_photo)) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // 搜索栏 + 错题本专属操作（打印/对话列表）同一行：
            // 这两个入口只服务错题本，从顶栏挪到搜索栏右侧，顶栏四个 Tab
            // 从此位置恒定，翻页不抖动；搜索栏让出部分宽度给它们。
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
                    // 不放灰色提示字（v0.0.84 用户反馈）：「搜题干、标题、答案、解析」
                    // 在让出右侧三个入口按钮后的剩余宽度里显示不全，被裁掉一截
                    // 反而影响观感；搜索图标本身已足够表达语义。
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
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
                IconButton(onClick = onOpenPrint, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = stringResource(R.string.home_action_print),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onOpenChatList, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.Forum,
                        contentDescription = stringResource(R.string.chat_list_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                // AI 学习周报：诊断 + 建议，与打印/对话并列的轻量入口
                IconButton(onClick = { showWeeklyReport = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.Insights,
                        contentDescription = stringResource(R.string.weekly_report_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                // 思维导图（F4）：知识体系 + 薄弱分支标红
                IconButton(onClick = { showMindmap = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = stringResource(R.string.mindmap_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                // 错题统计（F5）：错因/难度/学科/知识点分布
                IconButton(onClick = { showStats = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.BarChart,
                        contentDescription = stringResource(R.string.stats_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // 考前倒计时（F3）：搜索栏与筛选行之间的常驻卡片，未设日期时是设置入口
            ExamCountdownCard(container = container)

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

                state.questions.isEmpty() && state.totalCount == 0 -> EmptyStateWithActions(
                onAddByPhoto = onAddByPhoto,
                onAddFromGallery = { pickFromGallery() },
                onImportPdf = onImportPdf,
                onAddManual = onAddManual
                )

                state.questions.isEmpty() -> EmptyState(
                    title = stringResource(R.string.home_empty_title),
                    subtitle = "换个筛选条件试试"
                )

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
                    if (state.dueCount > 0) {
                        item(key = "review-banner", contentType = "banner") {
                            Surface(
                                onClick = onStartReview,
                                shape = RoundedCornerShape(12.dp),
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
                            question = question,
                            subjectName = subjectName,
                            dueToday = state.dueCount > 0 && isDue(question, todayEpochDay),
                            dragging = draggedQuestionId == question.id,
                            onReorderStart = { beginQuestionDrag(question.id) },
                            onReorder = ::updateQuestionDrag,
                            onReorderEnd = ::finishQuestionDrag,
                            onClick = { onOpenQuestion(question.id) },
                            onDelete = {
                                viewModel.delete(question)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "已删除「${question.displayTitle}」",
                                        actionLabel = "撤销",
                                        duration = androidx.compose.material3.SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.undoDelete(question)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
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
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Button(onClick = onAddByPhoto) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_add_by_photo))
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    androidx.compose.material3.TextButton(onClick = onAddFromGallery) {
                        Text(stringResource(R.string.home_add_from_gallery))
                    }
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.TextButton(onClick = onImportPdf) {
                        Text(stringResource(R.string.home_import_pdf))
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 空状态里也留着手动录入：新用户没有题，第一个键还可能没配，
                // 这时如果只有「拍照/相册/PDF」三条路，他会以为自己用不了
                androidx.compose.material3.TextButton(onClick = onAddManual) {
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
    question: Question,
    subjectName: String?,
    dueToday: Boolean,
    dragging: Boolean,
    onReorderStart: () -> Unit,
    onReorder: (Float) -> Unit,
    onReorderEnd: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val revealWidthPx = with(density) { SwipeDeleteRevealWidth.toPx() }

    val offsetX = remember { Animatable(0f) }
    // 「是否已划开」是**离散状态**，只在松手时翻转。
    //
    // 早先写成 `val isRevealed = offsetX.value <= ...`，那是在组合期读 Animatable，
    // 滑动时**每帧都会重组**一次。列表里几十张卡同时在手势里，就是几十次重组/帧。
    // 位移本身走 `Modifier.offset { }`（只跑布局阶段，不重组），这是正确写法。
    var revealed by remember { mutableStateOf(false) }

    fun close() {
        revealed = false
        scope.launch { offsetX.animateTo(0f) }
    }

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(DeleteReveal)
    ) {
        // 底层：删除区。
        //
        // **必须限定在露出来的那一条宽度内、并靠右对齐。**
        // v0.0.5 用的是 `fillMaxSize()` + `Alignment.Center`，
        // 图标被放在**整张卡的正中**——而滑开只露出最右边 88dp，
        // 于是图标压根没露出来，用户看到「一片红、什么都没有」，
        // 自然也没有东西可点。这是 v0.0.5 的实际回归。
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(SwipeDeleteRevealWidth)
                .align(Alignment.CenterEnd)
                .clickable(
                    // 滑开前点不到这里（被卡片挡住），但显式禁用更保险：
                    // 万一将来层级调整，也不会变成「随手一点就删」。
                    enabled = revealed,
                    onClick = {
                        close()
                        onDelete()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.swipe_delete_action),
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
                Text(
                    text = stringResource(R.string.swipe_delete_hint),
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }

        // 上层：卡片，跟随手势位移。
        //
        // 横向手势 v0.0.84 重写（修「左右滑动卡在一半」）：
        // Compose 的 Main 指针分发是**子先于父**，早先 `Modifier.draggable` 在越过
        // 触发阈值那一刻就消费事件——手指落在卡片上的翻页手势全被卡片吃掉，
        // 页面纹丝不动、卡片却被拖出一截卡在那（松手才回弹）。
        // 现在按「越过阈值用时」让道：
        // - 快速甩动（< SWIPE_FLING_YIELD_MS）：翻页意图，不消费不认领——
        //   门户 Pager 的同轴检测器在**同一事件稍后**处理，自然接手翻页；
        // - 慢速拖拽：认领为「展开删除」，消费后续移动；
        // - 纵向占优：交给列表滚动。
        // 认领过一次就不再反悔（yielded 锁存），避免 Pager 没接手时中途又抢回。
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(revealWidthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startTime = down.uptimeMillis
                        var lastX = down.position.x
                        var lastY = down.position.y
                        var accX = 0f
                        var accY = 0f
                        var claimed = false
                        var yielded = false
                        val slop = viewConfiguration.touchSlop

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break

                                // Pager 翻页 / 列表滚动已接手：立即让位
                                if (event.changes.any { it.isConsumed }) break

                                val change = pressed.first()
                                val dx = change.position.x - lastX
                                val dy = change.position.y - lastY
                                lastX = change.position.x
                                lastY = change.position.y
                                accX += dx
                                accY += dy

                                if (!claimed && !yielded &&
                                    (abs(accX) > slop || abs(accY) > slop)
                                ) {
                                    val elapsedMs = change.uptimeMillis - startTime
                                    when {
                                        abs(accY) >= abs(accX) -> yielded = true // 纵向：给列表
                                        elapsedMs < SWIPE_FLING_YIELD_MS -> yielded = true // 甩动：给 Pager
                                        else -> claimed = true // 慢速横向：展开删除
                                    }
                                    if (!claimed) continue
                                }

                                if (claimed) {
                                    change.consume()
                                    offsetX.snapTo(
                                        (offsetX.value + dx).coerceIn(-revealWidthPx, 0f)
                                    )
                                }
                            }
                        } finally {
                            if (claimed) {
                                val opened = offsetX.value < -revealWidthPx * 0.35f
                                revealed = opened
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
                    if (revealed) close() else onClick()
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
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                                .background(Danger)
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
                        color = MaterialTheme.colorScheme.outline
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
 * 划开删除时露出的宽度。
 *
 * 要放得下垃圾桶图标（24dp）+ 一行小字 + 左右留白，88dp 是个舒服的值：
 * 拇指能点中，又不至于把整张卡都推走（推太多用户会失去「这是同一张卡」的感觉）。
 */
private val SwipeDeleteRevealWidth = 88.dp

/**
 * 横向手势让道阈值（毫秒）：从落指到越过触摸 slop 的用时。
 * 翻页是快速甩动（几十 ms 就越过 8dp），展开删除是慢速拖拽（通常 150ms+）。
 * 用时短于该值判为翻页意图，卡片让道给门户 Pager。
 */
private const val SWIPE_FLING_YIELD_MS = 90L
