package com.hachimi.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 操作通道（红线 R2：仅官方 Accessibility API）。
 *
 * 八原语面与桌面版 environments/emulator/backend.py（u2）同构（架构 §4.1），
 * 内核只认原语名，后端切换零改动（红线 R3）。所有原语 JSON 进出（架构 §4.2）：
 * 返回 {"ok": true, ...payload} 或 {"ok": false, "error": "..."}，不抛穿。
 */
class HachimiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: HachimiAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // M1' 后续：事件驱动的窗口变化监听（等待型 observe）。骨架阶段轮询式 observe 已够。
    }

    override fun onInterrupt() {}

    // ---------- 原语 1: observe() —— UI 树序列化（对应 u2 dump_hierarchy） ----------

    fun observe(): JSONObject {
        val root = rootInActiveWindow
            ?: return err("observe", "no active window")
        val arr = JSONArray()
        walk(root, arr, depth = 0, maxDepth = 40, maxNodes = 500)
        return ok("observe").put("tree", arr)
    }

    private fun walk(node: AccessibilityNodeInfo, out: JSONArray, depth: Int, maxDepth: Int, maxNodes: Int) {
        if (depth > maxDepth || out.length() >= maxNodes) return
        val b = Rect()
        node.getBoundsInScreen(b)
        out.put(
            JSONObject()
                .put("view_id", node.viewIdResourceName ?: "")
                .put("cls", node.className ?: "")
                .put("text", node.text ?: "")
                .put("desc", node.contentDescription ?: "")
                .put("clickable", node.isClickable)
                .put("editable", node.isEditable)
                .put("bounds", JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom))
        )
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, out, depth + 1, maxDepth, maxNodes) }
        }
    }

    // ---------- 原语 2: tap_by_id(viewId) —— 控件点击（对应 u2 ByResourceID.click） ----------

    fun tapById(viewId: String): JSONObject {
        val root = rootInActiveWindow ?: return err("tap_by_id", "no active window")
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        val target = nodes.firstOrNull { it.isClickable } ?: nodes.firstOrNull()
            ?: return err("tap_by_id", "view not found: $viewId")
        return if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            ok("tap_by_id").put("view_id", viewId)
        } else err("tap_by_id", "performAction failed")
    }

    // ---------- 原语 3: tap_xy(x, y) —— 归一化坐标点按（对应 u2 click） ----------

    fun tapXY(xNorm: Double, yNorm: Double): JSONObject {
        val (w, h) = screenSize()
        val path = Path().apply {
            moveTo((xNorm * w).roundToInt().toFloat(), (yNorm * h).roundToInt().toFloat())
        }
        return dispatchStroke("tap_xy", path, durationMs = 60)
    }

    // ---------- 原语 4: type_text(t) —— 文本输入（GateUI 敏感操作） ----------

    fun typeText(text: String): JSONObject {
        val root = rootInActiveWindow ?: return err("type_text", "no active window")
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return err("type_text", "no focused editable node")
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            ok("type_text").put("length", text.length)
        } else err("type_text", "ACTION_SET_TEXT failed")
    }

    // ---------- 原语 5: gesture(points) —— 手势（对应 u2 swipe/drag） ----------

    /** points: 归一化坐标数组 [[x0,y0],[x1,y1],...] */
    fun gesture(points: JSONArray, durationMs: Long = 300): JSONObject {
        if (points.length() < 2) return err("gesture", "need >= 2 points")
        val (w, h) = screenSize()
        val path = Path()
        for (i in 0 until points.length()) {
            val p = points.getJSONArray(i)
            val x = (p.getDouble(0) * w).roundToInt().toFloat()
            val y = (p.getDouble(1) * h).roundToInt().toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return dispatchStroke("gesture", path, durationMs)
    }

    // ---------- 原语 6: screenshot() —— API 30+ 官方截图（MediaProjection 为 M1' 后续兜底通道） ----------

    fun screenshot(callback: (JSONObject) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(err("screenshot", "takeScreenshot requires API 30+ (架构 A6)"))
            return
        }
        takeScreenshot(
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    // M1' 后续：HardwareBuffer → Bitmap → JPEG bytes → Base64 交内核。
                    callback(ok("screenshot").put("width", screenshot.width).put("height", screenshot.height))
                }

                override fun onFailure(errorCode: Int) {
                    callback(err("screenshot", "takeScreenshot failed: $errorCode"))
                }
            }
        )
    }

    // ---------- 原语 7: launch_app(package) ----------

    fun launchApp(pkg: String): JSONObject {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: return err("launch_app", "package not installed or not launchable: $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return ok("launch_app").put("package", pkg)
    }

    // ---------- 原语 8: press(key) —— back/home/recents ----------

    fun press(key: String): JSONObject {
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return err("press", "unknown key: $key")
        }
        return if (performGlobalAction(action)) ok("press").put("key", key)
        else err("press", "performGlobalAction failed")
    }

    // ---------- helpers ----------

    private fun screenSize(): Pair<Int, Int> =
        resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels

    private fun dispatchStroke(name: String, path: Path, durationMs: Long): JSONObject {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {}
            override fun onCancelled(gestureDescription: GestureDescription?) {}
        }, null)
        return if (dispatched) ok(name) else err(name, "dispatchGesture rejected")
    }

    private fun ok(primitive: String): JSONObject = JSONObject().put("ok", true).put("primitive", primitive)
    private fun err(primitive: String, msg: String): JSONObject =
        JSONObject().put("ok", false).put("primitive", primitive).put("error", msg)
}
