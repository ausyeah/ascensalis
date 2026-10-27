package com.mistakebook.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.di.AppContainer
import com.mistakebook.data.local.entities.Question
import com.mistakebook.domain.ReviewResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 复习页状态。
 *
 * 队列是**进入页面时的一次性快照**：评分会推进 nextReviewAt，若每次都重查
 * dueQuestions，评过的题立即消失、队列越刷越短——回忆自测需要的是
 * 「这一轮我要面对哪些题」的固定清单。结束页「立即重刷未掌握」的数据源
 * 同理取内存快照（[ReviewUiState.retryIds]），不能重查 DB。
 */
data class ReviewUiState(
    /** 本轮队列快照。 */
    val queue: List<Question> = emptyList(),
    /** 当前下标。 */
    val index: Int = 0,
    /** 是否已显示答案（三键自评的前置条件）。 */
    val revealed: Boolean = false,
    /** 队列走完。 */
    val finished: Boolean = false,
    val correctCount: Int = 0,
    val vagueCount: Int = 0,
    val wrongCount: Int = 0,
    /** 今日剩余到期数（头部计数 + 结束页「还有 N 道」）。 */
    val remainingDueCount: Int = 0,
    /** 本轮评了 VAGUE/WRONG 的题 id：结束页「立即重刷未掌握」的数据源。 */
    val retryIds: List<Long> = emptyList()
) {
    /** 是否为空队列（显示空状态）。 */
    val isEmpty: Boolean get() = queue.isEmpty() && !finished
}

/**
 * 独立复习页（T1）。
 *
 * 职责单一：按快照队列过题 → 三键自评 → [com.mistakebook.data.repos.QuestionRepository.applyReviewResult]
 * 原子落库。评分后立即切下一题（无弹窗、无人工延迟）。
 */
class ReviewViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.questionRepository

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState

    init {
        loadQueue()
        // 头部剩余计数跟着 DB 实时走：评分一落库，数字立刻减一。
        viewModelScope.launch {
            repository.observeDueCount(LocalDate.now()).collect { due ->
                _uiState.update { it.copy(remainingDueCount = due) }
            }
        }
    }

    /** 取今日到期队列快照：nextReviewAt 升序、再按 updatedAt 降序。 */
    private fun loadQueue() {
        viewModelScope.launch {
            val queue = repository.dueQuestions(LocalDate.now(), limit = QUEUE_LIMIT)
                .sortedWith(compareBy<Question> { it.nextReviewAt }.thenByDescending { it.updatedAt })
            _uiState.update {
                it.copy(
                    queue = queue,
                    index = 0,
                    revealed = false,
                    finished = false
                )
            }
        }
    }

    fun reveal() {
        _uiState.update { it.copy(revealed = true) }
    }

    /**
     * 三键自评。落库失败（题被删等）静默跳过，照常前进——复习流不打断，
     * 详情：见 QuestionRepository.applyReviewResult 的注释。
     */
    fun rate(result: ReviewResult) {
        val state = _uiState.value
        if (!state.revealed || state.finished || state.index >= state.queue.size) return
        val question = state.queue[state.index]
        viewModelScope.launch {
            repository.applyReviewResult(question.id, result)
            // T9 事件驱动：复习完成后刷新桌面小组件的今日待复习数
            // （容器不持 Context，App 单例在进程内必然已初始化）
            (com.mistakebook.MistakeBookApp.instanceOrNull())?.let {
                com.mistakebook.widget.TodayWidgetProvider.refreshAll(it)
            }
            _uiState.update {
                val correct = it.correctCount + if (result == ReviewResult.CORRECT) 1 else 0
                val vague = it.vagueCount + if (result == ReviewResult.VAGUE) 1 else 0
                val wrong = it.wrongCount + if (result == ReviewResult.WRONG) 1 else 0
                val retry = if (result == ReviewResult.CORRECT) it.retryIds else it.retryIds + question.id
                it.copy(
                    correctCount = correct,
                    vagueCount = vague,
                    wrongCount = wrong,
                    retryIds = retry,
                    index = it.index + 1,
                    revealed = false,
                    finished = it.index + 1 >= it.queue.size
                )
            }
        }
    }

    /**
     * 结束页「立即重刷未掌握」：**内存重建队列**——从本轮快照里把评了
     * 模糊/不会的 id 逐个 findById。绝不能重查 dueQuestions：这些题的
     * nextReviewAt 已被推到明天，重查必然空集，按钮会变成死的。
     */
    fun restartUnmastered() {
        viewModelScope.launch {
            val ids = _uiState.value.retryIds
            val queue = ids.mapNotNull { id ->
                repository.findById(id)?.takeIf { it.deletedAt == null }
            }.sortedWith(compareBy<Question> { it.nextReviewAt }.thenByDescending { it.updatedAt })
            _uiState.update {
                it.copy(
                    queue = queue,
                    index = 0,
                    revealed = false,
                    finished = false,
                    retryIds = emptyList()
                )
            }
        }
    }

    /**
     * 结束页「还有 N 道，继续复习」：队列上限（200）没轮完时重取一批。
     */
    fun continueMore() {
        loadQueue()
    }

    companion object {
        /** 队列上限：一次复习最多 200 道，没刷完的从结束页继续。 */
        const val QUEUE_LIMIT = 200
    }
}
