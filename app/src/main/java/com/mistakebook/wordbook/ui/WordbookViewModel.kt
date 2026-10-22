package com.mistakebook.wordbook.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.di.AppContainer
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
    private var sessionAnswerCount = 0

    // 最近刷过的单词滑动窗口（冷却窗口，严格防止单词连着连续出现）
    private val recentWords = ArrayDeque<String>()
    private val COOL_DOWN_WINDOW = 8

    // 「上一题」历史栈：每离开一题就把刚做的题**连同作答现场**压栈，
    // 回退时原样重放（选项、选中、对错都不重抽）——见 [QuestionSnapshot]。
    // 限长防止无限增长——回看 30 题足够，再往前的没有记忆价值。
    private val questionHistory = ArrayDeque<QuestionSnapshot>()
    private val HISTORY_LIMIT = 30

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
                isCardRevealed = state.isCardRevealed
            )
        )
        while (questionHistory.size > HISTORY_LIMIT) {
            questionHistory.removeFirst()
        }
        _uiState.update { it.copy(hasPrevious = questionHistory.isNotEmpty()) }
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

    fun setStudyMode(mode: StudyMode) {
        if (_uiState.value.studyMode != mode) {
            _uiState.update { it.copy(studyMode = mode) }
            prepareNextQuestion()
        }
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
        if (_uiState.value.studyMode == StudyMode.SENSE) {
            var senseQ: SenseQuestion? = null
            var candidate: Word? = word
            var retryCount = 0
            val maxRetries = 10

            // 循环重选重试，确保 SENSE 模式下必定构建出有效义项题
            while (senseQ == null && retryCount < maxRetries && candidate != null) {
                senseQ = vocabRepo.buildSenseQuestion(candidate)
                if (senseQ != null) break
                retryCount++
                candidate = pickNextWord()
            }

            // 若候选词多次尝试未成，进行全局随机多义词兜底
            if (senseQ == null) {
                senseQ = vocabRepo.buildSenseQuestion(null)
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
                        isCardRevealed = false,
                        isLoading = false
                    )
                }
            } else {
                // 极端兜底：绝不降级四选一穿帮破坏题意，呈现清晰空状态
                _uiState.update {
                    it.copy(
                        currentWord = null,
                        currentProgress = null,
                        currentSense = null,
                        options = emptyList(),
                        selectedOptionIndex = null,
                        isAnswered = false,
                        isCardRevealed = false,
                        isLoading = false
                    )
                }
            }
            return
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
                isCardRevealed = false,
                isLoading = false
            )
        }
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
                isCardRevealed = snapshot.isCardRevealed,
                isLoading = false,
                hasPrevious = questionHistory.isNotEmpty()
            )
        }
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

        // 2. 错词本温和复习机制：
        //    - 权重由原先过重的 70% 降低至 20%（平滑融入日常刷题节奏，不反客为主）；
        //    - 严格排除当前冷却窗口（recentWords）以及等待短期重现（deferQueue）中的词。
        //    原来这里 `observeWrongBook().first()` 读全表、再逐词 `getWord`，
        //    现在读内存里的错词集合（答题后增量维护），抽词不再碰数据库。
        val allWords = vocabRepo.getAllWords()
        if (allWords.isEmpty()) return@withContext null

        val pendingDeferWords = deferQueue.map { it.word.word }.toSet()

        if (Random.nextFloat() < 0.20f) {
            val eligibleWrong = wrongWordsCache
                .filter { it !in recentWords && it !in pendingDeferWords }
                .mapNotNull { vocabRepo.getWord(it) }
                .filter { isEligibleSenseWord(it) }
            if (eligibleWrong.isNotEmpty()) return@withContext eligibleWrong.random(Random)
        }

        // 3. 抽取未掌握的新词/生词（排除熟词、冷却窗口以及排队中的重现词）
        //
        // **拒绝采样**替代全量 filter：原来每答一题都对全部四千多个词
        // 建两个临时 List（两轮 O(N) 分配 + 每个元素跑一遍判定），
        // 这是背单词页最直接的卡顿来源。改成随机抽有限次、命中即返回——
        // 拒绝采样是无偏的，抽到的分布与「全量过滤后随机」完全一致，
        // 代价从 O(N) 降到 O(尝试次数)。
        fun isUnmastered(w: Word): Boolean {
            val p = progressCache[w.word]
            return p == null || (!p.isMastered && p.level < 3)
        }

        val attempts = (allWords.size * 0.2f).toInt().coerceIn(80, 400)
        var picked: Word? = null
        var tried = 0
        while (tried < attempts && picked == null) {
            tried++
            val w = allWords.random(Random)
            if (w.word !in recentWords && w.word !in pendingDeferWords &&
                isEligibleSenseWord(w) && isUnmastered(w)
            ) {
                picked = w
            }
        }
        if (picked != null) return@withContext picked

        // 采样一直没中，说明未掌握的词已经很少了——这时才付 O(N) 的代价
        // 精确过滤，保证「还有未掌握词就绝不抽已掌握词」的语义不被采样近似破坏。
        val unmastered = allWords.filter { w ->
            w.word !in recentWords && w.word !in pendingDeferWords &&
                isEligibleSenseWord(w) && isUnmastered(w)
        }

        if (unmastered.isNotEmpty()) {
            return@withContext unmastered.random(Random)
        }

        // 4. 兜底回退：若未掌握词已抽完，放宽限制，但依然绝对排除上一道刚答过的词
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
     * 四选一模式选择选项（带严格防连击与作答状态原子锁）。
     */
    fun selectOption(index: Int) {
        val state = _uiState.value
        if (state.isLoading || state.isAnswered || index !in state.options.indices || isActionInProgress.get()) return
        val current = state.currentWord ?: return
        if (!isActionInProgress.compareAndSet(false, true)) return

        sessionAnswerCount++
        recordRecentWord(current.word)
        val selectedOption = state.options[index]
        val isCorrect = selectedOption.isCorrect

        val updatedOptions = state.options.mapIndexed { idx, opt ->
            opt.copy(isSelected = idx == index)
        }

        _uiState.update {
            it.copy(
                selectedOptionIndex = index,
                isAnswered = true,
                options = updatedOptions
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
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
                    // 答错惩罚：进入错题本，归零连对，并安排 8 题后重现强化
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
                        studyMode = if (state.studyMode == StudyMode.SENSE) "sense" else "quiz",
                        answeredAt = now
                    )
                )

                _uiState.update { it.copy(currentProgress = newProgress) }
            } finally {
                if (!isCorrect) {
                    // 答错后留在当前界面展示解析卡片，释放锁让用户可以点击「下一题」
                    isActionInProgress.set(false)
                }
            }
        }

        // 选对自动进入下一题：380ms 极短延迟（视觉保留绿标确认感，节奏紧凑跟手）
        if (isCorrect) {
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

    /** 朗读当前单词（TTS）。未就绪时静默忽略——初始化是异步的，首次点击可能没声音。 */
    fun speakCurrentWord() {
        val word = _uiState.value.currentWord?.word ?: return
        audioPlayer.speak(word)
    }

    /** 列表页朗读指定单词。 */
    fun speakWord(wordStr: String) {
        audioPlayer.speak(wordStr)
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
