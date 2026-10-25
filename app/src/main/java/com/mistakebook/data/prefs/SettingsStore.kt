package com.mistakebook.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mistakebook.BuildConfig
import com.mistakebook.data.chat.ChatSearchProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mistakebook_settings"
)

/**
 * 设置仓储。
 *
 * - 非敏感项（Base URL、模型名、打印默认、提醒开关）走 DataStore Preferences；
 * - 敏感项（MinerU Key、大模型 Key、整套接入配置 JSON）走 EncryptedSharedPreferences；
 * - 首次运行且两项都为空时，用 debug 的 buildConfig 预填值兜底（release 恒为空）。
 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext

    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            SECURE_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as SharedPreferences
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 密文部分的版本号。
     *
     * ## 为什么需要它
     * `settings` 这个 Flow 原本只由 DataStore 驱动，但**密钥类字段存在
     * EncryptedSharedPreferences 里**。写 SharedPreferences 不会通知 DataStore，
     * 于是 `setMineruKey()` 写完之后快照永远不会重发：
     * 受控的 `TextField` 拿到的还是旧值（空串），每敲一个字就被弹回去——
     * 表现就是「输入框打不进字」。
     *
     * 以前这个洞被 buildConfig 预填掩盖了：框里本来就有值，用户从不需要输入。
     * 公开分发包改成不预填密钥后，它立刻暴露出来。
     *
     * 用 `Flow<Long>` 而不是 SharedPreferences.OnSharedPreferenceChangeListener：
     * 后者拿不到「改动来自哪一次写入」，容易和 combine 出的初始值打架；
     * 版本号是显式单调递增，语义清楚，也不依赖回调线程。
     */
    private val secureVersion = MutableStateFlow(0L)

    val settings: Flow<SettingsSnapshot> = combine(
        appContext.settingsDataStore.data,
        secureVersion
    ) { prefs, _ -> snapshot(prefs) }

    suspend fun snapshotNow(): SettingsSnapshot =
        snapshot(appContext.settingsDataStore.data.first())

    private fun snapshot(prefs: Preferences): SettingsSnapshot {
        val profiles = loadProfiles()
        val activeId = prefs[KEY_ACTIVE_PROFILE] ?: profiles.firstOrNull()?.id
        // 生图渠道：与对话配置同构——JSON 密文存 key，激活 id 走 DataStore。
        val imageProfiles = loadImageProfiles()
        val activeImageId = prefs[KEY_ACTIVE_IMAGE_PROFILE] ?: imageProfiles.firstOrNull()?.id
        return SettingsSnapshot(
            // 占位符不能当成真 Key，否则「已配置」判断会误判为通过
            mineruKey = readSecure(SECURE_MINERU_KEY).ifBlank {
                if (BuildConfigKeys.isPlaceholder(BuildConfig.MINERU_API_KEY)) "" else BuildConfig.MINERU_API_KEY
            },
            llmProfiles = profiles,
            activeProfileId = activeId,
            imageProfiles = imageProfiles,
            activeImageProfileId = activeImageId,
            themeMode = ThemeMode.fromKey(prefs[KEY_THEME_MODE]),
            networkMode = NetworkMode.fromKey(prefs[KEY_NETWORK_MODE]),
            reasoningEffort = ReasoningEffort.fromKey(prefs[KEY_REASONING_EFFORT]),
            // mineruModelVersion / ocrLanguage / forceOcr 已固定，不再从 prefs 读。
            // 旧版本存下的值会被自然忽略——它们不再是 SettingsSnapshot 的构造参数。
            attachOriginalImage = prefs[KEY_ATTACH_IMAGE] ?: true,
            imageLongEdgePx = prefs[KEY_IMAGE_LONG_EDGE] ?: 1280,
            // 识别方式默认 AUTO：老用户没存过这个键，行为是「MinerU 优先、
            // 不可用落大模型」——比从前的「没 Key 就失败」多一条活路，
            // 配了 MinerU 的用户毫无感知。
            recognitionMode = RecognitionMode.fromKey(prefs[KEY_RECOGNITION_MODE]),
            // 默认不带原图：打印错题本是给手写重做用的，贴照片反而挤掉作答空间。
            // 需要时可到设置页打开。
            printIncludeImage = prefs[KEY_PRINT_IMAGE] ?: false,
            printShowAnswer = prefs[KEY_PRINT_ANSWER] ?: false,
            printBlankRedo = prefs[KEY_PRINT_BLANK] ?: true,
            printBlankHeightPt = prefs[KEY_PRINT_BLANK_PT] ?: 100,
            ocrStrength = prefs[KEY_OCR_STRENGTH] ?: legacyStrength(prefs),
            reviewReminderEnabled = prefs[KEY_REMINDER_ON] ?: true,
            reminderHour = prefs[KEY_REMINDER_HOUR] ?: 20,
            reminderMinute = prefs[KEY_REMINDER_MINUTE] ?: 0,
            wordbookDailyGoal = (prefs[KEY_WORDBOOK_DAILY_GOAL] ?: DEFAULT_WORDBOOK_DAILY_GOAL)
                .coerceIn(MIN_WORDBOOK_DAILY_GOAL, MAX_WORDBOOK_DAILY_GOAL),
            wordbookReminderEnabled = prefs[KEY_WORDBOOK_REMINDER] ?: true,
            chatSearchEnabled = prefs[KEY_CHAT_SEARCH_ENABLED] ?: true,
            chatSearchProvider = ChatSearchProvider.fromKey(prefs[KEY_CHAT_SEARCH_PROVIDER]),
            deepseekSearchApiKey = prefs[KEY_DEEPSEEK_SEARCH_API_KEY].orEmpty(),
            tavilyApiKey = prefs[KEY_TAVILY_API_KEY].orEmpty(),
            pomodoroAutoRecord = prefs[KEY_POMODORO_AUTO_RECORD] ?: true,
            pomodoroBreakLoop = prefs[KEY_POMODORO_BREAK_LOOP] ?: false,
            pomodoroBreakMinutes = prefs[KEY_POMODORO_BREAK_MINUTES] ?: 10,
            // DataStore 没有"可空 long"，-1 是"未设置"哨兵
            examDateEpochDay = (prefs[KEY_EXAM_DATE] ?: -1L).takeIf { it > 0 }
        )
    }

    // ===== 大模型接入配置（多套，可切换） =====

    /**
     * 读取已保存的接入配置。
     *
     * 自愈逻辑：只要已存的配置里**一个都不可用**，就注入一份 buildConfig 预填兜底。
     * 早期版本在 Secrets 为空时会把空配置写进存储，之后 `parsed.isNotEmpty()` 恒成立、
     * 再也不会重新播种，于是这套装了预填 Key 的包也永远过不了 Key 门禁。
     */
    private fun loadProfiles(): List<LlmProfile> {
        val raw = readSecure(SECURE_LLM_PROFILES)
        val stored = if (raw.isBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<LlmProfile>>(raw) }.getOrDefault(emptyList())
        }
        val seed = buildConfigSeed()
        if (stored.none { it.isConfigured() } && seed != null) {
            val merged = stored.filterNot { it == seed } + seed
            Log.i(TAG, "已存接入配置均不可用，注入 buildConfig 预填兜底: ${merged.size} 套")
            // 用 writeProfiles 而不是 saveProfiles：这里正在 snapshot() 内部，
            // 而 snapshot() 又是 secureVersion 的收集者。saveProfiles 会 bump 版本号，
            // 等于在 combine 的 transform 里自触发重发，绕成活锁。
            writeProfiles(merged)
            return merged
        }
        if (stored.isNotEmpty()) return stored
        if (seed == null) return emptyList()
        writeProfiles(listOf(seed))
        return listOf(seed)
    }

    /**
     * 本地 debug 包从 buildConfig 取预填。
     *
     * **必须识别占位符**：公开分发包（`-PprefillKeys=false`）注入的是
     * [BuildConfigKeys.PLACEHOLDER]。当成了真 Key 的话，用户会拿着
     * `REPLACE_IN_SETTINGS` 去请求接口，得到 401，而且完全不知道为什么。
     */
    private fun buildConfigSeed(): LlmProfile? {
        if (BuildConfigKeys.isPlaceholder(BuildConfig.LLM_API_KEY)) return null
        if (BuildConfig.LLM_BASE_URL.isBlank() ||
            BuildConfig.LLM_API_KEY.isBlank() ||
            BuildConfig.LLM_MODEL.isBlank()
        ) {
            return null
        }
        return LlmProfile(
            name = "默认配置",
            baseUrl = BuildConfig.LLM_BASE_URL,
            apiKey = BuildConfig.LLM_API_KEY,
            model = BuildConfig.LLM_MODEL
        ).normalized()
    }

    /**
     * 只落盘、不通知观察者。
     *
     * 供 [loadProfiles] 的自愈播种使用——那条路径运行在 `settings` Flow 的
     * 收集栈里，此时 bump [secureVersion] 会让上游立刻重发，形成活锁。
     * 自愈本身已经把结果 `return` 出去了，调用方拿到的就是最新值，
     * 不需要额外的重发。
     */
    private fun writeProfiles(profiles: List<LlmProfile>) {
        val normalized = profiles.map { it.normalized() }
        securePrefs.edit()
            .putString(SECURE_LLM_PROFILES, json.encodeToString(normalized))
            .apply()
    }

    fun saveProfiles(profiles: List<LlmProfile>) {
        writeProfiles(profiles)
        secureVersion.value += 1
    }

    suspend fun upsertProfile(profile: LlmProfile) {
        val hadActive = appContext.settingsDataStore.data.first()[KEY_ACTIVE_PROFILE].isNullOrBlank()
        val current = loadProfiles().toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        val normalized = profile.normalized()
        if (index >= 0) current[index] = normalized else current.add(normalized)
        saveProfiles(current)
        if (hadActive) setActiveProfileId(normalized.id)
    }

    suspend fun deleteProfile(id: String) {
        val prefs = appContext.settingsDataStore.data.first()
        val remaining = loadProfiles().filterNot { it.id == id }
        saveProfiles(remaining)
        if (prefs[KEY_ACTIVE_PROFILE] == id) {
            setActiveProfileId(remaining.firstOrNull()?.id)
        }
    }

    suspend fun setActiveProfileId(id: String?) {
        appContext.settingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_PROFILE) else prefs[KEY_ACTIVE_PROFILE] = id
        }
    }

    // ===== 生图渠道（多套，可切换；同 LlmProfile 体系，字段更少） =====

    /**
     * 读已保存的生图渠道。
     *
     * 与 [loadProfiles] 不同：**没有 buildConfig 预填兜底**——生图渠道
     * 没有任何默认值可填（公开包不预置任何密钥），存量为空就是空，
     * 各功能按「未配置」给出友好提示。
     */
    private fun loadImageProfiles(): List<ImageProfile> {
        val raw = readSecure(SECURE_IMAGE_PROFILES)
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ImageProfile>>(raw) }
            .getOrDefault(emptyList())
    }

    private fun writeImageProfiles(profiles: List<ImageProfile>) {
        val normalized = profiles.map { it.normalized() }
        securePrefs.edit()
            .putString(SECURE_IMAGE_PROFILES, json.encodeToString(normalized))
            .apply()
    }

    fun saveImageProfiles(profiles: List<ImageProfile>) {
        writeImageProfiles(profiles)
        secureVersion.value += 1
    }

    suspend fun upsertImageProfile(profile: ImageProfile) {
        val hadActive = appContext.settingsDataStore.data.first()[KEY_ACTIVE_IMAGE_PROFILE].isNullOrBlank()
        val current = loadImageProfiles().toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        val normalized = profile.normalized()
        if (index >= 0) current[index] = normalized else current.add(normalized)
        saveImageProfiles(current)
        // 第一套渠道保存后自动激活：用户不该面对「配好了但没启用」的空态。
        if (hadActive) setActiveImageProfileId(normalized.id)
    }

    suspend fun deleteImageProfile(id: String) {
        val prefs = appContext.settingsDataStore.data.first()
        val remaining = loadImageProfiles().filterNot { it.id == id }
        saveImageProfiles(remaining)
        if (prefs[KEY_ACTIVE_IMAGE_PROFILE] == id) {
            setActiveImageProfileId(remaining.firstOrNull()?.id)
        }
    }

    suspend fun setActiveImageProfileId(id: String?) {
        appContext.settingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_IMAGE_PROFILE)
            else prefs[KEY_ACTIVE_IMAGE_PROFILE] = id
        }
    }

    suspend fun setMineruKey(value: String) {
        securePrefs.edit().putString(SECURE_MINERU_KEY, value.trim()).apply()
        // 密钥写在 SharedPreferences 里，DataStore 不会感知这次写入。
        // 不 bump 的话设置页的受控输入框会一直显示旧值，用户根本打不进字。
        secureVersion.value += 1
    }

    // ===== MinerU / 识别参数 =====

    // setMineruModelVersion / setOcrLanguage / setForceOcr 已随设置项一并移除。
    // 对应的 KEY_ 常量也删了——留着会让人以为还能通过 DataStore 改。

    suspend fun setAttachOriginalImage(value: Boolean) = editBoolean(KEY_ATTACH_IMAGE, value)

    suspend fun setRecognitionMode(value: RecognitionMode) = editString(KEY_RECOGNITION_MODE, value.name.lowercase())

    suspend fun setImageLongEdgePx(value: Int) = editInt(KEY_IMAGE_LONG_EDGE, value)

    // ===== 打印默认项 =====

    suspend fun setPrintIncludeImage(value: Boolean) = editBoolean(KEY_PRINT_IMAGE, value)

    suspend fun setPrintShowAnswer(value: Boolean) = editBoolean(KEY_PRINT_ANSWER, value)

    suspend fun setOcrStrength(value: Int) = editInt(KEY_OCR_STRENGTH, value.coerceIn(0, 3))

    /** 读旧的 KEY_ENHANCE_PHOTOS 作为迁移兼容，不写入。 */
    private fun legacyStrength(prefs: Preferences): Int {
        return when (prefs[KEY_ENHANCE_PHOTOS]) {
            false -> 0
            else -> 2 // true 或键不存在都回退到默认标准档
        }
    }

    // ===== 对话输入草稿（临时状态，存 DataStore 不进 Room）=====

    /**
     * 键里带 session id：每个会话各自保存一份草稿。
     *
     * 为什么要有这个：用户打了一大段字，退出会话再进来发现输入框空了，
     * 得重打一遍——这是「草稿丢失」类 bug 的典型表现。
     * 会话 id 是自增主键，不会冲突；空会话（还没发过消息）也有 id，
     * 所以草稿能覆盖「新建对话但还没发送」这种情况。
     */
    private fun chatDraftKey(sessionId: Long) = stringPreferencesKey("chat_draft_$sessionId")

    /** 读某个会话的输入草稿；没有则返回空串。 */
    suspend fun getChatDraft(sessionId: Long): String {
        if (sessionId <= 0L) return ""
        val key = chatDraftKey(sessionId)
        return appContext.settingsDataStore.data.first()[key].orEmpty()
    }

    /**
     * 写草稿。空串表示**清掉**草稿（已发送），顺手把键删掉，
     * 不然 DataStore 里会攒一堆空字符串。
     */
    suspend fun setChatDraft(sessionId: Long, text: String) {
        if (sessionId <= 0L) return
        val key = chatDraftKey(sessionId)
        appContext.settingsDataStore.edit { prefs ->
            if (text.isEmpty()) prefs.remove(key) else prefs[key] = text
        }
    }

    // ===== 手动录入草稿（T4，临时状态，存 DataStore 不进 Room）=====

    /**
     * 手动录入的自动暂存单槽：存一份 DraftBundle JSON（单题）。
     *
     * 与聊天草稿同思路：用户打了一半的字不该因为进程被杀而全丢。
     * 空串 = 清槽（已保存 / 用户主动丢弃）。
     */
    suspend fun getManualDraftJson(): String {
        val key = stringPreferencesKey("manual_draft_json")
        return appContext.settingsDataStore.data.first()[key].orEmpty()
    }

    suspend fun setManualDraftJson(value: String?) {
        val key = stringPreferencesKey("manual_draft_json")
        appContext.settingsDataStore.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(key) else prefs[key] = value
        }
    }

    // ===== 裁剪会话（临时编辑状态，存 DataStore 不进 Room）=====

    suspend fun getCropSession(imagePath: String): CropSessionRecord? {
        val key = cropSessionKey(imagePath)
        val stored = appContext.settingsDataStore.data.first()[key] ?: return null
        return CropSessionRecord(stored)
    }

    suspend fun putCropSession(imagePath: String, record: CropSessionRecord) {
        val key = cropSessionKey(imagePath)
        appContext.settingsDataStore.edit { it[key] = record.json }
    }

    suspend fun removeCropSession(imagePath: String) {
        val key = cropSessionKey(imagePath)
        appContext.settingsDataStore.edit { it.remove(key) }
    }

    val homeQuestionOrder: Flow<List<Long>> = appContext.settingsDataStore.data.map { prefs ->
        val raw = prefs[KEY_HOME_QUESTION_ORDER].orEmpty()
        runCatching { json.decodeFromString<List<Long>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun saveHomeQuestionOrder(ids: List<Long>) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[KEY_HOME_QUESTION_ORDER] = json.encodeToString(ids.distinct())
        }
    }

    /**
     * 键里带长度是为了降低哈希碰撞概率：光靠 `hashCode()`，
     * 两张不同路径的图片撞进同一个键的概率不为零，撞了就互相覆盖裁剪状态。
     * 拼接顺序固定，不影响正确性，只影响 key 的可读性。
     */
    private fun cropSessionKey(imagePath: String) = stringPreferencesKey(
        "crop_session_${imagePath.length}_${imagePath.hashCode().toString(16)}"
    )
    suspend fun setPrintBlankRedo(value: Boolean) = editBoolean(KEY_PRINT_BLANK, value)

    suspend fun setPrintBlankHeightPt(value: Int) = editInt(KEY_PRINT_BLANK_PT, value)

    // ===== 复习提醒 =====

    suspend fun setReviewReminderEnabled(value: Boolean) = editBoolean(KEY_REMINDER_ON, value)

    suspend fun setReminderTime(hour: Int, minute: Int) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[KEY_REMINDER_HOUR] = hour
            prefs[KEY_REMINDER_MINUTE] = minute
        }
    }

    // ===== 单词书每日目标 =====

    /** 设置每日单词学习目标（去重词数），越界自动夹紧。 */
    suspend fun setWordbookDailyGoal(value: Int) {
        editInt(KEY_WORDBOOK_DAILY_GOAL, value.coerceIn(MIN_WORDBOOK_DAILY_GOAL, MAX_WORDBOOK_DAILY_GOAL))
    }

    /** 开关「单词书每日目标」提醒。关闭后到点不再发通知。 */
    suspend fun setWordbookReminderEnabled(value: Boolean) =
        editBoolean(KEY_WORDBOOK_REMINDER, value)

    // ===== 考试日期（F3 考前排期） =====

    /** 设置考试日期（epochDay）。传 null 清除。 */
    suspend fun setExamDate(epochDay: Long?) {
        appContext.settingsDataStore.edit { prefs ->
            if (epochDay == null) prefs.remove(KEY_EXAM_DATE)
            else prefs[KEY_EXAM_DATE] = epochDay
        }
    }
    // ===== 番茄钟（T2 到点闭环） =====

    /** 开关「到点自动记录」。 */
    suspend fun setPomodoroAutoRecord(value: Boolean) =
        editBoolean(KEY_POMODORO_AUTO_RECORD, value)

    /** 开关「休息循环」。 */
    suspend fun setPomodoroBreakLoop(value: Boolean) =
        editBoolean(KEY_POMODORO_BREAK_LOOP, value)

    /** 设置休息时长（分钟），只允许 5 / 10。 */
    suspend fun setPomodoroBreakMinutes(value: Int) =
        editInt(KEY_POMODORO_BREAK_MINUTES, if (value == 5) 5 else 10)

    // ===== 外观与主题 =====

    suspend fun setThemeMode(mode: ThemeMode) {
        val value = when (mode) {
            ThemeMode.SYSTEM -> "system"
            ThemeMode.LIGHT -> "light"
            ThemeMode.DARK -> "dark"
        }
        editString(KEY_THEME_MODE, value)
    }

    /** 设置网络出口模式。写入即生效（NetworkRouter 每次建连前读）。 */
    suspend fun setNetworkMode(mode: NetworkMode) {
        val value = when (mode) {
            NetworkMode.SYSTEM -> "system"
            NetworkMode.DIRECT -> "direct"
            NetworkMode.AUTO -> "auto"
        }
        editString(KEY_NETWORK_MODE, value)
    }

    /** 开关会话联网搜索。写入即生效，下一轮对话带上。 */
    suspend fun setChatSearchEnabled(value: Boolean) =
        editBoolean(KEY_CHAT_SEARCH_ENABLED, value)

    /** 切换搜索出口（DeepSeek / Tavily）。 */
    suspend fun setChatSearchProvider(provider: ChatSearchProvider) =
        editString(KEY_CHAT_SEARCH_PROVIDER, provider.name.lowercase())

    /** 保存 DeepSeek 搜索服务专用 API Key（留空 = 复用 DeepSeek 模型 Key）。 */
    suspend fun setDeepseekSearchApiKey(key: String) =
        editString(KEY_DEEPSEEK_SEARCH_API_KEY, key.trim())

    /** 保存 Tavily API Key（去掉首尾空白）。 */
    suspend fun setTavilyApiKey(key: String) =
        editString(KEY_TAVILY_API_KEY, key.trim())

    /** 设置对话思考强度。写入即生效，下一次请求带上。 */
    suspend fun setReasoningEffort(effort: ReasoningEffort) {
        editString(KEY_REASONING_EFFORT, effort.key ?: "off")
    }

    // ===== 清空（恢复出厂） =====

    fun clearSecrets() {
        securePrefs.edit().clear().apply()
        secureVersion.value += 1
    }

    private fun readSecure(key: String): String = securePrefs.getString(key, "").orEmpty()

    private suspend fun editString(key: Preferences.Key<String>, value: String) {
        appContext.settingsDataStore.edit { it[key] = value.trim() }
    }

    private suspend fun editBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        appContext.settingsDataStore.edit { it[key] = value }
    }

    private suspend fun editInt(key: Preferences.Key<Int>, value: Int) {
        appContext.settingsDataStore.edit { it[key] = value }
    }

    internal companion object {
        const val TAG = "MistakeBookSettings"
        const val SECURE_FILE = "mistakebook_secure_prefs"
        const val SECURE_MINERU_KEY = "mineru_api_key"
        const val SECURE_LLM_PROFILES = "llm_profiles_json"
        const val SECURE_IMAGE_PROFILES = "image_profiles_json"

        // KEY_MINERU_MODEL / KEY_OCR_LANGUAGE / KEY_FORCE_OCR 已删：
        // 对应设置项固定为 vlm / ch / 开启，不再可配。旧库里的这些键会被忽略。
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_NETWORK_MODE = stringPreferencesKey("network_mode")
        val KEY_REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val KEY_ATTACH_IMAGE = booleanPreferencesKey("attach_original_image")
        val KEY_IMAGE_LONG_EDGE = intPreferencesKey("image_long_edge")
        val KEY_RECOGNITION_MODE = stringPreferencesKey("recognition_mode")
        val KEY_PRINT_IMAGE = booleanPreferencesKey("print_include_image")
        val KEY_PRINT_ANSWER = booleanPreferencesKey("print_show_answer")
        val KEY_ENHANCE_PHOTOS = booleanPreferencesKey("enhance_photos")
        val KEY_OCR_STRENGTH = intPreferencesKey("ocr_strength")
        val KEY_PRINT_BLANK = booleanPreferencesKey("print_blank_redo")
        val KEY_PRINT_BLANK_PT = intPreferencesKey("print_blank_height_pt")
        val KEY_REMINDER_ON = booleanPreferencesKey("review_reminder_enabled")
        val KEY_REMINDER_HOUR = intPreferencesKey("reminder_hour")
        val KEY_REMINDER_MINUTE = intPreferencesKey("reminder_minute")

        // 单词书每日目标与提醒
        val KEY_WORDBOOK_DAILY_GOAL = intPreferencesKey("wordbook_daily_goal")
        val KEY_WORDBOOK_REMINDER = booleanPreferencesKey("wordbook_reminder_enabled")
        val KEY_EXAM_DATE = longPreferencesKey("exam_date_epoch_day")

        // 番茄钟（T2）
        val KEY_POMODORO_AUTO_RECORD = booleanPreferencesKey("pomodoro_auto_record")
        val KEY_POMODORO_BREAK_LOOP = booleanPreferencesKey("pomodoro_break_loop")
        val KEY_POMODORO_BREAK_MINUTES = intPreferencesKey("pomodoro_break_minutes")
        val KEY_CHAT_SEARCH_ENABLED = booleanPreferencesKey("chat_search_enabled")
        val KEY_CHAT_SEARCH_PROVIDER = stringPreferencesKey("chat_search_provider")
        val KEY_DEEPSEEK_SEARCH_API_KEY = stringPreferencesKey("deepseek_search_api_key")
        val KEY_TAVILY_API_KEY = stringPreferencesKey("tavily_api_key")

        const val DEFAULT_WORDBOOK_DAILY_GOAL = 20
        const val MIN_WORDBOOK_DAILY_GOAL = 5
        // 200 封顶是早期拍的：有用户一天刷 300+ 词，200 直接顶格设不上去。
        // 500 对「雅思/托福刷词」也够用，再高就不是目标是许愿了。
        const val MAX_WORDBOOK_DAILY_GOAL = 500
        val KEY_ACTIVE_PROFILE = stringPreferencesKey("active_llm_profile")
        val KEY_ACTIVE_IMAGE_PROFILE = stringPreferencesKey("active_image_profile")
        val KEY_HOME_QUESTION_ORDER = stringPreferencesKey("home_question_order")
    }
}
