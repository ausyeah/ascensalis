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
    SENSE  // 义项辨析模式（题干单一特定义项，选项4个英文单词，专攻熟词僻义）
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
 * 错词本子标签。
 */
enum class WrongSubTab {
    WRONG_BOOK, // 错题本（排队待复习）
    EVER_WRONG, // 曾错本（已连对3次毕业）
    MASTERED    // 熟词本（手动标记的熟词）
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
    val isCardRevealed: Boolean = false,
    val remainingQueueCount: Int = 0,

    // 统计指标
    val studiedTodayCount: Int = 0,
    val masteredCount: Int = 0,
    val wrongBookCount: Int = 0,
    val totalVocabCount: Int = 4356,

    // 学习统计页：近 7 天每日学习量与正确率（顺序为从早到晚）
    val weeklyDayStats: List<DayStat> = emptyList(),

    // 是否有可回退的「上一题」（按钮置灰用）
    val hasPrevious: Boolean = false,

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
