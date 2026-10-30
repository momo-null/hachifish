package com.hachimi.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 音频播放器（远程播报 = PC CosyVoice2 合成的 WAV；Tier 2 音色与桌面侧一致）。
 * 单实例 MediaPlayer；打断 = stop()（C2：按住 🎤 第一动作）。
 */
object AudioPlayer {

    private var player: android.media.MediaPlayer? = null

    fun play(context: Context, wav: ByteArray) {
        stop()
        try {
            val tmp = java.io.File(context.cacheDir, "remote_reply.wav")
            tmp.writeBytes(wav)
            player = android.media.MediaPlayer().apply {
                setDataSource(tmp.absolutePath)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w("HachimiRemote", "play failed: ${e.message}")
            stop()
        }
    }

    fun stop() {
        try {
            player?.takeIf { it.isPlaying }?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    fun isPlaying(): Boolean = player?.isPlaying == true
}

/**
 * 语音 I/O（手机伴侣 C1-C2）：系统 ASR（SpeechRecognizer）+ 系统 TTS（TextToSpeech，
 * 仅 Tier 1/回退用——Tier 2 播报走 [AudioPlayer] 的 PC CosyVoice2 音色）。
 *
 * - ASR：对讲式（按住说话）——start() 收音，stopListening() 松手取最终结果；
 * - TTS：speak() QUEUE_FLUSH（新播报挤掉旧的），stop() 即打断（C2）；
 * - 生命周期：TTS 引擎进程级单例懒加载；SpeechRecognizer 每轮创建/销毁
 *   （官方要求主线程创建，调用方用 main Handler 包）。
 */
object TtsSpeaker {

    private const val TAG = "HachimiRemote"
    private var tts: TextToSpeech? = null
    private var ready = false

    /** 初始化（ChatPage 构造时调用一次，引擎提前热好）；失败静默关闭播报（对话不受影响）。 */
    fun ensure(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
                Log.i(TAG, "TTS ready (zh-CN)")
            } else {
                Log.w(TAG, "TTS init failed: status=$status")
            }
        }
    }

    /** 播报（QUEUE_FLUSH：按住 🎤 打断即被 stop() 取代）；引擎未就绪时静默跳过。 */
    fun speak(text: String) {
        val t = text.trim()
        if (t.isEmpty() || !ready) return
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "hachimi_${System.currentTimeMillis()}")
    }

    /** 打断播报（C2：按住 🎤 的第一动作）。 */
    fun stop() {
        tts?.stop()
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}

/**
 * 系统 ASR 收音控制器（对讲式：start 收音 → stopListening 出最终结果）。
 *
 * 调用方（ChatPage）：🎤 ACTION_DOWN → [start]；ACTION_UP → [stopListening]；
 * 最终文本经 [onFinal] 回到发送路径（同输入框路径，对讲式 = 松手出字并直接发送）。
 */
class SpeechInput(
    private val context: Context,
    private val lang: String = "zh-CN",
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit
) {

    private var recognizer: SpeechRecognizer? = null

    fun start() {
        destroy()   // 取消上一轮收音（若有）
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("语音识别不可用（设备缺少识别服务）")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val t = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty().trim()
                    if (t.isNotEmpty()) onFinal(t)
                }

                override fun onError(error: Int) {
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) return   // 没听清 = 静默结束，不打扰
                    onError(when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
                        SpeechRecognizer.ERROR_NETWORK -> "识别服务网络异常"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别服务网络超时"
                        SpeechRecognizer.ERROR_CLIENT -> "识别客户端异常"
                        else -> "识别出错（code=$error）"
                    })
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        }
    }

    /** 松手：结束收音并取最终结果（onFinal 回调）。 */
    fun stopListening() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
