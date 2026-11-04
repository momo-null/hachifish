package com.hachimi.app

/**
 * WAV 读写（纯字节逻辑，无 Android 依赖 → JVM 单测覆盖）。
 *
 * 存在的理由：播报从 MediaPlayer 改成 AudioTrack（[AudioPlayer]）后，必须自己把
 * 供应商 TTS 回来的 WAV 拆成 PCM 帧——同时免提收音侧要能把 PCM 拼成 WAV 上传 STT。
 * 两头共用一份头格式，避免"上传的 WAV 和下载的 WAV 是两套实现"这种漂移。
 */
data class Pcm16(val channels: Int, val sampleRate: Int, val bytes: ByteArray) {
    /** 播放总时长（ms）——AudioTrack 排空等待的上限依据。 */
    val durationMs: Long get() = bytes.size.toLong() * 1000 / (2L * channels * sampleRate)
}

object Wav {

    /** PCM16 小端字节流（对讲 / 免提分段共用：16k 单声道上传）。 */
    fun shortsToBytes(frames: List<ShortArray>): ByteArray {
        val out = ByteArray(frames.sumOf { it.size } * 2)
        var off = 0
        for (f in frames) for (v in f) {
            out[off++] = (v.toInt() and 0xFF).toByte()
            out[off++] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** PCM16 字节流 → 带 RIFF 头的 WAV（44 字节标准头）。 */
    fun fromPcm(pcm: ByteArray, rate: Int): ByteArray {
        val header = ByteArray(44)
        fun le32(off: Int, v: Int) {
            for (i in 0..3) header[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
        fun le16(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte(); header[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        "RIFF".toByteArray().copyInto(header, 0)
        le32(4, 36 + pcm.size)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        // fmt chunk size 是 4 字节 u32（2026-11-04 实测修正：曾写成 le16 → 整头错位
        // 2 字节，data 标记落 34，libsndfile 报 "No 'data' chunk marker"）
        le32(16, 16)
        le16(20, 1)      // PCM
        le16(22, 1)      // mono
        le32(24, rate)
        le32(28, rate * 2)
        le16(32, 2)
        le16(34, 16)
        "data".toByteArray().copyInto(header, 36)
        le32(40, pcm.size)
        return header + pcm
    }

    /**
     * WAV → PCM16（只认 PCM16；float/8bit/ADPCM 一律返 null，调用方如实报错，
     * 不做"猜格式"——猜错的结果是电流声而不是报错）。
     * 分块遍历（LIST/iXML 等扩展块在 fmt 前后都可能出现，写死偏移会读飞）。
     */
    fun parse(wav: ByteArray): Pcm16? {
        if (wav.size < 12) return null
        if (str(wav, 0) != "RIFF" || str(wav, 8) != "WAVE") return null
        var ch = 0
        var rate = 0
        var bits = 0
        var fmt = 0
        var i = 12
        var dataOff = -1
        var dataLen = 0
        while (i + 8 <= wav.size) {
            val id = str(wav, i)
            val sz = le32(wav, i + 4)
            val body = i + 8
            if (sz < 0 || body > wav.size) break
            when (id) {
                "fmt " -> if (body + 16 <= wav.size) {
                    fmt = le16(wav, body)
                    ch = le16(wav, body + 2)
                    rate = le32(wav, body + 4)
                    bits = le16(wav, body + 14)
                }
                "data" -> { dataOff = body; dataLen = minOf(sz, wav.size - body) }
            }
            i = body + sz + (sz and 1)   // 奇数长度块后有 1 字节填充
        }
        if (dataOff < 0 || ch <= 0 || rate <= 0) return null
        if (fmt != 1 || bits != 16) return null
        // 只取整帧（尾部半帧丢弃，否则声道会错位）
        val frameBytes = 2 * ch
        val usable = dataLen - (dataLen % frameBytes)
        if (usable <= 0) return null
        return Pcm16(ch, rate, wav.copyOfRange(dataOff, dataOff + usable))
    }

    private fun str(b: ByteArray, off: Int): String =
        if (off + 4 > b.size) "" else String(b, off, 4, Charsets.ISO_8859_1)

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}
