package com.hachimi.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本机应用索引（2026-09-30 需求：应用启动「先索引后跳转」，替代模型视觉搜索慢路径）。
 *
 * 三层结构对应需求三条：
 * ① 读取与索引——PackageManager 枚举全部 MAIN/LAUNCHER 入口，按包聚合
 *    （多入口应用的别名一并入索引）；内存缓存 + filesDir/app_index.json 磁盘缓存，
 *    冷启动先吃磁盘（毫秒级），过期后台刷新，首次跳转零阻塞。
 * ② 唯一命中直接跳转——launchApp 先试精确包名，再走 resolve 打分匹配
 *    （等值 > 前缀 > 包含 > 包名包含，系统应用同级 -0.5 罚分），
 *    严格唯一才自动跳，杜绝误跳转。
 * ③ 搜索兜底——多候选返回 candidates（≤5）供模型选定后按包名重试；
 *    无命中提示回退「press home → observe」桌面视觉搜索。
 *
 * 失效策略：TTL 5 分钟自动重建 + PACKAGE_ADDED/REPLACED/REMOVED 广播即时失效
 * + 无命中且索引偏旧时强制重建一次（覆盖「刚装的应用」边界）。
 * 权限零新增（queryIntentActivities 无需授权，红线 R2 不动）。
 */
object AppIndex {

    /** 单个可启动应用：主标签 + 别名（多入口 activity 的其余标签，如「浏览器/游戏中心」双入口）。 */
    data class AppInfo(val pkg: String, val label: String,
                       val aliases: List<String>, val system: Boolean) {
        val labels: List<String>
            get() = (listOf(label) + aliases).filter { it.isNotBlank() }.distinct()
    }

    /** 解析结论：package=精确包名；unique=唯一命中；ambiguous=多候选；none=无匹配。 */
    data class Resolution(val status: String, val pkg: String?, val label: String?,
                          val candidates: List<AppInfo>)

    private const val TTL_MS = 5 * 60_000L
    private const val STALE_RECHECK_MS = 30_000L
    private const val FILE = "app_index.json"
    private const val MAX_CANDIDATES = 5

    @Volatile private var cache: List<AppInfo>? = null
    @Volatile private var loadedAt = 0L
    @Volatile private var receiverHooked = false

    private val invalidationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            invalidate()
        }
    }

    /** 内核启动时调用：挂包变动广播 + 后台预热索引（首次 launch_app 零建索引成本）。 */
    fun warmup(context: Context) {
        val app = context.applicationContext
        if (!receiverHooked) {
            receiverHooked = true
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            runCatching {
                ContextCompat.registerReceiver(app, invalidationReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        }
        Thread { runCatching { apps(app) } }.start()
    }

    fun invalidate() {
        synchronized(this) { cache = null; loadedAt = 0L }
    }

    /** 应用列表（缓存优先：内存 > 磁盘 > PackageManager 现查）。 */
    fun apps(context: Context): List<AppInfo> {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        cache?.let { if (now - loadedAt < TTL_MS) return it }
        synchronized(this) {
            val c = cache
            if (c != null && now - loadedAt < TTL_MS) return c
            if (c == null) {
                disk(app)?.let { (ts, list) ->
                    if (list.isNotEmpty()) {
                        cache = list
                        loadedAt = ts
                        if (now - ts >= TTL_MS) {
                            // 磁盘过期：先用旧索引保证速度，后台重建
                            Thread { runCatching { refresh(app) } }.start()
                        }
                        return list
                    }
                }
            }
            return refresh(app)
        }
    }

    // ---------------- 解析（需求 ②③ 的匹配核心） ----------------

    fun resolve(context: Context, rawQuery: String): Resolution {
        val q = normalize(rawQuery)
        if (q.isEmpty()) return Resolution("none", null, null, emptyList())
        var res = match(apps(context), q)
        // 无命中且索引偏旧（刚装的应用）→ 强制重建一次再匹配，避免误回退搜索
        if (res.status == "none" && System.currentTimeMillis() - loadedAt > STALE_RECHECK_MS) {
            synchronized(this) { refresh(context.applicationContext) }
            res = match(apps(context), q)
        }
        return res
    }

    private fun match(list: List<AppInfo>, q: String): Resolution {
        val ql = q.lowercase()
        // ① 精确包名（调用方直接传全包名的原语义快路径）
        list.firstOrNull { it.pkg.equals(q, ignoreCase = true) }
            ?.let { return Resolution("package", it.pkg, it.label, emptyList()) }
        // ② 名称打分：等值 90 / 前缀 70 / 包含 60 / 反向包含 50 / 包名包含 40；
        //    系统应用同档 -0.5（用户应用优先）；模糊档要求 ≥2 字符防单字误命中
        data class Hit(val app: AppInfo, val score: Double, val label: String)
        val hits = ArrayList<Hit>()
        for (a in list) {
            var best = -1.0
            var bestLabel = a.label
            for (lbl in a.labels) {
                val ll = lbl.lowercase()
                val s = when {
                    ll == ql -> 90.0
                    ql.length >= 2 && ll.startsWith(ql) -> 70.0
                    ql.length >= 2 && ll.contains(ql) -> 60.0
                    ql.length >= 2 && ll.length >= 2 && ql.contains(ll) -> 50.0
                    ql.length >= 3 && a.pkg.lowercase().contains(ql) -> 40.0
                    else -> -1.0
                } - (if (a.system) 0.5 else 0.0)
                if (s > best) { best = s; bestLabel = lbl }
            }
            if (best > 0) hits.add(Hit(a, best, bestLabel))
        }
        if (hits.isEmpty()) return Resolution("none", null, null, emptyList())
        hits.sortWith(compareByDescending<Hit> { it.score }.thenBy { it.app.label.length })
        val top = hits[0]
        val tied = hits.filter { it.score >= top.score - 0.01 }
        return if (tied.size == 1) Resolution("unique", top.app.pkg, top.label, emptyList())
        else Resolution("ambiguous", null, null, tied.take(MAX_CANDIDATES).map { it.app })
    }

    /** 剥掉模型可能顺手带上的动词/引号（「打开微信」→「微信」），只做净化不做猜测。 */
    private fun normalize(raw: String): String {
        var q = raw.trim()
        for (ch in "「」『』“”\"'‘’") q = q.replace(ch.toString(), "")
        q = q.replace(Regex("^(打开|启动|开启|运行|跳转到|open|launch|start|run)\\s*", setOf(RegexOption.IGNORE_CASE)), "")
        return q.trim()
    }

    // ---------------- 枚举与缓存 ----------------

    private fun refresh(context: Context): List<AppInfo> = synchronized(this) {
        val fresh = query(context)
        if (fresh.isNotEmpty()) {
            cache = fresh
            loadedAt = System.currentTimeMillis()
            persist(context, fresh)
        }
        fresh
    }

    private fun query(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ris = if (android.os.Build.VERSION.SDK_INT >= 33)
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        else
            @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        val byPkg = LinkedHashMap<String, MutableList<Pair<String, Boolean>>>()
        for (ri in ris) {
            val info = ri.activityInfo ?: continue
            val label = runCatching { ri.loadLabel(pm)?.toString()?.trim() ?: "" }.getOrDefault("")
            val sys = ((info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
            byPkg.getOrPut(info.packageName) { mutableListOf() }.add(label to sys)
        }
        return byPkg.map { (pkg, entries) ->
            val labels = entries.map { it.first }.filter { it.isNotBlank() }.distinct()
            AppInfo(pkg, labels.firstOrNull() ?: pkg, labels.drop(1), entries.first().second)
        }.sortedBy { it.label.lowercase() }
    }

    /** 磁盘索引（冷启动毫秒级回放）：filesDir/app_index.json，tmp+rename 原子写。 */
    private fun disk(context: Context): Pair<Long, List<AppInfo>>? = runCatching {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        val arr = JSONArray(f.readText())
        val list = ArrayList<AppInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val aliases = ArrayList<String>()
            o.optJSONArray("a")?.let { a -> for (j in 0 until a.length()) aliases.add(a.optString(j)) }
            list.add(AppInfo(o.optString("p"), o.optString("l"), aliases, o.optBoolean("s")))
        }
        f.lastModified() to list
    }.getOrNull()

    private fun persist(context: Context, list: List<AppInfo>) {
        runCatching {
            val arr = JSONArray()
            for (a in list) {
                val aliases = JSONArray()
                a.aliases.forEach { aliases.put(it) }
                arr.put(JSONObject().put("p", a.pkg).put("l", a.label)
                    .put("a", aliases).put("s", a.system))
            }
            val f = File(context.filesDir, FILE)
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        }
    }

    /** 调试桥数据面：索引规模 + 样本（run-as/logcat 通道排查用）。 */
    fun debugJson(context: Context): JSONObject {
        val list = apps(context.applicationContext)
        val arr = JSONArray()
        for (a in list.take(80)) {
            arr.put(JSONObject().put("package", a.pkg).put("label", a.label)
                .put("system", a.system))
        }
        return JSONObject().put("ok", true).put("count", list.size)
            .put("indexed_at", loadedAt).put("apps", arr)
    }
}
