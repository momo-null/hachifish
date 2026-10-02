package com.hachimi.app

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * M4' DoD⑥ 示教录制器（对照组 C 输入）：无障碍事件录制。
 *
 * 记录 TYPE_VIEW_CLICKED / TYPE_VIEW_TEXT_CHANGED（点击事件附带事件时点 compact
 * observe 快照；连续同字段 TEXT_CHANGED 原地合并，文本保留完整最新值——与回放侧
 * type_text 替换语义对齐）。PC 侧 teach.py 将 dump 转换为 ScriptedBrain 兼容 script，
 * 经同一 MiniLoop 回放产出合法轨迹（与真跑同 schema，供 Curator 蒸馏）。
 *
 * 通道说明：事件流 = 官方无障碍 API（红线 R2）；控制面（start/stop/dump）走调试桥
 * 广播，仅 debuggable 构建可达（门控在 DebugBridgeReceiver，录制本身无副作用）。
 *
 * v1 已知边界：系统 back/home 无对应无障碍事件不录制——依赖 back 的任务由 PC 侧在
 * 导出脚本手工补 press 步骤，或示教时选用无 back 依赖的完成路径；滚动/长按同 v1
 * 范围外。未激活时 onAccessibilityEvent 开销 = 一次布尔检查。
 */
object TeachRecorder {

    private const val MAX_EVENTS = 500

    @Volatile
    private var active = false
    private val events = JSONArray()

    @Synchronized
    fun start(): JSONObject {
        while (events.length() > 0) events.remove(0)
        active = true
        return ok("teach_start").put("recording", true)
    }

    @Synchronized
    fun stop(): JSONObject {
        active = false
        return ok("teach_stop").put("recording", false).put("events", events.length())
    }

    @Synchronized
    fun status(): JSONObject =
        ok("teach_status").put("recording", active).put("events", events.length())

    /** 缓冲落盘 files/teach/<name>（非破坏性，PC 经 run-as cat 拉取）。 */
    @Synchronized
    fun dump(context: Context): JSONObject {
        val name = "teach_%d.json".format(System.currentTimeMillis())
        return try {
            val dir = File(context.filesDir, "teach").apply { mkdirs() }
            val f = File(dir, name)
            FileOutputStream(f).use {
                it.write(events.toString().toByteArray(Charsets.UTF_8))
            }
            ok("teach_dump").put("path", "files/teach/$name")
                .put("events", events.length()).put("bytes", f.length())
        } catch (e: Exception) {
            err("teach_dump", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---------------- 事件入口（HachimiAccessibilityService 调用） ----------------

    @Synchronized
    fun onEvent(svc: HachimiAccessibilityService, event: AccessibilityEvent) {
        if (!active || events.length() >= MAX_EVENTS) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> recordClick(svc, event)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> recordText(event)
            // 仅作上下文标记（窗口切换/Activity 变化），供 PC 侧断点对账，不转为回放步骤
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> append {
                put("ts", System.currentTimeMillis())
                    .put("type", "WINDOW_STATE_CHANGED")
                    .put("pkg", event.packageName?.toString() ?: "")
                    .put("cls", event.className?.toString() ?: "")
            }
        }
    }

    private fun recordClick(svc: HachimiAccessibilityService, event: AccessibilityEvent) {
        append {
            put("ts", System.currentTimeMillis())
                .put("type", "VIEW_CLICKED")
                .put("pkg", event.packageName?.toString() ?: "")
            putNode(this, svc, event)
            // 事件时点 compact 快照（点击后界面可能已切换，供 PC 侧对账参考）
            try {
                val r = svc.observe(compact = true)
                if (r.optBoolean("ok")) put("observe", r.getJSONArray("tree"))
            } catch (_: Exception) {
            }
        }
    }

    private fun recordText(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: ""
        val viewId = event.source?.viewIdResourceName ?: ""
        val text = event.text.joinToString("")
        // 连续同字段输入原地合并：缓冲只保留最新完整值（type_text 替换语义）
        if (events.length() > 0) {
            val last = events.getJSONObject(events.length() - 1)
            if (last.optString("type") == "VIEW_TEXT_CHANGED"
                && last.optString("pkg") == pkg
                && last.optString("view_id") == viewId
                && last.optString("text") != text) {
                last.put("text", text).put("ts", System.currentTimeMillis())
                return
            }
            if (last.optString("type") == "VIEW_TEXT_CHANGED"
                && last.optString("pkg") == pkg
                && last.optString("view_id") == viewId) return  // 同值重复事件
        }
        append {
            put("ts", System.currentTimeMillis())
                .put("type", "VIEW_TEXT_CHANGED")
                .put("pkg", pkg)
                .put("view_id", viewId)
                .put("text", text)
            event.beforeText?.let { put("before", it) }
        }
    }

    private fun putNode(out: JSONObject, svc: HachimiAccessibilityService,
                        event: AccessibilityEvent) {
        val src = event.source
        out.put("view_id", src?.viewIdResourceName ?: "")
            .put("cls", src?.className?.toString() ?: event.className?.toString() ?: "")
            .put("text", src?.text?.toString() ?: event.text.joinToString(""))
            .put("desc", src?.contentDescription?.toString() ?: "")
        src?.let {
            val b = android.graphics.Rect()
            it.getBoundsInScreen(b)
            out.put("bounds", JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom))
            out.put("screen", JSONArray()
                .put(svc.resources.displayMetrics.widthPixels)
                .put(svc.resources.displayMetrics.heightPixels))
        }
    }

    private inline fun append(build: JSONObject.() -> Unit) {
        try {
            events.put(JSONObject().apply(build))
        } catch (_: Exception) {
            // 单事件异常不中断录制
        }
    }

    private fun ok(prim: String): JSONObject = JSONObject().put("ok", true).put("prim", prim)
    private fun err(prim: String, msg: String): JSONObject =
        JSONObject().put("ok", false).put("prim", prim).put("error", msg)
}
