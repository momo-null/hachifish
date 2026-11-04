package com.hachimi.app

import android.content.Context
import android.content.res.AssetManager
import java.io.File

/**
 * 插件面上机：把 APK assets 里的 plugins 树落到 filesDir/plugins（技能/工具移植）。
 *
 * 为什么要在 Kotlin 侧落盘、而不是让 Python 直接读 assets：Chaquopy 把 app 的 Python
 * 源码整体打进 ``assets/chaquopy/app.imy`` 一个归档，单个 ``.py`` 不再是可寻址文件；
 * 而上游插件 loader 用 ``Path.iterdir()`` 真扫目录 + ``spec_from_file_location`` 按
 * 文件路径装载（见 omni_core/tools/loader.py），两者都要真实文件系统路径。所以插件树
 * 走标准 AGP assets（``scripts/sync_upstream.py`` 从上游仓拷入），首次启动落到 App
 * 私有目录，再把 ``runtime.tools.plugin_dirs`` 指过去——发现/开关/装载全部走上游原路，
 * 壳侧零分叉（红线 R6），也零新增权限（App 私有目录，红线 R2）。
 *
 * 幂等：逐文件按内容比对，相同不重写；assets 里已不存在的插件目录随之清除（上游删插件
 * = 手机上不再有它的陈旧副本，否则 loader 会扫到它）。
 *
 * 失败只降级不阻断：返回 ``dir = null`` 时内核不播种 plugin_dirs，手机只剩 core 工具 +
 * GUI 原语（与移植前的实际形态一致）。
 */
object PluginAssets {

    /** APK 内插件树根（AGP 标准 assets 路径，与 sync_upstream.PLUGIN_DEST 对齐）。 */
    private const val ASSET_ROOT = "plugins"

    /** 落盘后的插件根 = filesDir/plugins（可直接交给 runtime.tools.plugin_dirs）。 */
    fun dir(context: Context): File = File(context.filesDir, ASSET_ROOT)

    data class Result(val dir: File?, val summary: String)

    fun materialize(context: Context): Result = try {
        val am = context.assets
        val top = am.list(ASSET_ROOT)
        if (top == null || top.isEmpty()) {
            Result(null, "assets 无插件面（构建前未跑 scripts/sync_upstream.py）")
        } else {
            val root = dir(context)
            root.mkdirs()
            val stat = IntArray(3)                       // [写入/更新, 未变, 失败]
            copyAll(am, ASSET_ROOT, root, stat)
            val pruned = prune(root, top.toSet())
            val msg = "插件面 $root：写${stat[0]} 同${stat[1]} 败${stat[2]} 清$pruned"
            if (stat[2] > 0) Result(if (root.isDirectory) root else null, "$msg（降级）")
            else Result(root, msg)
        }
    } catch (e: Exception) {
        Result(null, "插件面落盘异常：${e.javaClass.simpleName}: ${e.message}")
    }

    /** 递归拷贝；``am.list()`` 非空 = 目录，空 = 文件（aapt 不打包空目录）。 */
    private fun copyAll(am: AssetManager, assetPath: String, dstDir: File, stat: IntArray) {
        for (name in am.list(assetPath).orEmpty()) {
            val child = "$assetPath/$name"
            val children = am.list(child)
            if (!children.isNullOrEmpty()) {
                val sub = File(dstDir, name)
                runCatching {
                    sub.mkdirs()
                    copyAll(am, child, sub, stat)
                }.onFailure { stat[2]++ }
                continue
            }
            runCatching {
                val bytes = am.open(child).use { it.readBytes() }
                val dst = File(dstDir, name)
                if (dst.exists() && dst.length() == bytes.size.toLong() &&
                    dst.readBytes().contentEquals(bytes)
                ) {
                    stat[1]++
                } else {
                    dst.writeBytes(bytes)
                    stat[0]++
                }
            }.onFailure { stat[2]++ }
        }
    }

    /** 清掉 assets 里已不存在的顶层插件目录，返回清除数。 */
    private fun prune(root: File, keep: Set<String>): Int {
        var n = 0
        root.listFiles()?.forEach { f ->
            if (f.isDirectory && f.name !in keep) {
                runCatching { f.deleteRecursively() }.onSuccess { n++ }
            }
        }
        return n
    }
}
