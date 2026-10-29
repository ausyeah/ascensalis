package com.mistakebook.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mistakebook.data.local.entities.ChatAttachment
import com.mistakebook.data.local.entities.ChatMessage
import com.mistakebook.data.local.entities.ChatSession
import com.mistakebook.domain.ChatRole
import com.mistakebook.domain.MessageStatus
import kotlinx.coroutines.flow.Flow

/**
 * 会话 / 消息 / 附件 三表合一。
 *
 * 合在一起而不是拆三个 Dao：这三张表**几乎总是一起用**
 * （进会话要读消息，读消息要读附件），拆开只会让调用处反复拼三次查询。
 */
@Dao
interface ChatDao {

    // ------------------------------------------------------------ 会话

    @Insert
    suspend fun insertSession(session: ChatSession): Long

    @Update
    suspend fun updateSession(session: ChatSession)

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    fun observeSession(id: Long): Flow<ChatSession?>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun findSession(id: Long): ChatSession?

    /**
     * 取某题已有的会话；没有则返回 null（调用方据此新建）。
     *
     * 「每题一个固定会话」靠这个保证。只取未删除的——
     * 用户删掉会话后再进题目页，应当得到一个干净的新会话，而不是复活旧的。
     */
    @Query(
        """SELECT * FROM chat_sessions
           WHERE questionId = :questionId AND deletedAt IS NULL
           ORDER BY updatedAt DESC LIMIT 1"""
    )
    suspend fun findSessionByQuestion(questionId: Long): ChatSession?

    /**
     * 最近一个**空**的自由会话（一条消息都没有）。
     *
     * 用于「点新对话」时复用而不是每次新建——否则会话列表里会攒出一串
     * 永远空着的孤儿会话，用户点几次「新对话」就多几行空白。
     */
    @Query(
        """SELECT s.* FROM chat_sessions s
           WHERE s.questionId IS NULL AND s.deletedAt IS NULL
             AND NOT EXISTS (SELECT 1 FROM chat_messages m WHERE m.sessionId = s.id)
           ORDER BY s.updatedAt DESC LIMIT 1"""
    )
    suspend fun findEmptyFreeSession(): ChatSession?

    /**
     * 会话列表页。
     *
     * 带 `questionId` 便于列表直接显示「哪道题」——否则用户面对十几个
     * 长得差不多的会话根本分不清。
     */
    @Query(
        """SELECT * FROM chat_sessions
           WHERE deletedAt IS NULL
           ORDER BY updatedAt DESC"""
    )
    fun observeSessions(): Flow<List<ChatSession>>

    /**
     * 一次性取出全部未删除会话。
     *
     * 「清空全部」要挨个删掉各会话的附件目录，删之前得先知道有哪些——
     * 从 Flow 上 `first()` 拿也能凑合，但那是把一次性操作伪装成订阅，
     * 意图不清，直接给 suspend 版。
     */
    @Query("SELECT * FROM chat_sessions WHERE deletedAt IS NULL")
    suspend fun observeSessionsOnce(): List<ChatSession>

    @Query("UPDATE chat_sessions SET deletedAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun softDeleteSession(id: Long, at: Long)

    @Query("UPDATE chat_sessions SET deletedAt = NULL, updatedAt = :at WHERE id = :id")
    suspend fun restoreSession(id: Long, at: Long)

    /** 清空全部会话（软删，保留数据以便撤销）。 */
    @Query("UPDATE chat_sessions SET deletedAt = :at, updatedAt = :at WHERE deletedAt IS NULL")
    suspend fun softDeleteAllSessions(at: Long)

    // ------------------------------------------------------------ 消息

    @Insert
    suspend fun insertMessage(message: ChatMessage): Long

    @Update
    suspend fun updateMessage(message: ChatMessage)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(sessionId: Long): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun messagesOf(sessionId: Long): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun findMessage(id: Long): ChatMessage?

    /**
     * 真删。
     *
     * 早先的「删除」是把状态标成 CANCELED——而「重试」正是「标记旧消息 + 插入新消息」，
     * 于是被标记的那条**永远留在列表里**，用户点了删除却看到消息还在。
     * 附件记录靠外键 CASCADE 一起清掉。
     */
    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    /**
     * 组装请求时用：排除 [ChatMessage.injected] 的题目上下文消息。
     *
     * 题目上下文是**给模型看**的，不是用户说的话——
     * 混在对话历史里发过去会让模型把它当成用户的一条发言。
     */
    @Query(
        """SELECT * FROM chat_messages
           WHERE sessionId = :sessionId AND injected = 0
           ORDER BY createdAt ASC, id ASC"""
    )
    suspend fun messagesExcludingInjected(sessionId: Long): List<ChatMessage>

    /**
     * 流式追加：只更新正文与状态，**不动其他字段**。
     *
     * 流式期间每收到一段就调一次，必须够轻——
     * 所以不能用 `@Update`（那要把整条读出来再写回去）。
     */
    @Query(
        """UPDATE chat_messages
           SET content = :content, status = :status, updatedAt = :updatedAt
           WHERE id = :id"""
    )
    suspend fun appendContent(id: Long, content: String, status: MessageStatus, updatedAt: Long)

    @Query(
        """UPDATE chat_messages
           SET status = :status, errorMessage = :errorMessage, updatedAt = :updatedAt
           WHERE id = :id"""
    )
    suspend fun markStatus(id: Long, status: MessageStatus, errorMessage: String?, updatedAt: Long)

    @Query(
        """UPDATE chat_messages
           SET promptTokens = :promptTokens, completionTokens = :completionTokens, updatedAt = :updatedAt
           WHERE id = :id"""
    )
    suspend fun markUsage(id: Long, promptTokens: Int, completionTokens: Int, updatedAt: Long)

    /**
     * 找出会话里最后一条仍在流式的消息。
     *
     * 用途是**崩溃/被杀后恢复**：进程死掉时那条消息永远停在 STREAMING，
     * 进页面时把它收敛成 CANCELED，否则 UI 会一直显示「生成中」。
     */
    @Query(
        """SELECT * FROM chat_messages
           WHERE sessionId = :sessionId AND status = 'STREAMING'
           ORDER BY id DESC LIMIT 1"""
    )
    suspend fun findStreamingMessage(sessionId: Long): ChatMessage?

    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun countMessages(sessionId: Long): Int

    /**
     * 找已注入的题目上下文消息。
     *
     * 判断「要不要再注入一次」必须用这个，**不能用 [countMessages]**：
     * 发送流程是「先插用户消息、再注入上下文」，用消息总数判断会恒为已注入。
     */
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId AND injected = 1 LIMIT 1")
    suspend fun findInjectedMessage(sessionId: Long): ChatMessage?

    // ------------------------------------------------------------ 附件

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: ChatAttachment): Long

    @Update
    suspend fun updateAttachment(attachment: ChatAttachment)

    @Query("SELECT * FROM chat_attachments WHERE messageId = :messageId ORDER BY id ASC")
    fun observeAttachments(messageId: Long): Flow<List<ChatAttachment>>

    @Query("SELECT * FROM chat_attachments WHERE id IN (:ids)")
    suspend fun attachmentsByIds(ids: List<Long>): List<ChatAttachment>

    @Query("SELECT * FROM chat_attachments WHERE id = :id")
    suspend fun findAttachment(id: Long): ChatAttachment?

    @Query("SELECT * FROM chat_attachments WHERE messageId = :messageId")
    suspend fun attachmentsOf(messageId: Long): List<ChatAttachment>

    /**
     * 一次取整个会话的附件，按 messageId 分组用。
     *
     * 逐条消息查会在长会话里打出几十次查询——气泡图是每次消息列表变化
     * 都要重算的，这里必须一次查完。
     */
    @Query("SELECT * FROM chat_attachments WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun attachmentsOfSession(sessionId: Long): List<ChatAttachment>

    /**
     * 会话里所有**图片**附件（按 id 倒序，最新的在前）。
     *
     * 用于「模型不识图时补做 OCR」：历史轮次里的图片也得有文字，
     * 否则模型对着用户几轮之前的照片提问，却什么都看不到。
     */
    @Query(
        """SELECT * FROM chat_attachments
           WHERE sessionId = :sessionId AND kind = 'IMAGE'
           ORDER BY id DESC"""
    )
    suspend fun imageAttachmentsOf(sessionId: Long): List<ChatAttachment>

    /**
     * 回写附件抽出来的文本。
     *
     * 只改这两个字段，不整行覆盖——附件行里还有 id / messageId 等外键，
     * 整行 `@Update` 要求先把整行读出来再写回去，多一次查询且容易漏字段。
     */
    @Query(
        """UPDATE chat_attachments
           SET textExcerpt = :text, extractedChars = :chars
           WHERE id = :id"""
    )
    suspend fun updateAttachmentText(id: Long, text: String, chars: Int)

    @Query(
        """UPDATE chat_attachments
           SET status = :status, errorMessage = :errorMessage
           WHERE id = :id"""
    )
    suspend fun markAttachmentStatus(
        id: Long,
        status: com.mistakebook.domain.AttachmentStatus,
        errorMessage: String?
    )

    @Query("DELETE FROM chat_attachments WHERE id = :id")
    suspend fun deleteAttachment(id: Long)

    /** 删除消息时连带清附件记录——靠外键 CASCADE 兜底，这里显式调一次更可控。 */
    @Query("DELETE FROM chat_attachments WHERE messageId = :messageId")
    suspend fun deleteAttachmentsOf(messageId: Long)

    // ------------------------------------------------------------ 会话统计

    /**
     * 刷新会话的消息数与摘要。
     *
     * 在**消息增删改之后**调用，而不是靠数据库触发器：
     * 触发器拿不到「最后一条消息是什么」，还得再查一次，得不偿失。
     */
    @Query(
        """UPDATE chat_sessions SET
             messageCount = (SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId),
             lastPreview = COALESCE((
                 SELECT content FROM chat_messages
                 WHERE sessionId = :sessionId AND role = 'USER'
                 ORDER BY id DESC LIMIT 1
             ), ''),
             updatedAt = :at
           WHERE id = :sessionId"""
    )
    suspend fun refreshSessionStats(sessionId: Long, at: Long)

    /** 会话被删时顺带把消息标记成不可用（消息本身靠 CASCADE 已删，这里只清统计）。 */
    @Query("SELECT id FROM chat_messages WHERE sessionId = :sessionId AND role = :role ORDER BY id DESC LIMIT 1")
    suspend fun lastMessageIdOfRole(sessionId: Long, role: ChatRole): Long?
}
