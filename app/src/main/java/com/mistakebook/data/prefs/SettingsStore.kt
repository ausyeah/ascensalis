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
        val visionProfiles = loadVisionProfiles()
        val activeVisionId = prefs[KEY_ACTIVE_VISION_PROFILE] ?: visionProfiles.firstOrNull()?.id
        val ocrProfiles = loadOcrProfiles()
        val activeOcrId = prefs[KEY_ACTIVE_OCR_PROFILE] ?: ocrProfiles.firstOrNull()?.id
        return SettingsSnapshot(
            // 占位符不能当成真 Key，否则「已配置」判断会误判为通过
            mineruKey = readSecure(SECURE_MINERU_KEY).ifBlank {
                if (BuildConfigKeys.isPlaceholder(BuildConfig.MINERU_API_KEY)) "" else BuildConfig.MINERU_API_KEY
            },
            // OCR 供应商（v0.0.78）：Key 存加密存储，URL/模型名走 DataStore
            ocrProvider = OcrProvider.fromKey(prefs[KEY_OCR_PROVIDER]),
            deepseekOcrBaseUrl = prefs[KEY_DEEPSEEK_OCR_BASE_URL]
                ?: "https://api.deepseek.com",
            deepseekOcrApiKey = readSecure(SECURE_DEEPSEEK_OCR_KEY),
            deepseekOcrModel = prefs[KEY_DEEPSEEK_OCR_MODEL] ?: "deepseek-ocr",
            ocrProfiles = ocrProfiles,
            activeOcrProfileId = activeOcrId,
            llmProfiles = profiles,
            activeProfileId = activeId,
            imageProfiles = imageProfiles,
            activeImageProfileId = activeImageId,
            visionProfiles = visionProfiles,
            activeVisionProfileId = activeVisionId,
            visionProfileId = prefs[KEY_VISION_PROFILE],
            themeMode = ThemeMode.fromKey(prefs[KEY_THEME_MODE]),
            accentTone = AccentTone.fromKey(prefs[KEY_ACCENT_TONE]),
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
            wordbookDailyNewLimit = prefs[KEY_WORDBOOK_DAILY_NEW] ?: 20,
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
        val stored = loadProfileList<LlmProfile>(SECURE_LLM_PROFILES)
        val seed = buildConfigSeed()
        if (stored.none { it.isConfigured() } && seed != null) {
            val merged = stored.filterNot { it == seed } + seed
            Log.i(TAG, "已存接入配置均不可用，注入 buildConfig 预填兜底: ${merged.size} 套")
            // 用 write 而不是 save：这里正在 snapshot() 内部，
            // 而 snapshot() 又是 secureVersion 的收集者。save 会 bump 版本号，
            // 等于在 combine 的 transform 里自触发重发，绕成活锁。
            writeProfileList(SECURE_LLM_PROFILES, merged)
            return merged
        }
        if (stored.isNotEmpty()) return stored
        if (seed == null) return emptyList()
        writeProfileList(SECURE_LLM_PROFILES, listOf(seed))
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

    fun saveProfiles(profiles: List<LlmProfile>) {
        writeProfileList(SECURE_LLM_PROFILES, profiles)
        secureVersion.value += 1
    }

    suspend fun upsertProfile(profile: LlmProfile) {
        upsertProfileInternal(KEY_ACTIVE_PROFILE, profile,
            load = { loadProfileList<LlmProfile>(SECURE_LLM_PROFILES) },
            save = { saveProfiles(it) })
    }

    suspend fun deleteProfile(id: String) {
        deleteProfileInternal(KEY_ACTIVE_PROFILE, id,
            load = { loadProfileList<LlmProfile>(SECURE_LLM_PROFILES) },
            save = { saveProfiles(it) })
    }

    suspend fun setActiveProfileId(id: String?) = setActiveIdByKey(KEY_ACTIVE_PROFILE, id)

    // ===== OCR 渠道（多套，可随时切换；同 LlmProfile 体系） =====

    fun loadOcrProfiles(): List<OcrProfile> {
        val existing = loadProfileList<OcrProfile>(SECURE_OCR_PROFILES)
        if (existing.isNotEmpty()) return existing

        val legacyMineruKey = readSecure(SECURE_MINERU_KEY).ifBlank {
            if (BuildConfigKeys.isPlaceholder(BuildConfig.MINERU_API_KEY)) "" else BuildConfig.MINERU_API_KEY
        }
        val legacyDeepSeekKey = readSecure(SECURE_DEEPSEEK_OCR_KEY)
        val legacyDeepSeekBase = "https://api.deepseek.com"
        val legacyDeepSeekModel = "deepseek-ocr"

        val initial = listOf(
            OcrProfile.defaultMineru(legacyMineruKey),
            OcrProfile.defaultDeepSeek(legacyDeepSeekKey, legacyDeepSeekBase, legacyDeepSeekModel)
        )
        saveOcrProfiles(initial)
        return initial
    }

    fun saveOcrProfiles(profiles: List<OcrProfile>) {
        writeProfileList(SECURE_OCR_PROFILES, profiles)
        secureVersion.value += 1
    }

    suspend fun upsertOcrProfile(profile: OcrProfile) {
        upsertProfileInternal(KEY_ACTIVE_OCR_PROFILE, profile,
            load = { loadOcrProfiles() },
            save = { saveOcrProfiles(it) })
    }

    suspend fun deleteOcrProfile(id: String) {
        deleteProfileInternal(KEY_ACTIVE_OCR_PROFILE, id,
            load = { loadOcrProfiles() },
            save = { saveOcrProfiles(it) })
    }

    suspend fun setActiveOcrProfileId(id: String?) =
        setActiveIdByKey(KEY_ACTIVE_OCR_PROFILE, id)

    // ===== 生图渠道（多套，可切换；同 LlmProfile 体系，字段更少） =====

    /**
     * 生图渠道**没有 buildConfig 预填兜底**——生图渠道没有任何默认值可填
     * （公开包不预置任何密钥），存量为空就是空，各功能按「未配置」给出友好提示。
     */
    private fun loadImageProfiles(): List<ImageProfile> =
        loadProfileList(SECURE_IMAGE_PROFILES)

    fun saveImageProfiles(profiles: List<ImageProfile>) {
        writeProfileList(SECURE_IMAGE_PROFILES, profiles)
        secureVersion.value += 1
    }

    suspend fun upsertImageProfile(profile: ImageProfile) {
        upsertProfileInternal(KEY_ACTIVE_IMAGE_PROFILE, profile,
            load = { loadImageProfiles() },
            save = { saveImageProfiles(it) })
    }

    suspend fun deleteImageProfile(id: String) {
        deleteProfileInternal(KEY_ACTIVE_IMAGE_PROFILE, id,
            load = { loadImageProfiles() },
            save = { saveImageProfiles(it) })
    }

    suspend fun setActiveImageProfileId(id: String?) =
        setActiveIdByKey(KEY_ACTIVE_IMAGE_PROFILE, id)

    // ===== 识图模型（独立多套，可切换；字段全由用户填，含模型名） =====

    private fun loadVisionProfiles(): List<LlmProfile> =
        loadProfileList(SECURE_VISION_PROFILES)

    suspend fun saveVisionProfiles(profiles: List<LlmProfile>) {
        writeProfileList(SECURE_VISION_PROFILES, profiles)
        secureVersion.value += 1
    }

    suspend fun upsertVisionProfile(profile: LlmProfile) {
        upsertProfileInternal(KEY_ACTIVE_VISION_PROFILE, profile,
            load = { loadVisionProfiles() },
            save = { saveVisionProfiles(it) })
    }

    suspend fun deleteVisionProfile(id: String) {
        deleteProfileInternal(KEY_ACTIVE_VISION_PROFILE, id,
            load = { loadVisionProfiles() },
            save = { saveVisionProfiles(it) })
    }

    suspend fun setActiveVisionProfileId(id: String?) =
        setActiveIdByKey(KEY_ACTIVE_VISION_PROFILE, id)

    // ===== 多套 Provider 配置的通用存取（v0.0.63 收敛） =====
    //
    // LLM 接入 / 生图渠道 / 识图模型三套配置的存储逻辑完全同构，差异只在
    // 序列化类型与存储键。此前每套 5 个方法逐字复制三份（约 180 行），
    // 改一处漏两处是这类复制的必然结局——收敛为下面四个泛型方法，
    // 上面的公共 API 全部保留（调用方零改动）。

    /** 读一组配置（JSON 落在 EncryptedSharedPreferences）。坏数据按空列表处理。 */
    private inline fun <reified T : KeyedProfile> loadProfileList(storageKey: String): List<T> {
        val raw = readSecure(storageKey)
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<T>>(raw) }.getOrDefault(emptyList())
    }

    /** 只落盘、不 bump 版本号（loadProfiles 的自愈播种用，见其注释的活锁说明）。 */
    private inline fun <reified T : KeyedProfile> writeProfileList(storageKey: String, profiles: List<T>) {
        @Suppress("UNCHECKED_CAST")
        val normalized = profiles.map { it.normalized() as T }
        securePrefs.edit()
            .putString(storageKey, json.encodeToString(normalized))
            .apply()
    }

    /**
     * upsert：按 id 覆盖或追加。原本**没有**激活配置时自动激活这套——
     * 用户不该面对「配好了但没启用」的空态。
     */
    private suspend fun <T : KeyedProfile> upsertProfileInternal(
        activeKey: Preferences.Key<String>,
        profile: T,
        load: () -> List<T>,
        // save 声明为 suspend：saveVisionProfiles 是挂起函数（识图模型保存
        // 历史上就带 suspend），非挂起 lambda 里调用它会直接编译失败
        save: suspend (List<T>) -> Unit
    ) {
        val autoActivate = appContext.settingsDataStore.data.first()[activeKey].isNullOrBlank()
        val current = load().toMutableList()
        val normalized = profile.normalized()
        val index = current.indexOfFirst { it.id == profile.id }
        @Suppress("UNCHECKED_CAST")
        val typed = normalized as T
        if (index >= 0) current[index] = typed else current.add(typed)
        save(current)
        if (autoActivate) setActiveIdByKey(activeKey, normalized.id)
    }

    /** 删除：被删的是激活配置时切换到剩余的第一套（没有就清空激活）。 */
    private suspend fun <T : KeyedProfile> deleteProfileInternal(
        activeKey: Preferences.Key<String>,
        id: String,
        load: () -> List<T>,
        save: suspend (List<T>) -> Unit
    ) {
        val prefs = appContext.settingsDataStore.data.first()
        val remaining = load().filterNot { it.id == id }
        save(remaining)
        if (prefs[activeKey] == id) {
            setActiveIdByKey(activeKey, remaining.firstOrNull()?.id)
        }
    }

    /** 激活切换只存 id，不存整套配置——存副本就是两份真相（改了 Key 那边不知道）。 */
    private suspend fun setActiveIdByKey(
        activeKey: Preferences.Key<String>,
        id: String?
    ) {
        appContext.settingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(activeKey) else prefs[activeKey] = id
        }
    }

    /**
     * 指定专门的识图模型（传 null 表示不指定）。
     *
     * 只存 id，不存整套配置：模型配置本身在 [loadProfiles] 里维护，
     * 这里存副本就会有两份真相（改了 Key 这边不知道）。
     */
    suspend fun setVisionProfileId(id: String?) {
        appContext.settingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_VISION_PROFILE) else prefs[KEY_VISION_PROFILE] = id
        }
    }

    suspend fun setMineruKey(value: String) {
        securePrefs.edit().putString(SECURE_MINERU_KEY, value.trim()).apply()
        // 密钥写在 SharedPreferences 里，DataStore 不会感知这次写入。
        // 不 bump 的话设置页的受控输入框会一直显示旧值，用户根本打不进字。
        secureVersion.value += 1
    }

    // ===== OCR 配置（v0.0.78：MinerU / DeepSeek OCR 二选一） =====

    suspend fun setOcrProvider(provider: OcrProvider) = editString(KEY_OCR_PROVIDER, provider.name)

    suspend fun setDeepseekOcrBaseUrl(value: String) =
        editString(KEY_DEEPSEEK_OCR_BASE_URL, value.trim())

    suspend fun setDeepseekOcrModel(value: String) =
        editString(KEY_DEEPSEEK_OCR_MODEL, value.trim())

    suspend fun setDeepseekOcrApiKey(value: String) {
        securePrefs.edit().putString(SECURE_DEEPSEEK_OCR_KEY, value.trim()).apply()
        // 与 setMineruKey 同理：加密存储的写入 DataStore 感知不到
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

    // ===== 背单词 =====

    /**
     * 设置每日新词上限。档位换成 50/100/200/自定义（v0.0.84，旧档太少）：
     * 白名单放宽为 1..500，自定义档任意合法值都能存。
     * 旧值（15/20/30/0）读出来仍生效（快照原样透传），下次改设置时落进新区间。
     */
    suspend fun setWordbookDailyNewLimit(value: Int) =
        editInt(KEY_WORDBOOK_DAILY_NEW, if (value in 1..500) value else 20)

    // ===== 外观与主题 =====

    suspend fun setThemeMode(mode: ThemeMode) {
        val value = when (mode) {
            ThemeMode.SYSTEM -> "system"
            ThemeMode.LIGHT -> "light"
            ThemeMode.DARK -> "dark"
        }
        editString(KEY_THEME_MODE, value)
    }

    /** 设置自选主题色（v0.0.76）。 */
    suspend fun setAccentTone(tone: AccentTone) = editString(KEY_ACCENT_TONE, tone.name)

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

        /** DeepSeek OCR 的 Key（v0.0.78，加密存储，与 MinerU 各自独立）。 */
        const val SECURE_DEEPSEEK_OCR_KEY = "deepseek_ocr_api_key"
        const val SECURE_LLM_PROFILES = "llm_profiles_json"
        const val SECURE_IMAGE_PROFILES = "image_profiles_json"
        const val SECURE_VISION_PROFILES = "vision_profiles_json"
        const val SECURE_OCR_PROFILES = "ocr_profiles_json"
        val KEY_ACTIVE_OCR_PROFILE = stringPreferencesKey("active_ocr_profile_id")

        // KEY_MINERU_MODEL / KEY_OCR_LANGUAGE / KEY_FORCE_OCR 已删：
        // 对应设置项固定为 vlm / ch / 开启，不再可配。旧库里的这些键会被忽略。
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

        /** 自选主题色（v0.0.76）：存枚举名，认不出回默认。 */
        val KEY_ACCENT_TONE = stringPreferencesKey("accent_tone")

        // ===== OCR 配置（v0.0.78） =====
        val KEY_OCR_PROVIDER = stringPreferencesKey("ocr_provider")
        val KEY_DEEPSEEK_OCR_BASE_URL = stringPreferencesKey("deepseek_ocr_base_url")
        val KEY_DEEPSEEK_OCR_MODEL = stringPreferencesKey("deepseek_ocr_model")
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

        /** 每日新词上限（档位 50/100/200/自定义，历史值 0=不限 仍可读），见 [SettingsSnapshot.wordbookDailyNewLimit]。 */
        val KEY_WORDBOOK_DAILY_NEW = intPreferencesKey("wordbook_daily_new_limit")
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
        val KEY_VISION_PROFILE = stringPreferencesKey("vision_profile_id")
        val KEY_ACTIVE_VISION_PROFILE = stringPreferencesKey("active_vision_profile")
        val KEY_HOME_QUESTION_ORDER = stringPreferencesKey("home_question_order")
    }
}
