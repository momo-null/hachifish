package com.hachimi.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.util.LruCache
import com.hachimi.app.R
import kotlin.math.max

/**
 * 大肥鱼素材单一来源（redesign_plan A1）：drawable id 表 + 圆形裁剪 + 解码缓存。
 *
 * - 五张图均为透明底 PNG（drawable-nodpi，按密度不缩放，尺寸代码控制）。
 * - 圆形裁剪用 BitmapShader（抗锯齿），供对话头像/悬浮球等复用；
 *   用户上传头像（RoleStore）走同一条 [circle] 通道，风格一致。
 * - LruCache 1/8 内存，key = "res:sizePx"；素材小（5 张），命中率高。
 * - 全代码构建 UI 惯例：本对象无状态、无 Context 持有，线程安全。
 */
object FishAssets {

    // ---------------- 素材 id 表 ----------------

    /** 引导页「没吃饱」（自嘲萌开场）。 */
    val GUIDE = R.drawable.fish_guide
    /** 失败态「花 token 想的」（委屈萌）。 */
    val FAIL = R.drawable.fish_fail
    /** 空状态「压力大肥鱼」（疑惑/期待；亦作默认角色卡形象）。 */
    val EMPTY = R.drawable.fish_empty
    /** 空闲态 / 待派活主界面主图「吃白饭」（懒散萌，喂食英寸函 hachimi idle）。 */
    val TASK = R.drawable.fish_task
    /** 完成态彩蛋「看不太懂瞎编一个」（得意+心虚）。 */
    val DONE = R.drawable.fish_done
    /** 对话头像（女仆装透明图，聊天头部/消息旁/悬浮球统一使用）。 */
    val AVATAR = R.drawable.fish_avatar

    // ---------------- 解码与缓存 ----------------

    private const val CACHE_FRACTION = 8
    private var cache: LruCache<String, Bitmap>? = null

    @Synchronized
    private fun cache(c: Context): LruCache<String, Bitmap> {
        cache?.let { return it }
        val maxKb = max(1, (Runtime.getRuntime().maxMemory() / 1024 / CACHE_FRACTION).toInt())
        return LruCache<String, Bitmap>(maxKb).also {
            cache = it
            it.put("sz", Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) // 占位不占预算
            it.remove("sz")
        }
    }

    /** 解码资源并按 maxPx 长边降采样（大图一次到位，避免聊天列表反复缩放）。 */
    fun bitmap(c: Context, resId: Int, maxPx: Int): Bitmap {
        val key = "$resId:$maxPx"
        cache(c).get(key)?.let { return it }
        val opts = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeResource(c.resources, resId, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= maxPx && opts.outHeight / (sample * 2) >= maxPx) {
            sample *= 2
        }
        val size = 1
        val decode = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = android.graphics.BitmapFactory.decodeResource(c.resources, resId, decode)
            ?: Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val out = if (bmp.width > maxPx || bmp.height > maxPx) {
            val scale = maxPx.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp,
                (bmp.width * scale + 0.5f).toInt(), (bmp.height * scale + 0.5f).toInt(), true)
        } else bmp
        cache(c).put(key, out)
        return out
    }

    // ---------------- 圆形裁剪 ----------------

    /** 方形圆裁剪（BitmapShader 抗锯齿）：头像统一出口，资源图与用户上传图共用。 */
    fun circle(src: Bitmap, sizePx: Int): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // 源图按「居中裁方 → 填满圆」映射：短边撑满，长边居中
        val side = minOf(src.width, src.height).toFloat()
        val dx = (src.width - side) / 2f
        val dy = (src.height - side) / 2f
        val matrix = android.graphics.Matrix().apply {
            setScale(sizePx / side, sizePx / side)
            postTranslate(-dx * (sizePx / side), -dy * (sizePx / side))
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(matrix)
            }
        }
        val r = sizePx / 2f
        canvas.drawCircle(r, r, r, paint)
        return out
    }

    /** 资源图 → 圆形头像（大肥鱼对话头像/悬浮球入口）。 */
    fun circle(c: Context, resId: Int, sizeDp: Int): Bitmap =
        circle(bitmap(c, resId, Ds.dp(c, sizeDp) * 2), Ds.dp(c, sizeDp) * 2).let {
            // 上采样解码保清晰后缩到目标尺寸（*2 采样 → 1x 输出）
            Bitmap.createScaledBitmap(it, Ds.dp(c, sizeDp), Ds.dp(c, sizeDp), true)
        }
}
