package com.mistakebook.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 番茄钟任务。
 *
 * ## 为什么挂在已有学科下
 *
 * 用户要的是「学科分类 → 下面选具体任务」。而错题本里**已经有一套学科**
 * （数学、通信原理……用户自己建的都在里面）。再另起一套学科体系，
 * 结果是两处学科各说各话、改名要改两遍。
 *
 * 所以这里只建「任务」表，学科直接用 [Subject]（外键 subjectId）。
 * 学科为空表示「未分类」——允许存在，因为用户可能只是想随手计个时。
 */
@Entity(
    tableName = "pomodoro_tasks",
    foreignKeys = [
        ForeignKey(
            entity = Subject::class,
            parentColumns = ["id"],
            childColumns = ["subjectId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("subjectId"), Index("sortOrder")]
)
data class PomodoroTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val subjectId: Long? = null,
    /** 归档后不在选择器里出现，但历史记录照常保留。 */
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * 首次进入时的预置任务。
         *
         * 只给「通用」一条空学科任务，不替用户编造任务清单——
         * 每个人要专注的事不一样，预置一长串只是噪音。
         */
        const val DEFAULT_NAME = "自由专注"
    }
}
