package com.hachimi.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * 录屏前台服务（Android 14+ 类型校验的正解形态）：manifest 声明
 * mediaProjection 类型，但**只在用户完成 MediaProjection 授权后**由
 * KernelHostService.startProjection 拉起——此刻 project_media appop 已在，
 * startForeground 的类型校验必过。内核服务（KernelHostService）不带类型，
 * 避免未授权启动即闪退（2026-09-30 魅族 Android 16 实录：SecurityException
 * 与 InvalidForegroundServiceType 两种死法都踩过）。
 */
class ProjectionService : Service() {

    private val notification: Notification
        get() {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_kernel),
                    NotificationManager.IMPORTANCE_LOW)
            )
            return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_kernel_title))
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .build()
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // mediaProjection 类型前台必须先于 getMediaProjection（API 29+ 要求；
        // Android 14+ 此刻校验 project_media appop——授权刚完成，必过）
        startForeground(NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val rc = intent?.getIntExtra(EXTRA_RC, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data != null) {
            ProjectionHolder.start(this, rc, data)
        } else {
            Log.w(TAG, "projection start without data intent")
            stopSelf()
        }
        // 投影会话存活期间保持前台；停止由 KernelHostService ACTION_STOP 或
        // ProjectionHolder onStop 回调统一收口（这里轮询 ProjectionHolder 即可）
        Thread {
            while (ProjectionHolder.isRunning) {
                Thread.sleep(2000)
            }
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "HachimiProjection"
        private const val CHANNEL_ID = "hachimi_kernel"   // 与内核服务共用通道
        private const val NOTIFICATION_ID = 2
        const val EXTRA_RC = "resultCode"
        const val EXTRA_DATA = "data"
    }
}
