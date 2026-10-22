package com.mistakebook.wordbook.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 单词朗读（系统 TTS）。
 *
 * ## 为什么首次点击经常没声音
 *
 * [TextToSpeech] 构造后初始化是**异步**的（[onInit] 回调要等系统 TTS 服务绑好，
 * 通常几百毫秒到两秒）。旧版在 `speak()` 里发现未就绪就直接 `return`——
 * 用户第一次点发音正好撞上这个窗口，声音被**静默丢掉**，
 * 再点一次就正常了，看起来像「按钮时灵时不灵」。
 *
 * 现在未就绪时的文本先存进 [pendingSpeak]，初始化完成后自动补发，
 * 首次点击也能出声。
 *
 * ## 「完全没声音」的另一面
 *
 * 部分 ROM（小米/澎湃等）默认没有装任何 TTS 引擎，`onInit` 会收到
 * [TextToSpeech.ERROR] 或语言全部缺数据。这种情况应用侧无解，
 * 只能如实告诉用户去系统设置开引擎——暴露 [unavailable] 状态，
 * 由 ViewModel 弹 snackbar 提示，而不是让用户对着一个没反应的按钮干等。
 */
class WordAudioPlayer(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /** 系统没有可用的 TTS 引擎（或没有任何英语语言包）。 */
    private val _unavailable = MutableStateFlow(false)
    val unavailable: StateFlow<Boolean> = _unavailable.asStateFlow()

    /** 初始化完成前点下的那一次朗读，就绪后补发。 */
    private var pendingSpeak: String? = null

    init {
        try {
            // Use applicationContext to avoid Activity memory leaks
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e("WordAudioPlayer", "Failed to initialize TextToSpeech", e)
            _unavailable.value = true
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val ttsInstance = tts ?: return

            // 优先美音，退英音，再退泛英语。都不可用**不判死**：
            // 用户手机很可能装的是纯中文引擎（小米「系统语音引擎」
            // 首选语言是中文），setLanguage(英语) 会返回 NOT_SUPPORTED，
            // 但引擎仍能把英文单词念出来（音素近似或逐字母拼）。
            // 退回系统默认语言、照常标记 ready——有声音总比没有强。
            // 只有 status == ERROR（设备根本没有任何 TTS 引擎）才算不可用。
            val resultUS = ttsInstance.setLanguage(Locale.US)
            if (resultUS == TextToSpeech.LANG_MISSING_DATA || resultUS == TextToSpeech.LANG_NOT_SUPPORTED) {
                val resultUK = ttsInstance.setLanguage(Locale.UK)
                if (resultUK == TextToSpeech.LANG_MISSING_DATA || resultUK == TextToSpeech.LANG_NOT_SUPPORTED) {
                    val resultEn = ttsInstance.setLanguage(Locale.ENGLISH)
                    if (resultEn == TextToSpeech.LANG_MISSING_DATA || resultEn == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w("WordAudioPlayer", "English language unavailable (result=$resultEn), falling back to system default language")
                        ttsInstance.setLanguage(Locale.getDefault())
                    }
                }
            }
            _isReady.value = true
        } else {
            Log.e("WordAudioPlayer", "TTS Initialization failed with status: $status")
            _unavailable.value = true
        }

        // 就绪后把初始化期间点下的那次朗读补上——用户不该为系统的初始化慢买单。
        if (_isReady.value) {
            pendingSpeak?.let { text ->
                pendingSpeak = null
                speak(text)
            }
        }
    }

    /**
     * 朗读一个单词。
     *
     * @return false 表示当前设备发不出声（无 TTS 引擎 / 无语言包），
     *   调用方据此给用户提示；true 仅代表「已交给系统」，不代表一定有声音。
     */
    fun speak(text: String, pitch: Float = 1.0f, rate: Float = 0.95f): Boolean {
        if (text.isBlank()) return true
        if (_unavailable.value) return false
        if (!_isReady.value) {
            // 初始化中：暂存，就绪后补发。只留最新一条（连续快速点击以最后一次为准）。
            pendingSpeak = text
            return true
        }
        return try {
            tts?.apply {
                setPitch(pitch)
                setSpeechRate(rate)
                // Use QUEUE_FLUSH to interrupt current speech and speak immediately
                val utteranceId = "WordAudio_${System.currentTimeMillis()}"
                speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
            true
        } catch (e: Exception) {
            Log.e("WordAudioPlayer", "Error during TTS speak", e)
            false
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e("WordAudioPlayer", "Error during TTS stop", e)
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            _isReady.value = false
            pendingSpeak = null
        } catch (e: Exception) {
            Log.e("WordAudioPlayer", "Error during TTS shutdown", e)
        }
    }
}
