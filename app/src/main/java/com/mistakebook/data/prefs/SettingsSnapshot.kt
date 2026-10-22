package com.mistakebook.data.prefs

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
 * 生效中的设置快照：SettingsStore 每次发射都会重新拼一份。
 *
 * 读取优先级：用户填写的值 > buildConfig 预填（仅 debug）> 内置常量。
 */
data class SettingsSnapshot(
    val mineruKey: String = "",
    val llmProfiles: List<LlmProfile> = emptyList(),
    val activeProfileId: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,

    /**
     * 对话思考强度。默认 HIGH：整理错题、总结题目时「想清楚」比「答得快」重要。
     * 服务端不认对应字段时会自动降级（见 ReasoningFallback），用户无感。
     */
    val reasoningEffort: ReasoningEffort = ReasoningEffort.HIGH,
    val attachOriginalImage: Boolean = true,
    val imageLongEdgePx: Int = 1280,
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
    val wordbookReminderEnabled: Boolean = true
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

    val mineruConfigured: Boolean get() = mineruKey.isNotBlank()

    val llmConfigured: Boolean get() = activeProfile?.isConfigured() == true

    companion object {
        const val MINERU_MODEL_VLM = "vlm"
        const val OCR_LANG_ZH = "ch"
    }
}
