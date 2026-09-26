package com.hachimi.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 调试桥（M1'）：PC 经 `adb shell am broadcast` 远程调用八原语，结果落 Logcat
 * （tag=HachimiDebug），PC 侧 `adb logcat -d -s HachimiDebug` 收取。
 *
 * 这是 M2' 回环 HTTP API 的前身，也是 M4' harness 驱动通道的原型。
 * 安全：仅在 debuggable 构建中生效（release 为哑接收器，红线 R2 不受影响）。
 *
 * 用法：
 *   adb shell am broadcast -a com.hachimi.app.DEBUG_PRIMITIVE --es prim observe
 *   adb shell am broadcast -a com.hachimi.app.DEBUG_PRIMITIVE --es prim tap_by_id --es args '{"view_id":"..."}'
 */
class DebugBridgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return

        val svc = HachimiAccessibilityService.instance
        val prim = intent.getStringExtra("prim") ?: "observe"

        // 参数从独立 extras 读取（adb 传 JSON 转义易碎）；--es args '{json}' 仍可覆盖/补充
        val args = JSONObject()
        for (k in listOf("view_id", "text", "package", "key")) {
            intent.getStringExtra(k)?.let { args.put(k, it) }
        }
        if (intent.hasExtra("x")) args.put("x", intent.getStringExtra("x")?.toDoubleOrNull() ?: intent.getDoubleExtra("x", 0.5))
        if (intent.hasExtra("y")) args.put("y", intent.getStringExtra("y")?.toDoubleOrNull() ?: intent.getDoubleExtra("y", 0.9))
        intent.getStringExtra("args")?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let { j ->
                val keys = j.keys()
                while (keys.hasNext()) { val k = keys.next(); args.put(k, j.get(k)) }
            }
        }

        val t0 = System.currentTimeMillis()
        val result: JSONObject = if (svc == null) {
            JSONObject().put("ok", false).put("error", "accessibility not bound")
        } else try {
            when (prim) {
                "observe" -> svc.observe()
                "tap_by_id" -> svc.tapById(args.optString("view_id"))
                "tap_xy" -> svc.tapXY(args.optDouble("x", 0.5), args.optDouble("y", 0.9))
                "type_text" -> svc.typeText(args.optString("text"))
                "gesture" -> svc.gesture(
                    args.optJSONArray("points")
                        ?: JSONArray().put(JSONArray().put(0.5).put(0.8)).put(JSONArray().put(0.5).put(0.3))
                )
                "launch_app" -> svc.launchApp(args.optString("package"))
                "press" -> svc.press(args.optString("key", "back"))
                "screenshot" -> run {
                    // 异步：立即回执，真实结果随回调追加（latency 含等待截图完成）
                    svc.screenshot { r ->
                        Log.i(TAG, JSONObject()
                            .put("prim", "screenshot_result")
                            .put("result", r)
                            .put("latency_ms", System.currentTimeMillis() - t0).toString())
                    }
                    JSONObject().put("ok", true).put("note", "async, result follows")
                }
                else -> JSONObject().put("ok", false).put("error", "unknown primitive: $prim")
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}")
        }

        val out = JSONObject()
            .put("prim", prim).put("args", args).put("result", result)
            .put("latency_ms", System.currentTimeMillis() - t0)
        Log.i(TAG, out.toString())
    }

    companion object {
        const val TAG = "HachimiDebug"
        const val ACTION = "com.hachimi.app.DEBUG_PRIMITIVE"
    }
}
