package com.mistakebook.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.data.prefs.SettingsSnapshot
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.ErrorReason
import com.mistakebook.domain.Option
import com.mistakebook.domain.QuestionDraft
import com.mistakebook.pipeline.DraftBundle
import com.mistakebook.pipeline.DraftBundleCodec
import com.mistakebook.pipeline.DraftQuestion
import com.mistakebook.pipeline.RefinedOptionDto
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 自动暂存的防抖窗口（毫秒）：停止输入 0.8 秒后才落盘。 */
private const val AUTOSAVE_DEBOUNCE_MS = 800L

// 一道题在编辑页中的可变状态
data class EditableDraft(
    val imagePath: String,
    /** 题目附图（MinerU 切出的图形 / 用户手动裁的图）。 */
    val figurePaths: List<String> = emptyList(),
    val title: String = "",
    val subjectName: String = "",
    /** 学科识别失败时用户手填的原始字符串（subjectName 为空时才显示输入框）。 */
    val manualSubject: String = "",
    val subjectId: Long? = null,
    /** 所属错题本；null 表示存进默认错题本。 */
    val notebookId: Long? = null,
    val stem: String = "",
    val options: List<Option> = emptyList(),
    val answer: String = "",
    val analysis: String = "",
    val knowledgePoints: List<String> = emptyList(),
    val errorReason: ErrorReason = ErrorReason.OTHER,
    val difficulty: Int = 3,
    val note: String = ""
) {
    fun toDomainDraft(markdown: String): QuestionDraft = QuestionDraft(
        imagePath = imagePath,
        figurePaths = figurePaths,
        mineruMarkdown = markdown,
        subjectId = subjectId,
        notebookId = notebookId,
        stem = stem,
        options = options,
        answer = answer,
        analysis = analysis,
        title = title,
        // 手填的学科优先于「其他」这类无效识别结果
        subjectName = manualSubject.trim().ifBlank { subjectName },
        knowledgePoints = knowledgePoints,
        errorReason = errorReason,
        difficulty = difficulty,
        note = note
    )
}

data class EditUiState(
    val loading: Boolean = true,
    val taskMissing: Boolean = false,
    val total: Int = 0,
    val index: Int = 0,
    val drafts: List<EditableDraft> = emptyList(),
    val markdown: String = "",
    val degraded: Boolean = false,
    val degradedReason: String = "",
    val truncated: Boolean = false,
    val subjects: List<Subject> = emptyList(),
    val notebooks: List<com.mistakebook.data.local.entities.Notebook> = emptyList(),
    val imageRefs: List<String> = emptyList(),
    val savedCount: Int = 0,
    val savedQuestionIds: Map<Int, Long> = emptyMap(),
    val dirtyDraftIndices: Set<Int> = emptySet(),
    /**
     * 手动录入模式：没有原图、没有识别文本可对照，保存后也不回填识别任务。
     * UI 据此隐藏「查看原始识别文本」等只对识别流程有意义的入口。
     */
    val manualEntry: Boolean = false,
    /** 手动录入：DataStore 槽里有上次未保存的草稿，等用户决定恢复/丢弃（T4）。 */
    val pendingManualRestore: Boolean = false
) {
    val current: EditableDraft? get() = drafts.getOrNull(index)
    val canSave: Boolean get() = current?.stem?.isNotBlank() == true
}

internal fun parseSavedQuestionIds(value: String?, draftCount: Int): Map<Int, Long> {
    if (draftCount <= 0) return emptyMap()
    return value.orEmpty()
        .split(',')
        .mapIndexedNotNull { index, token ->
            if (index >= draftCount) return@mapIndexedNotNull null
            token.trim().toLongOrNull()?.takeIf { id -> id > 0 }?.let { index to it }
        }
        .toMap()
}

internal fun nextUnsavedDraftIndex(
    draftCount: Int,
    currentIndex: Int,
    savedIndices: Set<Int>
): Int? {
    if (draftCount <= 0) return null
    val current = currentIndex.coerceIn(0, draftCount - 1)
    return (current + 1 until draftCount).firstOrNull { it !in savedIndices }
        ?: (0 until current).firstOrNull { it !in savedIndices }
        ?: current.takeIf { it !in savedIndices }
}

class EditViewModel(
    private val container: AppContainer,
    /**
     * 识别任务 id；**null 表示手动录入**。
     *
     * ## 为什么用可空而不是新开一个 ViewModel
     * 手动录入要填的字段和识别出来的完全一样（题干/选项/答案/解析/错因/
     * 难度/知识点/备注/错题本），LaTeX 预览、附图 chip、学科选择器也都要复用。
     * 另开一套页面等于把这些再写一遍，之后改一处就会漏另一处。
     * 所以这里只让「草稿从哪来」有两种，其余逻辑完全共用。
     */
    private val taskId: Long?,
    initialIndex: Int
) : ViewModel() {

    /** 手动录入：没有识别任务，也就没有任务状态要回填。 */
    val isManualEntry: Boolean get() = taskId == null

    private val _uiState = MutableStateFlow(EditUiState(loading = true, index = initialIndex))
    val uiState: StateFlow<EditUiState> = _uiState.asStateFlow()

    private var saveInProgress = false

    /**
     * 初始加载基线（T4 dirty 判定）：加载完成前为 null，dirty 恒 false。
     * 每次保存成功后把对应索引的基线更新为已保存内容——「保存成功后
     * 返回不弹确认」靠这条保持成立。
     */
    private var baselineDrafts: List<EditableDraft>? = null
    private var baselineMarkdown: String = ""

    init {
        viewModelScope.launch {
            val subjects = container.subjectRepository.observeAll().first()
            val notebooks = container.notebookRepository.observeAll().first()
            val defaultNotebookId = notebooks.firstOrNull { it.isDefault }?.id

            if (taskId == null) {
                // 手动录入：一张空白草稿。
                // imagePath 留空串——详情页与打印都有 isNotBlank / 后缀守卫，
                // 空串表示「这道题没有原图」，不会去 decodeFile("") 崩掉。
                _uiState.value = EditUiState(
                    loading = false,
                    total = 1,
                    index = 0,
                    drafts = listOf(EditableDraft(imagePath = "", notebookId = defaultNotebookId)),
                    markdown = "",
                    manualEntry = true,
                    subjects = subjects,
                    notebooks = notebooks,
                    imageRefs = emptyList(),
                    savedCount = 0,
                    // 上次没保存完的草稿还在槽里：先标记，等用户决定恢复/丢弃（T4）
                    pendingManualRestore = DraftBundleCodec
                        .decode(container.settingsStore.getManualDraftJson())
                        ?.questions?.isNotEmpty() == true
                )
                baselineDrafts = listOf(EditableDraft(imagePath = "", notebookId = defaultNotebookId))
                baselineMarkdown = ""
                startAutosave()
                return@launch
            }

            val task = container.captureTaskRepository.findById(taskId)
            val bundle: DraftBundle? = DraftBundleCodec.decode(task?.refinedJson)
            if (task == null) {
                _uiState.value = EditUiState(loading = false, taskMissing = true)
                return@launch
            }
            val markdown = bundle?.markdown ?: task.markdown.orEmpty()
            // MinerU 切出的题目插图：markdown 里的 ![](绝对路径) 引用就是附图。
            // 之前只在编辑页做成可删的 chip，保存时丢弃了，导致打印出来没有题目本身的图。
            val figureRefs = collectImageRefs(markdown)
            val drafts = bundle?.questions.orEmpty()
                .map { question -> toEditable(question, task.photoPath, figureRefs) }
                .ifEmpty {
                    listOf(
                        EditableDraft(
                            imagePath = task.photoPath,
                            figurePaths = figureRefs,
                            stem = task.markdown.orEmpty().trim()
                        )
                    )
                }
            val settings: SettingsSnapshot = container.settingsStore.snapshotNow()
            // 识别完成后所有草稿默认落进默认错题本——用户可以逐题改，
            // 但默认必须是「有个去处」，不能是「未归类」。
            val seeded = if (defaultNotebookId == null) {
                drafts
            } else {
                drafts.map { it.copy(notebookId = defaultNotebookId) }
            }
            val savedQuestionIds = parseSavedQuestionIds(task.questionIdsJson, seeded.size)
            _uiState.value = EditUiState(
                loading = false,
                total = seeded.size,
                index = initialIndex.coerceIn(0, (seeded.size - 1).coerceAtLeast(0)),
                drafts = seeded,
                markdown = markdown,
                degraded = bundle?.degraded == true,
                degradedReason = bundle?.degradedReason.orEmpty(),
                truncated = bundle?.truncatedInput == true,
                subjects = subjects,
                notebooks = notebooks,
                imageRefs = figureRefs,
                savedCount = savedQuestionIds.size,
                savedQuestionIds = savedQuestionIds
            )
            baselineDrafts = seeded
            baselineMarkdown = markdown
            startAutosave()
        }
    }

    /**
     * 自动暂存收集器（T4）：字段变化后 debounce 800ms 落一份暂存。
     *
     * - taskId != null：写回 capture_tasks.refinedJson，且**只替换当前 index
     *   那一份草稿**（[replaceDraftQuestionInBundle]，bundle 从库里现读，
     *   同批其他题的草稿原样保留——整包重编码会把其他题的编辑覆盖掉）；
     * - taskId == null（手动录入）：DataStore 单槽 manual_draft_json。
     *
     * baseline 为空（加载中）或与基线一致（不脏）时跳过：暂存只发生
     * 在真正改动过之后。
     */
    @OptIn(FlowPreview::class)
    private fun startAutosave() {
        viewModelScope.launch {
            _uiState
                .debounce(AUTOSAVE_DEBOUNCE_MS)
                .collect { autosaveSnapshot() }
        }
    }

    private suspend fun autosaveSnapshot() {
        val state = _uiState.value
        val baseline = baselineDrafts ?: return
        if (!state.loading && state.drafts == baseline && state.markdown == baselineMarkdown) return
        if (taskId == null) {
            val draft = state.drafts.firstOrNull() ?: return
            val bundle = DraftBundle(
                imagePath = "",
                markdown = state.markdown,
                questions = listOf(draft.toDraftQuestion())
            )
            container.settingsStore.setManualDraftJson(DraftBundleCodec.encode(bundle))
        } else {
            val task = container.captureTaskRepository.findById(taskId) ?: return
            val refined = task.refinedJson ?: return
            val updated = replaceDraftQuestionInBundle(refined, state.index, state.current ?: return)
                ?: return
            container.captureTaskRepository.updateRefinedJson(
                id = taskId,
                refinedJson = updated,
                now = System.currentTimeMillis()
            )
        }
    }

    /** 手动录入：恢复上次未保存的草稿到当前草稿位（T4）。 */
    fun restoreManualDraft() {
        viewModelScope.launch {
            val bundle = DraftBundleCodec.decode(container.settingsStore.getManualDraftJson())
                ?: return@launch
            val question = bundle.questions.firstOrNull() ?: return@launch
            val restored = toEditable(question, imagePath = "", figureRefs = emptyList())
            val state = _uiState.value
            val drafts = state.drafts.toMutableList()
            if (drafts.isEmpty()) return@launch
            drafts[0] = restored.copy(notebookId = drafts[0].notebookId)
            _uiState.value = state.copy(
                drafts = drafts,
                markdown = bundle.markdown,
                pendingManualRestore = false
            )
        }
    }

    /** 手动录入：丢弃上次未保存的草稿（清槽）。 */
    fun discardManualDraft() {
        viewModelScope.launch {
            container.settingsStore.setManualDraftJson(null)
            _uiState.update { it.copy(pendingManualRestore = false) }
        }
    }

    /** dirty（T4）：任一可编辑字段与初始加载值不同。基线未就绪时恒 false。 */
    fun isDirty(): Boolean {
        val baseline = baselineDrafts ?: return false
        val state = _uiState.value
        if (state.loading) return false
        return state.drafts != baseline || state.markdown != baselineMarkdown
    }

    private fun toEditable(
        question: DraftQuestion,
        imagePath: String,
        figureRefs: List<String>
    ): EditableDraft {
        val options = question.options.mapIndexed { index, option ->
            Option(
                label = option.label.ifBlank { com.mistakebook.domain.labelFor(index) },
                text = option.text
            )
        }
        return EditableDraft(
            imagePath = imagePath,
            figurePaths = figureRefs,
            title = question.title,
            subjectName = question.subject,
            stem = question.stem,
            options = options,
            answer = question.answer,
            analysis = question.analysis,
            knowledgePoints = question.knowledgePoints,
            errorReason = ErrorReason.entries.firstOrNull { it.label == question.errorReason }
                ?: ErrorReason.OTHER,
            difficulty = question.difficulty.coerceIn(1, 5),
            note = ""
        )
    }

    private fun collectImageRefs(markdown: String): List<String> =
        Regex("!\\[[^\\]]*\\]\\(([^)]+)\\)").findAll(markdown)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    fun goTo(index: Int) {
        val state = _uiState.value
        if (index in draftsIndices(state)) {
            _uiState.value = state.copy(index = index)
        }
    }

    private fun draftsIndices(state: EditUiState): IntRange = 0..(state.drafts.size - 1).coerceAtLeast(0)

    fun updateCurrent(transform: (EditableDraft) -> EditableDraft) {
        val state = _uiState.value
        val current = state.current ?: return
        val updatedDraft = transform(current)
        val updated = state.drafts.toMutableList()
        updated[state.index] = updatedDraft
        val dirtyIndices = if (
            updatedDraft != current && state.index in state.savedQuestionIds
        ) state.dirtyDraftIndices + state.index else state.dirtyDraftIndices
        _uiState.value = state.copy(
            drafts = updated,
            dirtyDraftIndices = dirtyIndices,
            savedCount = (state.savedQuestionIds.keys - dirtyIndices).size
        )
    }

    fun setStem(value: String) = updateCurrent { it.copy(stem = value) }

    fun setAnswer(value: String) = updateCurrent { it.copy(answer = value) }

    fun setAnalysis(value: String) = updateCurrent { it.copy(analysis = value) }

    fun setNote(value: String) = updateCurrent { it.copy(note = value) }

    fun setSubject(subjectId: Long?) = updateCurrent { draft ->
        draft.copy(subjectId = subjectId)
    }

    /** 学科识别不出来时由用户手填；选了预设学科后此值会被覆盖。 */
    fun setSubjectName(value: String) = updateCurrent { it.copy(subjectName = value) }

    fun setReason(reason: ErrorReason) = updateCurrent { it.copy(errorReason = reason) }

    fun setDifficulty(value: Int) = updateCurrent { it.copy(difficulty = value.coerceIn(1, 5)) }

    fun addKnowledgePoint(point: String) {
        val clean = point.trim()
        if (clean.isEmpty()) return
        updateCurrent { draft ->
            if (draft.knowledgePoints.contains(clean)) draft
            else draft.copy(knowledgePoints = draft.knowledgePoints + clean)
        }
    }

    fun removeKnowledgePoint(point: String) = updateCurrent { draft ->
        draft.copy(knowledgePoints = draft.knowledgePoints - point)
    }

    fun addOption() = updateCurrent { draft ->
        draft.copy(
            options = draft.options + Option(label = com.mistakebook.domain.labelFor(draft.options.size), text = "")
        )
    }

    fun updateOption(index: Int, text: String) = updateCurrent { draft ->
        draft.copy(options = draft.options.mapIndexed { i, option ->
            if (i == index) option.copy(text = text) else option
        })
    }

    fun removeOption(index: Int) = updateCurrent { draft ->
        val kept = draft.options.filterIndexed { i, _ -> i != index }
            .mapIndexed { i, option -> option.copy(label = com.mistakebook.domain.labelFor(i)) }
        draft.copy(options = kept)
    }

    /** 设置题目标题（列表页展示用）。 */
    fun setTitle(value: String) = updateCurrent { it.copy(title = value) }

    fun setManualSubject(value: String) = updateCurrent { it.copy(manualSubject = value) }

    /**
     * 换错题本。传入的 id 来自已经存在列表，存 null 表示不归类。
     * 切换不触发任何磁盘写入，随保存一起落库。
     */
    fun setNotebook(value: Long?) = updateCurrent { it.copy(notebookId = value) }

    /** 设置原始照片（用户重新裁剪/旋转后替换）。 */
    fun setImagePath(value: String) = updateCurrent { it.copy(imagePath = value) }

    /** 追加一张题目附图。 */
    fun addFigure(path: String) = updateCurrent { draft ->
        if (draft.figurePaths.contains(path)) draft
        else draft.copy(figurePaths = draft.figurePaths + path)
    }

    /** 删除一张题目附图。 */
    fun removeFigure(path: String) = updateCurrent { draft ->
        draft.copy(figurePaths = draft.figurePaths - path)
    }

    /** 从 Markdown 中剔除被用户判定为噪声的插图引用（同时从附图列表移除）。 */
    fun removeImageRef(ref: String) {
        val state = _uiState.value
        val cleaned = state.markdown
            .lineSequence()
            .filterNot { line -> line.contains("($ref)") }
            .joinToString("\n")
        val updated = state.drafts.map { draft ->
            draft.copy(figurePaths = draft.figurePaths - ref)
        }
        val dirtyIndices = state.dirtyDraftIndices + state.savedQuestionIds.keys
        _uiState.value = state.copy(
            markdown = cleaned,
            imageRefs = state.imageRefs - ref,
            drafts = updated,
            dirtyDraftIndices = dirtyIndices,
            savedCount = (state.savedQuestionIds.keys - dirtyIndices).size
        )
    }

    /** 保存当前题；全部草稿索引均已保存后才回填任务并回调。 */
    fun saveCurrent(onSaved: (Long) -> Unit) {
        if (saveInProgress) return
        val state = _uiState.value
        val index = state.index
        val draft = state.current ?: return
        if (!draft.isValid()) return
        saveInProgress = true
        viewModelScope.launch {
            try {
                val task = taskId?.let { container.captureTaskRepository.findById(it) }
                val id = persistDraft(
                    index = index,
                    draft = draft,
                    markdown = state.markdown,
                    savedQuestionIds = state.savedQuestionIds,
                    replacesQuestionId = task?.replacesQuestionId,
                    now = System.currentTimeMillis()
                )
                val latest = _uiState.value
                val savedIds = latest.savedQuestionIds + (index to id)
                persistQuestionIds(task?.id, savedIds, System.currentTimeMillis())
                val dirtyIndices = if (latest.drafts.getOrNull(index) == draft) {
                    latest.dirtyDraftIndices - index
                } else {
                    latest.dirtyDraftIndices + index
                }
                val completedIndices = savedIds.keys - dirtyIndices
                val nextIndex = nextUnsavedDraftIndex(latest.drafts.size, index, completedIndices)
                _uiState.value = latest.copy(
                    savedQuestionIds = savedIds,
                    dirtyDraftIndices = dirtyIndices,
                    savedCount = completedIndices.size,
                    index = if (latest.index == index) nextIndex ?: index else latest.index
                )
                // 基线推进：已保存的这份不再算 dirty（T4「保存成功后返回不弹确认」）
                baselineDrafts?.let { base ->
                    val updatedBase = base.toMutableList()
                    latest.drafts.getOrNull(index)?.let { updatedBase[index] = it }
                    baselineDrafts = updatedBase
                }
                // 手动录入：refinedJson 路径没有，暂存槽已无意义，清掉
                if (taskId == null) {
                    container.settingsStore.setManualDraftJson(null)
                }
                if (nextIndex == null) {
                    finishTask(savedIds, task?.refinedJson.orEmpty(), onSaved)
                }
            } finally {
                saveInProgress = false
            }
        }
    }

    fun saveAll(onSaved: (Long) -> Unit) {
        if (saveInProgress) return
        saveInProgress = true
        viewModelScope.launch {
            try {
                val state = _uiState.value
                val task = taskId?.let { container.captureTaskRepository.findById(it) }
                val savedIds = state.savedQuestionIds.toMutableMap()
                val dirtyIndices = state.dirtyDraftIndices.toMutableSet()
                val now = System.currentTimeMillis()
                state.drafts.forEachIndexed { index, draft ->
                    if (draft.isValid()) {
                        savedIds[index] = persistDraft(
                            index = index,
                            draft = draft,
                            markdown = state.markdown,
                            savedQuestionIds = savedIds,
                            replacesQuestionId = task?.replacesQuestionId,
                            now = now
                        )
                        persistQuestionIds(task?.id, savedIds, now)
                        dirtyIndices.remove(index)
                    }
                }
                val latest = _uiState.value
                val changedDuringSave = latest.drafts.indices.filterTo(mutableSetOf()) { index ->
                    latest.drafts[index] != state.drafts.getOrNull(index)
                }
                val remainingDirty = dirtyIndices + changedDuringSave
                val completedIndices = savedIds.keys - remainingDirty
                _uiState.value = latest.copy(
                    savedQuestionIds = savedIds,
                    dirtyDraftIndices = remainingDirty,
                    savedCount = completedIndices.size
                )
                // 基线推进到已保存内容（T4）
                baselineDrafts?.let { base ->
                    baselineDrafts = latest.drafts.mapIndexed { i, draft ->
                        if (i in savedIds) draft else base.getOrNull(i) ?: draft
                    }
                }
                if (taskId == null) {
                    container.settingsStore.setManualDraftJson(null)
                }
                if (latest.drafts.isNotEmpty() && completedIndices.size == latest.drafts.size) {
                    finishTask(savedIds, task?.refinedJson.orEmpty(), onSaved)
                }
            } finally {
                saveInProgress = false
            }
        }
    }

    private suspend fun persistDraft(
        index: Int,
        draft: EditableDraft,
        markdown: String,
        savedQuestionIds: Map<Int, Long>,
        replacesQuestionId: Long?,
        now: Long
    ): Long {
        val existingId = savedQuestionIds[index]
        return when {
            existingId != null -> overwrite(existingId, draft, markdown, now)
            savedQuestionIds.isEmpty() && replacesQuestionId != null ->
                overwrite(replacesQuestionId, draft, markdown, now)
            else -> container.questionRepository.save(draft.toDomainDraft(markdown), now)
        }
    }

    private suspend fun persistQuestionIds(taskId: Long?, savedIds: Map<Int, Long>, now: Long) {
        if (taskId == null || savedIds.isEmpty()) return
        container.captureTaskRepository.attachQuestionIds(
            id = taskId,
            questionIds = savedIds,
            now = now
        )
    }

    /**
     * 覆盖已有题目。
     *
     * 保留 id、掌握状态、复习时间、创建时间——重新识别只该换「题目内容」，
     * 不该把用户已经标了「掌握」的状态、复习排期一起抹掉。
     * 备注也保留：那是用户自己写的东西，跟识别结果无关。
     */
    private suspend fun overwrite(
        questionId: Long,
        draft: EditableDraft,
        markdown: String,
        now: Long
    ): Long {
        val existing = container.questionRepository.findById(questionId)
        if (existing == null) {
            return container.questionRepository.save(draft.toDomainDraft(markdown), now)
        }
        val domain = draft.toDomainDraft(markdown)
        val updated = existing.applyRecognitionDraft(domain)
        container.questionRepository.overwrite(updated, domain.figurePaths, domain.knowledgePoints)
        return questionId
    }

    /**
     * 回填识别任务并回调。
     *
     * 手动录入没有任务可回填（`taskId == null`），直接回调，
     * 否则会对着一个 null id 写数据库。
     */
    private suspend fun finishTask(
        savedQuestionIds: Map<Int, Long>,
        originalRefinedJson: String,
        onSaved: (Long) -> Unit
    ) {
        val orderedIds = savedQuestionIds.toSortedMap().values.toList()
        val firstId = orderedIds.firstOrNull() ?: return
        val id = taskId
        if (id == null) {
            onSaved(firstId)
            return
        }
        val state = _uiState.value
        container.captureTaskRepository.finish(
            id = id,
            markdown = state.markdown,
            refinedJson = originalRefinedJson,
            questionCount = orderedIds.size,
            now = System.currentTimeMillis()
        )
        container.captureTaskRepository.attachQuestionIds(id, savedQuestionIds, System.currentTimeMillis())
        onSaved(firstId)
    }

    private fun EditableDraft.isValid(): Boolean = stem.isNotBlank()
}
