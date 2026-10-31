package com.hachimi.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 前台服务（架构 §3.1 KernelHostService）：保活 + 常驻通知 + 停止动作。
 * M2' TODO：在此挂 Chaquopy 解释器（Python 内核生命周期）与回环 HTTP API。
 */
class KernelHostService : Service() {

    companion object {
        const val CHANNEL_ID = "hachimi_kernel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.hachimi.app.action.STOP"
        const val ACTION_PROJECTION = "com.hachimi.app.action.PROJECTION"
        const val EXTRA_RC = "resultCode"
        const val EXTRA_DATA = "data"

        /** 面板按钮 → Python 控制面的通道（attachKernel 时缓存 bridge 模块）。 */
        @Volatile
        var pyBridgeModule: com.chaquo.python.PyObject? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, KernelHostService::class.java))
        }

        /** 授权成功后调用：ProjectionService 以 mediaProjection 类型进前台再取 projection
         *  （Android 14+：类型校验此刻必过；内核服务本身不带类型）。 */
        fun startProjection(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ProjectionService::class.java)
                .putExtra(EXTRA_RC, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KernelHostService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_kernel), NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_kernel_title))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            // 用户随时可停（架构 §3.1 / 预注册 §8：可随时中止）
            .addAction(0, "停止", android.app.PendingIntent.getService(
                this, 0,
                Intent(this, KernelHostService::class.java).setAction(ACTION_STOP),
                android.app.PendingIntent.FLAG_IMMUTABLE
            ))
            .build()
        // Android 14+：FGS 必须带类型（见 manifest 注释）。specialUse 无运行时授权
        // 要求；API<34 用 two-arg（沿用 manifest）即可。投影升格移交 ProjectionService
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // TODO(M2'): KernelHost.attach(this) —— Chaquopy 解释器启动 + 知识层目录注入 + /health 回环 API
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ProjectionHolder.stop()
                NarrativePanel.hide()
                stopService(Intent(this, ProjectionService::class.java))
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startForeground(NOTIFICATION_ID, buildNotification())
        }
        attachKernel()
        return START_STICKY
    }

    /** M2' DoD②：Chaquopy Python 桥冒烟——启动解释器并调用 hachimi_kernel.kernel_info()。 */
    private var loopback: LoopbackServer? = null

    private fun attachKernel() {
        try {
            com.hachimi.app.Rlog.init(filesDir)   // ROM 丢 logcat——排障日志落文件
            // 持久化根注入（runtime_paths 在 import 时读取）：App 私有目录 = files/
            android.system.Os.setenv("HACHIMI_DATA_DIR", filesDir.absolutePath, true)
            if (!com.chaquo.python.Python.isStarted()) {
                com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(this))
            }
            val py = com.chaquo.python.Python.getInstance()
            val info = py.getModule("hachimi_kernel").callAttr("kernel_info")
            android.util.Log.i("HachimiKernel", "kernel_info: ${info.toString()}")

            // [spike/full-kernel-hosting] Phase 0 五道闸自检已收官（2026-10-31 真机全绿）。
            // spike 挂钩已摘除：它占用 18471 会与 omni_host 冲突（实测地址占用竞态）；
            // spike_check.py 保留供后续诊断复用。

            // Phase 2（mini_loop 退役）：bridge 只剩数据面（BYOK 测试 / 会话回放 /
            // 任务与知识页读取），执行脑在完整内核（omni_host）——原语注入与叙事
            // sink 随工具面/本地运行一起摘除；工具面 = hachimi_kernel.phone_tools。
            val bridgeMod = py.getModule("hachimi_kernel.bridge")
            // P2：知识开关持久化值随 attach 同步 kernel（进程重启后 kernel 默认开，
            // 用户此前关过的选择会丢——设置页只在切换时推送，冷启动必须这里回放）
            bridgeMod.callAttr("set_knowledge_enabled",
                getSharedPreferences("hachimi_ui", MODE_PRIVATE)
                    .getBoolean("knowledge_enabled", true))
            pyBridgeModule = bridgeMod
            // 本机应用索引预热（后台线程）：首次 launch_app 按应用名跳转零建索引成本
            AppIndex.warmup(this)
            // P1.5：attach 不再 show——面板只在 run 开始时出现（直调/回环 /task 各自
            // show），否则开机即挂浮条遮挡引导页/主界面
            startLoopback(py)
            startOmniHost(py)
        } catch (e: Exception) {
            android.util.Log.e("HachimiKernel", "kernel attach failed: ${e.javaClass.simpleName}: ${e.message}")
            com.hachimi.app.Rlog.init(filesDir)   // ROM 丢 logcat——失败落文件排障
            com.hachimi.app.Rlog.log("attachKernel failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** P1 视觉注入（P1.5 v4：视觉独立端点；未配置=传空串，bridge 关闭兜底，退 P0）。 */
    private fun injectVision(bridge: com.chaquo.python.PyObject, cfg: BrainConfigStore.BrainConfig) {
        if (cfg.visionEnabled) {
            bridge.callAttr("set_vision", cfg.visionBaseUrl, cfg.visionApiKey, cfg.visionModel)
        } else {
            bridge.callAttr("set_vision", "", "", "")
        }
    }

    /**
     * Phase 1 kernel host v2：启动环回 omni 内核（完整上游真身，方案文档
     * full_kernel_hosting_plan_2026-10-31.md）。BYOK 配置（Keystore 解密）以
     * JSON 注入，内核侧写 models.json（模型路由真源）。幂等；失败不影响旧链路。
     */
    private var omniHostStarted = false

    private fun startOmniHost(py: com.chaquo.python.Python) {
        if (omniHostStarted) return
        omniHostStarted = true
        Thread({
            try {
                // 序列：prepare（HOME/CWD，零依赖）→ phone_tools bind（import 即注册，
                // 依赖 prepare 先行）→ omni_host.start（config/导入序/uvicorn）
                py.getModule("hachimi_kernel.omni_host").callAttr("prepare")
                py.getModule("hachimi_kernel.phone_tools").callAttr("bind", PrimitiveDispatcher())
                val cfg = BrainConfigStore.load(this)
                val payload = org.json.JSONObject().apply {
                    put("base_url", cfg?.baseUrl ?: "")
                    put("api_key", cfg?.apiKey ?: "")
                    put("model", cfg?.model ?: "")
                    put("vision_base_url", cfg?.visionBaseUrl ?: "")
                    put("vision_api_key", cfg?.visionApiKey ?: "")
                    put("vision_model", cfg?.visionModel ?: "")
                }
                val r = py.getModule("hachimi_kernel.omni_host")
                    .callAttr("start", payload.toString())
                android.util.Log.i("OmniHost", "start: ${r}")
                com.hachimi.app.Rlog.log("omni_host start: ${r}")
            } catch (e: Exception) {
                android.util.Log.e("OmniHost",
                    "start failed: ${e.javaClass.simpleName}: ${e.message}")
                com.hachimi.app.Rlog.log(
                    "omni_host start failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "omni-host-boot").start()
    }

    private fun startLoopback(py: com.chaquo.python.Python) {
        if (loopback?.isAlive == true) return
        loopback = LoopbackServer(8071) { method, path, body ->
            val bridge = py.getModule("hachimi_kernel.bridge")
            when {
                method == "GET" && path == "/health" -> {
                    200 to """{"ok":true,"a11y":${HachimiAccessibilityService.instance != null},""" +
                            """"projection":${ProjectionHolder.isRunning},"python":${com.chaquo.python.Python.isStarted()},""" +
                            """"loopback":true}"""
                }
                method == "POST" && path == "/task" -> {
                    // BYOK 注入：Keystore 解密配置以独立参数传给 Python（无 JSON 拼接转义问题）
                    val cfg = BrainConfigStore.load(this)
                    if (cfg != null) {
                        bridge.callAttr("set_brain", cfg.baseUrl, cfg.apiKey, cfg.model)
                        injectVision(bridge, cfg)
                    }
                    NarrativePanel.show(this)
                    200 to bridge.callAttr("start_task", body).toString()
                }
                method == "POST" && path == "/stop" ->
                    200 to bridge.callAttr("request_stop").toString()
                method == "POST" && path == "/pause" ->
                    200 to bridge.callAttr("request_pause").toString()
                method == "POST" && path == "/resume" ->
                    200 to bridge.callAttr("request_resume").toString()
                method == "GET" && path == "/status" ->
                    200 to bridge.callAttr("status").toString()
                method == "GET" && path == "/metrics" ->
                    200 to bridge.callAttr("metrics").toString()
                method == "GET" && path == "/trajectory" ->
                    200 to bridge.callAttr("trajectory").toString()
                method == "GET" && path.startsWith("/trajectory/") ->
                    200 to bridge.callAttr("trajectory_export", path.removePrefix("/trajectory/")).toString()
                else -> 404 to """{"ok":false,"error":"no route: $method $path"}"""
            }
        }.also { it.start() }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_kernel_title))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .addAction(0, "停止", android.app.PendingIntent.getService(
                this, 0,
                Intent(this, KernelHostService::class.java).setAction(ACTION_STOP),
                android.app.PendingIntent.FLAG_IMMUTABLE
            ))
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
}
