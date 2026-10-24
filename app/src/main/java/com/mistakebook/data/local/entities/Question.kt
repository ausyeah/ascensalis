package com.mistakebook.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mistakebook.domain.ErrorReason
import com.mistakebook.domain.MasteryStatus

/**
 * 错题主表。
 */
@Entity(
    tableName = "questions",
    indices = [
        Index("subjectId"),
        Index("status"),
        Index("deletedAt"),
        Index("nextReviewAt"),
        Index("notebookId")
    ]
)
data class Question(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long?,
    /**
     * 所属错题本。为空表示「还没归类」，UI 按默认错题本处理。
     * 与 [subjectId] 正交：学科管「这是什么题」，错题本管「归到哪」。
     */
    val notebookId: Long? = null,
    /**
     * 原始照片（整页）。
     * 用户拍摄/导入的原始图，可能包含整页的多道题与噪声，不适合直接打印。
     */
    val imagePath: String,

    /**
     * 题目附图（MinerU 从原图里切出的图形部分，或用户手动裁剪的图）。
     * 这才是「题目的图」，打印时优先用；为空才回退到 [imagePath] 原图。
     * 存 JSON 数组字符串，相对 app files 目录的绝对路径。
     */
    val figurePathsJson: String = "",

    val mineruMarkdown: String,
    val stem: String,
    val optionsJson: String,
    val answer: String,
    val analysis: String,

    /**
     * 题目标题：列表页展示用，优先由大模型生成、可由用户自定义。
     * 优先于 stem 展示；为空时列表回退显示题干前若干字。
     */
    val title: String = "",
    val knowledgePointsJson: String,
    val errorReason: ErrorReason,
    val difficulty: Int,
    val status: MasteryStatus,
    val note: String = "",
    val reviewStage: Int = 0,
    val nextReviewAt: Long? = null,
    /**
     * 来源题：本题由 AI 对哪道题「举一反三」生成（F1 变式题）。
     * 为空表示普通录入的题。不设外键——来源题可能被软删/硬删，
     * 关联只是展示信息，不值得为它承担级联行为的复杂度。
     */
    val relatedQuestionId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)
