package com.hachimi.app.gate

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 授权门控管理（架构 §4.4）：敏感操作（type_text / launch_app）执行前强制询问。
 *
 * 作用域（2026-09-30 用户改）：**不按包名持久化**，改为「当前任务内总放行」。
 * - allow_once：仅本次放行
 * - allow_always：当前任务内所有敏感操作都不再问（下次新任务自动重置）
 * - deny：拒绝
 * 任务开始时由 UI 层调 [beginTask] 重置。
 * 阻塞桥：原语线程 latch 等待 GateActivity 用户选择（超时视为拒绝）。
 */
object GateManager {

    private const val TAG = "HachimiGate"
    private val seq = AtomicLong(0)

    /** 当前任务是否已被用户「本次任务都允许」——内存态，任务开始时重置。 */
    @Volatile
    private var sessionOpen: Boolean = false

    /** 调试/自动化总放行（DebugBridge ``gate_allow``）——与用户 session 分开：
     *  harness 的预授权发生在 **run 之前**，而 run start 会 [beginTask] 重置
     *  sessionOpen（旧任务放行不外泄）。两者共用一个标志时，自动化跑的每个敏感
     *  操作都会撞门控 UI 并在 10s 倒计时后被拒（2026-10-01 回归：harness 跑 T1
     *  首步 launch_app 即 deny_timeout，26 步预算全烧在找图标）。
     *  这里是显式调试开关，只由 [debugOpenSession] 打开、[debugClear] 关闭，
     *  [beginTask] 不碰它；用户侧「仅本次任务」语义保持不变。 */
    @Volatile
    private var debugOpen: Boolean = false

    data class Verdict(val granted: Boolean, val source: String, val choice: String)

    /** 新任务开始：重置用户总放行开关（旧任务的 allow_always 不带到新任务）。 */
    fun beginTask() {
        sessionOpen = false
        Log.i(TAG, "gate session reset (new task; debugOpen=$debugOpen)")
    }

    /** 调试用：强制打开总放行（对应 DebugBridge gate_allow），跨任务生效。 */
    fun debugOpenSession() {
        debugOpen = true
        Log.i(TAG, "gate debug-open (persists until explicit clear)")
    }

    /** 调试桥 gate_clear：连调试放行一起关，回到零授权起点。 */
    fun debugClear() {
        debugOpen = false
        sessionOpen = false
        Log.i(TAG, "gate debug + session cleared")
    }

    /** 敏感操作判定：调试放行/当前任务已总放行则直接通过；否则弹 GateUI 阻塞等待用户选择。
     *  级别：relaxed 时 launch_app 不询问（仅 type_text 问）。 */
    fun enforce(context: Context, pkg: String, op: String, preview: String = ""): Verdict {
        if (debugOpen) {
            return Verdict(true, "debug", "allow_always")
        }
        if (sessionOpen) {
            return Verdict(true, "session", "allow_always")
        }
        val level = context.getSharedPreferences("hachimi_ui", Context.MODE_PRIVATE)
            .getString("gate_level", "standard")
        if (level == "relaxed" && op == "launch_app") {
            return Verdict(true, "level", "relaxed")
        }
        val id = seq.incrementAndGet()
        val latch = CountDownLatch(1)
        var choice = RESULT_NAMES[GateActivity.RESULT_DENY]!!
        var timedOut = false
        pending.put(id, { c, to ->
            choice = if (to) "deny_timeout" else RESULT_NAMES[c] ?: "deny"
            timedOut = to
            latch.countDown()
        })
        try {
            val i = Intent(context, GateActivity::class.java)
                .putExtra(GateActivity.EXTRA_PACKAGE, pkg)
                .putExtra(GateActivity.EXTRA_OP, op)
                .putExtra(GateActivity.EXTRA_PREVIEW, preview)
                .putExtra(GateActivity.EXTRA_REQUEST, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "gate activity failed: ${e.message}")
            pending.remove(id)
            return Verdict(true, "fallback", "activity_error")
        }
        val got = latch.await(90, TimeUnit.SECONDS)
        pending.remove(id)
        Log.i(TAG, "gate pkg=$pkg op=$op choice=$choice timeout=${!got || timedOut}")
        if (choice == "allow_always") {
            sessionOpen = true
            Log.i(TAG, "session opened (all ops allowed this task)")
        }
        // source 语义：timedOut=倒计时自动拒绝（非用户选择）；latch 90s 兜底超时同口径
        val source = if (!got || timedOut) "timeout" else "user"
        return Verdict(choice.startsWith("allow"), source, choice)
    }

    /** GateActivity 用户选择回调（按请求 id 定向）。 */
    fun resolve(requestId: Long, resultCode: Int) {
        pending.remove(requestId)?.invoke(resultCode, false)
    }

    /** GateActivity 倒计时归零回调：拒绝语义，留痕 deny_timeout（2026-10-01：
     *  此前超时与手动 deny 在轨迹里同显示 source=user，排查误导）。 */
    fun resolveTimeout(requestId: Long) {
        pending.remove(requestId)?.invoke(GateActivity.RESULT_DENY, true)
    }

    private val pending = HashMap<Long, (Int, Boolean) -> Unit>()
    private val RESULT_NAMES = mapOf(
        GateActivity.RESULT_ALLOW_ONCE to "allow_once",
        GateActivity.RESULT_ALLOW_ALWAYS to "allow_always",
        GateActivity.RESULT_DENY to "deny",
    )
}
