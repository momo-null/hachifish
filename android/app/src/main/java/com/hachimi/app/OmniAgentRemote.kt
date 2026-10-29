package com.hachimi.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * OmniAgent 远程客户端（doc/plans/omniagent_remote_client_plan.md C1-C3，Tier 2 遥控模式）。
 *
 * 契约 = 上游 `doc/plans/remote-client-contract-2026-10-30.md` 的线上实现（已对齐
 * `backend/api/routers/chat_runtime.py`，非文档臆测）：
 * - POST /api/runtime/chat   body `{messages:[{role,content}], task_id?, force?}`
 *   → `{ok, task_id, mode, persona}`；force=true = 同 task barge-in（打断运行轮并
 *   立即派发新消息，上游半句标 [被打断]）；409 = 已有任务运行且不可 force。
 * - GET  /api/runtime/stream?task_id=&sub=  SSE（`event: <name>\ndata: <json>`）。
 *   手机关注：message（增量口播块）/ chat（最终结论，殿后）/ status（running=false =
 *   轮结束）。`sub` 旁路订阅（多播，与 Web 主订阅互不抢事件）。
 * - POST /api/runtime/stop   body `{task_id?}`。
 * - GET  /health             探活（1s 超时，Tier 2↔Tier 1 切换依据，PC-0 豁免鉴权）。
 *
 * 零第三方依赖（HttpURLConnection + org.json）——android/ 依赖走评审惯例，C1 不新增。
 */
object OmniAgentRemote {

    private const val TAG = "HachimiRemote"

    data class Config(val baseUrl: String, val token: String) {
        val ready: Boolean get() = baseUrl.startsWith("http")
    }

    /** 一条 SSE 事件（event 名 + 已解析 JSON data）。 */
    data class SseEvent(val event: String, val data: JSONObject)

    /** 一条 SSE 事件（原始形态：event 名 + data 字符串；纯 JVM，零 Android 依赖）。 */
    data class RawSseEvent(val event: String, val data: String)

    /**
     * SSE 行解析（纯函数，JVM 可测）：按行喂入，凑齐 event/data 对即产出事件。
     * 容忍 CRLF；空行 = 事件边界；`:` 开头 = 注释/keep-alive，忽略。
     * 输出 raw 字符串（org.json 不入此层，ChatModels 同款约定）——JSON 解析在
     * [stream] 运行时做。
     */
    class SseParser {
        private var event = ""
        private var data = StringBuilder()

        fun feed(rawLine: String): RawSseEvent? {
            val line = rawLine.trimEnd('\r')
            return when {
                line.isEmpty() -> {
                    if (event.isNotEmpty() || data.isNotEmpty()) {
                        val e = RawSseEvent(event.ifEmpty { "message" }, data.toString())
                        event = ""
                        data = StringBuilder()
                        e
                    } else null
                }
                line.startsWith(":") -> null
                line.startsWith("event:") -> { event = line.removePrefix("event:").trim(); null }
                line.startsWith("data:") -> { data.append(line.removePrefix("data:").trim()); null }
                else -> null
            }
        }
    }

    /** 探活（Tier 2↔Tier 1 切换依据）。任何异常（含 401/超时）= 不可达。 */
    fun health(baseUrl: String, token: String, timeoutMs: Int = 1000): Boolean = try {
        val c = conn("$baseUrl/health", token, timeoutMs)
        val ok = c.responseCode in 200..299
        c.disconnect()
        ok
    } catch (_: Exception) {
        false
    }

    /**
     * 发起对话轮 → task_id。force=true = 同 task barge-in（上游打断运行轮、半句标
     * [被打断]、立即派发新消息）；409（他任务运行/打断超时）抛 RuntimeException。
     */
    fun chat(baseUrl: String, token: String, text: String,
             taskId: String?, force: Boolean = false): String {
        val body = JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
        taskId?.takeIf { it.isNotBlank() }?.let { body.put("task_id", it) }
        if (force) body.put("force", true)
        val c = conn("$baseUrl/api/runtime/chat", token, 15000)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val resp = (c.inputStream.bufferedReader().use { it.readText() })
        c.disconnect()
        val o = JSONObject(resp)
        if (!o.optBoolean("ok")) throw RuntimeException(o.optString("error", "chat 被拒绝"))
        return o.optString("task_id")
    }

    /** 停止运行轮（task_id 缺省 = 上游回退当前运行体）。 */
    fun stop(baseUrl: String, token: String, taskId: String?) {
        val c = conn("$baseUrl/api/runtime/stop", token, 10000)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use {
            it.write(JSONObject().put("task_id", taskId ?: "").toString().toByteArray(Charsets.UTF_8))
        }
        c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
    }

    /**
     * 订阅 SSE 流（阻塞，调用方放后台线程）。[onEvent] 返回 false = 调用方要求
     * 停止读取（轮结束/页面销毁）。
     */
    fun stream(baseUrl: String, token: String, taskId: String, sub: String,
               onEvent: (SseEvent) -> Boolean) {
        val c = conn("$baseUrl/api/runtime/stream?task_id=$taskId&sub=$sub", token, 0)
        try {
            val parser = SseParser()
            c.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (ln in lines) {
                    val raw = parser.feed(ln) ?: continue
                    val json = runCatching { JSONObject(raw.data) }.getOrElse { JSONObject() }
                    if (!onEvent(SseEvent(raw.event, json))) return
                }
            }
        } catch (_: Exception) {
            // 断流（网络抖动/服务重启）：调用方经 status 状态机与下轮探活自愈（C3）
        } finally {
            c.disconnect()
        }
    }

    private fun conn(url: String, token: String, timeoutMs: Int): HttpURLConnection {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs.coerceAtLeast(1000)
            readTimeout = if (timeoutMs <= 0) 0 else timeoutMs
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "text/event-stream, application/json")
        }
        return c
    }
}
