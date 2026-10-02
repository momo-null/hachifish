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
        // M4' DoD⑥ 示教录制挂载点（未激活时开销 = 一次布尔检查）。
        // 事件驱动的等待型 observe 仍为 M1' 后续项。
        if (event != null) TeachRecorder.onEvent(this, event)
    }

    override fun onInterrupt() {}

    // ---------- 原语 1: observe() —— UI 树序列化（对应 u2 dump_hierarchy） ----------

    /** compact=true 只保留有文本/可交互节点（导航用，噪声约为全树 1/10）；
     *  默认全树（对拍/细查语义不变，R3 同构面）。
     *  转场稳态重试：对话框关闭/App 切换瞬间 rootInActiveWindow 短暂为空，
     *  轮询至多 2s 再报错（2026-09-27 DoD① 实测：该噪声让模型反复重试烧穿预算）。 */
    fun observe(compact: Boolean = false): JSONObject {
        var root = rootInActiveWindow
        val deadline = System.currentTimeMillis() + 2000
        while (root == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(150)
            root = rootInActiveWindow
        }
        if (root == null) return err("observe", "no active window")
        val arr = JSONArray()
        walk(root, arr, depth = 0, maxDepth = 40, maxNodes = 500, compact = compact)
        return ok("observe").put("tree", arr).put("compact", compact)
    }

    private fun walk(node: AccessibilityNodeInfo, out: JSONArray,
                     depth: Int, maxDepth: Int, maxNodes: Int, compact: Boolean) {
        if (depth > maxDepth || out.length() >= maxNodes) return
        val b = Rect()
        node.getBoundsInScreen(b)
        val hasText = !(node.text.isNullOrEmpty() && node.contentDescription.isNullOrEmpty())
        val interactive = node.isClickable || node.isEditable || node.isScrollable
        if (!compact || hasText || interactive) {
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
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, out, depth + 1, maxDepth, maxNodes, compact) }
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
        // 底部钳制阈值从设备 profile（SharedPreferences "hachimi_config"）读取，默认 1.0 = 不钳制。
        // 仅对已知「系统手势区吞底部触摸」的 ROM（如旧 Flyme）经调试桥 config 写入 0.93 启用；
        // 通用设备不裁剪，避免误伤底部控件（2026-09-27 跨品牌兼容改造）。
        // 防御性读取：旧版本 config 原语曾以 String 落盘，getFloat 直接抛 ClassCastException
        // 令 tap_xy 全灭（2026-09-28 实录），历史字符串值也要能解析。
        val prefs = getSharedPreferences("hachimi_config", android.content.Context.MODE_PRIVATE)
        val limit = (prefs.getString("tap_bottom_limit", null)?.toFloatOrNull()
            ?: runCatching { prefs.getFloat("tap_bottom_limit", 1.0f) }.getOrNull()
            ?: 1.0f).toDouble()
        val yC = minOf(yNorm, limit)
        val path = Path().apply {
            moveTo((xNorm * w).roundToInt().toFloat(), (yC * h).roundToInt().toFloat())
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

    // ---------- 原语 7: launch_app(package 或应用名) ----------

    /**
     * 跳转顺序（2026-09-30 需求「先索引后跳转」）：
     * ① 精确包名快路径（原语义，harness/脚本零影响）；
     * ② 本机应用索引解析（AppIndex）——唯一命中直接跳转，省去
     *    「回桌面 → observe → 找图标」的视觉搜索慢路径（每单省 5~15 步）；
     * ③ 多候选返回 candidates 供选定后按包名重试；无命中提示桌面视觉搜索兜底。
     * 门控语义不变：仍按解析后的真实包名走 GateManager.enforce。
     */
    fun launchApp(pkgOrName: String): JSONObject {
        val q = pkgOrName.trim()
        if (q.isEmpty()) return err("launch_app", "empty package or app name")
        var intent = packageManager.getLaunchIntentForPackage(q)
        var resolvedPkg = q
        var matchedLabel: String? = null
        if (intent == null) {
            val r = AppIndex.resolve(this, q)
            when (r.status) {
                "package", "unique" -> {
                    resolvedPkg = r.pkg ?: q
                    intent = packageManager.getLaunchIntentForPackage(resolvedPkg)
                    matchedLabel = r.label
                }
                "ambiguous" -> return err("launch_app",
                    "ambiguous app name: \"$q\" → ${r.candidates.size} 个候选，" +
                            "从 candidates 选定一个后用其 package 重试")
                        .put("query", q)
                        .put("candidates", candidatesJson(r.candidates))
                else -> return err("launch_app",
                    "no match in local app index: \"$q\" — 请回退桌面搜索" +
                            "（press home → observe → 找图标点按），或核对应用名后重试")
                        .put("query", q)
            }
        }
        if (intent == null) return err("launch_app", "package not installed or not launchable: $q")
        // 授权门控（架构 §4.4：跨 App 跳转默认敏感；按解析后包名门控）
        val verdict = com.hachimi.app.gate.GateManager.enforce(
            this, resolvedPkg, "launch_app",
            "跳转到应用: $resolvedPkg" + (matchedLabel?.let { "（$it）" } ?: ""))
        if (!verdict.granted) return err("launch_app", "denied by gate ($verdict)")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        val out = ok("launch_app").put("package", resolvedPkg)
        matchedLabel?.let { out.put("matched", it) }
        return out
    }

    private fun candidatesJson(candidates: List<AppIndex.AppInfo>): JSONArray {
        val arr = JSONArray()
        for (a in candidates) {
            arr.put(JSONObject().put("package", a.pkg).put("label", a.label))
        }
        return arr
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

    private fun screenSize(): Pair<Int, Int> {
        // 必须与 observe 树的坐标系一致：树用的是 AccessibilityNodeInfo.getBoundsInScreen，
        // 落在「真实全屏」像素空间（含系统导航栏）。而 Service 上下文的 resources.displayMetrics
        // 是「可用区域」（排除了导航栏），两者不同源会让 tap_xy/gesture 的归一化 y 落点整体偏高
        // （实测：设备真实分辨率 1080x2400，而 Service 的 displayMetrics 只有 ~2250，落点偏高 ~6.7%，
        // 底部 tab/fab 系统性打不中，且 cleanup_alarms 误把「点开了闹钟编辑器」当成「列表已空」）。
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        runCatching {
            val dmgr = getSystemService(android.content.Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            val d = dmgr?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?: (getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager)
                    ?.defaultDisplay
            d?.getRealMetrics(dm)
        }
        if (dm.widthPixels > 0 && dm.heightPixels > 0) return dm.widthPixels to dm.heightPixels
        @Suppress("DEPRECATION")
        return resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
    }

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
