package com.hachimi.app

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 文件日志（ROM 日志过滤兜底）：Oplus 等 ROM 会整档丢弃第三方 app 的 logcat 输出
 * （实测：app 内 Log.i 连内核启动行都不落 logcat），线上排障改为落 App 私有文件，
 * adb `run-as com.hachimi.app cat files/chat_remote.log` 可读。有界：超过 256KB 整档重来。
 */
object Rlog {
    @Volatile private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @JvmStatic fun init(dir: File) {
        try {
            val f = File(dir, "chat_remote.log")
            if (f.exists() && f.length() > 262_144) f.delete()
            file = f
        } catch (_: Exception) {}
    }

    @JvmStatic fun log(msg: String) {
        try {
            file?.appendText(fmt.format(Date()) + " " + msg + "\n")
        } catch (_: Exception) {}
    }
}

/**
 * OmniAgent 远程客户端（doc/plans/omniagent_remote_client_plan.md C1-C3，Tier 2 遥控模式）。
 *
 * 契约 = 上游 `doc/plans/remote-client-contract-2026-10-30.md` 的线上实现（已对齐
 * `backend/api/routers/chat_runtime.py`，非文档臆测）：
 * - POST /api/runtime/chat   body `{messages:[{role,content}], task_id?, force?,
 *   max_steps?, model?, mode?}`；mode = chat/task/auto（与 PC 同一取向开关）
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
             taskId: String?, force: Boolean = false, maxSteps: Int = 0,
             model: String? = null, mode: String? = null): String {
        val body = JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
        taskId?.takeIf { it.isNotBlank() }?.let { body.put("task_id", it) }
        if (force) body.put("force", true)
        // 步数预算：内核按它跑 agent 循环；壳同时用它做悬浮球进度环的分母（total）
        if (maxSteps > 0) body.put("max_steps", maxSteps)
        // 会话级模型选择（模型路由 "<provider_id>/<model_id>"；空 = 跟随目录默认）
        model?.takeIf { it.isNotBlank() }?.let { body.put("model", it) }
        // 对话取向（与 PC 同一开关）：chat=对话纪律（不干活、无深度思考）/
        // task=工作 lane（工具循环 + 深度思考卡）。缺省不传 = 内核 auto。
        mode?.takeIf { it == "chat" || it == "task" || it == "auto" }?.let { body.put("mode", it) }
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

    /** 内核审批决议（SSE approval 卡的人工应答；remember=true = 内核做任务级记忆）。 */
    fun decideApproval(baseUrl: String, token: String, approvalId: String,
                       approve: Boolean, remember: Boolean) {
        val body = JSONObject()
            .put("action", if (approve) "approve" else "deny")
            .put("remember", remember)
        val c = conn("$baseUrl/api/runtime/approvals/" +
                java.net.URLEncoder.encode(approvalId, "UTF-8") + "/decision", token, 10000)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
    }

    /** 读写内核 settings（deep-merge 语义：patch 只带要改的子树）。 */
    fun settingsPut(baseUrl: String, token: String, patch: JSONObject): JSONObject {
        val c = conn("$baseUrl/api/settings", token, 10000)
        c.requestMethod = "PUT"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(patch.toString().toByteArray(Charsets.UTF_8)) }
        val resp = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return runCatching { JSONObject(resp) }.getOrElse { JSONObject() }
    }

    fun settingsGet(baseUrl: String, token: String): JSONObject {
        val c = conn("$baseUrl/api/settings", token, 10000)
        val resp = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return runCatching { JSONObject(resp) }.getOrElse { JSONObject() }
    }

    /** 通用 GET（环回内核数据面：任务列表/详情/记忆/项目等，Web 同源端点）。 */
    fun getJson(baseUrl: String, token: String, path: String, timeoutMs: Int = 12000): JSONObject {
        val c = conn("$baseUrl$path", token, timeoutMs)
        val resp = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return runCatching { JSONObject(resp) }.getOrElse { JSONObject() }
    }

    /** 通用 POST JSON。 */
    fun postJson(baseUrl: String, token: String, path: String,
                 body: JSONObject, timeoutMs: Int = 10000): JSONObject {
        val c = conn("$baseUrl$path", token, timeoutMs)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val resp = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return runCatching { JSONObject(resp) }.getOrElse { JSONObject() }
    }

    /** 通用 PUT JSON（character/instructions 等写入口）。 */
    fun putJson(baseUrl: String, token: String, path: String,
                body: JSONObject, timeoutMs: Int = 12000): JSONObject {
        val c = conn("$baseUrl$path", token, timeoutMs)
        c.requestMethod = "PUT"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val resp = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return runCatching { JSONObject(resp) }.getOrElse { JSONObject() }
    }

    /** 通用 PATCH JSON（模型目录槽位默认等局部写入口）。 */
    fun patchJson(baseUrl: String, token: String, path: String,
                  body: JSONObject, timeoutMs: Int = 12000): JSONObject {
        val c = conn("$baseUrl$path", token, timeoutMs)
        c.requestMethod = "PATCH"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val resp = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return runCatching { JSONObject(resp) }.getOrElse { JSONObject() }
    }

    /**
     * 通用一次请求（技能/工具/MCP 子页共用）：**非 2xx 也读回响应体**。
     *
     * 为什么另开一个而不复用 [getJson]/[postJson]：那两个只读 `inputStream`，而
     * FastAPI 的错误原因（409「已有任务在运行」/400「未知插件」/422 校验失败）都在
     * `errorStream` 里—— HttpURLConnection 在这种时候直接抛 IOException，调用方只能
     * 拿到空 JSON，用户看到「失败」却不知道为什么。这里把状态码与 error 字段一起回，
     * 页面得以诚实呈现（GET/POST/PUT/PATCH/DELETE 同一入口）。
     *
     * @return 响应 JSON（解析失败 = 空对象）；额外注入 ``_status``（HTTP 码，传输层
     *         异常 = 0）与 ``_ok``（2xx）。
     */
    fun requestJson(method: String, baseUrl: String, path: String,
                    body: JSONObject?, timeoutMs: Int = 12000): JSONObject {
        var c: HttpURLConnection? = null
        val out = JSONObject()
        try {
            c = conn("$baseUrl$path", "", timeoutMs)
            if (body != null || method == "POST" || method == "PUT" || method == "PATCH") {
                c.requestMethod = method
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use {
                    it.write((body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8))
                }
            } else {
                c.requestMethod = method
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val parsed = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            for (k in parsed.keys()) out.put(k, parsed.get(k))
            if (!out.has("error") && code !in 200..299) {
                val brief = text.take(160).replace("\n", " ")
                out.put("error", if (brief.isBlank()) "HTTP $code" else brief)
            }
            out.put("_status", code)
            out.put("_ok", code in 200..299)
        } catch (e: Exception) {
            out.put("error", "${e.javaClass.simpleName}: ${e.message ?: ""}")
            out.put("_status", 0)
            out.put("_ok", false)
        } finally {
            c?.disconnect()
        }
        Rlog.log("req $method $path -> ${out.optInt("_status")} " +
                (if (out.optBoolean("_ok")) "ok" else out.optString("error").take(80)))
        return out
    }

    /**
     * 任务执行历史（Web 同源 /api/runtime/tasks/{id}/history，Phase 2：mini_loop
     * 旧库 task_trace_json 的替代——完整内核任务在旧库无数据，详情/回放全走这里）。
     * 返回 {task_id, project_id, session_id, messages:[...]}；assistant 消息带
     * extra.steps[]（thinking/tool_call/message 全量）+ extra.meta{success,steps}。
     */
    fun kernelTaskHistory(baseUrl: String, taskId: String): JSONObject =
        getJson(baseUrl, "", "/api/runtime/tasks/" +
                java.net.URLEncoder.encode(taskId, "UTF-8") + "/history", 15000)

    /** 删除任务及其资产（Web 同源 DELETE /api/runtime/tasks/{id}）。 */
    fun deleteTask(baseUrl: String, taskId: String): JSONObject {
        val c = conn("$baseUrl/api/runtime/tasks/" +
                java.net.URLEncoder.encode(taskId, "UTF-8"), "", 10000)
        c.requestMethod = "DELETE"
        val resp = runCatching { c.inputStream.bufferedReader().use { it.readText() } }
            .getOrElse { "{\"ok\":false,\"error\":\"${it.message}\"}" }
        c.disconnect()
        return runCatching { JSONObject(resp) }.getOrElse { JSONObject() }
    }

    /**
     * 环回内核任务列表（Web 同源 /api/runtime/tasks）+ 项目名映射归一化——
     * 会话面板/任务页共用（Phase 2：mini_loop 旧库 list_tasks_json 的替代）。
     * 归一化字段：task_id/objective/state/success/created_at/project/steps/wall_s。
     */
    fun kernelTasks(baseUrl: String, limit: Int = 12): org.json.JSONArray {
        val tasks = getJson(baseUrl, "", "/api/runtime/tasks", 12000)
            .optJSONArray("tasks") ?: return org.json.JSONArray()
        val names = HashMap<String, String>()
        runCatching {
            val pm = getJson(baseUrl, "", "/api/runtime/projects/meta", 8000)
            pm.optJSONArray("projects")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    names[p.optString("project_id")] =
                        p.optString("display_name").ifEmpty { p.optString("project_id") }
                }
            }
        }
        val out = org.json.JSONArray()
        for (i in 0 until minOf(tasks.length(), limit)) {
            val t = tasks.optJSONObject(i) ?: continue
            val st = t.optString("state")
            val success = t.optBoolean("success")
            val norm = JSONObject()
                .put("task_id", t.optString("task_id"))
                .put("objective", t.optString("objective"))
                .put("done_when", t.optString("done_when"))
                .put("state", when {
                    success -> "done"
                    success == false && st != "pending" -> "failed"
                    else -> st
                })
                .put("success", success)
                .put("created_at", t.optString("created_at"))
                .put("project", names[t.optString("project_id")] ?: t.optString("project_id"))
                .put("steps", -1)
                .put("wall_s", -1.0)
            runCatching {
                val f = java.time.OffsetDateTime.parse(t.optString("finished_at"))
                val c0 = java.time.OffsetDateTime.parse(t.optString("created_at"))
                norm.put("wall_s", java.time.Duration.between(c0, f).seconds.toDouble())
            }
            out.put(norm)
        }
        return out
    }

    /**
     * PC 侧语音合成（/api/audio/tts）已删除（2026-11-04 语音严格化）：播报端点唯一
     * 来源是 设置 · 语音供应商（OpenAI 音频兼容 /v1/audio/speech，指向 PC 自建
     * engine_service 也是这一种接法）。留着它等于让远程 base_url 兼作 TTS 端点，
     * 用户在同一屏幕里配了两套语音——已按「一条路径」原则整段移除。
     */

    /**
     * 订阅 SSE 流（阻塞，调用方放后台线程）。[onEvent] 返回 false = 调用方要求
     * 停止读取（轮结束/页面销毁）。
     */
    fun stream(baseUrl: String, token: String, taskId: String, sub: String,
               onEvent: (SseEvent) -> Boolean) {
        val c = conn("$baseUrl/api/runtime/stream?task_id=$taskId&sub=$sub", token, 0)
        Rlog.log("stream open task=$taskId sub=$sub")
        try {
            val parser = SseParser()
            c.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (ln in lines) {
                    val raw = parser.feed(ln) ?: continue
                    val json = runCatching { JSONObject(raw.data) }.getOrElse { JSONObject() }
                    if (!onEvent(SseEvent(raw.event, json))) return
                }
            }
            // 走到这里 = 服务端/链路静默关流（EOF，无异常）——调用方必须知道「本轮
            // 并没有正常收尾」（2026-10-31 真机实录：TCP 层连接消失、UI 永卡干活中）。
            Rlog.log("stream EOF task=$taskId")
            throw java.io.IOException("SSE 连接被静默关闭（EOF）")
        } catch (e: Exception) {
            android.util.Log.w("ChatRemote",
                "stream broke: ${e.javaClass.simpleName}: ${e.message}")
            Rlog.log("stream broke: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            android.util.Log.i("ChatRemote", "stream closed for task=$taskId")
            Rlog.log("stream closed task=$taskId")
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
