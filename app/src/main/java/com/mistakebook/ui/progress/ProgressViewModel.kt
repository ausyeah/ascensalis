package com.mistakebook.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.di.AppContainer
import com.mistakebook.pipeline.DraftBundle
import com.mistakebook.pipeline.DraftBundleCodec
import com.mistakebook.data.local.entities.CaptureTask
import com.mistakebook.domain.TaskStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.mistakebook.R

data class ProgressUiState(
    val task: CaptureTask? = null,
    val groupTasks: List<CaptureTask> = emptyList(),
    val loading: Boolean = true
) {
    val status: TaskStatus? get() = task?.status
    val failed: Boolean get() = task?.status == TaskStatus.FAILED
    val done: Boolean get() = task?.status == TaskStatus.DONE
    val groupPosition: String
        get() {
            val group = groupTasks
            if (group.size <= 1) return ""
            val index = group.indexOfFirst { it.id == task?.id }
            return "第 ${index + 1} / ${group.size} 张"
        }
}

class ProgressViewModel(
    private val container: AppContainer,
    private val taskId: Long
) : ViewModel() {

    private val repository = container.captureTaskRepository

    val uiState: StateFlow<ProgressUiState> = combine(
        repository.observeById(taskId),
        repository.observeGroupOf(taskId)
    ) { task, group ->
        ProgressUiState(task = task, groupTasks = group, loading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressUiState())

    fun bundle(): DraftBundle? {
        val raw = uiState.value.task?.refinedJson ?: return null
        return DraftBundleCodec.decode(raw)
    }

    fun cancel() {
        container.recognitionEngine.cancel(taskId)
    }

    fun retry() {
        viewModelScope.launch {
            val task = repository.findById(taskId) ?: return@launch
            repository.update(
                task.copy(
                    status = TaskStatus.PENDING,
                    errorMessage = null,
                    errorKind = null,
                    stageText = container.appContext.getString(R.string.task_stage_queued),
                    updatedAt = System.currentTimeMillis()
                )
            )
            container.recognitionEngine.submit(taskId)
        }
    }

    /** 跳过 AI：用原始识别文本构造降级草稿。 */
    fun skipLlm(onReady: () -> Unit) {
        viewModelScope.launch {
            val task = repository.findById(taskId) ?: return@launch
            val markdown = task.markdown.orEmpty()
            repository.finish(
                id = taskId,
                markdown = markdown,
                refinedJson = com.mistakebook.pipeline.DraftBundleCodec.encode(
                    com.mistakebook.pipeline.DraftBundle(
                        imagePath = task.photoPath,
                        markdown = markdown,
                        degraded = true,
                        degradedReason = com.mistakebook.pipeline.RecognitionEngine.SKIP_MESSAGE,
                        questions = listOf(
                            com.mistakebook.pipeline.DraftQuestion(
                                stem = markdown.trim(),
                                knowledgePoints = listOf("未标注知识点"),
                                difficulty = 3,
                                errorReason = "其他"
                            )
                        )
                    )
                ),
                questionCount = 1,
                now = System.currentTimeMillis()
            )
            onReady()
        }
    }
}
