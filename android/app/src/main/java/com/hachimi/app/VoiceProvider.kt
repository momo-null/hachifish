package com.hachimi.app

import android.content.Context

/**
 * M2.5b 语音供应商（OpenAI 音频兼容通用接入）：多槽位可切换——相当于在线语音
 * 模型，买云服务或指向自建引擎服务（PC 的 plugins/voice/engine_service 同形状）
 * 都走这一种接法。**它是语音的唯一来源**（2026-11-04 改）：没配 = 对讲/免提在入口
 * 直接报错并指向这里，不再静默回退端侧系统 ASR/TTS——"语音用的是什么"必须只有一个答案。
 */
object VoiceProviderStore {
    data class Provider(
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val sttModel: String,
        val ttsModel: String,
        val voice: String,
    ) {
        val readyStt: Boolean get() = baseUrl.startsWith("http") && sttModel.isNotBlank()
        val readyTts: Boolean get() = baseUrl.startsWith("http") && ttsModel.isNotBlank()
    }

    private fun decode(s: String): List<Provider> = runCatching {
        val arr = org.json.JSONArray(s)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Provider(
                name = o.optString("name"), baseUrl = o.optString("base_url"),
                apiKey = o.optString("api_key"), sttModel = o.optString("stt_model"),
                ttsModel = o.optString("tts_model"), voice = o.optString("voice"))
        }
    }.getOrDefault(emptyList())

    fun list(context: Context): List<Provider> = decode(
        context.getSharedPreferences("hachimi_ui", Context.MODE_PRIVATE)
            .getString("voice_providers", "[]") ?: "[]")

    fun active(context: Context): Provider? {
        val all = list(context)
        if (all.isEmpty()) return null
        val idx = context.getSharedPreferences("hachimi_ui", Context.MODE_PRIVATE)
            .getInt("voice_active", 0)
        return all.getOrNull(idx) ?: all.first()
    }

    fun save(context: Context, list: List<Provider>, activeIndex: Int) {
        val arr = org.json.JSONArray()
        list.forEach { p ->
            arr.put(org.json.JSONObject()
                .put("name", p.name).put("base_url", p.baseUrl).put("api_key", p.apiKey)
                .put("stt_model", p.sttModel).put("tts_model", p.ttsModel).put("voice", p.voice))
        }
        context.getSharedPreferences("hachimi_ui", Context.MODE_PRIVATE).edit()
            .putString("voice_providers", arr.toString())
            .putInt("voice_active", activeIndex.coerceIn(0, maxOf(0, list.size - 1)))
            .apply()
    }

    /**
     * 语音入口前置检查（2026-11-04 严格化）：按形态要的能力查配置，缺什么就回一句
     * 指向 设置 的人话（null = 齐活）。🎤 对讲与免提都要识别+播报——免提是语音自由
     * 对话、会念回复，标准和对讲一样，没有"只要能听不要能说"的中间档。
     */
    fun missing(context: Context, needStt: Boolean, needTts: Boolean): String? {
        val p = active(context)
        if (p == null) return "还没配语音供应商 → 设置 · 语音供应商（新增并启用后才能用语音）"
        val lack = mutableListOf<String>()
        if (needStt && !p.readyStt) lack.add("识别（base_url + stt_model）")
        if (needTts && !p.readyTts) lack.add("播报（base_url + tts_model）")
        return if (lack.isEmpty()) null
        else "供应商「${p.name}」缺${lack.joinToString("、")} → 设置 · 语音供应商 补齐"
    }
}

/**
 * 供应商 TTS 的结局（严格化后调用方必须能区分「没配」与「没成」——
 * 一个指向设置，一个指向网络/引擎，混成 null 就只能报一句含糊的"播报失败"）。
 */
sealed class TtsResult {
    /** 拿到音频字节（调用方交 [AudioPlayer] 播）。 */
    class Audio(val wav: ByteArray) : TtsResult()

    /** 设置里没配供应商 / 没填 tts_model——入口拦之外的兜底（配置中途被改）。 */
    object NoProvider : TtsResult()

    /** 配了但这次没成（网络/HTTP/引擎侧报错），message 是透传的人话。 */
    class Failed(val message: String) : TtsResult()
}

/**
 * 供应商 STT 的一次转写（对讲与免提共用，R10：一份实现）。
 * 成功返回文本（可能空串 = 没听出字）；失败抛 RuntimeException，message 是给用户的
 * 人话（HTTP 码 + 引擎侧正文透传——ROM 丢 logcat，异常文本是唯一的诊断面）。
 *
 * @param onConn 把在途连接交给调用方保管（destroy = 断开）：看门狗/退出时若不断开，
 * 引擎懒加载 + 60s 读超时会让多代请求并发到服务端（2026-11-04 实测 503 三连）。
 */
object ProviderStt {
    fun transcribe(
        provider: VoiceProviderStore.Provider,
        wav: ByteArray,
        onConn: (java.net.HttpURLConnection) -> Unit = {},
    ): String {
        val boundary = "----hachifivoice" + java.util.UUID.randomUUID().toString().replace("-", "")
        val parts = mutableListOf<ByteArray>()
        parts.add(("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n" +
            provider.sttModel + "\r\n").toByteArray())
        parts.add(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; " +
            "filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n").toByteArray()
            + wav + "\r\n".toByteArray())
        parts.add("--$boundary--\r\n".toByteArray())
        val req = java.net.URL(provider.baseUrl.trimEnd('/') + "/v1/audio/transcriptions")
            .openConnection() as java.net.HttpURLConnection
        req.requestMethod = "POST"
        req.doOutput = true
        req.connectTimeout = 10000
        req.readTimeout = 60000
        req.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        if (provider.apiKey.isNotBlank()) {
            req.setRequestProperty("Authorization", "Bearer " + provider.apiKey)
        }
        onConn(req)
        // 组包后必须真正写出请求体（2026-11-04 实测：只 build 不 write = 空体
        // POST，FastAPI 422 Unprocessable Entity——首次端到端实测抓出）
        req.outputStream.use { it.write(parts.reduce { a, b -> a + b }) }
        val code = req.responseCode
        if (code !in 200..299) {
            val err = runCatching { req.errorStream?.bufferedReader()?.readText() }.getOrNull()
                .orEmpty()
            throw RuntimeException("HTTP $code ${err.take(300)}")
        }
        val resp = req.inputStream.bufferedReader().use { it.readText() }
        return org.json.JSONObject(resp).optString("text").trim()
    }
}

/** 供应商 TTS：文本 → /v1/audio/speech → [TtsResult]（语音播报的唯一实现；
 *  没配/没成都如实回报，调用方不再有端侧回退可躲）。 */
fun voiceProviderTts(context: Context, text: String): TtsResult {
    val p = VoiceProviderStore.active(context)
    if (p == null || !p.readyTts) return TtsResult.NoProvider
    return runCatching {
        val req = java.net.URL(p.baseUrl.trimEnd('/') + "/v1/audio/speech")
            .openConnection() as java.net.HttpURLConnection
        req.requestMethod = "POST"
        req.doOutput = true
        req.connectTimeout = 10000
        req.readTimeout = 60000
        req.setRequestProperty("Content-Type", "application/json")
        if (p.apiKey.isNotBlank()) req.setRequestProperty("Authorization", "Bearer " + p.apiKey)
        req.outputStream.use {
            it.write(org.json.JSONObject()
                .put("model", p.ttsModel).put("input", text)
                .put("voice", p.voice.ifBlank { "alloy" })
                .put("response_format", "wav").toString().toByteArray())
        }
        val code = req.responseCode
        if (code !in 200..299) {
            // 错误正文透传（引擎侧的"模型缺失/显存不足"等人话都在 body 里；
            // ROM 丢 logcat，Rlog 是唯一排障通道）
            val err = runCatching { req.errorStream?.bufferedReader()?.readText() }.getOrNull()
                .orEmpty()
            throw RuntimeException("HTTP $code ${err.take(300)}")
        }
        val bytes = req.inputStream.use { it.readBytes() }
        if (bytes.size < 100) throw RuntimeException("返回音频过短（${bytes.size}B）")
        // 成功也留痕（不是啰嗦，是"打字轮不该有这行"的唯一反证通道）
        Rlog.log("tts ok: ${bytes.size}B")
        TtsResult.Audio(bytes)
    }.getOrElse {
        Rlog.log("tts failed: ${it.message ?: "null"}")
        TtsResult.Failed(it.message ?: "请求异常")
    }
}
