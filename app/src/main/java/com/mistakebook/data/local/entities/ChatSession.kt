package com.mistakebook.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一个对话会话。
 *
 * ## 两种会话
 *
 * - `questionId != null`：**每题一个固定会话**。从题目页进入时复用该题已有会话，
 *   没有则新建。题目被删除时（[com.mistakebook.domain.Question] 软删）
 *   会话仍在，只是失去关联。
 * - `questionId == null`：**自由会话**，从首页入口进入。
 *
 * 这里**故意不加到 questions 的外键**：加了就得决定级联还是置空，
 * 而题目走的是软删除（`deletedAt`），级联会连带删掉用户的对话记录——
 * 那是不可逆的数据损失。改成普通索引，删除题目时由上层显式处理。
 */
@Entity(
    tableName = "chat_sessions",
    indices = [
        Index("questionId"),
        Index("updatedAt"),
        Index("deletedAt")
    ]
)
data class ChatSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val questionId: Long? = null,
    val title: String = "",

    /**
     * 用户是否重命名过。
     *
     * 标题默认取首条用户消息前若干字（聊天软件的老规矩），
     * 但**一旦用户手动改过就不能再被自动标题覆盖**——
     * 否则下次进会话时他自己起的名字会被悄悄冲掉。
     */
    val titleLocked: Boolean = false,

    /** 首条消息所用的模型快照。会话内换模型不影响历史，这里记的是「当初是谁答的」。 */
    val model: String = "",
    val messageCount: Int = 0,

    /** 列表页展示的最后一条消息摘要。 */
    val lastPreview: String = "",

    /**
     * 置顶（v0.0.96）。
     *
     * 列表排序是「置顶在前、其余按最近更新」——只按更新时间排的话，
     * 一条常翻的长期会话会被新会话反复冲走，用户每次都要往下找。
     */
    val pinned: Boolean = false,

    /**
     * 临时对话（v0.0.96）：退出会话就删掉，不进历史列表。
     *
     * 为什么要在库里留这个标记，而不是「退出时记住 id 再删」：
     * 进程被系统杀掉时退出回调根本不会跑。有了标记，下次启动扫一遍
     * `temporary = 1` 就能收拾干净；而列表查询直接滤掉它们，
     * 用户永远不该看见一条本该消失的对话。
     */
    val temporary: Boolean = false,

    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)
