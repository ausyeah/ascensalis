package com.mistakebook.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.data.prefs.ReasoningEffort
import com.mistakebook.data.prefs.SettingsSnapshot
import com.mistakebook.data.prefs.SettingsStore
import com.mistakebook.data.prefs.ThemeMode
import com.mistakebook.di.AppContainer
import com.mistakebook.net.ApiResult
import com.mistakebook.net.ApiError
import com.mistakebook.net.errorOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 一次「测试连接」的结果：给用户看的话 + 是否成功。 */
data class TestOutcome(val message: String, val ok: Boolean)

data class SettingsUiState(
    val snapshot: SettingsSnapshot = SettingsSnapshot(),
    val testingMineru: Boolean = false,

    /**
     * 各自归属的测试结果，**刻意不合并成一个字段**。
     *
     * 早先只有一对 `testResult` / `testOk`，MinerU 和大模型两个测试共用——
     * 于是点 MinerU 的测试按钮，结果却渲染在大模型那一组的下方
     * （两组在页面上相距好几屏，用户根本对不上是哪个按钮的结果）。
     * 而且两个测试还会互相覆盖：先测完大模型再测 MinerU，大模型的结果被冲掉。
     */
    val mineruTestResult: String? = null,
    val mineruTestOk: Boolean = false,

    /**
     * 大模型的测试结果，**按配置 id 归属**。
     *
     * 之前这里是单个 `llmTestResult`，而 `ProfileRow` 在
     * `snapshot.llmProfiles.forEach` 里给**每一个**配置都传了它——
     * 于是配了第二个模型之后，测第一个的结果会同时显示在两个配置下面，
     * 点第二个的「测试」按钮，第二个的转圈也同时出现在两个配置上。
     * 用户报告的「测试结果不对不上按钮」就是这个。
     *
     * 用 map 而不是「单条 + id 标记」：每个配置各自留着自己最近一次的结果，
     * 测完 A 再测 B 不会把 A 的抹掉（那正是最初拆字段要解决的问题）。
     */
    val llmTestResults: Map<String, TestOutcome> = emptyMap(),

    /** 正在测试的配置 id；`null` 表示当前没有测试在进行。 */
    val llmTestingProfileId: String? = null,
    val loadingModels: Boolean = false,
    val models: List<String> = emptyList(),
    val modelsError: String? = null,
    val pickedModel: String? = null,
    val backupMessage: String? = null
) {
    val activeProfile: LlmProfile? get() = snapshot.activeProfile

    /** 有测试在进行（任意配置）。 */
    val testingLlm: Boolean get() = llmTestingProfileId != null

    /** 某个配置自己的测试结果。 */
    fun llmOutcome(profileId: String): TestOutcome? = llmTestResults[profileId]

    /** **只有**正在测试的那一行该转圈。 */
    fun isTestingLlm(profileId: String): Boolean = llmTestingProfileId == profileId
}

// 测试连接的瞬时状态
private data class TestState(
    val flags: TestFlags,
    val llmTestingId: String?,
    val llmOutcomes: Map<String, TestOutcome>
) {
    val testingMineru: Boolean get() = flags.testingMineru
    val loadingModels: Boolean get() = flags.loadingModels
    val mineruResult: String? get() = flags.mineruResult
    val mineruOk: Boolean get() = flags.mineruOk
}

/** MinerU 的测试标志与结果 + 拉模型列表的标志。 */
private data class TestFlags(
    val testingMineru: Boolean = false,
    val loadingModels: Boolean = false,
    val mineruResult: String? = null,
    val mineruOk: Boolean = false
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val store: SettingsStore = container.settingsStore

    private val testingMineru = MutableStateFlow(false)
    private val loadingModels = MutableStateFlow(false)
    private val mineruTestResult = MutableStateFlow<String?>(null)
    private val mineruTestOk = MutableStateFlow(false)
    private val llmTestingProfileId = MutableStateFlow<String?>(null)
    private val llmTestResults = MutableStateFlow<Map<String, TestOutcome>>(emptyMap())
    private val models = MutableStateFlow<List<String>>(emptyList())
    private val modelsError = MutableStateFlow<String?>(null)
    private val pickedModel = MutableStateFlow<String?>(null)
    private val backupMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        store.settings,
        // combine 的类型化重载最多到 5 个参数，而这里有 7 个。
        // 套一层：两个测试各自合成一个，再合成 TestState。
        combine(
            combine(
                testingMineru, loadingModels,
                mineruTestResult, mineruTestOk
            ) { a, b, c, d -> TestFlags(a, b, c, d) },
            combine(llmTestingProfileId, llmTestResults) { id, map -> id to map }
        ) { flags, llm -> TestState(flags, llm.first, llm.second) },
        combine(models, modelsError, pickedModel) { list, error, picked ->
            Triple(list, error, picked)
        },
        backupMessage
    ) { snapshot, test, modelsPair, backup ->
        SettingsUiState(
            snapshot = snapshot,
            testingMineru = test.testingMineru,
            loadingModels = test.loadingModels,
            mineruTestResult = test.mineruResult,
            mineruTestOk = test.mineruOk,
            llmTestResults = test.llmOutcomes,
            llmTestingProfileId = test.llmTestingId,
            models = modelsPair.first,
            modelsError = modelsPair.second,
            pickedModel = modelsPair.third,
            backupMessage = backup
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setMineruKey(value: String) = viewModelScope.launch {
        // 改了 Key 之后，上一次的测试结果就不再成立了（可能刚刚从错的改成对的，
        // 也可能反过来）。留着旧结果会让人以为新 Key 也测过了。
        clearMineruTestResult()
        store.setMineruKey(value)
    }

    fun saveActiveProfile(profile: LlmProfile) = viewModelScope.launch {
        val current = uiState.value.activeProfile ?: return@launch
        // 配置改了，之前那次测试的结论就不再代表当前配置
        clearLlmTestResult(current.id)
        store.upsertProfile(profile.copy(id = current.id))
    }

    fun addProfile(profile: LlmProfile) = viewModelScope.launch {
        store.upsertProfile(profile)
    }

    fun deleteProfile(id: String) = viewModelScope.launch {
        // 连同它的测试结论一起删，否则 id 被复用时会显示上一条配置的结果
        clearLlmTestResult(id)
        store.deleteProfile(id)
    }

    fun selectProfile(id: String) = viewModelScope.launch { store.setActiveProfileId(id) }

    // setMineruModelVersion / setOcrLanguage / setForceOcr 已随设置项移除。
    // 模型版本固定 vlm、语言固定 ch、强制 OCR 固定开启，写在 SettingsSnapshot 里。

    fun setAttachImage(value: Boolean) = viewModelScope.launch { store.setAttachOriginalImage(value) }

    fun setPrintIncludeImage(value: Boolean) = viewModelScope.launch {
        store.setPrintIncludeImage(value)
    }

    fun setPrintShowAnswer(value: Boolean) = viewModelScope.launch {
        store.setPrintShowAnswer(value)
    }

    fun setPrintBlankRedo(value: Boolean) = viewModelScope.launch { store.setPrintBlankRedo(value) }

    fun setPrintBlankHeight(value: Int) = viewModelScope.launch {
        store.setPrintBlankHeightPt(value)
    }

    fun setReminderEnabled(value: Boolean) = viewModelScope.launch {
        store.setReviewReminderEnabled(value)
    }

    fun setReminderTime(hour: Int, minute: Int) = viewModelScope.launch {
        store.setReminderTime(hour, minute)
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        store.setThemeMode(mode)
    }

    /** 设置对话思考强度（下一次请求生效）。 */
    fun setReasoningEffort(effort: ReasoningEffort) = viewModelScope.launch {
        store.setReasoningEffort(effort)
    }

    fun testMineru() {
        viewModelScope.launch {
            testingMineru.value = true
            val key = store.snapshotNow().mineruKey
            val result = container.mineruClient.testConnection(key)
            testingMineru.value = false
            mineruTestOk.value = result is com.mistakebook.net.ApiResult.Success
            mineruTestResult.value = if (result is com.mistakebook.net.ApiResult.Success) {
                SUCCESS
            } else {
                result.errorOrNull()?.serverMessage ?: FAILED
            }
        }
    }

    /**
     * 测试连接。
     *
     * ## 关键：用调用方传来的**当前输入**，不是已保存的配置
     * 原来读 `store.snapshotNow().activeProfile`，于是「填了 URL 和 API Key
     * 但还没按保存」时，测试用的是旧值（通常是空），必然失败。
     * 用户看到的就是「明明填了却说连不上」。
     *
     * @param draft 当前输入框里的内容
     * @return 毫秒数，UI 用来显示响应时间
     */
    fun testLlm(draft: LlmProfile) {
        viewModelScope.launch {
            llmTestingProfileId.value = draft.id
            // 只清**自己**那条：别的配置刚测出来的结果不该被这次点击抹掉
            clearLlmTestResult(draft.id)
            // 至少要有域名和 Key 才能测；模型名可以为空（测的就是「能不能拿到模型」）
            if (draft.baseUrl.isBlank() || draft.apiKey.isBlank()) {
                llmTestingProfileId.value = null
                publishLlmResult(draft.id, "请先填写接口地址和 API Key", ok = false)
                return@launch
            }
            val probe = draft.normalized()
            val started = System.nanoTime()
            // 第一步：Key 与域名是否通（拉模型列表）
            val listResult = container.llmClient.listModels(probe)
            val listMs = elapsedMs(started)
            val modelCount = (listResult as? ApiResult.Success)?.data?.size ?: 0
            if (listResult is ApiResult.Failure) {
                llmTestingProfileId.value = null
                publishLlmResult(
                    draft.id,
                    describeError(listResult.error, "连接失败") + "（${listMs}ms）",
                    ok = false
                )
                return@launch
            }
            // 第二步：模型本身能否真正出字。模型名为空时跳过——
            // 用户可能就是想先确认「接口通不通」，还没想好用哪个模型
            val probeStarted = System.nanoTime()
            val probeOutcome: ApiResult<String> = if (probe.model.isBlank()) {
                ApiResult.Success("")
            } else {
                container.llmClient.probeModel(probe)
            }
            val probeMs = elapsedMs(probeStarted)
            llmTestingProfileId.value = null

            val message = when {
                probe.model.isBlank() -> "接口可达 · 响应 ${listMs}ms · 共 $modelCount 个可用模型" +
                    "（填上模型名可再测首字响应）"

                probeOutcome is ApiResult.Success ->
                    "连接正常 · 首字响应 ${probeMs}ms · 模型 ${probe.model} · 共 $modelCount 个可用模型"

                else -> "接口可达（${listMs}ms，已拉到 $modelCount 个模型），但模型 ${probe.model} " +
                    describeError((probeOutcome as ApiResult.Failure).error, "无响应")
            }
            publishLlmResult(draft.id, message, probeOutcome is ApiResult.Success && probe.model.isNotBlank())
        }
    }

    /**
     * 记录某个配置自己的测试结果。
     *
     * 单独抽出来：结果**必须**跟着 profile id 走。
     * 之前是一个全局字段，配了第二个模型之后两边会显示同一份结果。
     */
    private fun publishLlmResult(profileId: String, message: String, ok: Boolean) {
        llmTestResults.value = llmTestResults.value + (profileId to TestOutcome(message, ok))
    }

    /**
     * 拉取可用模型列表。
     *
     * 同样用**当前输入**而不是已保存值——这正是「填了 URL 和 API 就该能拉列表」
     * 成立的前提。模型名可以为空：拉列表的目的就是拿到模型名。
     */
    fun fetchModels(draft: LlmProfile, onReady: (List<String>) -> Unit = {}) {
        viewModelScope.launch {
            loadingModels.value = true
            modelsError.value = null
            if (draft.baseUrl.isBlank() || draft.apiKey.isBlank()) {
                loadingModels.value = false
                modelsError.value = "请先填写接口地址和 API Key"
                return@launch
            }
            val started = System.nanoTime()
            when (val result = container.llmClient.listModels(draft.normalized())) {
                is ApiResult.Success -> {
                    val list = result.data
                    models.value = list
                    loadingModels.value = false
                    if (list.isEmpty()) {
                        modelsError.value = "接口可达（${elapsedMs(started)}ms）但没返回任何模型"
                    } else {
                        // 直接把选择弹窗打开：用户点这个按钮就是想选模型，
                        // 让他再点一次「可用模型」纯属多余
                        onReady(list)
                    }
                }

                is ApiResult.Failure -> {
                    models.value = emptyList()
                    loadingModels.value = false
                    modelsError.value = describeError(result.error, "获取模型列表失败")
                }
            }
        }
    }

    fun consumeModels() {
        models.value = emptyList()
        modelsError.value = null
    }

    /** 从模型列表里选一个，回填到当前配置的模型输入框。 */
    fun pickModel(model: String) {
        pickedModel.value = model
    }

    fun consumePickedModel() {
        pickedModel.value = null
    }

    private fun describeError(error: ApiError, prefix: String): String {
        val detail = error.serverMessage.takeIf { it.isNotBlank() }
        return if (detail != null) "$prefix：$detail" else prefix
    }

    /**
     * 毫秒耗时。
     * 用 [System.nanoTime] 而不是 `currentTimeMillis`：后者受系统时钟调整影响，
     * 用户改个系统时间就可能算出负的响应时间。
     */
    private fun elapsedMs(startNanos: Long): Long =
        (System.nanoTime() - startNanos) / 1_000_000

    /** 改动 Key / URL 后，旧的测试结论不再成立。 */
    fun clearMineruTestResult() {
        mineruTestResult.value = null
        mineruTestOk.value = false
    }

    /** 清掉**某个配置**的测试结论，别的配置不受影响。 */
    fun clearLlmTestResult(profileId: String) {
        llmTestResults.value = llmTestResults.value - profileId
    }

    fun clearTrash() {
        viewModelScope.launch {
            val trash = container.questionRepository.listDeletedBefore(0)
            trash.forEach { question ->
                container.questionRepository.hardDelete(listOf(question.id))
                container.files.deleteRecursively(container.files.questionDir(question.id))
            }
            backupMessage.value = if (trash.isEmpty()) "回收站已经是空的" else "已清空回收站"
        }
    }

    fun consumeBackupMessage() {
        backupMessage.value = null
    }

    companion object {
        const val SUCCESS = "连接成功"
        const val FAILED = "连接失败，请检查 Key 与网络"
        const val NO_PROFILE = "尚未配置大模型接入"
    }
}
