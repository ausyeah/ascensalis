package com.mistakebook.pomodoro

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.local.PomodoroDao
import com.mistakebook.data.local.entities.PomodoroSession
import com.mistakebook.data.local.entities.PomodoroTask
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.pomodoro.stats.PomodoroStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

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
    val todaySec: Long = 0L,
    val todayPomodoros: Int = 0,
    val streakDays: Int = 0,
    val totalSec: Long = 0L,
    val totalCompleted: Int = 0,
    val last7: List<Long> = List(7) { 0L },
    val taskTotals: List<PomodoroStats.TaskRow> = emptyList(),
    val showNewTask: Boolean = false,
    val newTaskName: String = "",
    val newTaskSubjectId: Long? = null,
    val message: String? = null
)

class PomodoroViewModel(
    app: Application,
    private val dao: PomodoroDao,
    private val timer: PomodoroTimer,
    /** 学科列表：复用错题本那套学科（见 PomodoroTask 的注释）。 */
    private val subjectsFlow: StateFlow<List<Subject>>
) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(PomodoroUiState())
    val ui: StateFlow<PomodoroUiState> = _ui

    private var subjectFilter = MutableStateFlow<Long?>(null)
    private var selectedTaskId = MutableStateFlow<Long?>(null)
    private var newTaskOpen = MutableStateFlow(false)
    private var message = MutableStateFlow<String?>(null)

    /** 每秒重算的显示值：只动 elapsed/finished，不重建整棵树。 */
    private val ticker = MutableStateFlow(0L)

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
            stats.value = PomodoroStats.snapshot(dao)
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
                message,
                stats
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val snapshot = values[6] as PomodoroStats.Snapshot
                PomodoroUiState(
                    tasks = values[0] as List<PomodoroTask>,
                    subjects = values[1] as List<Subject>,
                    subjectFilter = values[2] as Long?,
                    selectedTaskId = values[3] as Long?,
                    newTaskName = _ui.value.newTaskName,
                    newTaskSubjectId = _ui.value.newTaskSubjectId,
                    showNewTask = values[4] as Boolean,
                    message = values[5] as String?,
                    todaySec = snapshot.todaySec,
                    todayPomodoros = snapshot.todayPomodoros,
                    streakDays = snapshot.streakDays,
                    totalSec = snapshot.totalSec,
                    totalCompleted = snapshot.totalCompleted,
                    last7 = snapshot.last7,
                    taskTotals = snapshot.taskTotals,
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
        viewModelScope.launch {
            while (true) {
                delay(1_000L)
                if (timer.state.value.status == RunStatus.IDLE) continue
                timer.checkpoint()
                _tick.value = timer.elapsedMs()
                // 状态本身（开始/暂停/到点）变化频率极低，但仍要同步，
                // 否则「到点了」这件事界面不会知道
                if (timer.state.value.status != _ui.value.status || timer.isFinished() != _ui.value.finished) {
                    _ui.update {
                        it.copy(
                            status = timer.state.value.status,
                            finished = timer.isFinished(),
                            elapsedMs = timer.elapsedMs()
                        )
                    }
                }
                refreshStatsIfNeeded()
            }
        }
    }

    /** 倒计时到点后统计不会自己变，但每日/总数会随会话落库而变；
     *  这里做一次轻量刷新，只在运行态每秒最多一次。 */
    private var lastStatsAt = 0L
    private suspend fun refreshStatsIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastStatsAt < 30_000L) return
        lastStatsAt = now
        stats.value = PomodoroStats.snapshot(dao)
    }

    private suspend fun refreshStats() {
        lastStatsAt = System.currentTimeMillis()
        stats.value = PomodoroStats.snapshot(dao)
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
        PomodoroService.start(getApplication(), name, "专注进行中")
        syncTimerUi()
    }

    fun pause() {
        timer.pause()
        PomodoroService.stop(getApplication())
        syncTimerUi()
    }

    fun resume() {
        timer.resume()
        val name = _ui.value.tasks.firstOrNull { it.id == timer.state.value.taskId }?.name.orEmpty()
        PomodoroService.start(getApplication(), name, "专注进行中")
        syncTimerUi()
    }

    /** 结束并落库。 */
    fun stop(save: Boolean = true) {
        val harvest = timer.harvest() ?: return
        PomodoroService.stop(getApplication())
        if (!save || harvest.taskId == null) {
            syncTimerUi()
            return
        }
        // 少于 1 分钟不记：误点开始也算一次的话，统计会被噪声淹没
        if (harvest.durationMs < 60_000L) {
            message.value = "不足 1 分钟，未记录"
            syncTimerUi()
            return
        }
        viewModelScope.launch {
            val endedAt = System.currentTimeMillis()
            dao.insertSession(
                PomodoroSession(
                    taskId = harvest.taskId!!,
                    mode = harvest.mode.name,
                    day = LocalDate.now().toString(),
                    startedAt = harvest.startedWallMs,
                    endedAt = endedAt,
                    durationSec = harvest.durationMs / 1000L,
                    targetSec = harvest.targetMs / 1000L,
                    completed = harvest.completed
                )
            )
            refreshStats()
            message.value = if (harvest.completed) {
                "已记录一次专注：${formatMinutes(harvest.durationMs)}"
            } else {
                "已记录（未完成目标）：${formatMinutes(harvest.durationMs)}"
            }
        }
        syncTimerUi()
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

    fun adjustTarget(deltaMinutes: Int) {
        val current = (_ui.value.targetMs / 60_000L).toInt().coerceAtLeast(1)
        timer.setTargetMs((current + deltaMinutes).coerceIn(1, 180) * 60_000L)
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
    }

    companion object {
        fun formatMinutes(ms: Long): String {
            val totalMin = ms / 60_000L
            val h = totalMin / 60
            val m = totalMin % 60
            return if (h > 0) "${h}小时${m}分" else "${m}分钟"
        }
    }
}
