package com.hachimi.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * 八原语派发器（架构 §4.2：JSON 进出）——调试桥（DebugBridgeReceiver）与
 * Python 内核桥（hachimi_kernel.bridge）共用同一实现，保证原语面单一事实来源。
 */
class PrimitiveDispatcher {

    fun call(name: String, argsJson: String): String {
        val svc = HachimiAccessibilityService.instance
            ?: return JSONObject().put("ok", false).put("error", "a11y not bound").toString()
        val args = try { JSONObject(argsJson) } catch (e: Exception) { JSONObject() }
        return when (name) {
            "observe" -> svc.observe(args.optBoolean("compact", false))
            "tap_by_id" -> svc.tapById(args.optString("view_id"))
            "tap_xy" -> svc.tapXY(args.optDouble("x", 0.5), args.optDouble("y", 0.9))
            "type_text" -> svc.typeText(args.optString("text"))
            "gesture" -> svc.gesture(args.optJSONArray("points") ?: JSONArray(),
                args.optLong("duration_ms", 300L))
            "screenshot" -> ProjectionHolder.captureFrameJson(svc)
            "grab_frame" -> ProjectionHolder.grabFrameJson(
                args.optInt("max_width", 768), args.optInt("quality", 60))
            "launch_app" -> svc.launchApp(args.optString("package"))
            "press" -> svc.press(args.optString("key", "back"))
            else -> JSONObject().put("ok", false).put("error", "unknown primitive: $name")
        }.toString()
    }
}
