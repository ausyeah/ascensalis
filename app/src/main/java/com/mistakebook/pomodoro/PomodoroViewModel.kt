package com.mistakebook.pomodoro

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.local.PomodoroDao
import com.mistakebook.data.local.SessionWithTask
import com.mistakebook.data.local.SubjectDao
import com.mistakebook.data.local.SubjectTotal
import com.mistakebook.data.local.entities.PomodoroTask
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.pomodoro.stats.PomodoroStats
import com.mistakebook.pomodoro.stats.StatsRange
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate
import java.time.ZoneId

data class PomodoroUiState(
    val tasks: List<PomodoroTask> = emptyList(),
    val subjects: List<Subject> = emptyList(),
    /** 学科筛选：null = 全部。 */
    val subjectFilter: Long? = null,
    val selectedTaskId: Long? = null,
    val mode: FocusMode = FocusMode.POMODORO,
    val status: RunStatus = RunStatus.IDLE,
    val targetMs: Long = FocusMode.POMODORO.defaultTargetMs(),
    val elapsedMs: Long = 0L,
    /** 本次是否走完了目标（倒计时归零）。 */
    val finished: Boolean = false,
    val interruptedByReboot: Boolean = false,
    // ---- 统计（只在记录变化时刷新，不跟秒 tick）----
    val todaySec: Long = 0L,
    val todayPomodoros: Int = 0,
    val streakDays: Int = 0,
    val totalSec: Long = 0L,
    val totalCompleted: Int = 0,
    val sessionCount: Int = 0,
    val last7: List<Long> = List(7) { 0L },
    val last30: List<Long> = List(30) { 0L },
    val taskTotals: List<PomodoroStats.TaskRow> = emptyList(),
    val subjectTotals: List<SubjectTotal> = emptyList(),
    val recent: List<SessionWithTask> = emptyList(),
    // ---- 任务 / 学科编辑 ----
    val showNewTask: Boolean = false,
    val newTaskName: String = "",
    val newTaskSubjectId: Long? = null,
    /** 长按任务弹出的编辑面板（null = 关着）。 */
    val editingTask: PomodoroTask? = null,
    /** 长按学科弹出的编辑面板（null = 关着）。 */
    val editingSubject: Subject? = null,
    val showNewSubject: Boolean = false,
    val newSubjectName: String = "",
    /** 统计页当前选的时间范围。 */
    val statsRange: StatsRange = StatsRange.ALL,
    val message: String? = null
) {
    /** 完成率（0f~1f），统计页「目标达成」用。 */
    val completionRate: Float
        get() = if (sessionCount <= 0) 0f else totalCompleted.toFloat() / sessionCount
}

class PomodoroViewModel(
    app: Application,
    private val dao: PomodoroDao,
    private val subjectDao: SubjectDao,
    private val timer: PomodoroTimer,
    /** 学科列表：复用错题本那套学科（见 PomodoroTask 的注释）。 */
    private val subjectsFlow: StateFlow<List<Subject>>
) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(PomodoroUiState())
    val ui: StateFlow<PomodoroUiState> = _ui

    private var subjectFilter = MutableStateFlow<Long?>(null)
    private var selectedTaskId = MutableStateFlow<Long?>(null)
    private var newTaskOpen = MutableStateFlow(false)
    private var editingTask = MutableStateFlow<PomodoroTask?>(null)
    private var editingSubject = MutableStateFlow<Subject?>(null)
    private var newSubjectOpen = MutableStateFlow(false)
    private var statsRange = MutableStateFlow(StatsRange.ALL)
    private var message = MutableStateFlow<String?>(null)

    /** 统计单独存一份：它只在「记录变化」时刷新，不该跟着秒 tick 重算。 */
    private val stats = MutableStateFlow(PomodoroStats.Snapshot())

    /**
     * 每秒变化的**秒表读数**。
     *
     * **必须独立成一条流**：如果塞进主 ui 状态里，屏幕顶层一订阅就会让
     * 整棵树（任务选择器、统计卡片、柱状图）每秒重组一次——
     * 而它们一秒都没变过。只有表盘需要它。
     */
    val tick: StateFlow<Long> get() = _tick
    private val _tick = MutableStateFlow(0L)

    init {
        // 首次进入给一条「自由专注」，避免空页面逼用户先建任务才能计时
        viewModelScope.launch {
            if (dao.activeTaskCount() == 0) {
                dao.insertTask(
                    PomodoroTask(name = PomodoroTask.DEFAULT_NAME, sortOrder = 0)
                )
            }
            stats.value = PomodoroStats.snapshot(dao, statsRange.value)
        }

        // 主状态流：任务 / 学科 / 选择 / 弹窗 / 提示 / 统计。
        // **刻意不含 ticker**——每秒重算一次统计等于每秒查六次数据库，
        // 而统计一天也变不了几次。
        viewModelScope.launch {
            combine(
                dao.observeTasks(),
                subjectsFlow,
                subjectFilter,
                selectedTaskId,
                newTaskOpen,
                editingTask,
                editingSubject,
                newSubjectOpen,
                statsRange,
                message,
                stats
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val snapshot = values[10] as PomodoroStats.Snapshot
                PomodoroUiState(
                    tasks = values[0] as List<PomodoroTask>,
                    subjects = values[1] as List<Subject>,
                    subjectFilter = values[2] as Long?,
                    selectedTaskId = values[3] as Long?,
                    newTaskName = _ui.value.newTaskName,
                    newTaskSubjectId = _ui.value.newTaskSubjectId,
                    showNewTask = values[4] as Boolean,
                    editingTask = values[5] as PomodoroTask?,
                    editingSubject = values[6] as Subject?,
                    showNewSubject = values[7] as Boolean,
                    statsRange = values[8] as StatsRange,
                    message = values[9] as String?,
                    todaySec = snapshot.todaySec,
                    todayPomodoros = snapshot.todayPomodoros,
                    streakDays = snapshot.streakDays,
                    totalSec = snapshot.totalSec,
                    totalCompleted = snapshot.totalCompleted,
                    sessionCount = snapshot.sessionCount,
                    last7 = snapshot.last7,
                    last30 = snapshot.last30,
                    taskTotals = snapshot.taskTotals,
                    subjectTotals = snapshot.subjectTotals,
                    recent = snapshot.recent,
                    mode = timer.state.value.mode,
                    status = timer.state.value.status,
                    targetMs = timer.state.value.targetMs,
                    elapsedMs = timer.elapsedMs(),
                    finished = timer.isFinished(),
                    interruptedByReboot = timer.state.value.interruptedByReboot
                )
            }.collect { _ui.value = it }
        }

        // 秒刷新：只有**真的在计时**才跑，空闲时一秒一次的空转毫无意义。
        // 每轮顺手 checkpoint 一次，进程猝死最多丢一秒。
        //
        // checkpoint 每 5 秒才落一次盘：SharedPreferences.apply 虽是异步，
        // 每秒一次的全量写入在低端机上仍能看出调度抖动，而它的价值只是
        // 「进程猝死时少丢几秒」——5 秒粒度完全够（恢复时按单调时钟现算，
        // 丢的只是 base 里没固化的那部分，且恢复后是暂停态等用户决定）。
        var tickCount = 0
        viewModelScope.launch {
            while (true) {
                delay(1_000L)
                if (timer.state.value.status == RunStatus.IDLE) {
                    // 归位：空闲时秒表读数必须回零。表盘订阅的是这条流，
                    // 流里留着上一次会话的残留（倒计时结束时恰是 targetMs），
                    // 表盘就会在已结束的状态下一直显示 00:00 或旧的正计时读数。
                    if (_tick.value != 0L) _tick.value = 0L
                    continue
                }
                tickCount++
                if (tickCount % CHECKPOINT_EVERY_TICKS == 0L) timer.checkpoint()
                _tick.value = timer.elapsedMs()
                // 状态本身（开始/暂停/到点）变化频率极低，但仍要同步，
                // 否则「到点了」这件事界面不会知道。通知栏里操作了
                // 暂停/结束，也靠这一条在 1 秒内反映到界面。
                if (timer.state.value.status != _ui.value.status ||
                    timer.isFinished() != _ui.value.finished ||
                    timer.state.value.targetMs != _ui.value.targetMs
                ) {
                    _ui.update {
                        it.copy(
                            status = timer.state.value.status,
                            finished = timer.isFinished(),
                            targetMs = timer.state.value.targetMs,
                            elapsedMs = timer.elapsedMs()
                        )
                    }
                }
                refreshStatsIfNeeded()
            }
        }
    }

    /** 倒计时到点后统计不会自己变，但每日/总数会随会话落库而变；
     *  这里做一次轻量刷新，只在运行态每 30 秒最多一次。 */
    private var lastStatsAt = 0L
    private suspend fun refreshStatsIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastStatsAt < 30_000L) return
        lastStatsAt = now
        stats.value = PomodoroStats.snapshot(dao, statsRange.value)
    }

    private suspend fun refreshStats() {
        lastStatsAt = System.currentTimeMillis()
        stats.value = PomodoroStats.snapshot(dao, statsRange.value)
    }

    // ------------------------------------------------------------ 计时控制

    fun start() {
        val taskId = _ui.value.selectedTaskId
            ?: run {
                message.value = "先选一个任务"
                return
            }
        val mode = _ui.value.mode
        timer.start(mode, taskId, _ui.value.targetMs)
        val name = _ui.value.tasks.firstOrNull { it.id == taskId }?.name.orEmpty()
        PomodoroService.start(getApplication(), name)
        syncTimerUi()
    }

    fun pause() {
        timer.pause()
        PomodoroService.refresh(getApplication(), taskName())
        syncTimerUi()
    }

    fun resume() {
        timer.resume()
        PomodoroService.refresh(getApplication(), taskName())
        syncTimerUi()
    }

    /** 结束并落库。 */
    fun stop(save: Boolean = true) {
        if (!save) {
            timer.harvest()
            PomodoroService.stop(getApplication())
            syncTimerUi()
            return
        }
        viewModelScope.launch {
            val result = PomodoroRecorder.stopAndRecord(timer, dao)
            PomodoroService.stop(getApplication())
            if (result != null) {
                refreshStats()
                message.value = result
            }
            syncTimerUi()
        }
    }

    private fun taskName(): String {
        val id = timer.state.value.taskId ?: _ui.value.selectedTaskId
        return _ui.value.tasks.firstOrNull { it.id == id }?.name.orEmpty()
    }

    // ------------------------------------------------------------ 模式与目标

    /** 长按主按钮：循环切换模式。运行中不允许切（会打断这一次计时）。 */
    fun cycleMode() {
        if (_ui.value.status == RunStatus.RUNNING) {
            message.value = "正在计时，先结束再换模式"
            return
        }
        val next = FocusMode.next(_ui.value.mode)
        timer.setMode(next)
        syncTimerUi()
    }

    fun setMode(mode: FocusMode) {
        if (_ui.value.status == RunStatus.RUNNING) {
            message.value = "正在计时，先结束再换模式"
            return
        }
        timer.setMode(mode)
        syncTimerUi()
    }

    /**
     * 轮盘选定的目标时长（分钟）。
     *
     * 只对倒计时模式有意义；正计时拨了也不设目标（hasTarget=false）。
     * 上界 [MAX_TARGET_MINUTES]：轮盘小时位就到 12，再加 59 分钟，
     * 与选择器的表达范围严格一致，别让界面设得出、底层存不下。
     */
    fun setTargetMinutes(minutes: Int) {
        if (_ui.value.mode == FocusMode.STOPWATCH) return
        timer.setTargetMs(minutes.coerceIn(1, MAX_TARGET_MINUTES) * 60_000L)
        syncTimerUi()
    }

    // ------------------------------------------------------------ 任务

    fun selectSubject(filter: Long?) {
        subjectFilter.value = filter
    }

    fun selectTask(id: Long?) {
        selectedTaskId.value = id
    }

    fun openNewTask(open: Boolean) {
        newTaskOpen.value = open
    }

    fun onNewTaskName(name: String) {
        _ui.update { it.copy(newTaskName = name) }
    }

    fun onNewTaskSubject(id: Long?) {
        _ui.update { it.copy(newTaskSubjectId = id) }
    }

    fun createTask() {
        val name = _ui.value.newTaskName.trim()
        if (name.isBlank()) {
            message.value = "给任务起个名字"
            return
        }
        viewModelScope.launch {
            val id = dao.insertTask(
                PomodoroTask(
                    name = name,
                    subjectId = _ui.value.newTaskSubjectId,
                    sortOrder = _ui.value.tasks.size
                )
            )
            selectedTaskId.value = id
            _ui.update { it.copy(newTaskName = "", newTaskSubjectId = null) }
            newTaskOpen.value = false
            message.value = "任务已创建"
        }
    }

    fun archiveTask(id: Long) {
        viewModelScope.launch {
            dao.archiveTask(id)
            if (selectedTaskId.value == id) selectedTaskId.value = null
            if (editingTask.value?.id == id) editingTask.value = null
            message.value = "已归档，历史记录保留"
        }
    }

    /** 长按任务 → 打开编辑面板。 */
    fun editTask(task: PomodoroTask) {
        editingTask.value = task
    }

    fun closeEditTask() {
        editingTask.value = null
    }

    /** 编辑面板里改的名字/学科先落到编辑流，点保存才写库。
     *  （必须写 editingTask 流而不是 _ui：主状态流每次重组都会用
     *  流里的值覆盖 _ui，写 _ui 的话输入框会被冲掉。） */
    fun onEditTaskName(name: String) {
        editingTask.update { it?.copy(name = name) }
    }

    fun onEditTaskSubject(id: Long?) {
        editingTask.update { it?.copy(subjectId = id) }
    }

    fun saveEditedTask() {
        val task = _ui.value.editingTask ?: return
        val name = task.name.trim()
        if (name.isBlank()) {
            message.value = "任务名不能为空"
            return
        }
        viewModelScope.launch {
            dao.updateTask(task.copy(name = name))
            editingTask.value = null
            message.value = "已保存"
        }
    }

    fun deleteTask(id: Long) {
        viewModelScope.launch {
            dao.deleteTask(id)
            if (selectedTaskId.value == id) selectedTaskId.value = null
            if (timer.state.value.taskId == id) {
                // 删的是正在计时的任务：先收掉这次计时（不丢已专注的时长记录）
                stop(save = true)
            }
            editingTask.value = null
            message.value = "任务及其记录已删除"
        }
    }

    // ------------------------------------------------------------ 学科

    fun openNewSubject(open: Boolean) {
        newSubjectOpen.value = open
    }

    fun onNewSubjectName(name: String) {
        _ui.update { it.copy(newSubjectName = name) }
    }

    /**
     * 新建学科。
     *
     * 直接写错题本的 subjects 表而不是另建一套：番茄钟的任务本来就挂在
     * 这套学科下（外键），两处学科各说各话的话，改名要改两遍。
     */
    fun createSubject() {
        val name = _ui.value.newSubjectName.trim()
        if (name.isBlank()) {
            message.value = "给学科起个名字"
            return
        }
        viewModelScope.launch {
            val existing = subjectDao.findByName(name)
            if (existing != null) {
                message.value = "学科「$name」已存在"
                return@launch
            }
            subjectDao.insert(Subject(name = name, sortOrder = subjectDao.count()))
            _ui.update { it.copy(newSubjectName = "") }
            newSubjectOpen.value = false
            message.value = "学科已创建"
        }
    }

    // ------------------------------------------------------------ 学科编辑（长按）

    /** 长按学科 → 打开编辑面板。 */
    fun editSubject(subject: Subject) {
        editingSubject.value = subject
    }

    fun closeEditSubject() {
        editingSubject.value = null
    }

    /** 改名先落到编辑流，点保存才写库（与任务编辑同一套路，防 combine 覆盖）。 */
    fun onEditSubjectName(name: String) {
        editingSubject.update { it?.copy(name = name) }
    }

    fun saveEditedSubject() {
        val subject = _ui.value.editingSubject ?: return
        val name = subject.name.trim()
        if (name.isBlank()) {
            message.value = "学科名不能为空"
            return
        }
        viewModelScope.launch {
            val clash = subjectDao.findByName(name)
            if (clash != null && clash.id != subject.id) {
                message.value = "学科「$name」已存在"
                return@launch
            }
            subjectDao.update(subject.copy(name = name))
            editingSubject.value = null
            message.value = "已保存"
        }
    }

    /**
     * 删除学科。
     *
     * 外键 SET_NULL：学科下的任务全部滑到「未分类」，记录一条不丢。
     * 正在计时的任务不受影响（它只认 taskId，不认学科）。
     */
    fun deleteSubject(id: Long) {
        viewModelScope.launch {
            val subject = subjectDao.findById(id)
            if (subject != null) subjectDao.delete(subject)
            editingSubject.value = null
            message.value = "学科已删除，其任务已移至未分类"
        }
    }

    // ------------------------------------------------------------ 统计范围 / 重置 / 导入

    fun setStatsRange(range: StatsRange) {
        if (statsRange.value == range) return
        statsRange.value = range
        viewModelScope.launch { refreshStats() }
    }

    /** 清空全部专注记录（统计归零）。任务与学科不动。 */
    fun resetStats() {
        viewModelScope.launch {
            dao.clearAllSessions()
            refreshStats()
            message.value = "统计数据已清空"
        }
    }

    /**
     * 从外部表格导入专注记录（CSV / .xls）。
     *
     * 每行按「分类 / 任务名 / 时长」归档：学科与任务不存在就按名字
     * 自动建（复用错题本那套学科），时长按秒落一条会话流水，
     * 日期列认不出就归到今天。导入的是「已经发生过的事」，
     * 所以记为未完成（completed = false）——不替用户虚报番茄数。
     */
    fun importCsv(uri: android.net.Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<android.app.Application>().contentResolver
                        .openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                message.value = "打不开这个文件，换一个试试"
                return@launch
            }
            // 按文件头分流：OLE2 复合文档（.xls）走 BIFF 解析，
            // 其余按文本 CSV 走编码兜底解码
            val result = if (XlsTableReader.looksLikeXls(bytes)) {
                val table = XlsTableReader.readTable(bytes)
                if (table.isNullOrEmpty()) {
                    message.value = "这个 .xls 里没读到数据，换一个试试"
                    return@launch
                }
                CsvFocusImporter.parseTable(table, allowSerialDate = true)
            } else {
                CsvFocusImporter.parse(CsvFocusImporter.decodeText(bytes))
            }
            if (result.headerUnrecognized || result.rows.isEmpty()) {
                message.value = "没认出可导入的列：需要含「任务名」和「时长」"
                return@launch
            }

            var inserted = 0
            val today = LocalDate.now().toString()
            result.rows.forEach { row ->
                // 学科：按名字找，没有就建
                var subjectId: Long? = null
                val subjectName = row.subject?.takeIf { it.isNotBlank() }
                if (subjectName != null) {
                    subjectId = subjectDao.findByName(subjectName)?.id
                    if (subjectId == null) {
                        subjectId = subjectDao.insert(
                            Subject(name = subjectName, sortOrder = subjectDao.count())
                        )
                    }
                }
                // 任务：同学科下同名复用，否则新建
                val task = dao.findTasksByName(row.taskName)
                    .firstOrNull { it.subjectId == subjectId }
                    ?: run {
                        val newId = dao.insertTask(
                            PomodoroTask(
                                name = row.taskName,
                                subjectId = subjectId,
                                sortOrder = 0
                            )
                        )
                        PomodoroTask(id = newId, name = row.taskName, subjectId = subjectId)
                    }

                val day = row.day ?: today
                val startedAt = runCatching {
                    LocalDate.parse(day).atTime(12, 0)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrDefault(System.currentTimeMillis())
                dao.insertSession(
                    com.mistakebook.data.local.entities.PomodoroSession(
                        taskId = task.id,
                        mode = "POMODORO",
                        day = day,
                        startedAt = startedAt,
                        endedAt = startedAt + row.durationSec * 1000L,
                        durationSec = row.durationSec,
                        targetSec = 0L,
                        completed = false
                    )
                )
                inserted++
            }
            refreshStats()
            message.value = if (result.skipped > 0) {
                "已导入 $inserted 条，跳过 ${result.skipped} 条无法识别的行"
            } else {
                "已导入 $inserted 条记录"
            }
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    fun acknowledgeReboot() {
        timer.acknowledgeRebootNotice()
        syncTimerUi()
    }

    // ------------------------------------------------------------ 内部

    private fun syncTimerUi() {
        _ui.update {
            it.copy(
                mode = timer.state.value.mode,
                status = timer.state.value.status,
                targetMs = timer.state.value.targetMs,
                elapsedMs = timer.elapsedMs(),
                finished = timer.isFinished(),
                interruptedByReboot = timer.state.value.interruptedByReboot,
                selectedTaskId = timer.state.value.taskId ?: it.selectedTaskId
            )
        }
        // 秒表读数同步重算：开始新会话时若沿用上一条残留（如上轮倒计时
        // 到点的 targetMs），表盘头一秒会显示 00:00 冒充「已耗尽」。
        // 结束后这里是 0，表盘立刻回到标准目标时长，不等下一轮 tick。
        _tick.value = timer.elapsedMs()
    }

    companion object {
        /** 轮盘能表达的最大目标：12 小时 59 分。 */
        const val MAX_TARGET_MINUTES = 12 * 60 + 59

        /** 计时中每几秒把已跑时间固化进 SharedPreferences。 */
        const val CHECKPOINT_EVERY_TICKS = 5L
    }
}
