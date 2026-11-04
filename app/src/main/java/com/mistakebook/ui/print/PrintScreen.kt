package com.mistakebook.ui.print

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.di.AppContainer
import com.mistakebook.ui.common.EmptyState
import com.mistakebook.ui.common.containerViewModel
import com.mistakebook.ui.common.moveItem
import com.mistakebook.ui.common.orderByIds

/**
 * 打印页（PRD 7.7）：设置 → 筛选 → 逐题勾选 → 生成。
 *
 * ## 整页一条长滚动（v0.0.103 用户要求）
 *
 * 用户原话：「这个设置和上面的不用全部固定在页面内，让整个页面是长条滑动的，
 * 方便选择靠下的题目」。
 *
 * 之前是 Column 里依次摆「筛选 + 设置 + 题目列表」，而题目列表自己是个
 * LazyColumn——结果设置与筛选**永远钉在顶部**，题目列表只分到剩下的高度，
 * 想勾一道靠下的题得先滚过一屏设置。
 *
 * 现在整页只有**一个** LazyColumn：设置在最上、筛选其次、然后是逐题勾选，
 * 三块一起滚。拖拽排序照旧（每行右侧的手柄）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: PrintViewModel = containerViewModel(container) { PrintViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val formatName = stringResource(state.format.shortLabelRes)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.print_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        },
        bottomBar = {
            PrintBottomBar(
                generating = state.generating,
                selectedCount = state.selected.size,
                formatName = formatName,
                onGenerate = viewModel::generate
            )
        }
    ) { padding ->
        // 拖拽排序的状态机：它要看 LazyColumn 的布局信息，
        // 所以必须和列表住在同一个作用域里（塞进子组件就拿不到 layoutInfo）。
        val listState = rememberLazyListState()
        var arrangedQuestionIds by remember { mutableStateOf<List<Long>?>(null) }
        var draggedQuestionId by remember { mutableStateOf<Long?>(null) }
        var dragOffsetPx by remember { mutableFloatStateOf(0f) }

        val displayQuestions = remember(state.questions, arrangedQuestionIds) {
            arrangedQuestionIds?.let { orderByIds(state.questions, it) { question -> question.id } }
                ?: state.questions
        }

        fun beginQuestionDrag(id: Long) {
            draggedQuestionId = id
            dragOffsetPx = 0f
            arrangedQuestionIds = displayQuestions.map { it.id }
        }

        fun updateQuestionDrag(delta: Float) {
            val draggedId = draggedQuestionId ?: return
            dragOffsetPx += delta
            val layout = listState.layoutInfo
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
            arrangedQuestionIds?.let(viewModel::setQuestionOrder)
            draggedQuestionId = null
            dragOffsetPx = 0f
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ① 设置（导出格式 / 字号 / 字体 / 各种开关）
            item(key = "options") {
                PrintOptionsPanel(
                    includeImage = state.includeImage,
                    showAnswer = state.showAnswer,
                    blankRedo = state.blankRedo,
                    blankHeight = state.blankHeight,
                    format = state.format,
                    onIncludeImage = viewModel::setIncludeImage,
                    onShowAnswer = viewModel::setShowAnswer,
                    onBlankRedo = viewModel::setBlankRedo,
                    onBlankHeight = viewModel::setBlankHeight,
                    onFormatChange = viewModel::setFormat,
                    fontScale = state.fontScale,
                    fontFamily = state.fontFamily,
                    onFontScaleChange = viewModel::setFontScale,
                    onFontFamilyChange = viewModel::setFontFamily
                )
            }

            // ② 按标签筛选（学科 / 掌握状态）+ 全选 / 反选
            item(key = "filters") {
                PrintFilters(
                    state = state,
                    onSubjectChange = viewModel::setSubject,
                    onStatusChange = viewModel::setStatus,
                    onKeywordChange = viewModel::setKeyword,
                    onSetAll = { checked ->
                        val ids = state.questions.map { it.id }
                        if (checked) viewModel.selectAll(ids) else viewModel.deselectAll(ids)
                    },
                    onInvert = { viewModel.invertSelection(state.questions.map { it.id }) }
                )
            }

            // ③ 逐题勾选
            if (state.questions.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = stringResource(R.string.print_empty),
                        subtitle = ""
                    )
                }
            } else {
                itemsIndexed(displayQuestions, key = { _, item -> item.id }) { index, question ->
                    PrintRow(
                        question = question,
                        subjectName = state.subjectNames[question.subjectId],
                        index = index + 1,
                        checked = question.id in state.selected,
                        dragging = draggedQuestionId == question.id,
                        onToggle = { viewModel.toggleSelect(question.id) },
                        onReorderStart = { beginQuestionDrag(question.id) },
                        onReorder = ::updateQuestionDrag,
                        onReorderEnd = ::finishQuestionDrag
                    )
                }
            }
        }
    }

    state.result?.let { output ->
        PrintResultDialog(
            result = output,
            formatName = formatName,
            skipped = state.skipped,
            onDismiss = viewModel::consumeResult
        )
    }

    state.error?.let { errorMessage ->
        PrintErrorDialog(
            error = errorMessage,
            onDismiss = viewModel::consumeError
        )
    }
}
