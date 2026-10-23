package com.mistakebook.pomodoro

import com.mistakebook.data.local.PomodoroDao
import com.mistakebook.data.local.entities.PomodoroSession
import java.time.LocalDate

/**
 * 「结束一次专注并落库」的共享实现。
 *
 * ## 为什么抽出来
 *
 * 这条路径有两个调用方：界面上的「结束并记录」按钮，和通知栏上的
 * 「结束」操作按钮。两边各写一份的后果是——改一个「不足 1 分钟不记」
 * 的规则要记着改两处，漏一处的那个入口就会开始记噪声数据。
 *
 * 规则集中在一处：
 * 1. 没有在计的会话（IDLE）→ 什么都不做；
 * 2. 不足 1 分钟 → 不落库，只把原因回给调用方展示；
 * 3. 正常 → 落一条明细，completed 按「是否走完目标」记。
 */
object PomodoroRecorder {

    /** 落库结果：null = 没有可结束的会话；否则是要展示给用户的消息。 */
    suspend fun stopAndRecord(timer: PomodoroTimer, dao: PomodoroDao): String? {
        val harvest = timer.harvest() ?: return null
        if (harvest.taskId == null) return null
        if (harvest.durationMs < 60_000L) return "不足 1 分钟，未记录"
        val endedAt = System.currentTimeMillis()
        dao.insertSession(
            PomodoroSession(
                taskId = harvest.taskId,
                mode = harvest.mode.name,
                day = LocalDate.now().toString(),
                startedAt = harvest.startedWallMs,
                endedAt = endedAt,
                durationSec = harvest.durationMs / 1000L,
                targetSec = harvest.targetMs / 1000L,
                completed = harvest.completed
            )
        )
        val minutes = harvest.durationMs / 60_000L
        val h = minutes / 60
        val m = minutes % 60
        val duration = if (h > 0) "${h}小时${m}分" else "${m}分钟"
        return if (harvest.completed) "已记录一次专注：$duration" else "已记录（未完成目标）：$duration"
    }
}
