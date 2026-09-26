package com.hachimi.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 主界面骨架（交互原型 S2 的最小化）：状态显示 + 三个入口。
 * M1' 后续：模式切换（前台引导/分屏）、任务输入、悬浮叙述面板。
 */
class MainActivity : android.app.Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        status = TextView(this).apply { setText(R.string.hello_stub) }
        root.addView(status)

        root.addView(button("打开无障碍设置") {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("启动内核前台服务") {
            KernelHostService.start(this)
            refresh()
        })
        root.addView(button("请求屏幕录制授权") {
            // M1' 后续：onActivityResult 存 MediaProjection token 交给观察通道
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            startActivityForResult(pm.createScreenCaptureIntent(), 1001)
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val a11y = HachimiAccessibilityService.instance != null
        status.text = "无障碍通道: ${if (a11y) "已连接" else "未开启"}\n设备: Android ${android.os.Build.VERSION.SDK_INT}"
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply { text = label; setOnClickListener { onClick() } }
}
