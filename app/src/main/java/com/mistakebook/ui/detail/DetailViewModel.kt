package com.mistakebook.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.data.local.knowledgePoints
import com.mistakebook.data.local.options
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.MasteryStatus
import com.mistakebook.domain.Option
import com.mistakebook.domain.QuestionDraft
import com.mistakebook.net.ApiResult
import com.mistakebook.pipeline.VariantPrompts
import com.mistakebook.pipeline.VariantQuestionParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DetailUiState(
    val loading: Boolean = true,
    val question: Question? = null,
    val subjectName: String? = null,
    val notebooks: List<com.mistakebook.data.local.entities.Notebook> = emptyList(),
    val dueToday: Boolean = false,
    val deleted: Boolean = false
) {
    val options: List<com.mistakebook.domain.Option> get() = question?.options.orEmpty()

    val knowledgePoints: List<String> get() = question?.knowledgePoints.orEmpty()

    val isMastered: Boolean get() = question?.status == MasteryStatus.MASTERED
}

/** 重新识别的结果。UI 靠它决定是跳进度页还是留在原地显示原因。 */
sealed interface ReRecognizeResult {
    /** 已提交，[taskId] 是新建的识别任务。 */
    data class Submitted(val taskId: Long) : ReRecognizeResult

    /** 未提交，[reason] 是可直接展示给用户的中文原因。 */
    data class Failed(val reason: String) : ReRecognizeResult
}

/**
 * 变式题（F1 举一反三）的界面状态。
 *
 * 独立于 [DetailUiState] 而不是并进去：它有自己的生命周期（生成中→出结果/失败→关闭），
 * 且不应因为题目数据刷新（改难度、改备注）被打断或重置。
 */
data class VariantState(
    val generating: Boolean = false,
    val draft: com.mistakebook.pipeline.RefinedQuestionDto? = null,
    val error: String? = null
) {
    val visible: Boolean get() = generating || draft != null || error != null
}

/**
 * 辅助图（F-IMG-4）的界面状态。
 *
 * 与 [VariantState] 同理：独立于 [DetailUiState]——题目数据刷新
 * （改难度、改备注）不该把已经生成的图清掉，它有自己的生命周期。
 */
data class AuxImageState(
    val generating: Boolean = false,
    /**
     * 已生成的 SVG 源码（v0.0.57：辅助图从「生图模型」改为「LLM 直接出 SVG」）。
     *
     * 用户反馈「生成辅助图还是用生图模型太不可控」：几何画歪、坐标轴标错、
     * 数字乱写。SVG 是代码，结构确定、可缩放、可裁剪，才适合数学/物理图。
     */
    val svg: String? = null,
    /** 生成说明（例如「按题干画的函数图像」）。 */
    val prompt: String? = null,
    val error: String? = null
) {
    val visible: Boolean get() = generating || svg != null || error != null
}

class DetailViewModel(
    private val container: AppContainer,
    private val questionId: Long
) : ViewModel() {

    private val _auxImage = MutableStateFlow(AuxImageState())
    val auxImage: StateFlow<AuxImageState> = _auxImage.asStateFlow()

    /**
     * 生图缓存种子：题目 id + 题干 hash。
     *
     * 带题干 hash 而不是只用 id：改过题干的题是另一道题，
     * 图也该重新生成（否则「改了题、图还是旧的」）。
     */
    private fun auxSeed(question: Question): String =
        "q$questionId-${question.stem.hashCode()}"

    /**
     * 生成这道题的辅助图（v0.0.57 重做）：**让对话大模型直接输出 SVG**。
     *
     * ## 为什么不再走生图 API
     *
     * 旧实现是「LLM 写英文描述 → 生图模型画」。用户实测的问题很集中：
     * 生图模型会把三角形画歪、坐标轴标错、把题目里的数字改掉——
     * 而错题辅助图恰恰要求**精确**。SVG 是代码：形状、坐标、标注都由
     * 文本决定，模型写错了也能从源码看出来，渲染出来不会「像那么回事但全是错的」。
     *
     * 提示词强约束：
     * - 只输出一个 `<svg>...</svg>`，不要解释、不要 Markdown 围栏；
     * - viewBox 固定 `0 0 400 300`，只允许基本形状；
     * - 题里的数字/字母必须原样标注。
     */
    fun generateAuxImage() {
        viewModelScope.launch {
            val question = uiState.value.question ?: return@launch
            if (_auxImage.value.generating) return@launch
            _auxImage.value = AuxImageState(generating = true)
            val profile = container.settingsStore.snapshotNow().activeProfile
            if (profile == null || !profile.isConfigured()) {
                _auxImage.value = AuxImageState(error = "尚未配置对话模型：请到设置 → 接口配置里添加")
                return@launch
            }
            val user = buildString {
                val subject = uiState.value.subjectName
                if (!subject.isNullOrBlank()) append("学科：").append(subject).append('\n')
                append("题目：").append(question.stem.trim()).append('\n')
                if (question.answer.isNotBlank()) append("答案：").append(question.answer.trim()).append('\n')
            }
            val result = container.llmClient.plainText(
                profile = profile,
                systemPrompt = AUX_SVG_SYSTEM_PROMPT,
                userPrompt = user,
                maxTokens = 4096
            )
            _auxImage.value = when (result) {
                is com.mistakebook.net.ApiResult.Success -> {
                    val svg = com.mistakebook.markdown.extractSvgDocument(result.data)
                    if (svg == null) {
                        AuxImageState(error = "模型没有给出可用的 SVG，请重试")
                    } else {
                        runCatching { auxFile(question).writeText(svg, Charsets.UTF_8) }
                        AuxImageState(svg = svg, prompt = "由 AI 按题干生成的结构示意图")
                    }
                }

                is com.mistakebook.net.ApiResult.Failure ->
                    AuxImageState(
                        error = result.error.serverMessage.ifBlank { "生成失败，请重试" }
                    )
            }
        }
    }

    /** 辅助图缓存文件：题目 id + 题干 hash（改题=换图）。 */
    private fun auxFile(question: Question): java.io.File {
        val dir = java.io.File(container.files.rootDir, "aux").apply { mkdirs() }
        return java.io.File(dir, "${auxSeed(question)}.svg")
    }

    /** 关掉辅助图（失败提示用；图本身留在缓存里，下次进来还在）。 */
    fun dismissAuxImage() {
        _auxImage.value = AuxImageState()
    }

    /**
     * **删除**辅助图：清掉磁盘缓存里的源文件，再清界面状态。
     *
     * 早先只有 [dismissAuxImage]（只清状态）——而 `init` 每次进页都按
     * key 从缓存回填，文件还在图就永远会再出现，用户看到的「删除」
     * 形同虚设（v0.0.54 用户反馈「辅助图无法删除」）。真正的删除
     * 必须落在文件层：源文件没了，回填自然找不到，下次进来是「生成」按钮。
     */
    fun deleteAuxImage() {
        viewModelScope.launch {
            runCatching {
                val question = uiState.value.question
                if (question != null) {
                    auxFile(question).delete()
                    // 兼容老版本生成的 PNG 缓存
                    val profile = container.settingsStore.snapshotNow().activeImageProfile
                    if (profile != null) {
                        container.imageDiskCache.remove(
                            container.imageDiskCache.keyFor(auxSeed(question), profile.model)
                        )
                    }
                }
            }
            _auxImage.value = AuxImageState()
        }
    }
    val uiState: StateFlow<DetailUiState> = combine(
        container.questionRepository.observeById(questionId),
        container.subjectRepository.observeAll(),
        container.notebookRepository.observeAll()
    ) { question, subjects, notebooks ->
        DetailUiState(
            loading = false,
            question = question,
            subjectName = subjects.firstOrNull { it.id == question?.subjectId }?.name,
            notebooks = notebooks,
            dueToday = question?.let {
                it.status != MasteryStatus.MASTERED &&
                    (it.nextReviewAt ?: Long.MAX_VALUE) <= LocalDate.now().toEpochDay()
            } ?: false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    /**
     * 进详情页先回填辅助图缓存：这道题出过图就直接显示，不用再点一次按钮。
     *
     * **位置必须在 [uiState] 声明之后**。Kotlin 的 init 块按**出现顺序**
     * 执行，写在 uiState 前面的话，块里访问到的 uiState 还是未赋值的
     * 空引用——构造 ViewModel 就 NPE，表现是「点开任何错题立刻闪退」。
     * 这类崩溃不会在编译期报错，只能靠声明顺序规避。
     */
    init {
        viewModelScope.launch {
            runCatching {
                val question = uiState.mapNotNull { it.question }.first()
                // v0.0.57：辅助图现在是缓存目录里的 .svg 文本文件
                val file = auxFile(question)
                if (file.isFile) {
                    _auxImage.value = AuxImageState(
                        svg = file.readText(Charsets.UTF_8),
                        prompt = "由 AI 按题干生成的结构示意图"
                    )
                }
            }.onFailure { /* 回填失败不影响看题 */ }
        }
    }

    /** 改错题本归属，立即落库。传 null 表示移出分类（回到「未归类」）。 */
    fun setNotebook(notebookId: Long?) {
        viewModelScope.launch {
            container.notebookRepository.assignQuestion(questionId, notebookId)
        }
    }

    /** 「是否会了」开关。会了的进 MASTERED，不再进待复习队列与每日提醒。 */
    fun setMastered(mastered: Boolean) {
        viewModelScope.launch {
            container.questionRepository.setMastered(questionId, mastered)
        }
    }

    private var titleDraft: String? = null

    /**
     * 重新识别这道题的原图。
     *
     * 用 sealed 结果而不是 `Any?` / `(String?) -> Unit`：
     * 后者分不清「成功但没任务」和「失败且没有原因」，调用方只能靠猜。
     */
    fun reRecognize(onResult: (ReRecognizeResult) -> Unit) {
        viewModelScope.launch {
            val question = uiState.value.question
            if (question == null) {
                onResult(ReRecognizeResult.Failed("题目已不存在"))
                return@launch
            }
            val snapshot = container.settingsStore.snapshotNow()
            if (!snapshot.mineruConfigured || !snapshot.llmConfigured) {
                onResult(ReRecognizeResult.Failed("请先到设置填写 API Key"))
                return@launch
            }
            val file = java.io.File(question.imagePath)
            if (!file.exists()) {
                onResult(ReRecognizeResult.Failed("原图已不存在，无法重新识别"))
                return@launch
            }
            val taskId = container.recognitionSubmitter.reRecognize(
                file = file,
                replacesQuestionId = questionId,
                groupTitle = "重新识别"
            )
            onResult(
                if (taskId == null) ReRecognizeResult.Failed("提交失败，请重试")
                else ReRecognizeResult.Submitted(taskId)
            )
        }
    }

    fun delete() {
        viewModelScope.launch {
            container.questionRepository.softDelete(questionId)
        }
    }

    /**
     * 难度。
     * 大模型给的星级只是初值——用户对自己做的题难度有最终判断权，
     * 所以详情页直接点星就能改，不用跳去编辑页找控件。
     */
    fun setDifficulty(value: Int) {
        val clamped = value.coerceIn(1, 5)
        val current = uiState.value.question?.difficulty ?: return
        if (clamped == current) return
        viewModelScope.launch {
            container.questionRepository.setDifficulty(questionId, clamped)
        }
    }

    /** 标题：列表页展示用，详情页直接改。 */
    fun setTitle(value: String) {
        titleDraft = value
    }

    fun currentTitle(): String = titleDraft
        ?: uiState.value.question?.title.orEmpty()

    fun saveTitle() {
        val draft = titleDraft ?: return
        val saved = uiState.value.question?.title.orEmpty()
        titleDraft = null
        if (draft.trim() == saved) return
        viewModelScope.launch {
            container.questionRepository.updateTitle(questionId, draft.trim())
        }
    }

    // ===== 备注：看题时直接改，收起时落库 =====

    private var noteDraft: String? = null

    /** 编辑中的临时值：优先用草稿，否则用库里已存的。 */
    fun currentNote(): String = noteDraft
        ?: uiState.value.question?.note.orEmpty()

    fun setNote(value: String) {
        noteDraft = value
    }

    fun saveNote() {
        val draft = noteDraft ?: return
        val saved = uiState.value.question?.note.orEmpty()
        noteDraft = null
        if (draft == saved) return
        viewModelScope.launch {
            container.questionRepository.updateNote(questionId, draft)
        }
    }

    // ===== 变式题（F1 举一反三）：基于本题出一道同知识点的新题 =====

    private val _variant = MutableStateFlow(VariantState())
    val variantState: StateFlow<VariantState> = _variant.asStateFlow()

    /**
     * 入口：状态为空时发起生成，已有结果/失败时只把对话框打开（由 [variantState].visible 驱动）。
     * 生成中重复点击直接忽略——LLM 调用没有「暂停重来」，连点只会多烧一次调用。
     */
    fun openVariant() {
        val state = _variant.value
        if (state.generating || state.draft != null || state.error != null) return
        generateVariant()
    }

    fun generateVariant() {
        if (_variant.value.generating) return
        val question = uiState.value.question ?: return
        viewModelScope.launch {
            _variant.value = VariantState(generating = true)
            val snapshot = container.settingsStore.snapshotNow()
            val profile = snapshot.activeProfile
            if (profile == null || !profile.isConfigured()) {
                _variant.value = VariantState(error = "请先到设置填写大模型 API Key")
                return@launch
            }
            val raw = container.llmClient.refineJson(
                profile = profile,
                systemPrompt = VariantPrompts.system(),
                userPrompt = VariantPrompts.user(question, subjectName = uiState.value.subjectName),
                imageFile = null,
                longEdgePx = 0,
                attachImage = false
            )
            when (raw) {
                is ApiResult.Success -> {
                    val dto = VariantQuestionParser.parse(raw.data)
                    _variant.value = if (dto == null) {
                        VariantState(error = "AI 没能生成有效的变式题，请重试")
                    } else {
                        VariantState(draft = dto)
                    }
                }
                is ApiResult.Failure -> {
                    _variant.value = VariantState(
                        error = raw.error.serverMessage.ifBlank { "生成失败，请检查网络后重试" }
                    )
                }
            }
        }
    }

    /** 把当前变式存为错题并关联本题；[onSaved] 带新题 id（UI 用来跳转）。 */
    fun saveVariant(onSaved: (Long) -> Unit) {
        val dto = _variant.value.draft ?: return
        val question = uiState.value.question ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // DTO → QuestionDraft：复用 save() 的全部入库规则（学科解析/标签/排期初值）。
            // errorReason 用默认 OTHER——变式是「还没做的新题」，错因留给用户做完再标。
            val draft = QuestionDraft(
                imagePath = "",
                mineruMarkdown = "",
                subjectName = dto.subject,
                stem = dto.stem,
                options = dto.options.map { Option(it.label, it.text) },
                answer = dto.answer,
                analysis = dto.analysis,
                title = dto.title.trim().ifBlank {
                    question.title.trim().takeIf { it.isNotBlank() }?.let { "变式 · $it" } ?: ""
                },
                knowledgePoints = dto.knowledge_points,
                difficulty = dto.difficulty
            )
            val id = container.questionRepository.saveVariant(question, draft, now)
            _variant.value = VariantState()
            onSaved(id)
        }
    }

    /** 关闭变式弹层。生成中不允许关（没有取消 LLM 的路径），其余状态直接清空。 */
    fun dismissVariant() {
        if (_variant.value.generating) return
        _variant.value = VariantState()
    }
}

/**
 * 辅助图的系统提示词（v0.0.57）：直接让模型写 SVG，不再经生图 API。
 *
 * 约束都来自真实翻车：模型爱写解释性文字（「下面是示意图」）、爱用
 * Markdown 围栏、爱省略 viewBox、爱在 SVG 里塞外链图片。逐条禁掉，
 * 剩下的就是一段能直接被 SvgSanitizer / AndroidSVG 吃进去的图。
 */
private const val AUX_SVG_SYSTEM_PROMPT = """你是教学绘图助手。根据用户给的题目，画一张**精确**的示意图。
只输出一个完整的 SVG 文档，不要任何解释、不要 Markdown 代码围栏、不要前后缀。
要求：
1. 根标签必须带 viewBox="0 0 400 300"，不要写 width/height 百分比。
2. 只用基本元素：<line> <circle> <path> <polygon> <polyline> <rect> <text>。
3. 不引用外部图片/字体/脚本，不用 <foreignObject>。
4. 题目里出现的数字、字母、坐标必须原样标注，不要改数。
5. 线条用 stroke，填充用 fill，文字用 <text> 并标在对应位置。
6. 白色/浅色背景假设下可读：线条用深色（如 #333），不要纯白线。
如果这张题确实不需要图（例如纯代数运算），输出一个空的 <svg viewBox="0 0 400 300"></svg>。"""