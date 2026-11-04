package com.hachimi.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 通话链路形态（免提全双工的地基）。
 *
 * 系统的回声消除不是"一个可以调用的库"，是 AudioFlinger 在**通话模式**下做的：
 * 只有当麦克风走 VOICE_COMMUNICATION、喇叭走 USAGE_VOICE_COMMUNICATION、且
 * AudioManager.mode = MODE_IN_COMMUNICATION 时，HAL 才同时握着"你说的话"和
 * "它刚放出去的声音"，才有参考信号可对消。MediaPlayer/媒体流上的声音对它是透明的
 * ——念出去的话回收到自己耳朵里，就是免提自我循环打断的根因。
 *
 * 代价：通话模式会把路由切到"听筒优先"，所以显式把路由钉在外放扬声器，
 * 退出时恢复原路由与原 mode（不打扰用户接下来的通话/媒体）。
 */
object CommsAudio {

    private var depth = 0
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false

    /** 平台是否提供 AEC 效果（true ≠ 一定消得干净，但 false 一定没有）。 */
    fun aecAvailable(): Boolean = runCatching { AcousticEchoCanceler.isAvailable() }
        .getOrDefault(false)

    fun enter(context: Context) {
        val am = am(context) ?: return
        if (depth++ > 0) return
        savedMode = runCatching { am.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        savedSpeaker = runCatching { @Suppress("DEPRECATION") am.isSpeakerphoneOn }
            .getOrDefault(false)
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
            .onFailure { Rlog.log("comms mode 设置失败：${it.message}") }
        runCatching { routeSpeaker(am) }.onFailure { Rlog.log("外放路由失败：${it.message}") }
    }

    fun exit(context: Context) {
        if (depth == 0) return
        if (--depth > 0) return
        val am = am(context) ?: return
        runCatching { routeRelease(am) }
        runCatching { am.mode = savedMode }
    }

    private fun am(context: Context): AudioManager? =
        runCatching { context.getSystemService(AudioManager::class.java) }.getOrNull()

    /** 钉到本机扬声器（API 31+ 用 CommunicationDevice，30 用旧的 speakerphone 开关）。 */
    private fun routeSpeaker(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val spk = am.availableCommunicationDevices.firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (spk != null) am.setCommunicationDevice(spk)
            else Rlog.log("没找到本机扬声器通信设备，路由交给系统默认")
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        }
    }

    private fun routeRelease(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
        else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = savedSpeaker
        }
    }
}

/**
 * 挂在录音 session 上的平台音效（AEC / NS / AGC）。
 * 可用性一律以 `isAvailable()` + `create()` 实测为准——设备 ROM 常报"可用"但创建失败，
 * 所以 [aecOn] 只认"真的 enabled 了"，不认"平台说有"。诚实口径直接进 toast 与 Rlog。
 */
class SessionEffects(sessionId: Int) {

    private val held = mutableListOf<AudioEffect>()
    var aecOn = false; private set
    var nsOn = false; private set
    var agcOn = false; private set

    init {
        if (runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)) {
            val fx = runCatching { AcousticEchoCanceler.create(sessionId) }.getOrNull()
            if (fx != null) {
                held.add(fx)
                aecOn = runCatching { fx.enabled = true; fx.enabled }.getOrDefault(false)
            }
        }
        if (runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)) {
            val fx = runCatching { NoiseSuppressor.create(sessionId) }.getOrNull()
            if (fx != null) {
                held.add(fx)
                nsOn = runCatching { fx.enabled = true; fx.enabled }.getOrDefault(false)
            }
        }
        // AGC 谨慎开：它会把近端小声放大，也可能把余 echo 一起放大。只在平台提供时开，
        // 实测"插话误触发多"就把这条关掉——留观测口（summary 进 Rlog）。
        if (runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false)) {
            val fx = runCatching { AutomaticGainControl.create(sessionId) }.getOrNull()
            if (fx != null) {
                held.add(fx)
                agcOn = runCatching { fx.enabled = true; fx.enabled }.getOrDefault(false)
            }
        }
    }

    fun release() {
        held.forEach { runCatching { it.enabled = false; it.release() } }
        held.clear()
    }

    /** 能力真相（"硬件说有 AEC"与"这台机器上 AEC 真在跑"是两件事）。 */
    fun summary(): String = "aec=$aecOn ns=$nsOn agc=$agcOn"
}

/**
 * 通话（+ 菜单入口，形态 = 免提）的全双工收音引擎：连续听 + 端点分段 + 播报期间插话。
 *
 * 前身是"按住说话"的对讲形态（2026-11-04 整段删除——它本质只是把语音转成文字再打字，
 * 通话是它的超集）。对讲是半双工、天然没有回音问题；这条链路是大肥鱼念回复时麦克风
 * 仍开着的全双工，所以要
 * ① 走通话链路拿平台 AEC（[CommsAudio] + [SessionEffects]）；
 * ② 播报期间的起句门槛更高（[Segmenter] 的 barge 阈值），过线即先停播再收句；
 * ③ 消不干净的余音若真被转写回来，用 [EchoGuard] 按文本比掉，不发出去。
 *
 * 设备没有平台 AEC 时（[SessionEffects.aecOn]=false）自动退成半双工：
 * 播报期间整段丢弃不判句——宁可"这会儿插不进去"，也不要"它自己跟自己聊"。
 */
class DuplexListener(
    private val context: Context,
    private val provider: VoiceProviderStore.Provider,
    /** 一句收口 + 转写成功且判定不是回声（主线程回调由调用方自己 post）。 */
    private val onUtterance: (String) -> Unit,
    /** 播报期间检到近端说话：调用方第一动作是 [AudioPlayer.stop]。 */
    private val onBargeIn: () -> Unit,
    private val onError: (String) -> Unit,
    private val params: VadParams = VadParams(),
) {
    private var rec: AudioRecord? = null
    private var effects: SessionEffects? = null
    private var capture: Thread? = null
    private var stt: Thread? = null
    @Volatile private var listening = false
    /** 在途转写连接：stop/退出必须断开，否则陈旧请求叠着打引擎（懒加载 + 60s 读超时）。 */
    @Volatile private var conn: java.net.HttpURLConnection? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val seg = Segmenter(params)

    /** 平台 AEC 是否真在跑——决定"能不能边听边说"。 */
    var aecOn = false; private set
    val fullDuplex: Boolean get() = aecOn
    val inUtterance: Boolean get() = seg.inUtterance()

    /**
     * 起麦。返回 null = 起来了；返回字符串 = 起不来的原因（直接给用户看的那句人话，
     * 走调用方的 toast，不混进运行时 [onError] 通道——"开不起来"和"开着的时候坏了"
     * 是两件事，重试计数只该记后者）。
     */
    fun start(): String? {
        CommsAudio.enter(context)
        val minBuf = runCatching {
            AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
        }.getOrDefault(0)
        if (minBuf <= 0) return refuse("本机不支持 16k 单声道收音")
        // VOICE_COMMUNICATION 是这套方案的全部前提：它把这条收音拉进通话链路，
        // 平台 AEC/NS 才有资格挂上来（MIC 源挂不上，也拿不到远端参考信号）
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 16384))
        } catch (se: SecurityException) {
            return refuse("麦克风权限未授予 → 设置 · 应用权限")
        } catch (e: Exception) {
            return refuse("录音器创建失败：${e.message}")
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { r.release() }
            return refuse("通话链路收音器初始化失败（这台设备不支持 VOICE_COMMUNICATION 采集）")
        }
        rec = r
        effects = SessionEffects(r.audioSessionId)
        aecOn = effects?.aecOn == true
        Rlog.log("duplex start: ${effects?.summary()} fullDuplex=$aecOn")
        listening = true
        capture = Thread { captureLoop(r) }.apply { start() }
        stt = Thread { sttLoop() }.apply { start() }
        return null
    }

    /** 收麦：停采集 → 等两条线程退出 → 释放 → 退出通话模式。 */
    fun stop() {
        listening = false
        runCatching { conn?.disconnect() }
        conn = null
        queue.clear()
        seg.reset()
        runCatching { capture?.join(500) }
        runCatching { stt?.join(500) }      // 在途转写的连接已由 conn 断开
        val r = rec; rec = null
        runCatching { r?.stop() }
        runCatching { r?.release() }
        effects?.release(); effects = null
        CommsAudio.exit(context)
        Rlog.log("duplex stop")
    }

    private fun captureLoop(r: AudioRecord) {
        val frame = ShortArray(FRAME_SAMPLES)
        var badReads = 0
        runCatching { r.startRecording() }
            .onFailure { onError("收音启动失败：${it.message}") }
        while (listening) {
            val n = runCatching { r.read(frame, 0, frame.size) }.getOrDefault(-1)
            if (n <= 0) {
                if (n < 0 && ++badReads > 30) {
                    Rlog.log("duplex read 连续失败 $badReads 次，收麦")
                    listening = false
                }
                continue
            }
            badReads = 0
            val f = if (n == frame.size) frame.copyOf() else frame.copyOf(n)
            val playing = AudioPlayer.isPlaying()
            when (val ev = seg.feed(Segmenter.rms(f),
                android.os.SystemClock.elapsedRealtime(), f, playing, aecOn)) {
                SegEvent.BargeIn -> {
                    Rlog.log("barge-in: 播报中检到近端语音（thr=${"%.4f".format(seg.threshold)}）")
                    onBargeIn()
                }
                is SegEvent.Endpoint -> {
                    Rlog.log("utt ${ev.ms}ms startedWhilePlaying=${ev.startedWhilePlaying}")
                    queue.offer(ev.pcm)
                }
                else -> Unit     // None / SpeechStart / Rejected：不刷屏，句太短直接丢
            }
        }
    }

    /** 串行转写：一句一句上传（并发打 STT = 引擎侧排队/503，实测过）。 */
    private fun sttLoop() {
        while (listening) {
            val pcm = runCatching { queue.poll(200, TimeUnit.MILLISECONDS) }.getOrNull()
                ?: continue
            val wav = Wav.fromPcm(pcm, RATE)
            // 物证：免提这段音频单独落一个文件（对讲是 last_stt.wav），
            // "听见的是不是自己"要能拉下来听
            runCatching { java.io.File(context.cacheDir, "last_duplex.wav").writeBytes(wav) }
            try {
                val text = ProviderStt.transcribe(provider, wav) { conn = it }
                conn = null
                if (text.isBlank()) continue
                if (EchoGuard.looksLikeEcho(text)) {
                    Rlog.log("echo dropped（${text.length}字，与自己刚念的话重合）")
                    continue
                }
                onUtterance(text)
            } catch (e: Exception) {
                conn = null
                Rlog.log("duplex stt failed: ${e.message ?: "null"}")
                onError("语音转写失败：${e.message}")
            }
        }
    }

    /** 起不来的收口：留痕 + 退出通话模式（别让设备停在 MODE_IN_COMMUNICATION 上）。 */
    private fun refuse(msg: String): String {
        Rlog.log("duplex refused: $msg")
        CommsAudio.exit(context)
        return msg
    }

    private companion object {
        const val RATE = 16000

        /** 10ms @16k——与 [VadParams.frameMs] 一帧一算。 */
        const val FRAME_SAMPLES = 160
    }
}
