package com.mistakebook.wordbook.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.di.AppContainer
import com.mistakebook.wordbook.algo.SpellRules
import com.mistakebook.wordbook.algo.WordbookRules
import com.mistakebook.wordbook.data.SenseQuestion
import com.mistakebook.wordbook.data.Word
import com.mistakebook.wordbook.data.WordProgress
import com.mistakebook.wordbook.data.WordStudyLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class WordbookViewModel(private val container: AppContainer) : ViewModel() {

    /** 顶部摘要四个计数的合并载体（combine 四路 Flow 的产物）。 */
    private data class StatsCounts(
        val today: Int,
        val mastered: Int,
        val wrong: Int,
        val favorite: Int
    )

    private val vocabRepo = container.vocabRepository
    private val dao = container.wordbookDao

    /** 单词发音（TTS）。单例随 AppContainer，初始化异步完成，未就绪时点击静默忽略。 */
    private val audioPlayer = container.wordAudioPlayer

    private val _uiState = MutableStateFlow(WordbookUiState(isLoading = true))
    val uiState: StateFlow<WordbookUiState> = _uiState.asStateFlow()

    // 内存中的短期错题重现队列（答错后延迟若干题再次重现强化）
    private data class DeferItem(val word: Word, val reappearAtAnswerCount: Int)
    private val deferQueue = mutableListOf<DeferItem>()

    /**
     * 每个词已经延迟重现过几次。
     *
     * 没有上限的话，一个总答错的词会在同一会话里无限次地冒出来，
     * 用户做同一道题十遍却一个新词没学到。封顶 [MAX_DEFER_TIMES] 次后
     * 它退出本会话的候选池，下次会话重新计数。
     */
    private val deferCount = mutableMapOf<String, Int>()
    private var sessionAnswerCount = 0

    /**
     * 错词本抽词权重。
     *
     * 70% 与 Web 版 wordbook 一致：错词本是薄弱项，应当主导出题；
     * 留 30% 给新词是为了词汇量增长——只刷错题的话新词永远见不到。
     */
    private companion object {
        const val WRONG_BOOK_WEIGHT = 0.70f

        /** 单个词在一次会话内最多延迟重现几次。 */
        const val MAX_DEFER_TIMES = 3
    }

    // 最近刷过的单词滑动窗口（冷却窗口，严格防止单词连着连续出现）
    private val recentWords = ArrayDeque<String>()
    private val COOL_DOWN_WINDOW = 8

    // 「上一题」历史栈：每离开一题就把刚做的题**连同作答现场**压栈，
    // 回退时原样重放（选项、选中、对错都不重抽）——见 [QuestionSnapshot]。
    // 限长防止无限增长——回看 30 题足够，再往前的没有记忆价值。
    private val questionHistory = ArrayDeque<QuestionSnapshot>()
    private val HISTORY_LIMIT = 30

    /**
     * 每个出题模式（四选一 / 义项）最近一道题的现场存档。
     *
     * ## 为什么需要它
     *
     * 切模式（四选一 → 卡片 → 四选一）走的是 [setStudyMode] →
     * [presentWord] **重新构题**：干扰项重抽、isAnswered/选中全清。
     * 用户实测：「回退到 radical 时作答状态还在，点了几下选项、
     * 再回到四选一模式，之前的选择状态就消失了」——模式绕一圈回来，
     * 题还是那道题，作答痕迹却被洗掉了。
     *
     * 现在离开一个模式前先把它的现场按模式存下来，切回来时**同一个词
     * 直接重放**（选项不洗牌、选中与对错原样恢复），与「上一题」回退
     * 共用同一套快照语义。词变了（抽了新题）存档自然失效——重放前
     * 校验 word 相等。
     */
    private val modeSnapshots = mutableMapOf<StudyMode, QuestionSnapshot>()

    /** 错词本列表的刷新防抖：答题写库很密集，没必要每次都重建列表。 */
    private val WRONG_LIST_DEBOUNCE_MS = 500L

    // 近 7 天统计（正确率 + 每日学习量），切到统计页时按需加载
    private val weeklyStats = MutableStateFlow<List<DayStat>>(emptyList())

    /**
     * 进度内存缓存。
     *
     * 抽词是**每答一题就跑一次**的热路径：原来每题都 `observeAllProgress().first()`
     * 把整张 progress 表（词库有多大就有多少行）读出来现建 Map，再对全部词
     * 做两轮 filter。改成进程内缓存 + 答题后增量 upsert，抽词不再碰数据库。
     */
    private val progressCache = java.util.concurrent.ConcurrentHashMap<String, WordProgress>()

    /**
     * 错词本单词集合（只存 word 字符串，抽词只需要判定「在不在错词本里」）。
     * 与 [progressCache] 同样在答题后增量维护。
     */
    @Volatile
    private var wrongWordsCache: Set<String> = emptySet()

    // 快速连击与防重入原子状态锁
    private val isActionInProgress = AtomicBoolean(false)
    private val isTogglingMaster = AtomicBoolean(false)
    private val isTogglingFavorite = AtomicBoolean(false)
    private var nextQuestionJob: Job? = null
    private var librarySearchJob: Job? = null

    private fun recordRecentWord(wordStr: String) {
        recentWords.addLast(wordStr)
        while (recentWords.size > COOL_DOWN_WINDOW) {
            recentWords.removeFirst()
        }
    }

    /**
     * 把当前这道题的**完整现场**压入「上一题」历史栈。
     *
     * 存的不是 Word 而是 [QuestionSnapshot]：回退时要原样恢复
     * 「当时选的哪个、对不对、卡片翻没翻」，只存词的话回退等于重抽题。
     */
    private fun pushHistory(state: WordbookUiState) {
        val word = state.currentWord ?: return
        // 连着同一个词不重复入栈（自动切题与手动下一题可能快速连点）
        if (questionHistory.lastOrNull()?.word?.word == word.word) return
        questionHistory.addLast(
            QuestionSnapshot(
                word = word,
                progress = state.currentProgress,
                sense = state.currentSense,
                options = state.options,
                selectedOptionIndex = state.selectedOptionIndex,
                isAnswered = state.isAnswered,
                isCardRevealed = state.isCardRevealed,
                spellInput = state.spellInput,
                spellSubmitted = state.spellSubmitted,
                spellCorrect = state.spellCorrect
            )
        )
        while (questionHistory.size > HISTORY_LIMIT) {
            questionHistory.removeFirst()
        }
        _uiState.update { it.copy(hasPrevious = questionHistory.isNotEmpty()) }
    }

    /**
     * 把当前这道题的现场按**当前出题模式**存档（四选一 / 义项）。
     *
     * 卡片 / 拼写模式没有选项可存，不入档。调用时机：呈现新题后、
     * 作答后、模式切换离开前——任何让界面发生变化的地方都存一遍，
     * 存档永远是「这个模式最近一次的可见状态」。
     */
    private fun captureModeSnapshot() {
        val s = _uiState.value
        if (s.studyMode != StudyMode.QUIZ && s.studyMode != StudyMode.SENSE) return
        val word = s.currentWord ?: return
        modeSnapshots[s.studyMode] = QuestionSnapshot(
            word = word,
            progress = s.currentProgress,
            sense = s.currentSense,
            options = s.options,
            selectedOptionIndex = s.selectedOptionIndex,
            isAnswered = s.isAnswered,
            isCardRevealed = s.isCardRevealed,
            spellInput = s.spellInput,
            spellSubmitted = s.spellSubmitted,
            spellCorrect = s.spellCorrect
        )
    }

    /**
     * 原样重放一道题的存档现场：不重新构题、不洗牌。
     *
     * 「上一题」回退与「切模式回来」共用——两条路径的诉求完全一致：
     * 看到的就是当时那道题，一个像素都不要变。
     */
    private fun replaySnapshot(snapshot: QuestionSnapshot) {
        _uiState.update {
            it.copy(
                currentWord = snapshot.word,
                currentProgress = snapshot.progress,
                currentSense = snapshot.sense,
                options = snapshot.options,
                selectedOptionIndex = snapshot.selectedOptionIndex,
                isAnswered = snapshot.isAnswered,
                // 切模式回来同样允许改选（状态保留，但能改）。
                allowReselect = snapshot.isAnswered,
                isCardRevealed = snapshot.isCardRevealed,
                spellInput = snapshot.spellInput,
                spellSubmitted = snapshot.spellSubmitted,
                spellCorrect = snapshot.spellCorrect,
                isLoading = false
            )
        }
    }

    init {
        viewModelScope.launch {
            vocabRepo.ensureLoaded()
            // 抽词要用的两份数据先载入内存（只在启动时读一次全表），
            // 之后靠答题增量维护，避免每题都全表扫描。
            withContext(Dispatchers.IO) {
                progressCache.putAll(dao.observeAllProgress().first().associateBy { it.word })
                wrongWordsCache = dao.observeWrongBook().first().map { it.word }.toSet()
            }
            observeDatabaseStats()
            prepareNextQuestion()
            _uiState.update { it.copy(isLoading = false) }
        }
        // 周统计刷新后实时同步到 UI 状态（切到统计页或答完题都会触发）
        viewModelScope.launch {
            weeklyStats.collect { stats ->
                _uiState.update { it.copy(weeklyDayStats = stats) }
            }
        }
        // 每日目标与提醒开关跟随设置变化
        viewModelScope.launch {
            container.settingsStore.settings.collect { s ->
                _uiState.update {
                    it.copy(
                        dailyGoal = s.wordbookDailyGoal,
                        reminderEnabled = s.wordbookReminderEnabled
                    )
                }
            }
        }
        // 设置页重置了单词数据（清流水 / 清进度）：缓存、历史栈、当前题全部作废，
        // 按数据库的新现状重载。drop(1) 跳过初始值——init 本身就要做一遍全量载入。
        viewModelScope.launch {
            container.wordbookEpoch.drop(1).collect {
                reloadAfterExternalReset()
            }
        }
    }

    /**
     * 外部重置（设置页）后的重载。
     *
     * 重置有两种口径，但 VM 侧的重载动作完全一样——数据库已经是新现状，
     * 这里只负责把「内存里那份旧现状」全部丢掉重读：
     * - 抽词缓存：progressCache / wrongWordsCache 重建；
     * - 会话状态：历史栈、短期重现队列、冷却窗口清空（旧题引用的进度
     *   可能已经不存在，留着回退会展示幽灵数据）；
     * - 当前题：重新抽一道，让界面立刻反映「重来」。
     */
    private suspend fun reloadAfterExternalReset() {
        nextQuestionJob?.cancel()
        withContext(Dispatchers.IO) {
            progressCache.clear()
            progressCache.putAll(dao.observeAllProgress().first().associateBy { it.word })
            wrongWordsCache = dao.observeWrongBook().first().map { it.word }.toSet()
        }
        questionHistory.clear()
        modeSnapshots.clear()
        deferQueue.clear()
        recentWords.clear()
        sessionAnswerCount = 0
        _uiState.update {
            it.copy(
                hasPrevious = false,
                wrongBookList = emptyList(),
                libraryWords = emptyList()
            )
        }
        refreshWeeklyStats()
        refreshMonthlyStats()
        prepareNextQuestion()
    }

    /** 修改每日学习目标（去重词数）。设置层会夹紧到合理区间。 */
    fun setDailyGoal(goal: Int) {
        viewModelScope.launch {
            container.settingsStore.setWordbookDailyGoal(goal)
        }
    }

    /** 开关每日目标的本地提醒。 */
    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.setWordbookReminderEnabled(enabled)
        }
    }

    private fun observeDatabaseStats() {
        val startOfDayMs = getStartOfDayMillis()
        viewModelScope.launch {
            combine(
                dao.observeStudiedTodayCount(startOfDayMs),
                dao.observeMasteredCount(),
                dao.observeWrongBookCount(),
                dao.observeFavoriteCount()
            ) { today, mastered, wrong, favorite ->
                StatsCounts(today, mastered, wrong, favorite)
            }.collect { counts ->
                _uiState.update {
                    it.copy(
                        studiedTodayCount = counts.today,
                        masteredCount = counts.mastered,
                        wrongBookCount = counts.wrong,
                        favoriteCount = counts.favorite
                    )
                }
            }
        }

        // 监听错词本数据变动。
        //
        // debounce 是必要的：答一题就 upsert 一次，Room 的 Flow 会立刻重发，
        // 于是「每答一题就整表重查一次错词本 + 逐词 getWord」——
        // 用户在刷题 Tab 上根本看不到这份列表，却一直在为它付代价。
        // 再叠一层「必须真的停在错词本页」的判断，刷题时不刷新。
        viewModelScope.launch {
            dao.observeWrongBook()
                .debounce(WRONG_LIST_DEBOUNCE_MS)
                .collect {
                    if (_uiState.value.currentTab == WordbookTab.WRONG_BOOK &&
                        _uiState.value.wrongSubTab == WrongSubTab.WRONG_BOOK
                    ) {
                        refreshWrongSubTabList(WrongSubTab.WRONG_BOOK)
                    }
                }
        }
    }

    fun setTab(tab: WordbookTab) {
        _uiState.update { it.copy(currentTab = tab) }
        if (tab == WordbookTab.WRONG_BOOK) {
            refreshWrongSubTabList(_uiState.value.wrongSubTab)
        } else if (tab == WordbookTab.LIBRARY) {
            refreshLibraryList()
        } else if (tab == WordbookTab.STATS) {
            refreshWeeklyStats()
            refreshMonthlyStats()
        }
    }

    /**
     * 近 7 天统计：每日学习量与正确率，按天聚合后放入 weeklyStats。
     *
     * 聚合放在 Kotlin 侧而不是 SQL：近 7 天的流水最多几千行，
     * groupBy 一次的成本远低于给 SQLite 方言差异（strftime 的
     * 'localtime' 修饰符在各 ROM 上行为不一）交的学费。
     */
    private fun refreshWeeklyStats() {
        viewModelScope.launch {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -6)
            }
            val sinceMs = calendar.timeInMillis
            val logs = withContext(Dispatchers.IO) { dao.logsSince(sinceMs) }
            val dayMsOf: (Long) -> Long = { ts ->
                Calendar.getInstance().apply {
                    timeInMillis = ts
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            // 近 7 天一个不少地铺满，没有学习的日期补 0，图表上才是完整的一周
            val dayStarts = (0..6).map { offset ->
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    add(Calendar.DAY_OF_YEAR, -6 + offset)
                }.timeInMillis
            }
            val grouped = logs.groupBy { dayMsOf(it.answeredAt) }
            val stats = dayStarts.map { dayMs ->
                val dayLogs = grouped[dayMs].orEmpty()
                val correct = dayLogs.count { it.isCorrect }
                DayStat(
                    dayMs = dayMs,
                    studiedCount = dayLogs.map { it.word }.distinct().size,
                    correctCount = correct,
                    totalCount = dayLogs.size
                )
            }
            weeklyStats.value = stats
        }
    }

    /**
     * 月度热力图 + 连续打卡天数。
     *
     * ## 为什么查 120 天而不是 30 天
     *
     * 连续打卡可能横跨上个月（月初连着上月底连学 20 天）。
     * 只查本月流水算出来的 streak 会被截断在「当月 1 号」，
     * 用户明明连着学了一个多月，界面却显示 3 天。
     * 所以一次查 120 天：月度热力图只取当月部分，
     * streak 用全部（有跨月能力的）日期集合算。
     */
    private fun refreshMonthlyStats() {
        viewModelScope.launch {
            val today = getStartOfDayMillis()
            val lookbackStart = Calendar.getInstance().apply {
                timeInMillis = today
                add(Calendar.DAY_OF_YEAR, -120)
            }.timeInMillis
            val logs = withContext(Dispatchers.IO) { dao.logsSince(lookbackStart) }

            // 按天分组（本地时区 00:00 为界，与 DAO 的今日统计口径一致）
            val dayMsOf: (Long) -> Long = { ts ->
                Calendar.getInstance().apply {
                    timeInMillis = ts
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            val grouped = logs.groupBy { dayMsOf(it.answeredAt) }

            // ----- 月度热力图：当月 1 号到月末，一天一格 -----
            val monthCal = Calendar.getInstance().apply {
                timeInMillis = today
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val year = monthCal.get(Calendar.YEAR)
            val month = monthCal.get(Calendar.MONTH)
            val daysInMonth = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, 1)
            }.getActualMaximum(Calendar.DAY_OF_MONTH)
            val monthStats = (1..daysInMonth).map { day ->
                val dayMs = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val dayLogs = grouped[dayMs].orEmpty()
                DayStat(
                    dayMs = dayMs,
                    studiedCount = dayLogs.map { it.word }.distinct().size,
                    correctCount = dayLogs.count { it.isCorrect },
                    totalCount = dayLogs.size
                )
            }

            // ----- 连续打卡 -----
            // 今天还没学**不算断签**：一天还没过完，
            // 昨晚连学 5 天的人不该在早上 9 点看到 streak 归零。
            var cursor = today
            if (cursor !in grouped) {
                cursor = Calendar.getInstance().apply {
                    timeInMillis = today
                    add(Calendar.DAY_OF_YEAR, -1)
                }.timeInMillis
            }
            var streak = 0
            while (cursor in grouped) {
                streak++
                cursor = Calendar.getInstance().apply {
                    timeInMillis = cursor
                    add(Calendar.DAY_OF_YEAR, -1)
                }.timeInMillis
            }

            _uiState.update { it.copy(monthDayStats = monthStats, streakDays = streak) }
        }
    }

    /**
     * 切换学习模式：**保持当前单词不变**，也不碰「上一题」历史栈。
     *
     * 旧实现切模式时直接 `prepareNextQuestion()`——那是「抽新词」的入口，
     * 于是出现两个 bug：
     * 1. 从四选一切到卡片，题目变成了另一个词（用户报「四个模式切换后
     *    不是同一个单词」）；
     * 2. 抽新词会把当前这道题压进历史栈，而新题用户根本没做过——
     *    回退「上一题」看到的是一堆没答过的题，「回去又要重新做」。
     *
     * 后来改成按新模式重新呈现当前词，但又洗掉了作答状态（用户报
     * 「再回到四选一模式，之前的选择状态就消失了」）。现在分两种：
     * - 这个模式**见过这道题**（有存档且词没变）→ 原样重放，
     *   选项不洗牌、选中与对错原样恢复；
     * - 没见过 → 才按新模式构建（四选一重抽干扰项、义项重挑辨析点）。
     */
    fun setStudyMode(mode: StudyMode) {
        if (_uiState.value.studyMode == mode) return
        // 离开当前模式前存档：四选一 → 卡片 → 四选一能原样回来的前提
        captureModeSnapshot()
        _uiState.update { it.copy(studyMode = mode) }
        val current = _uiState.value.currentWord
        if (current == null) {
            // 空状态（词库抽完了）才需要真的抽新题
            prepareNextQuestion()
            return
        }
        nextQuestionJob?.cancel()
        val replay = modeSnapshots[mode]
        if (replay != null && replay.word.word == current.word) {
            replaySnapshot(replay)
            return
        }
        nextQuestionJob = viewModelScope.launch { presentWord(current) }
    }

    fun setWrongSubTab(subTab: WrongSubTab) {
        _uiState.update { it.copy(wrongSubTab = subTab) }
        refreshWrongSubTabList(subTab)
    }

    // =========================================================================
    // 刷题流程调度
    // =========================================================================

    fun prepareNextQuestion() {
        if (_uiState.value.isLoading && nextQuestionJob?.isActive == true) return
        nextQuestionJob?.cancel()
        nextQuestionJob = viewModelScope.launch {
            loadNextQuestionInternal()
        }
    }

    private suspend fun loadNextQuestionInternal() {
        _uiState.update { it.copy(isLoading = true) }
        val nextWord = pickNextWord()
        if (nextWord == null) {
            _uiState.update {
                it.copy(
                    currentWord = null,
                    currentProgress = null,
                    currentSense = null,
                    options = emptyList(),
                    isAnswered = false,
                    selectedOptionIndex = null,
                    isCardRevealed = false,
                    spellInput = "",
                    spellSubmitted = false,
                    spellCorrect = false,
                    isLoading = false
                )
            }
            return
        }
        // 离开当前题：把这道题的完整现场（含作答状态）压入「上一题」历史栈
        pushHistory(_uiState.value)
        presentWord(nextWord)
    }

    /**
     * 把指定单词构造成一道题并推到界面上。
     *
     * 从 loadNextQuestionInternal 抽出来：正常刷题与「上一题」回退
     * 共用同一套构题逻辑（四选一 / 义项辨析两种模式）。
     */
    private suspend fun presentWord(word: Word) {
        if (_uiState.value.studyMode == StudyMode.SPELL) {
            // 拼写模式：只给释义，不给单词、不给选项。
            // 题干卡显示的是 currentWord.full/meaning，
            // 单词本身要等用户提交后才揭晓。
            val progress = dao.getProgress(word.word) ?: WordProgress(word = word.word)
            _uiState.update {
                it.copy(
                    currentWord = word,
                    currentProgress = progress,
                    currentSense = null,
                    options = emptyList(),
                    selectedOptionIndex = null,
                    isAnswered = false,
                    allowReselect = false,
                    isCardRevealed = false,
                    spellInput = "",
                    spellSubmitted = false,
                    spellCorrect = false,
                    isLoading = false
                )
            }
            return
        }

        if (_uiState.value.studyMode == StudyMode.SENSE) {
            // **绝不换词**：切到义项辨析要保留当前这个词，只是换呈现方式。
            //
            // 早先这里有一层「构题失败就 pickNextWord() 换词」的重试，
            // 结果是用户盯着某个词，切一下模式题面就变成别的词了。
            // 现在只重试**同��个词的另一个义项**；连这个都构不出来时，
            // 退回到「按当前词出四选一」而不是换词。
            var senseQ: SenseQuestion? = null
            var retry = 0
            while (senseQ == null && retry < 4) {
                senseQ = vocabRepo.buildSenseQuestion(word)
                if (senseQ != null) break
                retry++
            }

            if (senseQ != null) {
                val progress = dao.getProgress(senseQ.word.word) ?: WordProgress(word = senseQ.word.word)
                val labels = listOf("A", "B", "C", "D")
                val options = senseQ.options.mapIndexed { index, candidateWord ->
                    QuizOption(
                        label = labels.getOrElse(index) { "?" },
                        word = candidateWord,
                        isCorrect = candidateWord.word == senseQ.word.word,
                        isSelected = false
                    )
                }
                _uiState.update {
                    it.copy(
                        currentWord = senseQ.word,
                        currentProgress = progress,
                        currentSense = senseQ.sense,
                        options = options,
                        selectedOptionIndex = null,
                        isAnswered = false,
                        // 新题必须显式复位：copy 不写这个字段会沿用旧值，
                        // 上一题若处于「回退重放可改选」态，true 会泄漏到
                        // 这道新题上——答完还能点选项，状态就乱了。
                        allowReselect = false,
                        isCardRevealed = false,
                        isLoading = false
                    )
                }
                captureModeSnapshot()
                return
            }

            // 构不出义项题（词库里干扰项不够）：**仍不换词**，
            // 直接按当前词出四选一，保证「切模式不换题」这条铁律。
        }

        val progress = dao.getProgress(word.word) ?: WordProgress(word = word.word)
        val distractors = vocabRepo.pickDistractors(word, 3)

        // 构造四选一并打乱
        val labels = listOf("A", "B", "C", "D")
        val allCandidates = (listOf(word) + distractors).shuffled(Random)
        val options = allCandidates.mapIndexed { index, candidateWord ->
            QuizOption(
                label = labels.getOrElse(index) { "?" },
                word = candidateWord,
                isCorrect = candidateWord.word == word.word,
                isSelected = false
            )
        }

        _uiState.update {
            it.copy(
                currentWord = word,
                currentProgress = progress,
                currentSense = null,
                options = options,
                selectedOptionIndex = null,
                isAnswered = false,
                allowReselect = false,
                isCardRevealed = false,
                isLoading = false
            )
        }
        // 新题入库存档：之后切模式再切回来，重放的是这道新题
        captureModeSnapshot()
    }

    /**
     * 返回上一题。卡片模式与四选一 / 义项辨析都可用。
     *
     * 标准回退栈语义：弹掉栈顶（上一题）并**原样重放**，**当前题直接丢弃**。
     * 不回压当前题——否则连按「上一题」只会在最近两题之间振荡
     * （w3→w2→w3→w2…），永远回不到更早的题。
     * 代价是回退后再按「下一题」会抽新题，往前走过的题不保留，
     * 与浏览器「后退后再前进」丢弃 forward 历史的行为一致。
     *
     * 重放走 [QuestionSnapshot] 直接套状态：**不重新构题**——
     * 干扰项不重抽、选中与对错原样恢复（学习 wordbook 仓库的
     * keepOptions 重放）。同步完成，没有异步，也就不需要
     * isActionInProgress 守卫；但要取消可能在飞的自动切题协程
     * （答对后 380ms 的那一个），否则它会按旧词的条件把界面切走。
     */
    fun previousQuestion() {
        // 用 removeLast 而不是 pollLast：这里是 kotlin.collections.ArrayDeque，
        // pollLast 是 java.util.ArrayDeque 的方法名（混用会编译不过）。
        if (questionHistory.isEmpty()) return
        val snapshot = questionHistory.removeLast()
        nextQuestionJob?.cancel()
        _uiState.update {
            it.copy(
                currentWord = snapshot.word,
                currentProgress = snapshot.progress,
                currentSense = snapshot.sense,
                options = snapshot.options,
                selectedOptionIndex = snapshot.selectedOptionIndex,
                isAnswered = snapshot.isAnswered,
                // 回退到已作答的题：保留刚才的选中状态，但**允许改选**。
                // 早先这里只恢复 isAnswered=true，UI 又用 enabled=!isAnswered
                // 锁死选项，于是「返回上一个词」之后那个词再也点不动了。
                allowReselect = snapshot.isAnswered,
                isCardRevealed = snapshot.isCardRevealed,
                spellInput = snapshot.spellInput,
                spellSubmitted = snapshot.spellSubmitted,
                spellCorrect = snapshot.spellCorrect,
                isLoading = false,
                hasPrevious = questionHistory.isNotEmpty()
            )
        }
        // 回退重放后也刷新一次存档：从上一题再切模式，回来还是这道题的状态
        captureModeSnapshot()
    }

    /** 历史栈里还有可回退的题时 UI 才显示「上一题」按钮。 */
    fun hasPreviousQuestion(): Boolean = questionHistory.isNotEmpty()

    /**
     * 写库后同步内存缓存。
     *
     * 抽词只读缓存，任何一处 upsert 漏掉同步，就会出现
     * 「标记成熟词了却还在被抽到」「移出错词本了还按错词复习」这类
     * 界面与数据库不一致的怪现象。所以统一走这一个入口。
     */
    private fun syncProgressCache(p: WordProgress) {
        progressCache[p.word] = p
        wrongWordsCache = if (p.isWrongBook) wrongWordsCache + p.word else wrongWordsCache - p.word
    }

    /**
     * 抽下一道题。
     *
     * ## 出题顺序（与 Web 版 wordbook 保持一致）
     *
     * 1. **短期重现队列** —— 答错的词延迟若干题后再考一次；
     * 2. **循环轮次队列** —— 用户手动开了新一轮时，把已学过且本轮
     *    还没复习的词按「错得多的先来」排在最前。放在错词本分流**之前**：
     *    用户主动开新一轮就是要过一遍，别被错词本截走流量；
     * 3. **错词本 70% / 新词 30%** —— 错词本是薄弱项，权重理应更高；
     *    但不能 100%，否则用户就退化成「只刷错题」，新词一个不认，
     *    词汇量永远上不去。早期版本这里压到 20%，等于把错题本这个
     *    核心机制晾在一边，与 Web 版（70/30）行为分叉，这里改回一致。
     *
     * 冷却窗口 [recentWords] 与重现队列 [deferQueue] 在每一层都排除：
     * 连着出同一个词是所有分支的公共底线。
     */
    private suspend fun pickNextWord(): Word? = withContext(Dispatchers.IO) {
        val isSenseMode = _uiState.value.studyMode == StudyMode.SENSE
        fun isEligibleSenseWord(w: Word): Boolean = !isSenseMode || vocabRepo.splitSenses(w.meaning).size >= 2

        // 1. 优先查看短期重现队列（已达到重现题数且不在冷却窗口内的词）
        val deferIndex = deferQueue.indexOfFirst {
            sessionAnswerCount >= it.reappearAtAnswerCount && it.word.word !in recentWords && isEligibleSenseWord(it.word)
        }
        if (deferIndex >= 0) {
            val item = deferQueue.removeAt(deferIndex)
            return@withContext item.word
        }

        val allWords = vocabRepo.getAllWords()
        if (allWords.isEmpty()) return@withContext null

        val pendingDeferWords = deferQueue.map { it.word.word }.toSet()

        fun blocked(word: String): Boolean =
            word in recentWords || word in pendingDeferWords || deferCount[word] != null && deferCount[word]!! >= MAX_DEFER_TIMES

        // 2. 错词本 70% / 新词 30%
        val eligibleWrong = wrongWordsCache
            .filter { !blocked(it) }
            .mapNotNull { vocabRepo.getWord(it) }
            .filter { isEligibleSenseWord(it) }

        // 新词：未标记熟词且等级不足 3。答对过的新词在本次会话内
        // 不再出（sessionDone），否则一轮下来全在原地打转。
        fun isUnmastered(w: Word): Boolean {
            val p = progressCache[w.word]
            return p == null || (!p.isMastered && p.level < 3)
        }

        var picked: Word? = null

        if (eligibleWrong.isNotEmpty() && Random.nextFloat() < WRONG_BOOK_WEIGHT) {
            picked = eligibleWrong.random(Random)
        }

        // 拒绝采样替代全量 filter：原来每答一题都对全部四千多个词
        // 建两个临时 List（两轮 O(N) 分配 + 每个元素跑一遍判定），
        // 这是背单词页最直接的卡顿来源。改成随机抽有限次、命中即返回——
        // 拒绝采样是无偏的，抽到的分布与「全量过滤后随机」完全一致，
        // 代价从 O(N) 降到 O(尝试次数)。
        if (picked == null) {
            val attempts = (allWords.size * 0.2f).toInt().coerceIn(80, 400)
            var tried = 0
            while (tried < attempts && picked == null) {
                tried++
                val w = allWords.random(Random)
                if (!blocked(w.word) && isEligibleSenseWord(w) && isUnmastered(w)) {
                    picked = w
                }
            }
        }
        if (picked != null) return@withContext picked

        // 采样一直没中：改走精确筛选，保证「还有候选就绝不抽已掌握的词」。
        val eligibleFresh = allWords.filter {
            !blocked(it.word) && isEligibleSenseWord(it) && isUnmastered(it)
        }
        if (eligibleFresh.isNotEmpty()) {
            return@withContext eligibleFresh.random(Random)
        }
        // 错词本还有词但新词池空了就用错词本，别开天窗
        if (eligibleWrong.isNotEmpty()) {
            return@withContext eligibleWrong.random(Random)
        }

        // 4. 兜底回退：候选全空时放宽限制，但依然绝对排除上一道刚答过的词
        val lastWord = recentWords.lastOrNull()
        val fallbackWords = allWords.filter { it.word != lastWord && isEligibleSenseWord(it) }
        if (fallbackWords.isNotEmpty()) {
            return@withContext fallbackWords.random(Random)
        }

        if (isSenseMode) {
            vocabRepo.getMultiSenseWords().randomOrNull(Random)
        } else {
            allWords.randomOrNull(Random)
        }
    }

    /**
     * 换一批释义：**不换词**，只把当前题的选项重新抽一遍。
     *
     * 用户诉求：「换一个词」理解错了——想要的不是抽一道新题，
     * 而是「这几个释义/选项我看着别扭，换一批」。所以这里锚定
     * [WordbookUiState.currentWord] 重新构题：
     * - 四选一：重抽 3 个干扰项，答案还是当前词；
     * - 义项辨析：重抽一个义项 + 新干扰项（[VocabRepo.buildSenseQuestion]
     *   内部对同一单词随机取义项），构不出来时退回按当前词出四选一；
     * - 拼写 / 卡片模式没有「一批选项」可换，按钮不出现。
     *
     * 作答状态整题复位（[presentWord] 里统一 copy），答过的题也能换。
     * 不压历史栈：换的是同一道题的选项，「上一题」应回到上一个词，
     * 而不是在同一词的两批选项之间打转。
     */
    fun reshuffleOptions() {
        val state = _uiState.value
        if (state.isLoading || isActionInProgress.get()) return
        val word = state.currentWord
        if (word == null || state.studyMode == StudyMode.SPELL) {
            // 没有当前题（词库空了）：退回抽新题，别让按钮变成死的
            prepareNextQuestion()
            return
        }
        nextQuestionJob?.cancel()
        nextQuestionJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            presentWord(word)
        }
    }

    /**
     * 四选一模式选择选项（带严格防连击与作答状态原子锁）。
     */
    fun selectOption(index: Int) {
        val state = _uiState.value
        // 已作答的题默认锁死；但**回退/切模式重放**回来的题允许改选
        // （allowReselect）。用户原话：「仍然保留刚才的状态但是可以修改」。
        val answeredLocked = state.isAnswered && !state.allowReselect
        if (state.isLoading || answeredLocked || index !in state.options.indices || isActionInProgress.get()) return
        val current = state.currentWord ?: return
        if (!isActionInProgress.compareAndSet(false, true)) return

        // **改选**（回退后重选）不再重复计数：这一题已经记过一次分，
        // 再记一次会把进度刷成两遍。只更新选中与判定。
        val isReselect = state.isAnswered
        if (!isReselect) {
            sessionAnswerCount++
            recordRecentWord(current.word)
        }
        val selectedOption = state.options[index]
        val isCorrect = selectedOption.isCorrect

        val updatedOptions = state.options.mapIndexed { idx, opt ->
            opt.copy(isSelected = idx == index)
        }

        _uiState.update {
            it.copy(
                selectedOptionIndex = index,
                isAnswered = true,
                // 改选后仍保留「可改」：用户回退到旧题就是来改主意的，
                // 改一次又锁死等于只给一次反悔机会。只有**首次作答**
                // 才落锁（首答后的锁定交给自动跳题 / 答错驻留来接管）。
                allowReselect = state.allowReselect,
                options = updatedOptions
            )
        }
        // 作答痕迹入存档：切模式逛一圈回来，看到的还是「刚选的这个」
        captureModeSnapshot()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!isReselect) {
                    recordAnswer(
                        current = current,
                        isCorrect = isCorrect,
                        studyModeLabel = if (state.studyMode == StudyMode.SENSE) "sense" else "quiz"
                    )
                }
            } finally {
                if (!isCorrect) {
                    // 答错后留在当前界面展示解析卡片，释放锁让用户可以点击「下一题」
                    isActionInProgress.set(false)
                }
            }
        }

        // 选对自动进入下一题：380ms 极短延迟（视觉保留绿标确认感，节奏紧凑跟手）
        //
        // **改选不自动跳题**：用户是回退回来改主意的，改完立刻被弹走，
        // 等于没改成。改选后就留在这一题，让他自己看结果再点「下一题」。
        if (isCorrect && !isReselect) {
            viewModelScope.launch {
                try {
                    delay(380L)
                    val s = _uiState.value
                    if (s.isAnswered && s.currentWord?.word == current.word && (s.studyMode == StudyMode.QUIZ || s.studyMode == StudyMode.SENSE)) {
                        loadNextQuestionInternal()
                    }
                } finally {
                    isActionInProgress.set(false)
                }
            }
        } else if (isCorrect && isReselect) {
            //
            // **锁死 bug 的根因**：防连击锁的释放只挂在两条路径上——
            // 「首答答对」靠自动跳题 job 的 finally，「答错」靠
            // recordAnswer job 的 finally。而「改选 + 答对」两条 job
            // 都不会跑（改选不记账、不自动跳），锁就永远悬着，
            // 之后每一次 selectOption 都被 isActionInProgress 拦下——
            // 用户实测「选对了→返回上一个→锁定死了不能选择」。
            // 改选答对不跳题，就地释放锁。
            isActionInProgress.set(false)
        }
    }

    /**
     * 记录一次作答（四选一 / 义项辨析 / 拼写共用同一条打分路径）。
     *
     * 抽出来是因为三条路径的「答对/答错」后果完全一致：
     * 答对 → 连对 +1、掌握度提到「认识」、错词本连对够次数就毕业；
     * 答错 → 进错词本、连对归零、8 题后安排重现强化。
     * 以前四选一和卡片各写一份，改毕业条件要改两处，
     * 迟早漏一处（上一版就出现过 UI 显示 x/3 而逻辑是 x/1）。
     *
     * @param studyModeLabel 写进流水表的模式标记（quiz / sense / spell）。
     */
    private suspend fun recordAnswer(current: Word, isCorrect: Boolean, studyModeLabel: String) {
        val now = System.currentTimeMillis()
        val oldProgress = dao.getProgress(current.word) ?: WordProgress(word = current.word)

        val newProgress = if (isCorrect) {
            val newReps = oldProgress.reps + 1
            // 错题在间隔复习中连对 2 次才毕业移出错词本（保留曾错标记）。
            // 1 次太松（刚看完解析带着印象选对，并没真记住），3 次太严
            // （错词本越积越厚）。规则见 WordbookRules.GRADUATION_REPS。
            val outOfWrongBook = oldProgress.isWrongBook && WordbookRules.shouldGraduateFromWrongBook(newReps)
            oldProgress.copy(
                level = maxOf(oldProgress.level, 2),
                reps = newReps,
                correctCount = oldProgress.correctCount + 1,
                isWrongBook = if (outOfWrongBook) false else oldProgress.isWrongBook,
                lastAnsweredAt = now,
                updatedAt = now
            )
        } else {
            // 答错惩罚：进入错词本，归零连对，并安排 8 题后重现强化
            deferCount[current.word] = (deferCount[current.word] ?: 0) + 1
            deferQueue.add(DeferItem(current, sessionAnswerCount + 8))
            oldProgress.copy(
                level = 0,
                reps = 0,
                lapses = oldProgress.lapses + 1,
                wrongCount = oldProgress.wrongCount + 1,
                isWrongBook = true,
                everWrong = true,
                lastAnsweredAt = now,
                updatedAt = now
            )
        }

        dao.upsertProgress(newProgress)
        // 内存缓存同步增量：下一次抽词直接读缓存，不再全表读 progress
        progressCache[current.word] = newProgress
        wrongWordsCache = if (newProgress.isWrongBook) {
            wrongWordsCache + current.word
        } else {
            wrongWordsCache - current.word
        }
        dao.insertStudyLog(
            WordStudyLog(
                word = current.word,
                isCorrect = isCorrect,
                studyMode = studyModeLabel,
                answeredAt = now
            )
        )

        // 只在界面还停在这道题时才回写进度：作答落库是异步的，
        // 期间可能已经自动切到下一题，这时把旧词的进度塞给新题
        // 会让收藏/熟词图标显示错位。
        _uiState.update { st ->
            if (st.currentWord?.word == current.word) st.copy(currentProgress = newProgress) else st
        }
    }

    /**
     * 拼写模式：更新输入框内容。
     *
     * 只在未提交时允许输入——提交后输入框锁定，
     * 否则用户改了答案却还沿用旧的判定结果。
     */
    fun setSpellInput(text: String) {
        val state = _uiState.value
        if (state.studyMode != StudyMode.SPELL || state.isAnswered) return
        _uiState.update { it.copy(spellInput = text) }
    }

    /**
     * 拼写模式：提交作答。
     *
     * 判定规则见 [SpellRules]：忽略大小写与首尾空格，严格比对拼写。
     * 答对 600ms 后自动进入下一题（比四选一的 380ms 稍长——
     * 拼写要低头看自己写的字母，闪一下就走等于没看到对错）；
     * 答错停在当前题，展示正确拼写与释义，等用户点「下一题」。
     */
    fun submitSpell() {
        val state = _uiState.value
        if (state.isLoading || state.isAnswered) return
        if (state.studyMode != StudyMode.SPELL) return
        val current = state.currentWord ?: return
        val input = state.spellInput
        if (input.isBlank()) {
            _uiState.update { it.copy(snackbarMessage = "先写出你的答案再提交") }
            return
        }
        if (!isActionInProgress.compareAndSet(false, true)) return

        sessionAnswerCount++
        recordRecentWord(current.word)
        val isCorrect = SpellRules.isCorrect(input, current.word)

        _uiState.update {
            it.copy(
                isAnswered = true,
                spellSubmitted = true,
                spellCorrect = isCorrect
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                recordAnswer(current = current, isCorrect = isCorrect, studyModeLabel = "spell")
            } finally {
                if (!isCorrect) {
                    // 答错停留当前题，释放锁让用户可以点「下一题」
                    isActionInProgress.set(false)
                }
            }
        }

        if (isCorrect) {
            viewModelScope.launch {
                try {
                    delay(600L)
                    val s = _uiState.value
                    if (s.isAnswered && s.currentWord?.word == current.word && s.studyMode == StudyMode.SPELL) {
                        loadNextQuestionInternal()
                    }
                } finally {
                    isActionInProgress.set(false)
                }
            }
        }
    }

    /**
     * 卡片模式翻面。
     */
    fun revealCard() {
        _uiState.update { it.copy(isCardRevealed = true) }
    }

    /**
     * 卡片模式评分打分（严格防狂点重入，杜绝重复写入 Room 与竞争跳题）：
     * 0: 遗忘 (Bad)
     * 1: 模糊 (Fuzzy)
     * 2: 认识 (Good)
     * 3: 熟练 (Master)
     */
    fun rateCard(rating: Int) {
        val state = _uiState.value
        if (state.isLoading || isActionInProgress.get()) return
        val current = state.currentWord ?: return
        if (!isActionInProgress.compareAndSet(false, true)) return

        sessionAnswerCount++
        recordRecentWord(current.word)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val old = dao.getProgress(current.word) ?: WordProgress(word = current.word)

                val updated = when (rating) {
                    0 -> {
                        deferCount[current.word] = (deferCount[current.word] ?: 0) + 1
                        deferQueue.add(DeferItem(current, sessionAnswerCount + 8))
                        old.copy(
                            level = 0,
                            reps = 0,
                            lapses = old.lapses + 1,
                            wrongCount = old.wrongCount + 1,
                            isWrongBook = true,
                            everWrong = true,
                            lastAnsweredAt = now,
                            updatedAt = now
                        )
                    }
                    1 -> {
                        old.copy(
                            level = 1,
                            reps = 0,
                            correctCount = old.correctCount + 1,
                            lastAnsweredAt = now,
                            updatedAt = now
                        )
                    }
                    2 -> {
                        val newReps = old.reps + 1
                        val outOfWrongBook = old.isWrongBook && WordbookRules.shouldGraduateFromWrongBook(newReps)
                        old.copy(
                            level = 2,
                            reps = newReps,
                            correctCount = old.correctCount + 1,
                            isWrongBook = if (outOfWrongBook) false else old.isWrongBook,
                            lastAnsweredAt = now,
                            updatedAt = now
                        )
                    }
                    else -> {
                        old.copy(
                            level = 3,
                            reps = old.reps + 1,
                            correctCount = old.correctCount + 1,
                            isMastered = true,
                            isWrongBook = false,
                            lastAnsweredAt = now,
                            updatedAt = now
                        )
                    }
                }

                dao.upsertProgress(updated)
                dao.insertStudyLog(
                    WordStudyLog(
                        word = current.word,
                        isCorrect = rating >= 2,
                        studyMode = "card",
                        answeredAt = now
                    )
                )

                loadNextQuestionInternal()
            } finally {
                isActionInProgress.set(false)
            }
        }
    }

    /**
     * 一键标记/取消熟词（☆，带防抖与原子并发控制）。
     */
    fun toggleMasterCurrentWord() {
        val current = _uiState.value.currentWord ?: return
        if (!isTogglingMaster.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val old = dao.getProgress(current.word) ?: WordProgress(word = current.word)
                val nextMastered = !old.isMastered
                val updated = old.copy(
                    isMastered = nextMastered,
                    level = if (nextMastered) 3 else old.level,
                    isWrongBook = if (nextMastered) false else old.isWrongBook,
                    updatedAt = System.currentTimeMillis()
                )
                dao.upsertProgress(updated)
                syncProgressCache(updated)
                _uiState.update {
                    it.copy(
                        currentProgress = updated,
                        snackbarMessage = if (nextMastered) "已标记为熟词，后续不再安排练习" else "已取消熟词"
                    )
                }
            } finally {
                isTogglingMaster.set(false)
            }
        }
    }

    /**
     * 收藏/取消收藏当前单词（生词本，书签图标）。
     *
     * 与熟词标记完全独立：收藏不影响抽词权重、不影响错题本状态，
     * 纯粹是「我想重点关照这个词」的手动清单。取消收藏只是把书签
     * 拔掉，学习进度原样保留。
     */
    fun toggleFavoriteCurrentWord() {
        val current = _uiState.value.currentWord ?: return
        if (!isTogglingFavorite.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val old = dao.getProgress(current.word) ?: WordProgress(word = current.word)
                val nextFavorite = !old.isFavorite
                val updated = old.copy(
                    isFavorite = nextFavorite,
                    updatedAt = System.currentTimeMillis()
                )
                dao.upsertProgress(updated)
                syncProgressCache(updated)
                _uiState.update {
                    it.copy(
                        currentProgress = updated,
                        snackbarMessage = if (nextFavorite) "已收进生词本" else "已从生词本移除"
                    )
                }
                // 生词本子页正开着时才值得刷新；刷题页上收藏不改变任何列表
                if (_uiState.value.currentTab == WordbookTab.WRONG_BOOK &&
                    _uiState.value.wrongSubTab == WrongSubTab.FAVORITE
                ) {
                    refreshWrongSubTabList(WrongSubTab.FAVORITE)
                }
            } finally {
                isTogglingFavorite.set(false)
            }
        }
    }

    /** 列表页（词库检索 / 生词本）里的收藏切换。 */
    fun toggleFavorite(wordStr: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val p = dao.getProgress(wordStr) ?: return@launch
            val updated = p.copy(
                isFavorite = !p.isFavorite,
                updatedAt = System.currentTimeMillis()
            )
            dao.upsertProgress(updated)
            syncProgressCache(updated)
            refreshWrongSubTabList(_uiState.value.wrongSubTab)
            // 词库页也显示收藏态：刷新一次列表让书签图标即时翻转
            if (_uiState.value.currentTab == WordbookTab.LIBRARY) {
                refreshLibraryList()
            }
        }
    }

    /** 朗读当前单词（TTS）。初始化中的点击会被暂存、就绪后补发（见 WordAudioPlayer）。 */
    fun speakCurrentWord() {
        val word = _uiState.value.currentWord?.word ?: return
        speakWithFallback(word)
    }

    /** 列表页朗读指定单词。 */
    fun speakWord(wordStr: String) {
        speakWithFallback(wordStr)
    }

    /**
     * 朗读 + 失败兜底：设备没有任何可用 TTS 引擎时（部分 ROM 默认不带），
     * 旧版是「点了完全没反应」，用户只会以为按钮坏了。
     * 这里用 snackbar 把原因和出路说清楚。
     */
    private fun speakWithFallback(text: String) {
        if (!audioPlayer.speak(text)) {
            _uiState.update {
                it.copy(snackbarMessage = "本机没有可用的语音引擎：请在系统设置搜索「文字转语音」启用一个（如 Google TTS / 小爱朗读）")
            }
        }
    }

    // =========================================================================
    // 错词本子列表加载
    // =========================================================================

    private fun refreshWrongSubTabList(subTab: WrongSubTab) {
        viewModelScope.launch(Dispatchers.IO) {
            val progressList = when (subTab) {
                WrongSubTab.WRONG_BOOK -> dao.observeWrongBook().first()
                WrongSubTab.EVER_WRONG -> dao.observeEverWrong().first()
                WrongSubTab.MASTERED -> dao.observeMastered().first()
                WrongSubTab.FAVORITE -> dao.observeFavorite().first()
            }

            val pairedList = progressList.mapNotNull { p ->
                vocabRepo.getWord(p.word)?.let { w -> w to p }
            }

            _uiState.update { it.copy(wrongBookList = pairedList) }
        }
    }

    fun removeFromWrongBook(wordStr: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val p = dao.getProgress(wordStr) ?: return@launch
            val updated = p.copy(isWrongBook = false, updatedAt = System.currentTimeMillis())
            dao.upsertProgress(updated)
            syncProgressCache(updated)
            refreshWrongSubTabList(_uiState.value.wrongSubTab)
            _uiState.update { it.copy(snackbarMessage = "已移出错题本") }
        }
    }

    // =========================================================================
    // 词库检索与筛选
    // =========================================================================

    fun setLibraryKeyword(keyword: String) {
        _uiState.update { it.copy(libraryKeyword = keyword) }
        triggerLibrarySearch(debounceMs = 200L)
    }

    fun setLibraryFilterLevel(level: Int?) {
        _uiState.update { it.copy(libraryFilterLevel = level) }
        triggerLibrarySearch(debounceMs = 0L)
    }

    fun refreshLibraryList() {
        triggerLibrarySearch(debounceMs = 0L)
    }

    private fun triggerLibrarySearch(debounceMs: Long) {
        librarySearchJob?.cancel()
        librarySearchJob = viewModelScope.launch(Dispatchers.IO) {
            if (debounceMs > 0) {
                delay(debounceMs)
            }
            val kw = _uiState.value.libraryKeyword
            val filterLv = _uiState.value.libraryFilterLevel
            val matchedWords = vocabRepo.search(kw)
            val allProgress = dao.observeAllProgress().first().associateBy { it.word }

            val limit = 100
            val filtered = if (filterLv == null && kw.isBlank()) {
                // 搜索框为空且未筛选等级时，极速懒截取前 100 词，避免遍历全量 4356 词
                matchedWords.take(limit).map { w -> w to allProgress[w.word] }
            } else {
                // 配合 Sequence 惰性流截取，满足 limit 后立即终止遍历，杜绝内存开销与卡顿
                matchedWords.asSequence()
                    .mapNotNull { w ->
                        val p = allProgress[w.word]
                        val currentLevel = p?.level ?: 0
                        if (filterLv == null || currentLevel == filterLv) {
                            w to p
                        } else null
                    }
                    .take(limit)
                    .toList()
            }

            _uiState.update { it.copy(libraryWords = filtered) }
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun getStartOfDayMillis(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}
