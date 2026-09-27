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
        for (k in listOf("view_id", "text", "package", "key", "op",
                         "base_url", "api_key", "model")) {
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
                        ?: JSONArray().put(JSONArray().put(0.5).put(0.8)).put(JSONArray().put(0.5).put(0.3)),
                    args.optLong("duration_ms", 300L)
                )
                "launch_app" -> svc.launchApp(args.optString("package"))
                "press" -> svc.press(args.optString("key", "back"))
                "projection_frame" -> ProjectionHolder.captureFrameJson(context)
                "gate_show" -> {
                    context.startActivity(
                        Intent(context, GateActivity::class.java)
                            .putExtra(GateActivity.EXTRA_PACKAGE, args.optString("package", "org.fossify.notes"))
                            .putExtra(GateActivity.EXTRA_OP, args.optString("op", "type_text"))
                            .putExtra(GateActivity.EXTRA_PREVIEW, args.optString("preview", "Hachimi M1' gate 测试"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    JSONObject().put("ok", true).put("note", "gate shown, 用户选择后写 gate_result.json")
                }
                "set_brain" -> {
                    // BYOK 配置（debug 通道限定）：key 进 Keystore 加密文件，明文不落盘
                    val ok = BrainConfigStore.save(
                        context,
                        args.optString("base_url"),
                        args.optString("api_key"),
                        args.optString("model")
                    )
                    JSONObject().put("ok", ok).put("note", "brain config stored (Keystore-encrypted)")
                }
                "gate_clear" -> {
                    // M4' 状态重置：清空授权白名单（实验组间口径一致）
                    com.hachimi.app.gate.GateManager.clear(context)
                    JSONObject().put("ok", true).put("note", "gate whitelist cleared")
                }
                "gate_allow" -> {
                    // M4' 门控预授权（reset 阶段逐任务注入 App×操作对，避免 run 内人工应答）
                    val pkg = args.optString("package")
                    val op = args.optString("op")
                    com.hachimi.app.gate.GateManager.remember(context, pkg, op)
                    JSONObject().put("ok", true).put("note", "whitelist + $pkg|$op")
                }
                // M4' DoD⑥ 示教录制器控制面（事件流 = 无障碍 API，落盘经 run-as 拉取）
                "teach_start" -> TeachRecorder.start()
                "teach_stop" -> TeachRecorder.stop()
                "teach_status" -> TeachRecorder.status()
                "teach_dump" -> TeachRecorder.dump(context)
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
