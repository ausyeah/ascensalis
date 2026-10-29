package com.mistakebook.poster

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * 海报生图任务的**全局**状态。
 *
 * ## 为什么状态不能放在 ViewModel 里
 *
 * 用户点「生图打卡」后离开页面 → 导航栈弹出 → 该页的 ViewModel 被销毁
 * → ViewModel 里的 `viewModelScope` 取消 → **状态也没了**。
 *
 * 但任务本身（WorkManager）是**活着的**：它会在后台把海报生成完。
 * 于是回来后用户看到的是「按钮回到初始态，什么都没发生」，
 * 而文件其实已经在磁盘上了。
 *
 * 所以状态必须活得比 ViewModel 长：这里挂在 [com.mistakebook.di.AppContainer]
 * 的 `appScope` 上（与应用同生命周期），任何页面都能订阅到。
 */
sealed interface PosterWorkState {
    data object Idle : PosterWorkState
    data class Running(val mode: PosterImageMode) : PosterWorkState
    data class Done(val filePath: String) : PosterWorkState
    data class Failed(val message: String) : PosterWorkState
}

/**
 * 观察 [PosterWorker] 的执行状态，转成 [PosterWorkState] 广播出去。
 *
 * 挂在 appScope 上，只在应用进程活着期间观察——这没问题：
 * Worker 本身由系统调度，进程被杀后系统会重新拉起它完成任务；
 * 用户回到应用时，[replayLatest] 会把磁盘上已完成的结果重新读出来。
 */
class PosterWorkMonitor(
    private val appContext: Context,
    private val scope: CoroutineScope,
    /** 来源标识（"wordbook" / "pomodoro"），决定观察哪个工作名下的任务。 */
    private val source: String
) {

    private val workName: String = PosterWorker.workName(source)

    private val _state = MutableStateFlow<PosterWorkState>(PosterWorkState.Idle)
    val state: StateFlow<PosterWorkState> = _state.asStateFlow()

    init {
        scope.launch {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWorkFlow(workName)
                .filter { infos -> infos.isNotEmpty() }
                .map { infos -> pickCurrent(infos) }
                .collect { info ->
                    _state.value = info.toPosterState()
                }
        }
    }

    /**
     * 在同名 unique work 的**全部历史记录**里挑出「当前应该展示的那条」。
     *
     * 不能按 state.ordinal 取 max（v0.0.59 修复）：用户第二次点「生图打卡」
     * 走 REPLACE 后，旧的 SUCCEEDED/CANCELLED 记录（ordinal 更大）会永远
     * 压过新任务的 ENQUEUED/RUNNING——新图生成了（生图额度已烧），
     * 界面却停在旧图或「海报生成已取消」。
     *
     * 优先级：活跃任务 > 最近一次成功 > 最近一次失败 > 其余（取消）。
     * 「最近」取列表最后一条：WorkManager 按 WorkSpec 创建序返回同名记录。
     */
    private fun pickCurrent(infos: List<WorkInfo>): WorkInfo? =
        infos.firstOrNull {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }
            ?: infos.lastOrNull { it.state == WorkInfo.State.SUCCEEDED }
            ?: infos.lastOrNull { it.state == WorkInfo.State.FAILED }
            ?: infos.lastOrNull()

    /** 清空状态（用户已经处理完结果后调用）。 */
    fun reset() {
        _state.value = PosterWorkState.Idle
    }

    /** 页面回来时把「已完成」的结果重新读出来（进程重启后状态会丢）。 */
    fun replayLatest() {
        val infos = runCatching {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWork(workName)
                .get()
        }.getOrNull().orEmpty()
        // 与 pickCurrent 同口径：多条成功记录取最后一条（创建序最新的那次）。
        // 旧实现按 runAttemptCount 取，REPLACE 之后新旧记录的计数没有可比性。
        val succeeded = infos.lastOrNull { it.state == WorkInfo.State.SUCCEEDED } ?: return
        val path = succeeded.outputData.getString(PosterWorker.KEY_RESULT_PATH) ?: return
        if (File(path).isFile) _state.value = PosterWorkState.Done(path)
    }

    /** 从 WorkInfo 的 mode 标签还原生图模式。 */
    private fun WorkInfo?.mode(): PosterImageMode {
        val tag = this?.tags?.firstOrNull { it.startsWith(PosterWorker.TAG_MODE_PREFIX) }
            ?: return PosterImageMode.BACKGROUND
        val name = tag.removePrefix(PosterWorker.TAG_MODE_PREFIX)
        return runCatching { PosterImageMode.valueOf(name) }.getOrElse { PosterImageMode.BACKGROUND }
    }

    private fun WorkInfo?.toPosterState(): PosterWorkState = when (this?.state) {
        WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED ->
            PosterWorkState.Running(mode())

        WorkInfo.State.SUCCEEDED -> {
            val path = outputData.getString(PosterWorker.KEY_RESULT_PATH)
            if (path.isNullOrBlank()) {
                PosterWorkState.Failed("海报生成结果丢失")
            } else {
                PosterWorkState.Done(path)
            }
        }

        WorkInfo.State.FAILED -> PosterWorkState.Failed(
            outputData.getString(PosterWorker.KEY_ERROR) ?: "海报生成失败"
        )

        WorkInfo.State.CANCELLED -> PosterWorkState.Failed("海报生成已取消")

        else -> PosterWorkState.Idle
    }
}
