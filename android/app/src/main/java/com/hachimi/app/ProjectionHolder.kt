package com.hachimi.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 观察通道（架构 §3.1）：MediaProjection 一次授权 → VirtualDisplay 连续取帧，
 * ImageReader 始终持有最新帧，原语 captureFrame 直接取用（对应预注册 §4 观察通道）。
 *
 * 安全/生命周期：
 * - 前置条件：KernelHostService 已以 mediaProjection 类型进入前台（API 29+ 要求）
 * - 授权仅一次（录屏模式），projection.stop / 应用被杀即失效
 * - 每帧用完 close()，避免 fd 泄漏
 */
object ProjectionHolder {

    private const val TAG = "HachimiProjection"

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var latest: Image? = null

    val isRunning: Boolean
        get() = virtualDisplay != null

    fun start(context: Context, resultCode: Int, data: Intent): JSONObject {
        if (isRunning) return JSONObject().put("ok", true).put("note", "already running")
        return try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, data)
                ?: return JSONObject().put("ok", false).put("error", "getMediaProjection returned null")
            val metrics = context.resources.displayMetrics
            val w = metrics.widthPixels
            val h = metrics.heightPixels
            val dpi = metrics.densityDpi

            handlerThread = HandlerThread("HachimiProjection").apply { start() }
            handler = Handler(handlerThread!!.looper)

            val reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                latest?.close()
                latest = img
            }, handler)

            virtualDisplay = mp.createVirtualDisplay(
                "hachimi-observe", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
            imageReader = reader
            projection = mp
            Log.i(TAG, "projection started ${w}x$h")
            JSONObject().put("ok", true).put("width", w).put("height", h)
        } catch (e: Exception) {
            stop()
            JSONObject().put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 取最新帧：转 Bitmap → 存 PNG（供 PC run-as 拉取）→ 返回尺寸。 */
    fun captureFrameJson(context: Context): JSONObject {
        if (!isRunning) return JSONObject().put("ok", false).put("error", "projection not started")
        val img: Image = latest ?: return JSONObject().put("ok", false).put("error", "no frame yet")
        return try {
            val plane = img.planes[0]
            val bitmap: Bitmap
            if (plane.rowStride == img.width * 4) {
                bitmap = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
            } else {
                // 非对齐 rowStride（少数设备）：逐行去 padding。骨架阶段先诚实报错，后续补齐。
                return JSONObject().put("ok", false)
                    .put("error", "rowStride ${plane.rowStride} != width*4 ${img.width * 4}（逐行拷贝未实现）")
            }
            val file = File(context.filesDir, "projection_frame.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            JSONObject().put("ok", true)
                .put("width", bitmap.width).put("height", bitmap.height)
                .put("file", "files/projection_frame.png")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun stop() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        latest?.close()
        latest = null
        virtualDisplay = null
        imageReader = null
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }
}
