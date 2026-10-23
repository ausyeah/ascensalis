package com.mistakebook.wordbook.ui

import com.mistakebook.wordbook.data.Word
import com.mistakebook.wordbook.data.WordProgress

/**
 * 单词书主界面标签页。
 */
enum class WordbookTab {
    STUDY,       // 背词刷题
    WRONG_BOOK,  // 错题本体系
    LIBRARY,     // 4356 词库检索
    STATS        // 学习统计
}

/**
 * 学习刷题模式。
 */
enum class StudyMode {
    QUIZ,  // 四选一单选题（题干单词，选项一行一释义）
    CARD,  // 卡片翻面自测（正面单词，点击揭晓完整词性与多义项）
    SENSE, // 义项辨析模式（题干单一特定义项，选项4个英文单词，专攻熟词僻义）
    SPELL  // 拼写模式（题干给中文释义，用户拼出英文单词）
}

/**
 * 四选一选项。
 */
data class QuizOption(
    val label: String,       // A, B, C, D
    val word: Word,
    val isCorrect: Boolean,
    val isSelected: Boolean = false
)

/**
 * 「上一题」历史栈里存的一道题的**完整现场**。
 *
 * ## 为什么不是只存 Word
 *
 * 早先栈里只存 `Word`，回退时调 `presentWord(prev)` **重新构题**——
 * 干扰项重抽一遍（选项顺序、内容都变了）、`isAnswered`/`selectedOptionIndex`
 * 重置。用户回看上一题，看到的是「同一道题但选项完全不同、且不知道自己
 * 当时选了什么、对没对」——反馈「返回上一词没有上一词的状态」。
 *
 * 学习 github.com/ausyeah/wordbook 的 `prevQuestion()`：原选项重放
 * （keepOptions），选中与对错原样恢复。所以这里把整道题的可见状态
 * 全存下来：词、进度、题干义项、四个选项（含选中标记）、作答状态。
 *
 * 内存占用：一道题 ≈ 四个 QuizOption（各含一个 Word），30 题上限约
 * 几百 KB，相对 4356 词的词库可忽略。
 */
data class QuestionSnapshot(
    val word: Word,
    val progress: WordProgress?,
    val sense: String?,
    val options: List<QuizOption>,
    val selectedOptionIndex: Int?,
    val isAnswered: Boolean,
    val isCardRevealed: Boolean,
    // 拼写模式的作答现场：输入了什么、交没交、对不对
    val spellInput: String = "",
    val spellSubmitted: Boolean = false,
    val spellCorrect: Boolean = false
)

/**
 * 错词本子标签。
 */
enum class WrongSubTab {
    WRONG_BOOK, // 错题本（排队待复习）
    EVER_WRONG, // 曾错本（已连对2次毕业）
    MASTERED,   // 熟词本（手动标记的熟词）
    FAVORITE    // 生词本（手动收藏，书签图标）
}

/**
 * 单日学习统计。用于学习统计页的「近 7 天波动」图表。
 */
data class DayStat(
    /** 当天 00:00 的时间戳（毫秒） */
    val dayMs: Long,
    /** 当天学习过的去重单词数 */
    val studiedCount: Int,
    /** 当天答对次数 */
    val correctCount: Int,
    /** 当天作答总次数 */
    val totalCount: Int
) {
    /** 当天正确率；没有作答时为 null（图表上显示空柱而不是 0%） */
    val accuracy: Float? get() = if (totalCount <= 0) null else correctCount.toFloat() / totalCount
}

/**
 * 单词书整屏 UI 状态。
 */
data class WordbookUiState(
    val currentTab: WordbookTab = WordbookTab.STUDY,
    val studyMode: StudyMode = StudyMode.QUIZ,

    // 刷题状态
    val currentWord: Word? = null,
    val currentProgress: WordProgress? = null,
    val currentSense: String? = null, // 义项辨析题干（单一特定义项）
    val options: List<QuizOption> = emptyList(),
    val selectedOptionIndex: Int? = null,
    val isAnswered: Boolean = false,

    /**
     * 已作答，但**允许改选**。
     *
     * ## 为什么需要它
     *
     * 用户反馈：「上一个词选对了然后返回上一个之后，锁定死了不能选择，
     * 是仍然保留刚才的状态但是可以修改的意思」。
     *
     * 「上一题」回退的语义是**原样重放**：选项、选中、对错都不变。
     * 但如果因此把选项**锁死**（`enabled = !isAnswered`），
     * 用户就是想改主意时却改不了——回退就成了只能看不能动。
     *
     * 所以拆成两个维度：
     * - [isAnswered] = 是否已判定（决定要不要显示对错颜色与解析）
     * - 本字段 = 是否还能改选
     *
     * 回退/切模式重放时：已作答的题置 `allowReselect = true`，
     * 于是**保留刚才的选中状态**（视觉上还是那道题、那个选择），
     * 同时允许用户改选。
     */
    val allowReselect: Boolean = false,
    val isCardRevealed: Boolean = false,
    val remainingQueueCount: Int = 0,

    // 拼写模式（SPELL）状态
    val spellInput: String = "",
    val spellSubmitted: Boolean = false,
    val spellCorrect: Boolean = false,

    // 统计指标
    val studiedTodayCount: Int = 0,
    val masteredCount: Int = 0,
    val wrongBookCount: Int = 0,
    val favoriteCount: Int = 0,
    val totalVocabCount: Int = 4356,

    // 学习统计页：近 7 天每日学习量与正确率（顺序为从早到晚）
    val weeklyDayStats: List<DayStat> = emptyList(),

    // 学习统计页：当月每日学习量（用于月度热力图，按 1 号到月末顺序）
    val monthDayStats: List<DayStat> = emptyList(),

    /** 连续打卡天数（今天没学不算断，从昨天接着数）。 */
    val streakDays: Int = 0,

    /** 每日单词学习目标（去重词数）。 */
    val dailyGoal: Int = 20,

    /** 是否开启每日目标的本地提醒。 */
    val reminderEnabled: Boolean = true,

    // 是否有可回退的「上一题」（按钮置灰用）
    val hasPrevious: Boolean = false,

    // ---- 循环复习轮次 ----
    /** 当前轮次号；0 = 还没开过任何一轮。 */
    val roundNumber: Int = 0,
    /** 本轮剩余待复习词数。 */
    val roundRemaining: Int = 0,
    /** 已学过的词数（进过轮次队列的总数）。 */
    val studiedWordCount: Int = 0,

    // 错题本子页
    val wrongSubTab: WrongSubTab = WrongSubTab.WRONG_BOOK,
    val wrongBookList: List<Pair<Word, WordProgress>> = emptyList(),

    // 词库浏览页
    val libraryKeyword: String = "",
    val libraryFilterLevel: Int? = null,
    val libraryWords: List<Pair<Word, WordProgress?>> = emptyList(),

    val isLoading: Boolean = false,
    val snackbarMessage: String? = null
)
