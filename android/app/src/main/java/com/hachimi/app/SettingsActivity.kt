package com.hachimi.app

import android.app.Activity
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
}
