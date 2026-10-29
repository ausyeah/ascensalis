package com.mistakebook.data.chat

import com.mistakebook.domain.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文组装测试。
 *
 * 这里的每条规则都对应一个**已经踩过或能想出来的坑**，
 * 尤其注意「反向用例」——只测正确路径的话，
 * 「丢半轮」「吞掉最新一轮」「省略条数算错」都能悄悄通过。
 */
class ChatContextAssemblerTest {

    private fun user(id: Long, text: String) =
        OutgoingMessage(id = id, role = ChatRole.USER, text = text)

    private fun assistant(id: Long, text: String) =
        OutgoingMessage(id = id, role = ChatRole.ASSISTANT, text = text)

    /** 去掉头部（system + 题目上下文），只看对话正文。 */
    private fun AssembledContext.body(): List<OutgoingMessage> = messages.filter { it.role != ChatRole.SYSTEM }

    private fun assemble(
        history: List<OutgoingMessage>,
        pendingText: String = "",
        questionContext: String = "题目"
    ) = ChatContextAssembler.assemble("sys", questionContext, history, pendingText)

    // ------------------------------------------------------------ 头部结构

    @Test
    fun `system 与题目上下文拼在输出头部`() {
        // 输出必须自包含。早先 assemble 收下这两个参数却没用，
        // 靠调用方自己拼——那种约定迟早在某次改动里漏掉，
        // 漏了还不报错，只是模型突然失忆。
        val result = assemble(listOf(user(1, "问1")))
        assertEquals(3, result.messages.size)
        assertEquals(ChatRole.SYSTEM, result.messages[0].role)
        assertEquals("sys", result.messages[0].text)
        assertEquals(ChatRole.SYSTEM, result.messages[1].role)
        assertEquals("题目", result.messages[1].text)
        assertEquals(ChatRole.USER, result.messages[2].role)
    }

    @Test
    fun `题目上下文为空时不占位`() {
        val result = ChatContextAssembler.assemble("sys", "", listOf(user(1, "问1")))
        assertEquals(2, result.messages.size)
        assertEquals("sys", result.messages[0].text)
        assertEquals("问1", result.messages[1].text)
    }

    @Test
    fun `system 为空时也不占位`() {
        val result = ChatContextAssembler.assemble("", "题目", listOf(user(1, "问1")))
        assertEquals(2, result.messages.size)
        assertEquals("题目", result.messages[0].text)
    }

    // ------------------------------------------------------------ 分轮

    @Test
    fun `一轮是一条用户消息加其后的助手消息`() {
        val rounds = ChatContextAssembler.groupIntoRounds(
            listOf(
                user(1, "问1"), assistant(2, "答1"),
                user(3, "问2"), assistant(4, "答2"),
                assistant(5, "追问"), assistant(6, "续答")
            )
        )
        // 2 轮：第 2 轮的 assistant 连续两条都归到 user(3) 那一轮
        assertEquals(2, rounds.size)
        assertEquals(listOf(1L, 2L), rounds[0].map { it.id })
        assertEquals(listOf(3L, 4L, 5L, 6L), rounds[1].map { it.id })
    }

    @Test
    fun `开头是孤立的助手消息也自成一轮`() {
        // 脏数据兜底：不该崩，也不该把它和后面的用户消息并成一轮。
        val rounds = ChatContextAssembler.groupIntoRounds(
            listOf(assistant(1, "残留"), user(2, "问"))
        )
        assertEquals(2, rounds.size)
        assertEquals(listOf(1L), rounds[0].map { it.id })
    }

    @Test
    fun `连续两条用户消息是各自一轮`() {
        // 用户连发两条没等回答就发了下一条——这是常见操作，不能并轮。
        val rounds = ChatContextAssembler.groupIntoRounds(
            listOf(user(1, "问1"), user(2, "问2"), assistant(3, "答"))
        )
        assertEquals(2, rounds.size)
        assertEquals(listOf(1L), rounds[0].map { it.id })
        assertEquals(listOf(2L, 3L), rounds[1].map { it.id })
    }

    // ------------------------------------------------------------ 基本保留

    @Test
    fun `短历史全部保留且顺序不变`() {
        val result = assemble(listOf(user(1, "问1"), assistant(2, "答1"), user(3, "问2"), assistant(4, "答2")))
        assertEquals(0, result.omittedCount)
        assertEquals(listOf(1L, 2L, 3L, 4L), result.body().map { it.id })
    }

    @Test
    fun `历史里的 SYSTEM 消息被剔除`() {
        // SYSTEM 是给模型看的上下文标记，不该作为「用户说过的话」再发一遍。
        val result = assemble(
            listOf(
                user(1, "问1"), assistant(2, "答1"),
                OutgoingMessage(id = 3, role = ChatRole.SYSTEM, text = "已省略 2 条早期对话")
            )
        )
        assertEquals(listOf(1L, 2L), result.body().map { it.id })
    }

    // ------------------------------------------------------------ 轮数上限

    @Test
    fun `超过轮数上限时丢最旧的整轮`() {
        val totalRounds = ChatContextAssembler.MAX_HISTORY_ROUNDS + 2
        val history = (1L..(totalRounds * 2)).map { id ->
            if (id % 2 == 1L) user(id, "问$id") else assistant(id, "答$id")
        }
        val result = assemble(history)
        assertEquals(ChatContextAssembler.MAX_HISTORY_ROUNDS, result.keptRounds)
        assertEquals(2, result.omittedCount)
        assertEquals(ChatContextAssembler.MAX_HISTORY_ROUNDS * 2, result.body().size)
        assertTrue(result.body().any { it.id == 5L })
        assertTrue(result.body().none { it.id == 1L || it.id == 2L })
    }

    @Test
    fun `丢的是整轮不是半轮`() {
        val totalRounds = ChatContextAssembler.MAX_HISTORY_ROUNDS + 2
        val history = (1L..(totalRounds * 2)).map { id ->
            if (id % 2 == 1L) user(id, "问$id") else assistant(id, "答$id")
        }
        val result = assemble(history)
        val keptIds = result.body().map { it.id }.toSet()
        keptIds.filter { it % 2 == 1L }.forEach { userId ->
            assertTrue("用户 $userId 的回答被丢了", keptIds.contains(userId + 1))
        }
    }

    // ------------------------------------------------------------ 字符预算

    @Test
    fun `超字符预算时丢最旧的整轮`() {
        val big = "x".repeat(ChatContextAssembler.MAX_HISTORY_CHARS / 6)
        val history = listOf(
            user(1, "问1$big"), assistant(2, "答1$big"),
            user(3, "问3$big"), assistant(4, "答4$big"),
            user(5, "问5$big"), assistant(6, "答6$big")
        )
        val result = assemble(history)
        assertEquals(2, result.keptRounds)
        assertEquals(1, result.omittedCount)
        assertEquals(listOf(3L, 4L, 5L, 6L), result.body().map { it.id })
    }

    @Test
    fun `附件文本计入字符预算`() {
        // 反向用例：只算正文不算附件，预算会形同虚设。
        val sameHistory: List<OutgoingMessage> = listOf(
            OutgoingMessage(id = 1, role = ChatRole.USER, text = "问", attachmentText = "y".repeat(ChatContextAssembler.MAX_HISTORY_CHARS / 2 + 1)),
            assistant(2, "答"),
            OutgoingMessage(id = 3, role = ChatRole.USER, text = "问3", attachmentText = "y".repeat(ChatContextAssembler.MAX_HISTORY_CHARS / 2 + 1)),
            assistant(4, "答4")
        )
        val withAttachment = assemble(sameHistory)
        // 两条附件文本合计超过预算，因此只保留最新一轮。
        assertEquals(1, withAttachment.keptRounds)
        assertEquals(1, withAttachment.omittedCount)

        // 同样两条消息，附件文本清空后正文只有几个字 -> 一轮都不该丢。
        // 两条断言放一起，才能证明「丢弃是被附件文本撑出来的」而不是别的原因。
        val withoutAttachment = assemble(sameHistory.map { it.copy(attachmentText = "") })
        assertEquals(0, withoutAttachment.omittedCount)
        assertEquals(2, withoutAttachment.keptRounds)
    }

    @Test
    fun `system 与题目上下文不占历史预算`() {
        // 它们是「每轮都要带」的固定开销，不该挤掉历史。
        val big = "x".repeat(1900)
        val history = listOf(
            user(1, "问1$big"), assistant(2, "答1$big"),
            user(3, "问3$big"), assistant(4, "答4$big")
        )
        val result = assemble(history, questionContext = "题".repeat(3000))
        assertEquals(2, result.keptRounds)
        assertEquals(0, result.omittedCount)
    }

    // ------------------------------------------------------------ 最新一轮必留

    @Test
    fun `最新一轮永远保留即使超预算`() {
        // 灾难场景：用户刚发的问题被自己发出去的历史挤掉。
        val huge = "x".repeat(ChatContextAssembler.MAX_HISTORY_CHARS / 2 + 1)
        val history = listOf(
            user(1, "旧问$huge"), assistant(2, "旧答$huge"),
            user(3, "旧问3$huge"), assistant(4, "旧答4$huge")
        )
        val result = assemble(history)
        assertEquals(1, result.keptRounds)
        assertEquals(listOf(3L, 4L), result.body().map { it.id })
    }

    @Test
    fun `本次新输入无论多大都发送`() {
        val huge = "x".repeat(ChatContextAssembler.MAX_HISTORY_CHARS + 1)
        val result = assemble(listOf(user(1, "旧问"), assistant(2, "旧答")), pendingText = "新的超长问题$huge")
        assertEquals(listOf(ChatContextAssembler.ID_PENDING), result.body().map { it.id })
        assertEquals(1, result.omittedCount)
    }

    @Test
    fun `只有新输入没有历史时省略数为零`() {
        val result = assemble(emptyList(), pendingText = "你好")
        assertEquals(0, result.omittedCount)
        assertEquals(1, result.keptRounds)
        assertEquals(listOf(ChatContextAssembler.ID_PENDING), result.body().map { it.id })
    }

    @Test
    fun `全空输入不崩`() {
        val result = assemble(emptyList())
        // 只有 system + 题目上下文两条，正文为空
        assertEquals(2, result.messages.size)
        assertEquals(0, result.body().size)
        assertEquals(0, result.omittedCount)
    }

    // ------------------------------------------------------------ 图片张数

    @Test
    fun `图片张数超上限时保留最近的`() {
        val img = listOf("data:image/jpeg;base64,AAA")
        // 带图消息数 = 上限 + 1，保证「名额不够」的前提随上限调整（v0.0.58 由 2 调 4）仍成立
        val count = ChatContextAssembler.MAX_IMAGES_PER_REQUEST + 1
        val history = (1..count).flatMap { n ->
            val base = (n - 1) * 2L
            listOf(
                OutgoingMessage(base + 1, ChatRole.USER, "问$n", images = img),
                assistant(base + 2, "答$n")
            )
        }
        val result = assemble(history)
        val withImages = result.body().filter { it.images.isNotEmpty() }
        assertEquals(ChatContextAssembler.MAX_IMAGES_PER_REQUEST, withImages.size)
        // 输出按时间排序；这里要验的是「拿到名额的是最近 N 条」，不是名额的分配顺序
        assertEquals(
            (2..count).map { (it - 1) * 2L + 1 }.toSet(),
            withImages.map { it.id }.toSet()
        )
        // 反向用例：最旧那条必须没有图（正序遍历会让它抢光名额）
        assertTrue(result.body().none { it.id == 1L && it.images.isNotEmpty() })
    }

    @Test
    fun `一条消息里多张图只取前几张并占满剩余名额`() {
        // 旧消息带 上限+1 张图，最新一条带 1 张：最新占走 1 个名额，
        // 旧消息只剩 上限-1 个名额，且必须从头截（A、B、C…）而不是留尾巴
        val img = (0 until ChatContextAssembler.MAX_IMAGES_PER_REQUEST + 1)
            .map { (it + 'A'.code).toChar().toString() }
        val history = listOf(
            OutgoingMessage(1, ChatRole.USER, "问1", images = img),
            assistant(2, "答2"),
            OutgoingMessage(3, ChatRole.USER, "问3", images = listOf("D"))
        )
        val result = assemble(history)
        val first = result.body().first { it.id == 1L }
        assertEquals(ChatContextAssembler.MAX_IMAGES_PER_REQUEST - 1, first.images.size)
        assertEquals("A", first.images.first())
    }

    @Test
    fun `没有图时不改动消息`() {
        val history = listOf(user(1, "纯文字"))
        val result = assemble(history)
        assertTrue(result.body().single().images.isEmpty())
    }

    // ------------------------------------------------------------ 省略条数

    @Test
    fun `省略数在有无新输入两种情况下都正确`() {
        val historyRounds = ChatContextAssembler.MAX_HISTORY_ROUNDS + 2
        val history = (1L..(historyRounds * 2)).map { id ->
            if (id % 2 == 1L) user(id, "问$id") else assistant(id, "答$id")
        }
        val withoutPending = assemble(history)
        assertEquals(ChatContextAssembler.MAX_HISTORY_ROUNDS, withoutPending.keptRounds)
        assertEquals(2, withoutPending.omittedCount)

        val withPending = assemble(history, pendingText = "新问题")
        assertEquals(ChatContextAssembler.MAX_HISTORY_ROUNDS, withPending.keptRounds)
        assertEquals(3, withPending.omittedCount)
        assertEquals(ChatContextAssembler.ID_PENDING, withPending.body().last().id)
    }

    @Test
    fun `usedChars 反映历史占用`() {
        val result = assemble(listOf(user(1, "12345"), assistant(2, "678")))
        assertEquals(8, result.usedChars)
    }
}
