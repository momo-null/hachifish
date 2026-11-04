package com.hachimi.app

import kotlin.math.sqrt

/**
 * 免提的"说到哪儿算一句"——能量 VAD + 挂起端点 + 播报期间插话判定。
 *
 * 为什么不用固定 8s 自动收口（旧实现）：自由对话里一句话的长度是说话人决定的，
 * 不是计时器决定的。固定窗口要么把人拦腰截断，要么在无人说话时空转上传空 WAV。
 *
 * 为什么自己写而不用 AudioRecord 的 VAD/自动端点：Android 没有可用的端侧连续
 * 识别 API 给"第三方 STT + 全双工"这种形态（SpeechRecognizer 已被系统 ASR
 * 严格化时整段删除），而插话判定要求"播报期间还能分辨出近端人声"——这需要自己
 * 拿到逐帧能量。
 *
 * 纯逻辑（不碰 Android 类）→ JVM 单测覆盖 [Segmenter]。
 */
data class VadParams(
    /** 帧长（ms）；16k 单声道 PCM16 下 10ms = 160 样本。 */
    val frameMs: Int = 10,
    /** 起句：连续 N 帧过阈（防单个咔哒声）。 */
    val startFrames: Int = 4,
    /** 收口：句内连续静音多久算说完。 */
    val hangoverMs: Long = 700,
    /** 浊音累计时长短于此判噪声（咳嗽/键盘声），整段丢。 */
    val minSpeechMs: Long = 240,
    /** 硬上限：一口气不停也先切一段（防无限增长）。 */
    val maxSpeechMs: Long = 15000,
    /** 起句前保留的缓冲：首音节常被 VAD 判晚 100~200ms。 */
    val prerollMs: Int = 300,
    /** 相对噪声底的起句倍率。 */
    val relThreshold: Double = 3.0,
    /** 绝对地板：安静房间里噪声底极低，不放地板会把自己呼吸当话。 */
    val absFloor: Double = 0.012,
    /** 播报期间起句倍率（近端要压过消不干净的余 echo 才算人在说话）。 */
    val bargeRel: Double = 6.0,
    val bargeFloor: Double = 0.035,
    /** 播报期间连续多少帧过插话阈才认（150ms：短促余音不算人开口）。 */
    val bargeFrames: Int = 15,
    /** 一次插话后的冷却：播报被停的这几帧里不再重复触发。 */
    val bargeCooldownMs: Long = 1500,
)

sealed class SegEvent {
    object None : SegEvent()

    /** 起句（未播报或播报已停）。 */
    object SpeechStart : SegEvent()

    /** 播报期间检到近端说话——调用方第一动作是停播（[AudioPlayer.stop]）。 */
    object BargeIn : SegEvent()

    /** 一句收口：pcm 是 PCM16 小端字节流（含 [VadParams.prerollMs] 前缓冲）。 */
    data class Endpoint(val pcm: ByteArray, val ms: Long, val startedWhilePlaying: Boolean) : SegEvent()

    /** 段太短（咳嗽/键盘声），已丢弃。 */
    data class Rejected(val ms: Long) : SegEvent()
}

/**
 * 逐帧喂能量，自己持有帧缓冲。一次一句：Endpoint/Rejected 后自动回静默态。
 *
 * @param aecOn 硬件回声消除是否真在生效。false 时调用方不会一边播一边喂——
 *              但这里也做了兜底：播报期间来的帧整段丢弃并复位（半双工）。
 */
class Segmenter(private val p: VadParams = VadParams()) {

    private val preroll = ArrayDeque<ShortArray>()
    private val open = mutableListOf<ShortArray>()
    private var noiseFloor = 0.002
    private var runFrames = 0
    private var bargeRun = 0
    private var lastBargeAt = Long.MIN_VALUE / 2
    private var inSpeech = false
    private var silentMs = 0L
    private var speechMs = 0L
    private var voicedMs = 0L
    private var tailSilent = 0
    private var startedWhilePlaying = false

    /** 当前起句阈值（观测用：Rlog 里能看出噪声底漂到哪儿了）。 */
    var threshold = startThr(0.002); private set

    private fun startThr(nf: Double) = maxOf(nf * p.relThreshold, p.absFloor)
    private fun bargeThr(nf: Double) = maxOf(nf * p.bargeRel, p.bargeFloor)

    /** 一帧进来 → 事件。nowMs 用单调时钟（SystemClock.elapsedRealtime）语义。 */
    fun feed(rms: Double, nowMs: Long, frame: ShortArray, playing: Boolean, aecOn: Boolean): SegEvent {
        threshold = startThr(noiseFloor)
        if (!aecOn && playing) {
            // 无 AEC 的半双工兜底：喇叭响着的时候不听（否则念什么都能"听见"什么）
            reset()
            return SegEvent.None
        }
        // 噪声底只在确认的静默期学习：说话/播报期间不更新，防止把人声/回声学成"底"
        if (!inSpeech && rms < threshold) noiseFloor = 0.95 * noiseFloor + 0.05 * rms

        if (inSpeech) {
            open.add(frame)
            speechMs += p.frameMs
            if (rms < threshold) { silentMs += p.frameMs; tailSilent++ } else {
                silentMs = 0; tailSilent = 0; voicedMs += p.frameMs
                bargeRun = if (playing && rms > bargeThr(noiseFloor)) bargeRun + 1 else 0
            }
            // 插话判定：播报期间持续过"插话线"（比起句线高）→ 一次性事件，
            // 调用方第一动作是停播；句子照常继续收，不等下一轮（冷却期内不重复发）。
            if (playing && aecOn && bargeRun >= p.bargeFrames &&
                nowMs - lastBargeAt >= p.bargeCooldownMs) {
                lastBargeAt = nowMs
                return SegEvent.BargeIn
            }
            if (silentMs >= p.hangoverMs) return close()
            if (speechMs >= p.maxSpeechMs) return close()
            return SegEvent.None
        }

        pushPreroll(frame)
        runFrames = if (rms > threshold) runFrames + 1 else 0
        bargeRun = if (playing && rms > bargeThr(noiseFloor)) bargeRun + 1 else 0

        if (runFrames < p.startFrames) return SegEvent.None
        inSpeech = true
        startedWhilePlaying = playing
        silentMs = 0
        tailSilent = 0
        voicedMs = p.frameMs.toLong() * p.startFrames
        speechMs = voicedMs
        open.clear()
        open.addAll(preroll)
        preroll.clear()
        val barge = playing && bargeRun >= p.bargeFrames &&
            nowMs - lastBargeAt >= p.bargeCooldownMs
        if (barge) lastBargeAt = nowMs
        return if (barge) SegEvent.BargeIn else SegEvent.SpeechStart
    }

    /** 停麦（关免提/退出）：丢弃在途片段，不补发。 */
    fun reset() {
        preroll.clear(); open.clear()
        inSpeech = false; runFrames = 0; bargeRun = 0
        silentMs = 0; speechMs = 0; voicedMs = 0; tailSilent = 0
    }

    /** 当前是否在句中（观测用）。 */
    fun inUtterance(): Boolean = inSpeech

    /** 噪声底（观测用）。 */
    fun noiseFloor(): Double = noiseFloor

    private fun pushPreroll(frame: ShortArray) {
        preroll.addLast(frame)
        val keep = (p.prerollMs / p.frameMs).coerceAtLeast(1)
        while (preroll.size > keep) preroll.removeFirst()
    }

    /**
     * 收口。两件事在这里做，都是为了让上传的那段音频"就是这句话"：
     * ① 按**浊音时长**判长短（voicedMs < minSpeechMs = 咳嗽/键盘声，整段丢）——
     *    不能拿总时长判，挂起静音和前置缓冲会把一段咔哒声撑到 1s 以上；
     * ② 尾部静音裁剪（留 [TRAIL_KEEP_MS] 兜底），否则每句都白传 700ms 空音，
     *    还白白多开 700ms 的回声窗口。
     */
    private fun close(): SegEvent {
        val voiced = voicedMs
        val tail = tailSilent
        val all = open.toList()
        reset()
        if (voiced < p.minSpeechMs) return SegEvent.Rejected(voiced)
        val keepTail = (TRAIL_KEEP_MS / p.frameMs).coerceAtLeast(1)
        val drop = (tail - keepTail).coerceAtLeast(0)
        val frames = if (drop > 0) all.take((all.size - drop).coerceAtLeast(1)) else all
        val samples = frames.sumOf { it.size }
        if (samples == 0) return SegEvent.Rejected(voiced)
        return SegEvent.Endpoint(
            Wav.shortsToBytes(frames), samples.toLong() * 1000 / 16000, startedWhilePlaying)
    }

    companion object {
        /** 收口时尾部保留的静音（ms）：留一点尾音给 STT，不留整段挂起窗口。 */
        const val TRAIL_KEEP_MS = 120

        /** 一帧的归一化 RMS（0..1）。空帧返 0。 */
        fun rms(buf: ShortArray, n: Int = buf.size): Double {
            if (n <= 0) return 0.0
            var acc = 0.0
            for (i in 0 until n) {
                val v = buf[i] / 32768.0
                acc += v * v
            }
            return sqrt(acc / n)
        }
    }
}

/**
 * 余 echo 转写过滤器：消不干净的那点回声，或者播报刚停麦克风采到的尾音，
 * 上传 STT 后可能回来一段"和大肥鱼刚念的话很像"的文本。这种文本一旦被当成
 * 用户插话发出去，就是自我循环——所以先比文本，再决定发不发。
 *
 * 判据是字符二元组重合率（不是编辑距离）：中文没有词边界，且 STT 会在 echo 上
 * 丢字/加字，重合率对"部分还原"最稳。纯逻辑 → JVM 单测。
 */
object EchoGuard {

    private const val WINDOW_MS = 20_000L

    @Volatile private var spokenNorm = ""
    @Volatile private var spokenAt = 0L

    /** 记下了正要念的话（在 TTS 合成前就记：窗口从"开口"算，不是从"念完"算）。 */
    fun note(text: String, nowMs: Long = System.currentTimeMillis()) {
        spokenNorm = normalize(text)
        spokenAt = nowMs
    }

    fun clear() {
        spokenNorm = ""
    }

    /** 这段转写像不像自己念的话的回声。窗口外/没念过 → false（不误杀正常插话）。 */
    fun looksLikeEcho(
        transcript: String,
        nowMs: Long = System.currentTimeMillis(),
        minOverlap: Double = 0.6,
        minChars: Int = 3,
    ): Boolean {
        val t = normalize(transcript)
        if (spokenNorm.isBlank() || t.isBlank()) return false
        if (nowMs - spokenAt > WINDOW_MS) return false
        if (t.length < minChars) return false
        if (spokenNorm.contains(t)) return true
        val bigrams = (0 until t.length - 1).map { t.substring(it, it + 2) }
        if (bigrams.isEmpty()) return false
        val hit = bigrams.count { spokenNorm.contains(it) }
        return hit.toDouble() / bigrams.size >= minOverlap
    }

    private fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }
}
