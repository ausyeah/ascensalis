package com.mistakebook.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.data.repos.QuestionRepository
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.MasteryStatus
import com.mistakebook.ui.common.mergeVisibleOrder
import com.mistakebook.ui.common.orderByIds
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

data class HomeUiState(
    val questions: List<Question> = emptyList(),
    val filteredCount: Int = 0,
    val totalCount: Int = 0,
    val dueCount: Int = 0,
    val status: MasteryStatus? = null,
    val subjectId: Long? = null,
    val notebookId: Long? = null,
    val keyword: String = "",
    val dueOnly: Boolean = false,
    val subjects: List<Subject> = emptyList(),
    val notebooks: List<com.mistakebook.data.local.entities.Notebook> = emptyList(),
    val loading: Boolean = true
) {
    val today: Long get() = LocalDate.now().toEpochDay()
}

internal fun matchesHomeSearch(question: Question, keyword: String): Boolean {
    val query = keyword.trim()
    if (query.isEmpty()) return true
    return question.stem.contains(query, ignoreCase = true) ||
        question.title.contains(query, ignoreCase = true) ||
        question.answer.contains(query, ignoreCase = true) ||
        question.analysis.contains(query, ignoreCase = true) ||
        question.optionsJson.contains(query, ignoreCase = true) ||
        question.note.contains(query, ignoreCase = true)
}

private fun currentLocalDateFlow() = flow {
    while (true) {
        val now = ZonedDateTime.now()
        emit(now.toLocalDate())
        delay(millisUntilNextLocalDay(now))
    }
}.distinctUntilChanged()

internal fun millisUntilNextLocalDay(now: ZonedDateTime): Long {
    val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
    return Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1L)
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val repository: QuestionRepository = container.questionRepository
    private val statusFilter = MutableStateFlow<MasteryStatus?>(null)
    private val subjectFilter = MutableStateFlow<Long?>(null)
    private val notebookFilter = MutableStateFlow<Long?>(null)
    private val keywordInput = MutableStateFlow("")
    private val dueOnly = MutableStateFlow(false)
    private val localDateRefreshes = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    private val questionOrder = MutableStateFlow<List<Long>>(emptyList())

    private val today = merge(
        currentLocalDateFlow(),
        localDateRefreshes.map { LocalDate.now() }
    )
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now())
    init {
        viewModelScope.launch {
            container.settingsStore.homeQuestionOrder.collect { questionOrder.value = it }
        }
    }


    private data class Query(
        val status: MasteryStatus?,
        val subjectId: Long?,
        val notebookId: Long?,
        val keyword: String,
        val dueOnly: Boolean
    )

    // combine 的类型化重载最多只到 5 个 flow。第 6 个用一次嵌套 combine 补上，
    // 强用 vararg 版本会退化成 Array<Any?>，类型全丢。
    private val filter = combine(
        statusFilter,
        subjectFilter,
        notebookFilter,
        keywordInput.debounce(300),
        dueOnly
    ) { status, subject, notebook, keyword, due ->
        Query(status, subject, notebook, keyword, due)
    }

    private fun questionsOf(query: Query) = if (query.dueOnly) {
        today.flatMapLatest { repository.observeDue(it) }
    } else {
        // 全量查询（v0.0.59）：旧的 30 条/页在 StateFlow 里只持当前页，
        // 触底加载是整页**替换**而非追加，>30 题滚动直接坏掉（换页还可能翻到空页）。
        repository.observeFiltered(
            filter = QuestionRepository.Filter(
                status = query.status,
                subjectId = query.subjectId,
                notebookId = query.notebookId,
                keyword = query.keyword
            )
        )
    }

    private val questions: StateFlow<List<Question>> = filter
        .flatMapLatest { query ->
            questionsOf(query).map { list ->
                if (!query.dueOnly) {
                    list
                } else {
                    // 待复习来自独立数据源，组合筛选需在内存中保持与列表搜索一致。
                    list.filter { question ->
                        (query.status == null || question.status == query.status) &&
                            (query.subjectId == null || question.subjectId == query.subjectId) &&
                            (query.notebookId == null || question.notebookId == query.notebookId) &&
                            matchesHomeSearch(question, query.keyword)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val filteredCount: StateFlow<Int> = filter
        .flatMapLatest { query ->
            if (query.dueOnly) {
                questions.map { it.size }
            } else {
                repository.observeCount(
                    QuestionRepository.Filter(
                        status = query.status,
                        subjectId = query.subjectId,
                        notebookId = query.notebookId,
                        keyword = query.keyword
                    )
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val dueCount = today.flatMapLatest { repository.observeDueCount(it) }

    val uiState: StateFlow<HomeUiState> = combine(
        questions,
        filteredCount,
        repository.observeTotalCount(),
        dueCount
    ) { list, filtered, total, due ->
        HomeUiState(
            questions = list,
            filteredCount = filtered,
            totalCount = total,
            dueCount = due,
            loading = false
        )
    }
        .combine(statusFilter) { state, status -> state.copy(status = status) }
        .combine(subjectFilter) { state, subjectId -> state.copy(subjectId = subjectId) }
        .combine(notebookFilter) { state, id -> state.copy(notebookId = id) }
        .combine(keywordInput) { state, keyword -> state.copy(keyword = keyword) }
        .combine(dueOnly) { state, due -> state.copy(dueOnly = due) }
        .combine(container.subjectRepository.observeAll()) { state, subjects ->
            state.copy(subjects = subjects)
        }
        .combine(container.notebookRepository.observeAll()) { state, notebooks ->
            state.copy(notebooks = notebooks)
        }
        .combine(questionOrder) { state, order ->
            state.copy(questions = orderByIds(state.questions, order) { it.id })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setStatus(status: MasteryStatus?) {
        statusFilter.value = status
    }

    fun setSubject(subjectId: Long?) {
        subjectFilter.value = subjectId
    }

    fun setNotebook(notebookId: Long?) {
        notebookFilter.value = notebookId
    }

    fun setKeyword(keyword: String) {
        keywordInput.value = keyword
    }

    fun setDueOnly(value: Boolean) {
        dueOnly.value = value
    }

    fun refreshLocalDate() {
        localDateRefreshes.tryEmit(Unit)
    }

    fun saveVisibleQuestionOrder(ids: List<Long>) {
        if (ids.isEmpty()) return
        val merged = mergeVisibleOrder(questionOrder.value, ids)
        questionOrder.value = merged
        viewModelScope.launch { container.settingsStore.saveHomeQuestionOrder(merged) }
    }
    fun delete(question: Question) {
        viewModelScope.launch {
            repository.softDelete(question.id)
        }
    }

    fun undoDelete(question: Question) {
        viewModelScope.launch {
            repository.restore(question.id)
        }
    }

    fun clearTrash() {
        viewModelScope.launch {
            repository.listDeletedBefore(0).forEach { question ->
                repository.hardDelete(listOf(question.id))
                container.files.deleteRecursively(container.files.questionDir(question.id))
            }
        }
    }
}
