package com.hachimi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免提全双工的三块纯逻辑（WAV 拆装 / VAD 分段 / 余 echo 文本判定）。
 *
 * 为什么单测而不是只靠真机：这三块的失败模式在真机上表现为"它自己跟自己聊天"
 * 或"我说前半句被吃了"，现场既难复现也难判定；而它们全是确定性函数——
 * 喂进能量序列/字节流就能断言。红线 R12：全双工在设备上的实际消音效果仍需真机
 * 留证（Rlog），这里覆盖的是**不依赖设备的判定逻辑**。
 */
class VoiceDuplexTest {

    // ---------------- Wav ----------------

    @Test
    fun `pcm 拼头再拆回原样`() {
        val pcm = ByteArray(3200) { (it % 7).toByte() }
        val back = Wav.parse(Wav.fromPcm(pcm, 16000))
        assertNotNull(back)
        assertEquals(1, back!!.channels)
        assertEquals(16000, back.sampleRate)
        assertEquals(pcm.toList(), back.bytes.toList())
        assertEquals(100L, back.durationMs)   // 3200B / (2×16000) = 100ms
    }

    @Test
    fun `分块遍历 - fmt 与 data 之间有扩展块也找得到`() {
        val pcm = ByteArray(640) { 1 }
        val head = Wav.fromPcm(pcm, 24000)
        // 在 fmt 与 data 之间插一个 iXML 扩展块（有些 TTS 引擎会塞元数据）：
        // 写死偏移的解析器会从 36 起读错位，按块走的不会
        val extra = "iXML".toByteArray() + intLE(8) + ByteArray(8)
        val withExtra = head.copyOfRange(0, 36) + extra + head.copyOfRange(36, head.size)
        val back = Wav.parse(withExtra)
        assertNotNull(back)
        assertEquals(24000, back!!.sampleRate)
        assertEquals(1, back.channels)          // fromPcm 恒单声道
        assertEquals(640, back.bytes.size)
    }

    @Test
    fun `非 PCM16 一律拒 - 不猜格式`() {
        val pcm = ByteArray(200)
        // float32（fmt=3）
        val f = Wav.fromPcm(pcm, 16000)
        f[20] = 3
        assertNull(Wav.parse(f))
        // 8bit
        val g = Wav.fromPcm(pcm, 16000)
        g[34] = 8
        assertNull(Wav.parse(g))
        // 垃圾
        assertNull(Wav.parse(ByteArray(50)))
    }

    @Test
    fun `尾部半帧丢弃 - 立体声不错位`() {
        val head = ByteArray(44)
        "RIFF".toByteArray().copyInto(head, 0)
        "WAVE".toByteArray().copyInto(head, 8)
        "fmt ".toByteArray().copyInto(head, 12)
        intLE(16).copyInto(head, 16)
        head[20] = 1                            // PCM
        head[22] = 2                            // 2ch
        intLE(48000).copyInto(head, 24)
        head[32] = 4                            // blockAlign
        head[34] = 16                           // bits
        "data".toByteArray().copyInto(head, 36)
        intLE(4001).copyInto(head, 40)          // 故意奇数字节
        val back = Wav.parse(head + ByteArray(4001))
        assertNotNull(back)
        assertEquals(2, back!!.channels)
        assertEquals(48000, back.sampleRate)
        assertEquals(4000, back.bytes.size)     // 对齐整帧
    }

    // ---------------- Segmenter ----------------

    /** 连续喂 n 帧指定能量的帧；nowMs 按 frameMs 递增（单调时钟语义）。 */
    private fun feedAll(seg: Segmenter, n: Int, rms: Double, nowMs: Long,
                        playing: Boolean = false, aec: Boolean = true): List<SegEvent> {
        val out = mutableListOf<SegEvent>()
        for (i in 0 until n) {
            out.add(seg.feed(rms, nowMs + (i + 1) * 10L, ShortArray(160), playing, aec))
        }
        return out
    }

    @Test
    fun `安静不出句`() {
        val seg = Segmenter()
        val evs = feedAll(seg, 300, 0.001, 0L)
        assertTrue(evs.none { it is SegEvent.Endpoint })
        assertTrue(evs.none { it is SegEvent.SpeechStart })
        assertFalse(seg.inUtterance())
    }

    @Test
    fun `说完挂起静音即收口 - 尾静音被裁掉`() {
        val seg = Segmenter()
        // 300ms 说话（30 帧）+ 1000ms 静音（挂起窗 700ms → 第 70 帧收口）
        val evs = feedAll(seg, 30, 0.05, 0L) + feedAll(seg, 100, 0.001, 300L)
        val ep = evs.filterIsInstance<SegEvent.Endpoint>().single()
        assertFalse(ep.startedWhilePlaying)
        // 42 帧 = 起句已收的 30 帧 + 尾部保留 12 帧（120ms 兜底），700ms 挂窗不上传
        assertEquals(420L, ep.ms)
        assertEquals(ep.ms * 32, ep.pcm.size.toLong())   // 16k 单声道 PCM16：1ms = 32B
    }

    @Test
    fun `咳嗽不是句话 - 按浊音时长判`() {
        val seg = Segmenter()
        val evs = feedAll(seg, 5, 0.05, 0L) + feedAll(seg, 100, 0.001, 50L)
        assertTrue(evs.none { it is SegEvent.Endpoint })
        assertEquals(50L, evs.filterIsInstance<SegEvent.Rejected>().single().ms)
    }

    @Test
    fun `一直说也要切段 - 硬上限`() {
        val seg = Segmenter()
        val evs = feedAll(seg, 2000, 0.05, 0L)     // 20s 不停
        val ep = evs.filterIsInstance<SegEvent.Endpoint>().single()
        assertEquals(15000L, ep.ms)                // 15s 硬上限切一刀
    }

    @Test
    fun `没有 AEC 时播报期间整段不听`() {
        val seg = Segmenter()
        val evs = feedAll(seg, 40, 0.05, 0L, playing = true, aec = false)
        assertTrue(evs.none { it is SegEvent.SpeechStart })
        assertTrue(evs.none { it is SegEvent.Endpoint })
        assertFalse(seg.inUtterance())
    }

    @Test
    fun `有 AEC 时播报期间持续过线 = 插话`() {
        val seg = Segmenter()
        val evs = feedAll(seg, 40, 0.05, 0L, playing = true, aec = true)
        // 插话是一次性的（冷却期内不重复轰炸调用方）
        assertEquals(1, evs.filterIsInstance<SegEvent.BargeIn>().size)
        // 插话后句子继续收（这 400ms 是用户正在说的话，不能丢）
        assertTrue(seg.inUtterance())
        val tail = feedAll(seg, 100, 0.001, 400L, playing = false, aec = true)
        assertEquals(1, tail.filterIsInstance<SegEvent.Endpoint>().size)
    }

    @Test
    fun `小声余音不过插话线 - 播报期间不自己打断自己`() {
        val seg = Segmenter()
        // 0.02：高于起句地板 0.012（算"有声音"），低于插话地板 0.035（不算"人在插话"）
        val evs = feedAll(seg, 60, 0.02, 0L, playing = true, aec = true)
        assertTrue("余 echo 不该触发插话", evs.none { it is SegEvent.BargeIn })
    }

    // ---------------- EchoGuard ----------------

    @Test
    fun `自己念的话被转写回来要判掉`() {
        EchoGuard.clear()
        EchoGuard.note("好的，我已经帮你把闹钟定在七点半啦", nowMs = 1000)
        assertTrue(EchoGuard.looksLikeEcho("我已经帮你把闹钟定在七点半", nowMs = 2000))
        // STT 丢字/加字的近似回声（二元组重合率判，不用编辑距离）
        assertTrue(EchoGuard.looksLikeEcho("好的我已经帮你定在七点半了", nowMs = 3000))
    }

    @Test
    fun `正常插话不误杀`() {
        EchoGuard.clear()
        EchoGuard.note("好的，我已经帮你把闹钟定在七点半啦", nowMs = 1000)
        assertFalse(EchoGuard.looksLikeEcho("再定一个八点的", nowMs = 2000))
        assertFalse(EchoGuard.looksLikeEcho("今天天气怎么样", nowMs = 2000))
    }

    @Test
    fun `窗口外与没念过都不判回声`() {
        EchoGuard.clear()
        assertFalse(EchoGuard.looksLikeEcho("随便一句话", nowMs = 5000))
        EchoGuard.note("随便一句话再说一遍", nowMs = 0)
        // 30s 后：这句早就是用户新说的了
        assertFalse(EchoGuard.looksLikeEcho("随便一句话再说一遍", nowMs = 30_000))
        // 太短的碎片不动用回声判定（宁放不误杀）
        EchoGuard.note("你好呀大肥鱼", nowMs = 0)
        assertFalse(EchoGuard.looksLikeEcho("你好", nowMs = 1000))
    }

    private fun intLE(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
}
