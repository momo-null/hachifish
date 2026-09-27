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
        val root0 = rootInActiveWindow ?: return err("type_text", "no active window")
        // 授权门控（架构 §4.4：文本输入默认敏感；白名单命中则直接放行）
        val gatedPkg = root0.packageName?.toString() ?: "?"
        val verdict = com.hachimi.app.gate.GateManager.enforce(
            this, gatedPkg, "type_text", "输入文本: \"$text\"")
        if (!verdict.granted) return err("type_text", "denied by gate ($verdict)")
        // Gate 弹层关闭后目标窗口的输入焦点恢复不保证：轮询等待（≤2.5s），
        // 排除自家窗口（gate 残留/面板），命中门控同包窗口或任何第三方窗口
        var target: AccessibilityNodeInfo? = null
        val deadline = System.currentTimeMillis() + 2500
        while (target == null && System.currentTimeMillis() < deadline) {
            rootInActiveWindow?.let { w ->
                val pkg = w.packageName?.toString() ?: ""
                if (pkg == gatedPkg || pkg != packageName) {
                    target = (w.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        ?: findFirstEditable(w))?.also { it.refresh() }
                }
            }
            if (target == null) Thread.sleep(200)
        }
        target ?: return err("type_text", "no focused editable node after gate")
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            ok("type_text").put("length", text.length)
        } else err("type_text", "ACTION_SET_TEXT failed")
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { c ->
                findFirstEditable(c)?.let { return it }
            }
        }
        return null
    }

    // ---------- 原语 5: gesture(points) —— 手势（对应 u2 swipe/drag） ----------

    /** points: 归一化坐标数组 [[x0,y0],[x1,y1],...]（兼容 [{x:..,y:..}] 对象形式） */
    fun gesture(points: JSONArray, durationMs: Long = 300): JSONObject {
        if (points.length() < 2) return err("gesture", "need >= 2 points")
        val (w, h) = screenSize()
        val path = Path()
        for (i in 0 until points.length()) {
            val p = points.get(i)
            val x: Float; val y: Float
            if (p is JSONArray) {
                x = (p.getDouble(0) * w).roundToInt().toFloat()
                y = (p.getDouble(1) * h).roundToInt().toFloat()
            } else if (p is JSONObject) {
                x = (p.getDouble("x") * w).roundToInt().toFloat()
                y = (p.getDouble("y") * h).roundToInt().toFloat()
            } else return err("gesture", "bad point format at $i")
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
        // SDK 35+ 为三参重载（多显示支持）：displayId, executor, callback
        // ScreenshotResult 本身无宽高（javap 核实），尺寸在 HardwareBuffer 上
        val cb = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                callback(
                    ok("screenshot")
                        .put("width", buffer.width)
                        .put("height", buffer.height)
                )
                buffer.close()
            }

            override fun onFailure(errorCode: Int) {
                callback(err("screenshot", "takeScreenshot failed: $errorCode"))
            }
        }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, cb)
    }

    // ---------- 原语 7: launch_app(package) ----------

    fun launchApp(pkg: String): JSONObject {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: return err("launch_app", "package not installed or not launchable: $pkg")
        // 授权门控（架构 §4.4：跨 App 跳转默认敏感）
        val verdict = com.hachimi.app.gate.GateManager.enforce(
            this, pkg, "launch_app", "跳转到应用: $pkg")
        if (!verdict.granted) return err("launch_app", "denied by gate ($verdict)")
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
