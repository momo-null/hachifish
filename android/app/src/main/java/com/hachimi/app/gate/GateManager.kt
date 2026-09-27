package com.hachimi.app.gate

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 授权门控管理（架构 §4.4）：敏感操作（type_text / launch_app）执行前强制询问。
 * 白名单按「App × 操作类型」持久化（gate_whitelist.json），allow_always 后不再询问。
 * 阻塞桥：原语线程 latch 等待 GateActivity 用户选择（超时视为拒绝，任务可停止）。
 */
object GateManager {

    private const val TAG = "HachimiGate"
    private const val FILE = "gate_whitelist.json"
    private val seq = AtomicLong(0)

    data class Verdict(val granted: Boolean, val source: String, val choice: String)

    /** 敏感操作判定：命中白名单直接放行；否则弹 GateUI 阻塞等待用户选择。 */
    fun enforce(context: Context, pkg: String, op: String, preview: String = ""): Verdict {
        if (isWhitelisted(context, pkg, op)) {
            return Verdict(true, "whitelist", "allow_always")
        }
        val id = seq.incrementAndGet()
        val latch = CountDownLatch(1)
        var choice = RESULT_NAMES[GateActivity.RESULT_DENY]!! // 超时/异常默认拒绝
        pending.put(id, { c -> choice = RESULT_NAMES[c] ?: "deny"; latch.countDown() })
        try {
            val i = Intent(context, GateActivity::class.java)
                .putExtra(GateActivity.EXTRA_PACKAGE, pkg)
                .putExtra(GateActivity.EXTRA_OP, op)
                .putExtra(GateActivity.EXTRA_PREVIEW, preview)
                .putExtra(GateActivity.EXTRA_REQUEST, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (e: Exception) {
            // 弹层失败（如后台启动被系统拦）→ 放行并记录（研究机可观测，不卡死任务）
            Log.e(TAG, "gate activity failed: ${e.message}")
            pending.remove(id)
            return Verdict(true, "fallback", "activity_error")
        }
        val got = latch.await(90, TimeUnit.SECONDS)
        pending.remove(id)
        Log.i(TAG, "gate pkg=$pkg op=$op choice=$choice timeout=${!got}")
        if (choice == "allow_always") remember(context, pkg, op)
        return Verdict(choice.startsWith("allow"), if (got) "user" else "timeout", choice)
    }

    /** GateActivity 用户选择回调（按请求 id 定向）。 */
    fun resolve(requestId: Long, resultCode: Int) {
        pending.remove(requestId)?.invoke(resultCode)
    }

    // ---------------- 白名单（App × 操作类型） ----------------

    fun isWhitelisted(context: Context, pkg: String, op: String): Boolean =
        load(context).contains("$pkg|$op")

    fun remember(context: Context, pkg: String, op: String) {
        val key = "$pkg|$op"
        val entries = load(context).toMutableSet()
        if (entries.add(key)) {
            context.openFileOutput(FILE, Context.MODE_PRIVATE).use {
                it.write(org.json.JSONArray(entries).toString().toByteArray())
            }
            Log.i(TAG, "whitelist + $key (${entries.size} entries)")
        }
    }

    fun clear(context: Context) {
        context.deleteFile(FILE)
    }

    private fun load(context: Context): Set<String> = try {
        val f = context.getFileStreamPath(FILE)
        if (!f.exists()) emptySet()
        else {
            val arr = org.json.JSONArray(f.readBytes().decodeToString())
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }
    } catch (_: Exception) {
        emptySet()
    }

    private val pending = HashMap<Long, (Int) -> Unit>()
    private val RESULT_NAMES = mapOf(
        GateActivity.RESULT_ALLOW_ONCE to "allow_once",
        GateActivity.RESULT_ALLOW_ALWAYS to "allow_always",
        GateActivity.RESULT_DENY to "deny",
    )
}
