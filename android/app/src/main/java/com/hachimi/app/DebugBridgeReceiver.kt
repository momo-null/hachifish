package com.hachimi.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import com.hachimi.app.gate.GateActivity
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

        // 参数从独立 extras 读取（adb 传 JSON 转义易碎，set_brain 三字段必须走独立
        // --es：嵌套 --es args '{json}' 在 Windows Git Bash→adb shell 双层引号中会丢值）
        val args = JSONObject()
        for (k in listOf("view_id", "text", "package", "key", "op", "value",
                         "base_url", "api_key", "model")) {
            intent.getStringExtra(k)?.let { args.put(k, it) }
        }
        // vision_enabled 接受 --ez（布尔）或 --es "true/false"（独立 extra，勿并入字符串白名单）
        if (intent.hasExtra("vision_enabled")) {
            val s = intent.getStringExtra("vision_enabled")
            val v = if (s != null) s.equals("true", ignoreCase = true)
                    else intent.getBooleanExtra("vision_enabled", false)
            args.put("vision_enabled", v)
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
        // 仅 a11y 依赖类原语要求服务已绑定；配置/门控/取帧类在无障碍掉线时也必须可用
        // （魅族 bootstrap 实录：set_brain 被 a11y 前置拦死，无障碍还没拨就配不了 BYOK）
        val needsA11y = when (prim) {
            "observe", "tap_by_id", "tap_xy", "type_text", "gesture",
            "launch_app", "press", "screenshot" -> true
            else -> false
        }
        val result: JSONObject = if (svc == null && needsA11y) {
            JSONObject().put("ok", false).put("error", "accessibility not bound")
        } else try {
            when (prim) {
                "observe" -> svc!!.observe()
                "tap_by_id" -> svc!!.tapById(args.optString("view_id"))
                "tap_xy" -> svc!!.tapXY(args.optDouble("x", 0.5), args.optDouble("y", 0.9))
                "type_text" -> svc!!.typeText(args.optString("text"))
                "gesture" -> svc!!.gesture(
                    args.optJSONArray("points")
                        ?: JSONArray().put(JSONArray().put(0.5).put(0.8)).put(JSONArray().put(0.5).put(0.3)),
                    args.optLong("duration_ms", 300L)
                )
                "launch_app" -> svc!!.launchApp(args.optString("package"))
                "press" -> svc!!.press(args.optString("key", "back"))
                "projection_frame" -> ProjectionHolder.captureFrameJson(context)
                "grab_frame" -> ProjectionHolder.grabFrameJson(
                    args.optInt("max_width", 768), args.optInt("quality", 60))
                "gate_show" -> {
                    context.startActivity(
                        Intent(context, GateActivity::class.java)
                            .putExtra(GateActivity.EXTRA_PACKAGE, args.optString("package", "org.fossify.notes"))
                            .putExtra(GateActivity.EXTRA_OP, args.optString("op", "type_text"))
                            .putExtra(GateActivity.EXTRA_PREVIEW, args.optString("preview", "Hachifish M1' gate 测试"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    JSONObject().put("ok", true).put("note", "gate shown, 用户选择后写 gate_result.json")
                }
                "set_brain" -> {
                    // BYOK 配置（debug 通道限定）：key 进 Keystore 加密文件，明文不落盘。
                    // vision_enabled 可选（--ez/--es "true"）：显式设定视觉开关（P1）；
                    // 不带此 extra 时沿用已存开关状态（3 参 save 的语义）。
                    // vision_enabled=true → 视觉字段沿用本次下发的规划端点（共用形态）；
                    // 视觉独立端点走产品设置页（v4 双端点）
                    val ok = if (args.optBoolean("vision_enabled")) {
                        val u = args.optString("base_url")
                        val k = args.optString("api_key")
                        val m = args.optString("model")
                        BrainConfigStore.save(context, BrainConfigStore.BrainConfig(
                            u, k, m, visionBaseUrl = u, visionApiKey = k, visionModel = m))
                    } else {
                        BrainConfigStore.save(
                            context,
                            args.optString("base_url"),
                            args.optString("api_key"),
                            args.optString("model")
                        )
                    }
                    JSONObject().put("ok", ok)
                        .put("note", "brain config stored (Keystore-encrypted)")
                }
                "gate_clear" -> {
                    // M4' 状态重置：任务级放行 + 调试放行一并关闭。
                    // 2026-10-01：只调 beginTask 的话，调试预授权（gate_allow 打开的
                    // debugOpen）永远关不掉 —— 必须以它为自动化的「关」对称操作。
                    com.hachimi.app.gate.GateManager.debugClear()
                    JSONObject().put("ok", true).put("note", "gate session + debug cleared")
                }
                "app_index" -> {
                    // 本机应用索引数据面（2026-09-30）：规模/样本/时效，PC 排查用
                    AppIndex.debugJson(context)
                }
                "gate_allow" -> {
                    // M4' 门控预授权（新实现：任务级总放行，不区分 pkg/op）
                    com.hachimi.app.gate.GateManager.debugOpenSession()
                    JSONObject().put("ok", true).put("note", "gate session opened for current task")
                }
                "panel_silent" -> {
                    // R15 实验静默开关：run 期间完全不挂悬浮层（球/浮条/控制台/红框）。
                    // 球与浮条是可触摸窗，会吞 agent 的 tap_xy（2026-10-02 实录）。
                    val on = if (intent.hasExtra("enabled")) intent.getBooleanExtra("enabled", true) else true
                    com.hachimi.app.NarrativePanel.silent = on
                    JSONObject().put("ok", true)
                        .put("note", if (on) "overlay silenced (no pill/bar/console/ground)"
                                     else "overlay normal")
                }
                "config" -> {
                    // 设备 profile 注入（跨品牌兼容）：把 ROM 专属参数写入应用私有 SharedPreferences
                    // "hachimi_config"，供原语运行时读取。例：tap_bottom_limit=0.93（手势区吞底部触摸）。
                    // 类型契约：数值必须写 Float（读方 getFloat，如 tapXY；putString 会让
                    // getFloat 抛 ClassCastException——2026-09-28 实录），其余写 String。
                    val key = args.optString("key")
                    val value = args.optString("value")
                    val editor = context.getSharedPreferences("hachimi_config", Context.MODE_PRIVATE).edit()
                    value.toFloatOrNull()?.let { editor.putFloat(key, it) }
                        ?: editor.putString(key, value)
                    editor.apply()
                    JSONObject().put("ok", true).put("note", "config $key=$value")
                }
                // M4' DoD⑥ 示教录制器控制面（事件流 = 无障碍 API，落盘经 run-as 拉取）
                "teach_start" -> TeachRecorder.start()
                "teach_stop" -> TeachRecorder.stop()
                "teach_status" -> TeachRecorder.status()
                "teach_dump" -> TeachRecorder.dump(context)
                "screenshot" -> run {
                    // 异步：立即回执，真实结果随回调追加（latency 含等待截图完成）
                    svc!!.screenshot { r ->
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
        // 大结果（observe 全树 >4KB）会被 logcat 截断，落文件由 PC run-as 读取
        try {
            context.openFileOutput("bridge_result.json", Context.MODE_PRIVATE).use {
                it.write(out.toString().toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "write result failed: ${e.message}")
        }
        Log.i(TAG, out.toString().take(200))
    }

    companion object {
        const val TAG = "HachimiDebug"
        const val ACTION = "com.hachimi.app.DEBUG_PRIMITIVE"
    }
}
