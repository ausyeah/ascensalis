package com.mistakebook.ui.chat

import com.mistakebook.data.chat.ChatPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 生成中的「换模型」与停止后的「继续 / 重试」（v0.0.109）。
 *
 * 两件事都源于同一句用户感受：**生成过程不该把人锁住**。
 * 换模型只是预先挑好下一轮用谁（正在跑的这一轮由服务拿着已发出的请求）；
 * 停止之后也不该逼用户回输入框重打一遍——「继续」和「重试」就是那两个出口。
 */
class ChatStopActionsTest {

    @Test
    fun `生成中也能换模型`() {
        val bar = readSource("src/main/java/com/mistakebook/ui/chat/ChatInputBar.kt")
        assertFalse(
            "模型入口不许再被 streaming 锁住——换的是下一轮，不影响正在输出的这一轮",
            bar.contains("clickable(enabled = !streaming, onClick = onPickModel)")
        )
        assertTrue("点开模型面板必须仍然可用", bar.contains(".clickable(onClick = onPickModel)"))

        val screen = readSource("src/main/java/com/mistakebook/ui/chat/ChatScreen.kt")
        assertFalse("模型面板本身也不许在流式中禁用", screen.contains("enabled = !state.isStreaming"))
    }

    @Test
    fun `停止的消息给出继续与重试`() {
        val bubble = readSource("src/main/java/com/mistakebook/ui/chat/ChatMessageBubble.kt")
        val start = bubble.indexOf("message.status == MessageStatus.CANCELED")
        assertTrue("停止态分支必须存在", start > 0)
        val block = bubble.substring(start, start + 1600)
        assertTrue("停止后要有「继续」", block.contains("R.string.chat_continue"))
        assertTrue("停止后要有「重试」", block.contains("R.string.chat_retry"))
        assertTrue("继续要传消息 id", block.contains("onContinue(message.id)"))
        assertTrue("重试要传消息 id", block.contains("onRetry(message.id)"))

        // 两侧接上才点得动
        assertTrue(
            "列表要把 onContinue 透传给气泡",
            readSource("src/main/java/com/mistakebook/ui/chat/ChatMessageList.kt")
                .contains("onContinue = onContinue")
        )
        assertTrue(
            "对话页要接到 ViewModel",
            readSource("src/main/java/com/mistakebook/ui/chat/ChatScreen.kt")
                .contains("viewModel.continueGeneration(id)")
        )
    }

    @Test
    fun `继续就是发一句继续但不落库`() {
        assertEquals("继续", ChatPrompts.CONTINUE)

        val vm = readSource("src/main/java/com/mistakebook/ui/chat/ChatViewModel.kt")
        // isRetry = true 是「不插入用户消息」的开关：走 pendingText，
        // 所以界面上看不到这句话，模型看得到。
        assertTrue(
            "继续必须走 retry 的通道（不落库、不进气泡）",
            vm.contains("runOnce(profile, ChatPrompts.CONTINUE, emptyList(), isRetry = true)")
        )
        assertTrue("正在流式的那条没有「继续」可言", vm.contains("if (stopped.status == MessageStatus.STREAMING) return"))
    }

    @Test
    fun `继续与重试的语义不同`() {
        val vm = readSource("src/main/java/com/mistakebook/ui/chat/ChatViewModel.kt")
        // 重试要删掉旧回答重答；继续保留已经写出来的部分
        assertTrue(vm.contains("repository.deleteMessage(failed.id)"))
        assertFalse(
            "继续绝不能删掉已经写出来的内容",
            vm.substring(
                vm.indexOf("fun continueGeneration("),
                vm.indexOf("fun stop()")
            ).contains("deleteMessage")
        )
    }

    private fun readSource(path: String): String {
        val candidates = listOf(
            File(path),
            File("../$path"),
            File(path.removePrefix("app/")),
            File("../" + path.removePrefix("app/"))
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalArgumentException("Cannot find file: $path among $candidates")
        return file.readText()
    }
}