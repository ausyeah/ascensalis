package com.mistakebook.data.prefs

import com.mistakebook.data.chat.ChatSearchProvider

/**
 * 主题显示模式。
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        fun fromKey(key: String?): ThemeMode = when (key?.lowercase()) {
            "light" -> LIGHT
            "dark" -> DARK
            else -> SYSTEM
        }
    }
}

/**
 * 题目识别方式（拍照/导入错题时，「图 → 文字」这一步走哪条路）。
 *
 * 四个选项（v0.0.49 补齐）：
 * - [MINERU]：只用 MinerU（老行为）。没配 Key 时按「请配置 Key」失败。
 * - [CURRENT]：只用**当前会话/当前默认的大模型**——可识图模型直接看原图、
 *   输出结构化题目，完全不依赖 MinerU。当前模型不支持识图时按「请换识图
 *   模型」失败。
 * - [VISION]：只用**用户单独配置的识图模型**（设置 → 大模型 → 识图模型）。
 *   主对话模型再笨也没关系——识图是另一套配置。
 * - [AUTO]（默认）：**自动回落链**，依次尝试：
 *   ① MinerU（可用就用）；② MinerU 失败则用当前默认大模型直连；
 *   ③ 当前默认大模型不识图则用配置的识图模型；④ 最后把拿到的文字交回
 *   大模型整理。**中途失败不弹失败**，只在整条链都不通时才报错。
 *
 * 历史键名兼容：[fromKey] 把旧的 `llm` 当作 [CURRENT]。
 */
enum class RecognitionMode {
    MINERU,
    CURRENT,
    VISION,
    AUTO;

    companion object {
        fun fromKey(key: String?): RecognitionMode = when (key?.lowercase()) {
            // 旧键名 `llm` = 「用大模型直连」= 现在的「当前模型」。
            "llm", "current" -> CURRENT
            "vision" -> VISION
            "auto" -> AUTO
            else -> AUTO
        }
    }
}

/**
 * 生效中的设置快照：SettingsStore 每次发射都会重新拼一份。
 *
 * 读取优先级：用户填写的值 > buildConfig 预填（仅 debug）> 内置常量。
 */
data class SettingsSnapshot(
    val mineruKey: String = "",
    val llmProfiles: List<LlmProfile> = emptyList(),
    val activeProfileId: String? = null,

    /** 生图渠道（多套，可切换）。key 走 EncryptedSharedPreferences，同 [llmProfiles]。 */
    val imageProfiles: List<ImageProfile> = emptyList(),
    val activeImageProfileId: String? = null,

    /**
     * 识图模型配置（**独立一套，可自由填模型名**）。
     *
     * 与 [imageProfiles] 同构：名称 / 地址 / Key / 模型名都由用户填，
     * 不受「已有对话配置」的限制——用户要的就是「我想用哪个模型识图就填哪个」。
     */
    val visionProfiles: List<LlmProfile> = emptyList(),
    val activeVisionProfileId: String? = null,

    /**
     * 专门的**识图模型**：指向 [llmProfiles] 里某一套配置的 id。
     *
     * ## 为什么单独要一个
     *
     * 用户接的主模型常常不识图（DeepSeek-chat、Qwen-plus），而识图能力
     * 又是刚需（拍题、看截图）。以前的唯一出路是 MinerU——它是第三方
     * 付费服务，且只能出 OCR 文字，问「这张图里画了什么」答不上来。
     *
     * 单独配一套识图模型后：对话里的图片可以由它来读，主模型不识图
     * 也不影响；它还能回答「图里有什么」这类**理解型**问题，
     * 这是 OCR 做不到的。留空表示不指定（走原来的 MinerU / 原图直传）。
     */
    val visionProfileId: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,

    /** 自选主题色（v0.0.76）：只改强调色，不改背景基调。 */
    val accentTone: AccentTone = AccentTone.DEFAULT,

    /**
     * 网络出口模式。默认 [NetworkMode.AUTO]：MinerU 直连、其余走系统代理。
     */
    val networkMode: NetworkMode = NetworkMode.AUTO,

    /**
     * 对话思考强度。默认 HIGH：整理错题、总结题目时「想清楚」比「答得快」重要。
     * 服务端不认对应字段时会自动降级（见 ReasoningFallback），用户无感。
     */
    val reasoningEffort: ReasoningEffort = ReasoningEffort.HIGH,
    val attachOriginalImage: Boolean = true,
    val imageLongEdgePx: Int = 1280,

    /**
     * 题目识别方式。默认 [RecognitionMode.AUTO]：MinerU 优先、不可用自动落大模型
     * （v0.0.44 新增，见 [RecognitionMode] 注释）。
     */
    val recognitionMode: RecognitionMode = RecognitionMode.AUTO,
    val printIncludeImage: Boolean = false,
    val printShowAnswer: Boolean = false,
    val printBlankRedo: Boolean = true,
    val printBlankHeightPt: Int = 100,
    /**
     * 拍照/导入时是否做 OCR 对比度增强。
     * 0=关闭 1=轻度 2=标准(默认) 3=强力。
     * 强度越高，纸张底色压得越白、字迹拉得越黑。
     */
    val ocrStrength: Int = 2,
    val reviewReminderEnabled: Boolean = true,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,

    /**
     * 每日单词学习目标（去重词数）。
     *
     * 默认 20：按「一天 20 个新词」定，覆盖 4356 词的词库约 218 天，
     * 配合错词本复习刚好是考研期的合理节奏。目标只影响进度条与
     * 提醒通知，不影响抽词算法。
     */
    val wordbookDailyGoal: Int = 20,

    /** 是否开启「单词书每日目标」本地提醒（到点未达标才通知）。 */
    val wordbookReminderEnabled: Boolean = true,

    // ===== 会话联网搜索 =====

    /**
     * 会话联网搜索开关。默认**开**（用户要求：配置好了就默认打开）。
     * 开启后每轮用户消息发出前先做一次网页检索，结果注入上下文。
     */
    val chatSearchEnabled: Boolean = true,

    /** 搜索出口：DeepSeek 官方 Search API（默认）或 Tavily。 */
    val chatSearchProvider: ChatSearchProvider = ChatSearchProvider.DEEPSEEK,

    /**
     * DeepSeek 搜索服务的专用 API Key（选 DeepSeek 出口时可配）。
     *
     * 留空则自动复用已配置的 DeepSeek 模型 Key——对话模型就是
     * DeepSeek 官方时零额外配置。用别家大模型对话时，在这里单独
     * 配一把 DeepSeek Key，搜索服务照常可用（Key 用于官方
     * api.deepseek.com，与对话走的中转站相互独立）。
     */
    val deepseekSearchApiKey: String = "",

    /** Tavily API Key（选 Tavily 时必填；DeepSeek 出口无需此项）。 */
    val tavilyApiKey: String = "",

    // ===== 番茄钟（T2 到点闭环） =====

    /** 番茄到点自动落库。默认开：到点后用户十有八九忘了点结束，自动记最准。 */
    val pomodoroAutoRecord: Boolean = true,

    /** 番茄结束后自动进入休息倒计时（休息时长见 [pomodoroBreakMinutes]）。 */
    val pomodoroBreakLoop: Boolean = false,

    /** 休息时长（分钟），可选 5 / 10。 */
    val pomodoroBreakMinutes: Int = 10,

    // ===== 背单词 =====

    /**
     * 每日新词上限（0 = 不限）。
     *
     * 一天狂刷新词是「前学后忘」的头号来源：新词当天见过一面，
     * 隔天基本全忘。默认 20——错词复习不受此限制，只管「今天
     * 开始学的新词」。设 0 可关闭限制（老习惯用户）。
     */
    val wordbookDailyNewLimit: Int = 20,

    /**
     * 考试日期（epochDay，F3 考前排期用）。null = 未设置。
     * 存 DataStore 而不是 Room：它是一条用户偏好，不是题目数据，
     * 不值得为它动 schema。
     */
    val examDateEpochDay: Long? = null
) {
    // ===== 以下三项已固定，不再暴露给用户 =====
    //
    // 原来是可配置项，实测下来「唯一正确」的组合就是这三个值：
    // - 模型版本固定 vlm：pipeline 模式对中文手写作业的表格/公式识别明显更差，
    //   而 vlm 对纯英文题面同样能自适应，不存在「切英文题要改设置」的问题。
    // - 语言固定 ch：MinerU 的 language 参数是「首选语言」而非「限定语言」，
    //   纯英文内容照常识别，不需要切 en。
    // - 强制 OCR 固定开启：输入几乎全是拍照/扫描的作业，照版识别才会逐字提取；
    //   关掉会直接漏掉整页文字。
    //
    // 保留为属性（而不是直接删掉）是为了让 [RecognitionEngine] 的调用点
    // 保持可读，值改主意时只改这一处。

    /** MinerU 模型版本。固定 vlm。 */
    val mineruModelVersion: String = MINERU_MODEL_VLM

    /** OCR 首选语言。固定 ch（对纯英文内容同样自适应）。 */
    val ocrLanguage: String = OCR_LANG_ZH

    /** 是否强制照版识别。固定开启。 */
    val forceOcr: Boolean = true

    val activeProfile: LlmProfile?
        get() = llmProfiles.firstOrNull { it.id == activeProfileId } ?: llmProfiles.firstOrNull()

    /** 当前激活的生图渠道：显式选的优先，没选过时用第一套。 */
    val activeImageProfile: ImageProfile?
        get() = imageProfiles.firstOrNull { it.id == activeImageProfileId }
            ?: imageProfiles.firstOrNull()

    val mineruConfigured: Boolean get() = mineruKey.isNotBlank()

    val llmConfigured: Boolean get() = activeProfile?.isConfigured() == true

    /** 生图渠道是否可用。所有生图入口统一按这个门禁决定「提示去设置」还是真调 API。 */
    val imageConfigured: Boolean get() = activeImageProfile?.isConfigured() == true

    /** 当前激活的识图模型：显式选的优先，没选过时用第一套。 */
    val activeVisionProfile: LlmProfile?
        get() = visionProfiles.firstOrNull { it.id == activeVisionProfileId }
            ?: visionProfiles.firstOrNull()

    /**
     * 指定的识图模型配置：**独立配置优先**，没配时才回落到
     * 「从对话模型里指定的那一套」（老机制，存量用户的设置）。
     *
     * 指到一套**已被删除**的配置时返回 null（而不是崩或返回第一套）——
     * 删了配置就等于没指定，回到默认路径。
     */
    val visionProfile: LlmProfile?
        get() = activeVisionProfile
            ?: visionProfileId?.let { id -> llmProfiles.firstOrNull { it.id == id } }

    /** 识图模型是否可用（指定了且配置完整）。 */
    val visionConfigured: Boolean get() = visionProfile?.isConfigured() == true

    companion object {
        const val MINERU_MODEL_VLM = "vlm"
        const val OCR_LANG_ZH = "ch"
    }
}
