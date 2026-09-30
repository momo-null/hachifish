package com.hachimi.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.hachimi.app.ui.Ds
import com.hachimi.app.ui.SettingsPage

/**
 * 设置页薄壳：真实 UI 在 ui/SettingsPage（S7 终态四分组，含 BYOK 双端点）。
 * 保留独立 Activity 入口（Onboarding BYOK 步 / 主界面环境卡跳转；主界面底部
 * 导航「设置」Tab 内嵌同一 SettingsPage 的独立实例）。
 */
class SettingsActivity : Activity() {

    private var page: SettingsPage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = SettingsPage(this)
        val v = page!!.view
        Ds.systemBars(this, v, extraBottomDp = 8)
        setContentView(v)
    }

    override fun onResume() {
        super.onResume()
        page?.onResume()
    }

    /** 屏幕录制授权入口（行为段「屏幕录制」行点击调用）。 */
    fun requestProjection() {
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) {
            KernelHostService.startProjection(this, resultCode, data)
        }
    }

    companion object {
        private const val REQ_PROJECTION = 2002
    }
}
