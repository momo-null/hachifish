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

        fun start(context: Context) {
            context.startForegroundService(Intent(context, KernelHostService::class.java))
        }

        /** 授权成功后调用：先以 mediaProjection 类型进前台（API 29+ 硬性要求），再取 projection。 */
        fun startProjection(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, KernelHostService::class.java)
                .setAction(ACTION_PROJECTION)
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
        startForeground(NOTIFICATION_ID, notification)
        // TODO(M2'): KernelHost.attach(this) —— Chaquopy 解释器启动 + 知识层目录注入 + /health 回环 API
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ProjectionHolder.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PROJECTION -> {
                // mediaProjection 类型前台必须先于 getMediaProjection（API 29+）
                startForeground(NOTIFICATION_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                val rc = intent.getIntExtra(EXTRA_RC, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (data != null) ProjectionHolder.start(this, rc, data)
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
            if (!com.chaquo.python.Python.isStarted()) {
                com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(this))
            }
            val py = com.chaquo.python.Python.getInstance()
            val info = py.getModule("hachimi_kernel").callAttr("kernel_info")
            android.util.Log.i("HachimiKernel", "kernel_info: ${info.toString()}")

            // 原语派发器注入 Python（工具面定义在 hachimi_kernel.bridge.TOOL_SPECS）
            val dispatcher = PrimitiveDispatcher()
            py.getModule("hachimi_kernel.bridge").callAttr("register_bridge", dispatcher)
            startLoopback(py)
        } catch (e: Exception) {
            android.util.Log.e("HachimiKernel", "kernel attach failed: ${e.javaClass.simpleName}: ${e.message}")
        }
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
                    }
                    200 to bridge.callAttr("start_task", body).toString()
                }
                method == "POST" && path == "/stop" ->
                    200 to bridge.callAttr("request_stop").toString()
                method == "GET" && path == "/status" ->
                    200 to bridge.callAttr("status").toString()
                method == "GET" && path == "/metrics" ->
                    200 to bridge.callAttr("metrics").toString()
                method == "GET" && path == "/trajectory" ->
                    200 to bridge.callAttr("trajectory").toString()
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
