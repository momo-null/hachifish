package com.hachimi.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * 播报播放器（供应商 / PC 合成回来的 WAV 直接播）。
 *
 * 2026-11-04 全双工改造：不再是 MediaPlayer。MediaPlayer 走的是媒体流
 * （USAGE_MEDIA），系统的回声消除拿不到它当参考信号——免提一边念一边听时，
 * 麦克风收进来的就是大肥鱼自己的声音，转写回去就成了自我循环打断。
 * 这里改走 [AudioTrack] + USAGE_VOICE_COMMUNICATION：与 [DuplexListener] 的
 * VOICE_COMMUNICATION 收音同属一条通话链路（MODE_IN_COMMUNICATION），HAL 才有
 * 可对消的远端参考信号。副作用：音量档跟着"通话音量"走，不是媒体音量。
 *
 * 打断语义不变：[stop] 即断（对讲按住 🎤 的第一动作 / 免提插话）。
 */
object AudioPlayer {

    /** 播放代际：stop()/新播都推进它，旧写线程看到不匹配就自己收口（不碰共享状态）。 */
    @Volatile private var gen = 0
    @Volatile private var playing = false
    private var track: AudioTrack? = null

    /**
     * 播一段 WAV（PCM16）。返回 false = 这台设备/这份音频播不了（非 PCM16、采样率不支持），
     * 调用方如实报错——不做"换个格式再试"的猜测，猜出来的通常是电流声。
     */
    fun play(context: Context, wav: ByteArray): Boolean {
        val pcm = Wav.parse(wav) ?: run {
            Rlog.log("play refused: not PCM16 wav (${wav.size}B)")
            return false
        }
        stop()
        val g = ++gen
        val mask = if (pcm.channels == 2) AudioFormat.CHANNEL_OUT_STEREO
        else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = try {
            AudioTrack.getMinBufferSize(pcm.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        } catch (e: Exception) {
            Rlog.log("getMinBufferSize failed: ${e.message}")
            0
        }
        if (minBuf <= 0) {
            Rlog.log("play refused: ${pcm.sampleRate}Hz/${pcm.channels}ch 不支持")
            return false
        }
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.sampleRate)
                    .setChannelMask(mask).build())
                .setBufferSizeInBytes(maxOf(minBuf, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Rlog.log("AudioTrack build failed: ${e.message}")
            return false
        }
        track = t
        playing = true
        Rlog.log("play: ${pcm.sampleRate}Hz ${pcm.channels}ch ${pcm.durationMs}ms")
        Thread {
            val step = 4096 - (4096 % (2 * pcm.channels))
            var off = 0
            try {
                t.play()
                while (off < pcm.bytes.size && gen == g) {
                    val w = t.write(pcm.bytes, off, minOf(step, pcm.bytes.size - off),
                        AudioTrack.WRITE_BLOCKING)
                    if (w <= 0) break
                    off += w
                }
                // 写完 ≠ 播完：等播放头追上总帧数，否则尾音被 stop() 吃掉
                if (gen == g) {
                    val totalFrames = pcm.bytes.size / (2 * pcm.channels).toLong()
                    val deadline = System.currentTimeMillis() + pcm.durationMs + 1500
                    while (gen == g && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < totalFrames
                        && System.currentTimeMillis() < deadline
                    ) Thread.sleep(15)
                }
            } catch (e: Exception) {
                Rlog.log("play failed: ${e.message}")
            } finally {
                runCatching { t.stop() }
                runCatching { t.release() }
                if (gen == g) { playing = false; track = null }
            }
        }.start()
        return true
    }

    /** 立即断播（打断 = 推进代际 + 唤醒阻塞中的 write；release 由写线程收尾）。 */
    fun stop() {
        gen++
        playing = false
        runCatching { track?.stop() }
        track = null
    }

    fun isPlaying(): Boolean = playing

    /** 观测口：Rlog 里能看出「这一秒扬声器是否真在出声」——插话判定的物证。 */
    fun state(): String = if (playing) "playing" else "idle"
}
