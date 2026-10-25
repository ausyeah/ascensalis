package com.mistakebook.ui

import android.app.Application
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.detectEnvironment
import com.mistakebook.data.local.SubjectTotal
import com.mistakebook.data.local.entities.PomodoroTask
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.pomodoro.FocusMode
import com.mistakebook.pomodoro.PomodoroUiState
import com.mistakebook.pomodoro.PomodoroViewModel
import com.mistakebook.pomodoro.RunStatus
import com.mistakebook.pomodoro.StatsTab
import com.mistakebook.pomodoro.TimerTab
import com.mistakebook.pomodoro.stats.PomodoroStats
import com.mistakebook.pomodoro.stats.StatsRange
import com.mistakebook.wordbook.data.Word
import com.mistakebook.wordbook.data.WordProgress
import com.mistakebook.wordbook.ui.DayStat
import com.mistakebook.wordbook.ui.QuizOption
import com.mistakebook.wordbook.ui.StudyMode
import com.mistakebook.wordbook.ui.StudyView
import com.mistakebook.wordbook.ui.StatsView
import com.mistakebook.wordbook.ui.WordbookTab
import com.mistakebook.wordbook.ui.WordbookUiState
import com.mistakebook.wordbook.ui.WordbookViewModel
import com.mistakebook.ui.theme.MistakeBookTheme
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * README 截图的来源：Paparazzi 在 JVM 上渲染 Compose UI，输出 PNG。
 *
 * 为什么不真机截图：CI/开发沙箱里没有可用安卓设备（无 KVM，模拟器
 * 软件渲染反复 ANR），但 UI 快照对 README 足够真实。渲染的是产品
 * 真实界面代码，只把 ViewModel 换成 relaxed mock（组件只把它当回调入口），
 * 状态数据为演示用构造值。
 *
 * 运行：./gradlew recordPaparazziDebug（只跑 debug 变体——release 变体会被
 * paparazzi 注入的 layoutlib android.jar 污染，BackupManagerTest 里真实
 * ContextWrapper 的 mBase 为 null 会挂；CI 只跑 testDebugUnitTest 不受影响）
 * 产物：app/src/test/snapshots/images/（golden 同步入库，CI 可 verify），
 *       拷入 docs/screenshots/ 供 README 引用
 */
class UiScreenshotTest {

    @get:Rule
    // compileSdkVersion 覆盖为 34：Compose 1.8 在 SDK 35 分支会调用
    // StaticLayout.Builder.setUseBoundsForWidth（API 35），而 paparazzi 1.3.x
    // 内置的 layoutlib 未实现该方法（cashapp/paparazzi#1866），渲染文本必崩。
    // 谎报 API 级别让 compose 走 34 分支即可；平台目录仍是 android-35。
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        environment = detectEnvironment().copy(compileSdkVersion = 34)
    )

    // ---------- 演示数据 ----------

    private fun words() = listOf(
        Word("ubiquitous", "adj. 无处不在的；普遍存在的", pos = "adj."),
        Word("ephemeral", "adj. 短暂的；朝生暮死的", pos = "adj."),
        Word("meticulous", "adj. 一丝不苟的；极其仔细的", pos = "adj."),
        Word("pragmatic", "adj. 实用主义的；务实的", pos = "adj.")
    )

    private fun wordbookStudyState(): WordbookUiState {
        val opts = words().mapIndexed { i, w ->
            QuizOption(label = "ABCD"[i].toString(), word = w, isCorrect = i == 0, isSelected = i == 0)
        }
        return WordbookUiState(
            currentTab = com.mistakebook.wordbook.ui.WordbookTab.STUDY,
            studyMode = StudyMode.QUIZ,
            currentWord = words()[0],
            currentProgress = WordProgress(word = "ubiquitous", level = 1, reps = 2, lapses = 1),
            options = opts,
            selectedOptionIndex = 0,
            isAnswered = true,
            allowReselect = true,
            remainingQueueCount = 37,
            studiedTodayCount = 18,
            masteredCount = 214,
            wrongBookCount = 23,
            favoriteCount = 9,
            totalVocabCount = 4356,
            streakDays = 6,
            dailyGoal = 30,
            hasPrevious = true
        )
    }

    private fun wordbookStatsState(): WordbookUiState {
        val today = LocalDate.now()
        fun stat(daysAgo: Long, studied: Int, correct: Int, total: Int) = DayStat(
            dayMs = today.minusDays(daysAgo).toEpochDay() * 86_400_000L,
            studiedCount = studied,
            correctCount = correct,
            totalCount = total
        )
        return WordbookUiState(
            currentTab = com.mistakebook.wordbook.ui.WordbookTab.STATS,
            studiedTodayCount = 18,
            masteredCount = 214,
            wrongBookCount = 23,
            favoriteCount = 9,
            totalVocabCount = 4356,
            weeklyDayStats = (6L downTo 0L).mapIndexed { i, d -> stat(d, 8 + i * 3, 6 + i * 2, 10 + i * 3) },
            monthDayStats = (29L downTo 0L).mapIndexed { i, d -> stat(d, i % 25, i % 22, (i % 28) + 2) },
            streakDays = 6,
            dailyGoal = 30
        )
    }

    private fun pomodoroTimerState(): PomodoroUiState {
        val subjects = listOf(
            Subject(id = 1, name = "数学"),
            Subject(id = 2, name = "英语"),
            Subject(id = 3, name = "408")
        )
        val tasks = listOf(
            PomodoroTask(id = 1, name = "高数强化 · 660 错题", subjectId = 1),
            PomodoroTask(id = 2, name = "阅读 2014 Text2", subjectId = 2),
            PomodoroTask(id = 3, name = "计网 · 滑动窗口", subjectId = 3)
        )
        return PomodoroUiState(
            tasks = tasks,
            subjects = subjects,
            selectedTaskId = 1,
            mode = com.mistakebook.pomodoro.FocusMode.POMODORO,
            status = RunStatus.RUNNING,
            targetMs = 45 * 60_000L,
            elapsedMs = 18 * 60_000L + 32_000L,
            todaySec = 3_600,
            todayPomodoros = 3,
            streakDays = 12,
            totalSec = 152_400,
            totalCompleted = 126,
            sessionCount = 138,
            last7 = listOf(3600, 5400, 0, 7200, 2700, 6300, 3600),
            last30 = List(30) { ((it * 977) % 7200).toLong() },
            taskTotals = listOf(
                PomodoroStats.TaskRow(1, "高数强化 · 660 错题", 1, 5400, 3),
                PomodoroStats.TaskRow(2, "阅读 2014 Text2", 2, 3600, 2)
            ),
            subjectTotals = listOf(
                SubjectTotal(1, 5400, 3),
                SubjectTotal(2, 3600, 2),
                SubjectTotal(3, 2700, 1)
            )
        )
    }

    private fun pomodoroStatsState(): PomodoroUiState {
        val today = LocalDate.now()
        val last7 = listOf(3600L, 5400L, 0L, 7200L, 2700L, 6300L, 3600L)
        return PomodoroUiState(
            tasks = listOf(PomodoroTask(id = 1, name = "高数强化 · 660 错题", subjectId = 1)),
            subjects = listOf(Subject(id = 1, name = "数学"), Subject(id = 2, name = "英语")),
            statsRange = StatsRange.TODAY,
            todaySec = 3_600,
            todayPomodoros = 3,
            streakDays = 12,
            totalSec = 152_400,
            totalCompleted = 126,
            sessionCount = 138,
            last7 = last7,
            last30 = List(30) { ((it * 977) % 7200).toLong() },
            months12 = List(12) { ((it * 4523) % 54000).toLong() },
            monthWeeks = listOf(5400, 7200, 3600, 6300),
            singleDay = today.toString(),
            singleDaySec = 3_600,
            singleDayPomodoros = 3,
            singleDayTasks = listOf(PomodoroStats.TaskRow(1, "高数强化 · 660 错题", 1, 3600, 3)),
            activeDays = 87,
            activeMonths = 4,
            totalAllSec = 152_400,
            totalAllCompleted = 126,
            subjectTotals = listOf(
                SubjectTotal(1, 5400, 3),
                SubjectTotal(2, 3600, 2)
            ),
            taskTotals = listOf(
                PomodoroStats.TaskRow(1, "高数强化 · 660 错题", 1, 5400, 3),
                PomodoroStats.TaskRow(2, "阅读 2014 Text2", 2, 3600, 2)
            )
        )
    }

    // ---------- 截图 ----------

    @Test
    fun wordbookStudy() {
        paparazzi.snapshot(name = "wordbook-study") {
            MistakeBookTheme {
                StudyView(state = wordbookStudyState(), viewModel = mockk(relaxed = true))
            }
        }
    }

    @Test
    fun wordbookStats() {
        paparazzi.snapshot(name = "wordbook-stats") {
            MistakeBookTheme {
                StatsView(state = wordbookStatsState(), viewModel = mockk(relaxed = true))
            }
        }
    }

    @Test
    fun pomodoroTimer() {
        val flow = MutableStateFlow(18 * 60_000L + 32_000L)
        paparazzi.snapshot(name = "pomodoro-timer") {
            MistakeBookTheme {
                TimerTab(
                    state = pomodoroTimerState(),
                    viewModel = mockk(relaxed = true),
                    elapsedFlow = flow
                )
            }
        }
    }

    @Test
    fun pomodoroStats() {
        paparazzi.snapshot(name = "pomodoro-stats") {
            MistakeBookTheme {
                StatsTab(
                    state = pomodoroStatsState(),
                    onRangeChange = {},
                    onShiftDay = {},
                    onResetStats = {},
                    onImportCsv = {},
                    onManualEntry = {}
                )
            }
        }
    }
}
