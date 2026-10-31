package com.mistakebook.ui.print

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.data.prefs.SettingsSnapshot
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.ErrorReason
import com.mistakebook.domain.MasteryStatus
import com.mistakebook.print.ExportCardBuilder
import com.mistakebook.print.ExportFormat
import com.mistakebook.print.ExportOptions
import com.mistakebook.print.ExportPublisher
import com.mistakebook.print.ExportResult
import com.mistakebook.print.HtmlExporter
import com.mistakebook.ui.common.mergeVisibleOrder
import com.mistakebook.ui.common.orderByIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.mistakebook.R

data class PrintUiState(
    val questions: List<Question> = emptyList(),
    val subjects: List<Subject> = emptyList(),
    val subjectId: Long? = null,
    val status: MasteryStatus? = null,
    val keyword: String = "",
    val selected: Set<Long> = emptySet(),
    val includeImage: Boolean = false,
    val showAnswer: Boolean = false,
    val blankRedo: Boolean = true,
    val blankHeight: Int = 100,
    /** 选中的导出格式，默认直接输出 A4 PDF。 */
    val format: ExportFormat = ExportFormat.PDF,
    val generating: Boolean = false,
    val result: ExportPublisher.Output? = null,
    val skipped: List<String> = emptyList(),
    val error: String? = null
) {
    val allSelected: Boolean
        get() = questions.isNotEmpty() && questions.all { it.id in selected }

    val subjectNames: Map<Long, String>
        get() = subjects.associate { it.id to it.name }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
/** combine 四个流时用的载荷。Kotlin 只预定义到 Tuple5，这里自己定义更清楚。 */
private data class ExportOutcome(
    val output: ExportPublisher.Output?,
    val skipped: List<String>,
    val error: String?,
    val format: ExportFormat
)

// `debounce` 与 `flatMapLatest` 都还是实验 API。
// 原来的 @OptIn 加在了 PrintUiState 上，ViewModel 类本身没加，
// 于是编译器在类的第 89 行就报「需要 opt-in」——两处都留着容易以为已处理。
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class PrintViewModel(private val container: AppContainer) : ViewModel() {

    private val subjectId = MutableStateFlow<Long?>(null)
    private val status = MutableStateFlow<MasteryStatus?>(null)
    private val keyword = MutableStateFlow("")

    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    private val selectedOrder = MutableStateFlow<List<Long>>(emptyList())
    private val questionOrder = MutableStateFlow<List<Long>>(emptyList())
    private val includeImage = MutableStateFlow(false)
    private val showAnswer = MutableStateFlow(false)
    private val blankRedo = MutableStateFlow(true)
    private val blankHeight = MutableStateFlow(100)
    private val format = MutableStateFlow(ExportFormat.PDF)
    private val generating = MutableStateFlow(false)
    private val result = MutableStateFlow<ExportPublisher.Output?>(null)
    private val skipped = MutableStateFlow<List<String>>(emptyList())
    private val error = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            val snapshot: SettingsSnapshot = container.settingsStore.snapshotNow()
            includeImage.value = snapshot.printIncludeImage
            showAnswer.value = snapshot.printShowAnswer
            blankRedo.value = snapshot.printBlankRedo
            blankHeight.value = snapshot.printBlankHeightPt
        }
    }

    private val questions: StateFlow<List<Question>> =
        combine(subjectId, status, keyword.debounce(300)) { s, st, kw -> Triple(s, st, kw) }
            .flatMapLatest { (s, st, kw) ->
                // v0.0.59 起列表查询就是全量（不再有分页参数）。
                container.questionRepository.observeFiltered(
                    filter = com.mistakebook.data.repos.QuestionRepository.Filter(
                        subjectId = s,
                        status = st,
                        keyword = kw
                    )
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val orderedQuestions: StateFlow<List<Question>> = combine(questions, questionOrder) { list, order ->
        orderByIds(list, order) { it.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<PrintUiState> = combine(
        orderedQuestions,
        combine(container.subjectRepository.observeAll(), selected) { subjects, sel -> subjects to sel },
        combine(includeImage, showAnswer) { image, answer -> image to answer },
        combine(blankRedo, blankHeight, generating) { redo, height, working ->
            Triple(redo, height, working)
        },
        combine(result, skipped, error, format) { output, skippedList, errorText, chosen ->
            ExportOutcome(output, skippedList, errorText, chosen)
        }
    ) { list, subjectsAndSelection, imageAndAnswer, redoAndMore, outcome ->
        PrintUiState(
            questions = list,
            subjects = subjectsAndSelection.first,
            selected = subjectsAndSelection.second,
            includeImage = imageAndAnswer.first,
            showAnswer = imageAndAnswer.second,
            blankRedo = redoAndMore.first,
            blankHeight = redoAndMore.second,
            generating = redoAndMore.third,
            result = outcome.output,
            skipped = outcome.skipped,
            error = outcome.error,
            format = outcome.format
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrintUiState())

    fun setSubject(id: Long?) {
        subjectId.value = id
    }

    fun setStatus(value: MasteryStatus?) {
        status.value = value
    }

    fun setKeyword(value: String) {
        keyword.value = value
    }

    fun toggleSelect(id: Long) {
        val current = selected.value
        if (id in current) {
            selected.value = current - id
            selectedOrder.value = selectedOrder.value.filterNot { it == id }
        } else {
            selected.value = current + id
            selectedOrder.value = selectedOrder.value + id
        }
    }

    fun selectAll(ids: List<Long>) {
        selected.value = selected.value + ids
        selectedOrder.value = (selectedOrder.value + ids).distinct()
    }

    fun deselectAll(ids: List<Long>) {
        val deselected = ids.toSet()
        selected.value = selected.value - deselected
        selectedOrder.value = selectedOrder.value.filterNot { it in deselected }
    }

    fun invertSelection(ids: List<Long>) {
        val current = selected.value
        val visible = ids.toSet()
        val added = ids.filterNot { it in current }
        val next = (current.filterNot { it in visible } + added).toSet()
        selected.value = next
        selectedOrder.value = selectedOrder.value.filter { it in next } + added
    }

    fun setQuestionOrder(ids: List<Long>) {
        questionOrder.value = mergeVisibleOrder(questionOrder.value, ids)
    }


    fun setIncludeImage(value: Boolean) {
        includeImage.value = value
    }

    fun setShowAnswer(value: Boolean) {
        showAnswer.value = value
    }

    fun setBlankRedo(value: Boolean) {
        blankRedo.value = value
    }

    fun setBlankHeight(value: Int) {
        blankHeight.value = value
    }

    /**
     * 按选中的格式导出。
     *
     * ## 为什么先建 [ExportDoc] 再分发
     *
     * 三种格式的版式各不相同，但「展示哪些内容」必须完全一致——
     * 否则用户会发现「导 PDF 有答案、导 DOCX 没有」，而这种不一致不会报错、
     * 只会让人怀疑自己选错了题。所以内容只建一份，三种格式共用。
     */
    fun generate() {
        val state = uiState.value
        if (state.selected.isEmpty() || state.generating) return
        val chosen = state.format
        generating.value = true
        error.value = null
        result.value = null
        viewModelScope.launch {
            val exportOrder = (questionOrder.value + state.questions.map { it.id } + selectedOrder.value).distinct()
            val questions = exportOrder.filter { it in state.selected }.mapNotNull { id ->
                container.questionRepository.findById(id)
            }
            if (questions.isEmpty()) {
                generating.value = false
                error.value = "选中的题目已不存在"
                return@launch
            }

            val options = ExportOptions(
                includeImage = state.includeImage,
                showAnswer = state.showAnswer,
                blankRedoMode = state.blankRedo,
                blankHeightPt = state.blankHeight
            )
            val subjectNames = state.subjectNames
            val doc = ExportCardBuilder(subjectNames).buildDoc(
                questions = questions,
                options = options,
                title = container.appContext.getString(R.string.print_doc_title),
                generatedAt = System.currentTimeMillis()
            )

            val target = container.exportPublisher.createTempFile(chosen)
            // 生成与「发布到导出目录」**都**要兜底（v0.0.73 修复）：
            // 旧实现只包了生成一步——publish（MediaStore 插入/拷贝）抛异常时
            // 直接崩溃；两个阶段失败留下的半成品文件也不清理，导出目录慢性堆积。
            val run = runCatching {
                when (chosen) {
                    ExportFormat.PDF -> com.mistakebook.print.PdfExporter(
                        container.mathRenderer,
                        container.appContext.getString(R.string.print_redo_tag),
                        container.appContext.getString(R.string.print_page_footer_tpl)
                    ).export(doc, target)
                    ExportFormat.HTML -> HtmlExporter(container.mathRenderer).export(doc, target)
                }
            }.onFailure { target.delete() }
            generating.value = false
            run.fold(
                onSuccess = { exported ->
                    runCatching { container.exportPublisher.publish(exported.file, chosen) }
                        .onSuccess { output ->
                            result.value = output
                            skipped.value = exported.skipped
                        }
                        .onFailure { throwable ->
                            exported.file.delete()
                            error.value = throwable.message ?: "保存到导出目录失败"
                        }
                },
                onFailure = { throwable ->
                    error.value = throwable.message ?: "导出失败"
                }
            )
        }
    }

    fun setFormat(value: ExportFormat) {
        format.value = value
    }

    fun consumeResult() {
        result.value = null
    }

    fun consumeError() {
        error.value = null
    }
}
