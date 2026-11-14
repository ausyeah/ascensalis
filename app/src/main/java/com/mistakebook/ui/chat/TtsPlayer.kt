package com.mistakebook.ui.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * 朗读播放器（v0.0.154，学 deepseek-ai/chat-demo 的 readAloud）。
 *
 * 单例：同一时刻只朗读一条消息，再点另一条就是「停旧的、读新的」。
 * TextToSpeech 是 Android 框架自带能力——零新依赖；引擎初始化是异步的，
 * 初始化完成前排队的 speak 请求在 onInit 成功后统一补发。
 *
 * 状态用 [StateFlow] 暴露给 Compose：按钮据此在「播放 / 停止」间切换。
 */
object TtsPlayer {

    /** 当前朗读的消息 id；null = 空闲。Compose 按它切换按钮图标。 */
    private val activeMessage = MutableStateFlow<Long?>(null)
    val speakingMessageId: StateFlow<Long?> get() = activeMessage

    private var tts: TextToSpeech? = null
    private var ready = false
    /** 初始化完成前排队待读的请求。 */
    private var pending: Pair<String, Long>? = null

    /** 目标消息正在朗读？ */
    fun isSpeaking(messageId: Long): Boolean = activeMessage.value == messageId

    /** 点按钮的总入口：在读就停，没在读就开始读这条。 */
    fun toggle(context: Context, messageId: Long, text: String) {
        if (isSpeaking(messageId)) {
            stop()
            return
        }
        stop()
        activeMessage.value = messageId
        speakAfterInit(context.applicationContext, text, messageId)
    }

    /** 停止朗读并复位状态（切页 / 换消息时也要调）。 */
    fun stop() {
        activeMessage.value = null
        pending = null
        tts?.let { engine ->
            engine.stop()
        }
    }

    private fun speakAfterInit(context: Context, text: String, messageId: Long) {
        val engine = tts ?: TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
                drainPending()
            } else {
                // 引擎不可用（无 TTS 数据等）：复位并让按钮回到可点状态
                tts = null
                activeMessage.value = null
            }
        }.also { tts = it }
        // 已就绪的引擎直接读；未就绪的排队等 onInit 回调
        if (ready) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "read-$messageId")
        } else {
            pending = text to messageId
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                activeMessage.value = null
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                activeMessage.value = null
            }
        })
    }

    private fun drainPending() {
        val (text, messageId) = pending ?: return
        pending = null
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "read-$messageId")
    }
}
