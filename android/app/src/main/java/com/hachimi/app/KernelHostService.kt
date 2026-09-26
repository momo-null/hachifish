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
    private fun attachKernel() {
        try {
            if (!com.chaquo.python.Python.isStarted()) {
                com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(this))
            }
            val info = com.chaquo.python.Python.getInstance()
                .getModule("hachimi_kernel").callAttr("kernel_info")
            android.util.Log.i("HachimiKernel", "kernel_info: ${info.toString()}")
        } catch (e: Exception) {
            android.util.Log.e("HachimiKernel", "kernel attach failed: ${e.javaClass.simpleName}: ${e.message}")
        }
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
