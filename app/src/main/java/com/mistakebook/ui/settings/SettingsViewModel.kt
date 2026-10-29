package com.mistakebook.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.data.prefs.NetworkMode
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
    val modelsError: String? = null,

    /**
     * **按配置 id 归���**的已拉取模型列表。
     *
     * 用户诉求：「你反正获取到了这个模型列表，为什么不让我自己选择勾选与否
     * 来确定这个配置可用模型呢」。
     *
     * 所以拉到的列表不能只存在一个全局变量里——配了第二套配置时，
     * 列表必须跟着「是哪套配置拉的」走，否则 A 的模型会出现在 B 的勾选区。
     */
    val fetchedModelsByProfile: Map<String, List<String>> = emptyMap(),
    /** 每个模型的来源 key 标签（「主 Key」/「Key 2」…），勾选区用来标注。 */
    val modelSourcesByProfile: Map<String, Map<String, String>> = emptyMap(),
    val backupMessage: String? = null,

    /** 最近一次查到的 Tavily 额度；null 表示还没查过或查失败。 */
    val tavilyUsage: com.mistakebook.data.chat.TavilySearch.Usage? = null,
    /** 正在查询 Tavily 额度。 */
    val tavilyUsageLoading: Boolean = false,
    /** 查询失败的说明（成功或还没查时为 null）。 */
    val tavilyUsageError: String? = null
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
/** 拉模型列表相关的一组状态（合并进 combine，避免超过 5 个参数的上限）。 */
private data class ModelsState(
    val error: String?,
    val byProfile: Map<String, List<String>>,
    val sourcesByProfile: Map<String, Map<String, String>>
)

private data class TestState(
    val flags: TestFlags,
    val llmTestingId: String?,
    val llmOutcomes: Map<String, TestOutcome>
) {    val testingMineru: Boolean get() = flags.testingMineru
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

/**
 * Tavily 额度查询的瞬时状态（v0.0.57）。
 *
 * 三个字段必须**一起**在 combine 里推进，否则会出现「转圈还没停 / 结果已显示」
 * 或者「错误提示盖着上一次的成功结果」这类不一致画面。
 */
private data class TavilyUsageState(
    val usage: com.mistakebook.data.chat.TavilySearch.Usage? = null,
    val loading: Boolean = false,
    val error: String? = null
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val store: SettingsStore = container.settingsStore

    private val testingMineru = MutableStateFlow(false)
    private val loadingModels = MutableStateFlow(false)
    private val mineruTestResult = MutableStateFlow<String?>(null)
    private val mineruTestOk = MutableStateFlow(false)
    private val llmTestingProfileId = MutableStateFlow<String?>(null)
    private val llmTestResults = MutableStateFlow<Map<String, TestOutcome>>(emptyMap())
    private val fetchedModelsByProfile = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    /** 每个模型是从哪把 key 拉到的（profileId → model → 「主 Key」/「Key 2」）。 */
    private val modelSourcesByProfile = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    private val modelsError = MutableStateFlow<String?>(null)
    private val backupMessage = MutableStateFlow<String?>(null)
    private val tavilyUsageState = MutableStateFlow(TavilyUsageState())

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
        combine(
            modelsError, fetchedModelsByProfile, modelSourcesByProfile
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            ModelsState(
                error = values[0] as String?,
                byProfile = values[1] as Map<String, List<String>>,
                sourcesByProfile = values[2] as Map<String, Map<String, String>>
            )
        },
        backupMessage,
        tavilyUsageState
    ) { snapshot, test, modelsPair, backup, tavilyUsage ->
        SettingsUiState(
            snapshot = snapshot,
            testingMineru = test.testingMineru,
            loadingModels = test.loadingModels,
            mineruTestResult = test.mineruResult,
            mineruTestOk = test.mineruOk,
            llmTestResults = test.llmOutcomes,
            llmTestingProfileId = test.llmTestingId,
            modelsError = modelsPair.error,
            fetchedModelsByProfile = modelsPair.byProfile,
            modelSourcesByProfile = modelsPair.sourcesByProfile,
            backupMessage = backup,
            tavilyUsage = tavilyUsage.usage,
            tavilyUsageLoading = tavilyUsage.loading,
            tavilyUsageError = tavilyUsage.error
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

    /**
     * 保存**指定**那一套配置（按草稿自带的 id）。
     *
     * 展开式交互下用户可以编辑任意一套配置（不只是当前使用的那套），
     * 此时若还走 [saveActiveProfile]，B 的草稿会被存进 A 的 id——
     * 点开 B 改一堆，保存后 A 变了、B 原封不动。
     */
    fun saveProfile(profile: LlmProfile) = viewModelScope.launch {
        clearLlmTestResult(profile.id)
        store.upsertProfile(profile)
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

    // ===== 生图渠道（多套，可切换） =====

    fun saveImageProfile(profile: com.mistakebook.data.prefs.ImageProfile) =
        viewModelScope.launch { store.upsertImageProfile(profile) }

    fun addImageProfile(profile: com.mistakebook.data.prefs.ImageProfile) =
        viewModelScope.launch { store.upsertImageProfile(profile) }

    fun deleteImageProfile(id: String) = viewModelScope.launch { store.deleteImageProfile(id) }

    fun selectImageProfile(id: String) = viewModelScope.launch { store.setActiveImageProfileId(id) }

    /** 指定专门的识图模型（传 null 取消指定，回到默认识图路径）。 */
    fun setVisionProfileId(id: String?) = viewModelScope.launch { store.setVisionProfileId(id) }

    // 识图模型：独立多套，字段全由用户填（含模型名）
    fun saveVisionProfile(profile: com.mistakebook.data.prefs.LlmProfile) =
        viewModelScope.launch { store.upsertVisionProfile(profile) }

    fun addVisionProfile(profile: com.mistakebook.data.prefs.LlmProfile) =
        viewModelScope.launch { store.upsertVisionProfile(profile) }

    fun deleteVisionProfile(id: String) = viewModelScope.launch { store.deleteVisionProfile(id) }

    fun selectVisionProfile(id: String) = viewModelScope.launch { store.setActiveVisionProfileId(id) }

    // setMineruModelVersion / setOcrLanguage / setForceOcr 已随设置项移除。
    // 模型版本固定 vlm、语言固定 ch、强制 OCR 固定开启，写在 SettingsSnapshot 里。

    fun setAttachImage(value: Boolean) = viewModelScope.launch { store.setAttachOriginalImage(value) }

    /** 切换题目识别方式（MinerU / 大模型 / 自动）。 */
    fun setRecognitionMode(value: com.mistakebook.data.prefs.RecognitionMode) =
        viewModelScope.launch { store.setRecognitionMode(value) }

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

    // ===== 番茄钟（T2 到点闭环） =====

    fun setPomodoroAutoRecord(value: Boolean) = viewModelScope.launch {
        store.setPomodoroAutoRecord(value)
    }

    fun setPomodoroBreakLoop(value: Boolean) = viewModelScope.launch {
        store.setPomodoroBreakLoop(value)
    }

    fun setPomodoroBreakMinutes(value: Int) = viewModelScope.launch {
        store.setPomodoroBreakMinutes(value)
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        store.setThemeMode(mode)
    }

    /** 设置对话思考强度（下一次请求生效）。 */
    fun setReasoningEffort(effort: ReasoningEffort) = viewModelScope.launch {
        store.setReasoningEffort(effort)
    }

    // ---- 联网搜索（v0.0.43）：配置从模型面板挪进设置页 ----

    /** 开关联网搜索（下一次请求生效）。 */
    fun setChatSearchEnabled(value: Boolean) = viewModelScope.launch {
        store.setChatSearchEnabled(value)
    }

    /** 切换搜索出口（DeepSeek 官方 Search API / Tavily）。 */
    fun setChatSearchProvider(provider: com.mistakebook.data.chat.ChatSearchProvider) =
        viewModelScope.launch {
            store.setChatSearchProvider(provider)
        }

    /** 保存 DeepSeek 搜索服务专用 Key（留空 = 复用 DeepSeek 模型 Key）。 */
    fun setDeepseekSearchApiKey(key: String) = viewModelScope.launch {
        store.setDeepseekSearchApiKey(key)
    }

    /** 保存 Tavily API Key（去首尾空白）。 */
    fun setTavilyApiKey(key: String) = viewModelScope.launch {
        store.setTavilyApiKey(key)
        // Key 变了，上一次查到的额度不再属于这把 Key——清掉，
        // 否则用户换 Key 后会看到一个「别的 Key 的额度」，还以为新 Key 也能用。
        tavilyUsageState.value = TavilyUsageState()
    }

    /**
     * 查询 Tavily 额度（v0.0.57）：`GET https://api.tavily.com/usage`。
     *
     * ## 为什么做成手动按钮而不是进页面自动查
     *
     * 1. 额度查询也要走一次网络，进设置页就发请求会在离线时无谓地失败；
     * 2. 用户真正关心额度的时点是「搜着搜着不搜了」，那时点一下即可，
     *    不需要每秒轮询；
     * 3. 官方对 `/usage` 有频率限制（文档写的是每分钟 10 次），
     *    自动查容易触发。
     *
     * 失败时**保留上一次的成功结果**，只在下面挂一条错误提示——
     * 「这次没查成」不等于「额度变了」，没理由把已知信息擦掉。
     */
    fun queryTavilyUsage() {
        viewModelScope.launch {
            val key = store.snapshotNow().tavilyApiKey
            if (key.isBlank()) {
                tavilyUsageState.value = TavilyUsageState(
                    usage = tavilyUsageState.value.usage,
                    loading = false,
                    error = "先填写 Tavily API Key 再查询额度"
                )
                return@launch
            }
            tavilyUsageState.value = tavilyUsageState.value.copy(loading = true, error = null)
            val result = com.mistakebook.data.chat.TavilySearch.fetchUsage(
                container.searchHttpClient,
                key
            )
            tavilyUsageState.value = when (result) {
                is com.mistakebook.data.chat.UsageResult.Ok ->
                    TavilyUsageState(usage = result.usage, loading = false, error = null)

                is com.mistakebook.data.chat.UsageResult.Failed ->
                    // 失败时保留上一次的成功结果（见方法注释），只把原因显示出来。
                    TavilyUsageState(
                        usage = tavilyUsageState.value.usage,
                        loading = false,
                        error = result.message
                    )
            }
        }
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
     * ## 遍历**全部 key**，不是只问主 key
     *
     * 同样用**当前输入**而不是已保存值——这正是「填了 URL 和 API 就该能拉列表」
     * 成立的前提。模型名可以为空：拉列表的目的就是拿到模型名。
     *
     * 关键点：中转站一个 URL 下挂着多把 key，**每把 key 能看到的模型不一样**
     * （额度与授权按 key 分开）。只拿主 key 去问，列出的就只是主 key 那一份，
     * 其余 key 供应的模型一个都看不见——也就无从勾选，更谈不上调用。
     *
     * 所以逐把 key 探测一次，把结果**合并去重**成一个完整模型池；
     * 同时生成两张表：
     * - **路由表**（model -> keyId）：主 key 看得见的模型一律走主 key，
     *   只有附加 key 看得见的才绑到那把 key。用户不填、也看不到这张表，
     *   保存后 [LlmProfile.resolveKey] 按它找到正确的 key；
     * - **来源标注**（model -> 「主 Key、Key 2」）：给用户看这个模型
     *   有几把钥匙能开。
     *
     * 两张表合起来保证：**合并池里的每一个模型都一定有 key 能调它**——
     * 这正是「选好之后都能够调用」的承诺。
     */
    fun fetchModels(
        draft: LlmProfile,
        onReady: (List<String>, Map<String, String>) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            loadingModels.value = true
            modelsError.value = null
            val keys = draft.normalized().allKeys().filter { it.isUsable() }
            if (draft.baseUrl.isBlank() || keys.isEmpty()) {
                loadingModels.value = false
                modelsError.value = "请先填写接口地址和 API Key"
                return@launch
            }
            val started = System.nanoTime()

            // keyId -> 这把 key 看到的模型
            val seen = LinkedHashMap<String, List<String>>()
            val failures = mutableListOf<String>()
            keys.forEach { entry ->
                when (val r = container.llmClient.listModels(draft.normalized(), entry.key)) {
                    is ApiResult.Success -> seen[entry.id] = r.data
                    is ApiResult.Failure ->
                        failures += describeError(r.error, "").trim().removePrefix("：")
                        .ifBlank { r.error.kind.name }
                }
            }

            val merged = LinkedHashSet<String>()
            seen.values.forEach { merged += it }

            if (merged.isEmpty()) {
                fetchedModelsByProfile.value = fetchedModelsByProfile.value - draft.id
                modelSourcesByProfile.value = modelSourcesByProfile.value - draft.id
                loadingModels.value = false
                modelsError.value = if (failures.isEmpty()) {
                    "接口可达（${elapsedMs(started)}ms）但没返回任何模型"
                } else {
                    "获取模型列表失败：${failures.distinct().joinToString("；")}"
                }
                return@launch
            }

            val mainModels = seen[LlmProfile.MAIN_KEY_ID].orEmpty().toSet()

            // keyId -> 展示标签（「主 Key」/「Key 2」）
            val labelOfKey = keys.associate { entry ->
                entry.id to if (entry.id == LlmProfile.MAIN_KEY_ID) "主 Key"
                else "Key ${keys.indexOf(entry) + 1}"
            }

            // model -> 看得见它的全部 key 标签（灰色小字，展示用）
            val sources = mutableMapOf<String, String>()
            merged.forEach { m ->
                val labels = keys.filter { seen[it.id]?.contains(m) == true }
                    .mapNotNull { labelOfKey[it.id] }
                if (labels.isNotEmpty()) sources[m] = labels.joinToString("、")
            }

            // model -> keyId 路由表：主 key 优先，只有主 key 看不见的才绑附加 key
            val bindings = mutableMapOf<String, String>()
            merged.forEach { m ->
                if (m in mainModels) {
                    bindings[m] = LlmProfile.MAIN_KEY_ID
                } else {
                    val extra = keys.firstOrNull {
                        it.id != LlmProfile.MAIN_KEY_ID && seen[it.id]?.contains(m) == true
                    }
                    bindings[m] = extra?.id ?: LlmProfile.MAIN_KEY_ID
                }
            }

            val list = merged.toList()
            fetchedModelsByProfile.value = fetchedModelsByProfile.value + (draft.id to list)
            modelSourcesByProfile.value = modelSourcesByProfile.value + (draft.id to sources)
            loadingModels.value = false
            onReady(list, bindings)
        }
    }

    fun consumeModels() {
        modelsError.value = null
    }

    /** 设置网络出口模式（系统代理 / 直连 / 自动）。 */
    fun setNetworkMode(mode: NetworkMode) = viewModelScope.launch {
        store.setNetworkMode(mode)
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
