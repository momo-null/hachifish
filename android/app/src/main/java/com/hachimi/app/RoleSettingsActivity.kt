package com.hachimi.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hachimi.app.ui.Ds
import com.hachimi.app.ui.FishAssets
import java.io.File

/**
 * 角色设置页（redesign_plan A5 / V9；2026-11-04 整合后 = 人设配置**唯一**入口）：
 *
 * - 大肥鱼区：角色名 / 人设提示词（**直读写内核 character.md**，GET/PUT
 *   /api/runtime/character——原 RoleStore personaPrompt 写 filesDir/character.md
 *   是 mini_loop 时代死链，内核读 .omniagent/character.md，随 system prompt 注入）/
 *   System Prompt 另外两层编辑入口（用户画像 user_profile.md · 纪律 AGENTS.md）
 * - 我区：头像（相册选择 → 中心裁方 → 圆形 → 256px PNG 落 filesDir）/
 *   对我的称呼（未设头像时取首字作占位）
 * - 拍照上传后置（需要 FileProvider 权限面，真机验收阶段接）
 */
class RoleSettingsActivity : Activity() {

    private lateinit var roleNameField: EditText
    private lateinit var personaField: EditText
    private lateinit var personaCount: TextView
    private lateinit var userNameField: EditText
    private var avatarPreview: ImageView? = null

    /** 内核 character.md 当前值（延迟加载；失焦保存以它是否就绪为准）。 */
    @Volatile private var kernelCharacter: String? = null
    private var charRetries = 0

    // 待保存的用户头像（filesDir 相对路径；null = 沿用/默认）
    private var pendingAvatarPath: String? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** 默认人设（V9 设计稿口径：勤快自嘲、适度卖萌、不承诺做不到的事）——
     *  内核 character.md 为空时播种一次，不覆盖用户手写内容。 */
    private val DEFAULT_PERSONA =
        "你是大肥鱼，一只蓝色的鲸鱼娘，用户的全能手机小帮手。性格：勤快但爱自嘲，" +
        "偶尔委屈，办成事会得意。说话风格：口语化、简短、爱用「主人」称呼用户，" +
        "可以适度卖萌。不要承诺做不到的事。闲聊或提问时直接文字回答；" +
        "需要操作手机时调用工具；全部做完才调用 task_done。"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val role = RoleStore.read(this)
        pendingAvatarPath = role.userAvatarPath

        val pad = Ds.dp(this, 20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ds.PAGE)
            setPadding(pad, Ds.dp(this@RoleSettingsActivity, 12), pad, Ds.dp(this@RoleSettingsActivity, 24))
        }

        // 返回行 + 标题
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "‹ 设置"; textSize = 15f; setTextColor(Ds.PRIMARY)
            // 返回前兜底持久化：最后一个字段可能仍持焦（失焦保存不触发）
            setOnClickListener { persist(); finish() }
        })
        head.addView(TextView(this).apply {
            text = "角色设置"; textSize = 17f; setTextColor(Ds.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // 右侧占位（无「保存」按钮，标题保持居中）
        head.addView(View(this), LinearLayout.LayoutParams(Ds.dp(this, 44), 1))
        box.addView(head)
        box.addView(Ds.small(this, "大肥鱼 × 我 —— 改动失焦即存，即时生效").apply {
            setPadding(0, Ds.dp(this@RoleSettingsActivity, 4), 0, Ds.dp(this@RoleSettingsActivity, 12))
        })

        // —— 大肥鱼区 ——
        box.addView(sectionTitle("大肥鱼"))
        val fishCard = Ds.card(this)
        // 头像行：鱼头像 + 角色名
        val avatarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        avatarRow.addView(ImageView(this).apply {
            setImageBitmap(FishAssets.circle(this@RoleSettingsActivity, FishAssets.AVATAR, 56))
        }, LinearLayout.LayoutParams(Ds.dp(this, 56), Ds.dp(this, 56)))
        avatarRow.addView(TextView(this).apply {
            text = "默认女仆装形象（更多形象敬请期待）"
            textSize = 12f; setTextColor(Ds.TEXT_2)
            val p = Ds.dp(this@RoleSettingsActivity, 12)
            setPadding(p, 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fishCard.addView(avatarRow)
        fishCard.addView(label("角色名"))
        roleNameField = field(fishCard, role.roleName, "给它起个名字").apply {
            onFocusChangeListener = FocusSave()
        }
        fishCard.addView(Ds.small(this, "显示在聊天头部和消息旁").apply {
            setPadding(0, 0, 0, Ds.dp(this@RoleSettingsActivity, 10))
        })
        fishCard.addView(label("人设提示词（内核 character.md）"))
        personaField = EditText(this).apply {
            setText("")
            hint = "内核就绪后自动载入；改完失焦即存"
            textSize = 12f; setTextColor(Ds.TEXT)
            background = Ds.round(this@RoleSettingsActivity, 10, Ds.PAGE)
            val p = Ds.dp(this@RoleSettingsActivity, 10)
            setPadding(p, p, p, p)
            minLines = 5
            gravity = Gravity.TOP
            setLineSpacing(Ds.dp(this@RoleSettingsActivity, 2).toFloat(), 1f)
            setOnFocusChangeListener { _, has -> if (!has) saveCharacter() }
        }
        fishCard.addView(personaField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        personaCount = Ds.small(this, "").apply { setPadding(0, Ds.dp(this@RoleSettingsActivity, 4), 0, 0) }
        fishCard.addView(personaCount)
        personaField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                personaCount.text = "已用 ${s?.length ?: 0} 字 · 随 system prompt 注入，下一轮对话生效"
                personaCount.setTextColor(Ds.TEXT_2)
            }
        })
        fishCard.addView(Ds.button(this, "恢复默认人设", primary = false) {
            personaField.setText(DEFAULT_PERSONA)
            saveCharacter()
        }, Ds.vp(top = Ds.dp(this, 8)))
        box.addView(fishCard)

        // —— System Prompt（内核层：角色卡在上方，另两层在此编辑） ——
        box.addView(sectionTitle("System Prompt", topPad = Ds.dp(this, 16)))
        val spCard = Ds.card(this)
        spCard.addView(Ds.small(this, "system prompt = 角色卡（上方人设提示词）+ 用户画像 + 纪律")
            .apply { setPadding(0, 0, 0, Ds.dp(this@RoleSettingsActivity, 6)) })
        spCard.addView(promptRow("用户画像", "user_profile.md · 它对你的了解（记录层）"))
        spCard.addView(Ds.divider(this@RoleSettingsActivity))
        spCard.addView(promptRow("纪律（AGENTS.md）", "system prompt 的用户纪律层全文"))
        box.addView(spCard)

        // —— 我区 ——
        box.addView(sectionTitle("我", topPad = Ds.dp(this, 16)))
        val meCard = Ds.card(this)
        val avatarRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        avatarPreview = ImageView(this).apply {
            setImageBitmap(userAvatarBitmap(role))
        }
        avatarRow2.addView(avatarPreview, LinearLayout.LayoutParams(Ds.dp(this, 56), Ds.dp(this, 56)))
        val btnCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pickRow.addView(Ds.button(this@RoleSettingsActivity, "从相册选择", primary = false) {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
            }, REQ_PICK)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pickRow.addView(Ds.button(this@RoleSettingsActivity, "恢复默认", primary = false) {
            pendingAvatarPath = null
            avatarPreview?.setImageBitmap(userAvatarBitmap(RoleStore.read(this@RoleSettingsActivity)))
            persist()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            // 显式 LP 里设 leftMargin：不能在对 button 的 apply 里改 layoutParams——
            // 那会在 addView 前访问 null LP → `as LinearLayout.LayoutParams` NPE。
            leftMargin = Ds.dp(this@RoleSettingsActivity, 8)
        })
        btnCol.addView(pickRow)
        btnCol.addView(Ds.small(this, "自动裁剪为圆形 · 仅存本机（filesDir），不上传").apply {
            setPadding(0, Ds.dp(this@RoleSettingsActivity, 4), 0, 0)
        })
        avatarRow2.addView(btnCol, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = Ds.dp(this@RoleSettingsActivity, 12)
        })
        meCard.addView(avatarRow2)
        meCard.addView(label("对我的称呼"))
        userNameField = field(meCard, role.userName, "例如：主人 / 老板 / 小明").apply {
            onFocusChangeListener = FocusSave()
        }
        meCard.addView(Ds.small(this, "大肥鱼会这样称呼你；未设头像时取首字作头像").apply {
            setPadding(0, 0, 0, 0)
        })
        box.addView(meCard)

        setContentView(ScrollView(this).apply { addView(box) })
        Ds.systemBars(this, box)
        loadKernelCharacter()   // 内核就绪后自动载入人设（空文件播种默认人设）
    }

    // ---------------- 头像 ----------------

    /** 用户头像位图：上传图 > 称呼首字（默认「主」）。 */
    private fun userAvatarBitmap(role: RoleStore.Role): Bitmap {
        RoleStore.userAvatarFile(this, role.userAvatarPath)?.let { f ->
            val raw = BitmapFactory.decodeFile(f.absolutePath)
            if (raw != null) return FishAssets.circle(raw, Ds.dp(this, 56))
        }
        val size = Ds.dp(this, 56)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(Ds.PRIMARY)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = size * 0.44f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(role.userInitial, size / 2f, y, paint)
        return out
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data
        try {
            // 两步采样解码：先读 bounds 算 inSampleSize（目标长边 ~512px），再二次开流解码。
            // 相册原图常 3000-4000px，全尺寸直解单张 48MB+ 极易 OOM（2026-10-01 头像不生效根因之一）。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri!!)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
            val src = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return
            // 中心裁方 → 圆形 → 256px → filesDir/user_avatar.png → 立即持久化
            val circle = FishAssets.circle(src, 256)
            val f = File(filesDir, "user_avatar.png")
            f.outputStream().use { circle.compress(Bitmap.CompressFormat.PNG, 90, it) }
            pendingAvatarPath = f.name
            avatarPreview?.setImageBitmap(FishAssets.circle(circle, Ds.dp(this, 56)))
            persist()
            Toast.makeText(this, "头像已更新", Toast.LENGTH_SHORT).show()
            src.recycle()
        } catch (e: Exception) {
            Toast.makeText(this, "图片处理失败：${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- 保存（失焦即存，无「保存」按钮） ----------------

    /** 三个输入框共用的失焦保存监听：焦点离开即持久化当前全部字段。 */
    private inner class FocusSave : View.OnFocusChangeListener {
        override fun onFocusChange(v: View, hasFocus: Boolean) {
            if (!hasFocus) persist()
        }
    }

    /** 读当前 UI 状态持久化（成功静默，失败提示；人设提示词走 saveCharacter 直写内核）。 */
    private fun persist() {
        val ok = RoleStore.write(this, RoleStore.read(this).copy(
            roleName = roleNameField.text.toString().trim().ifEmpty { "大肥鱼" },
            userName = userNameField.text.toString().trim().ifEmpty { "主人" },
            userAvatarPath = pendingAvatarPath
        ))
        if (!ok) Toast.makeText(this, "保存失败（写入异常），请重试", Toast.LENGTH_SHORT).show()
    }

    // ---------------- 人设提示词（内核 character.md 直读写） ----------------

    /**
     * 载入内核 character.md（kernel 冷启动 ~15s，失败退避重试）。空文件 = 尚无人设
     * ——播种 V9 默认人设（幂等：只在空时种，不覆盖用户手写内容）。
     */
    private fun loadKernelCharacter() {
        Thread {
            val cur = runCatching {
                OmniAgentRemote.getJson(com.hachimi.app.ui.ChatPage.OMNI_HOST_BASE, "",
                    "/api/runtime/character", 12000).optString("character")
            }.getOrNull()
            if (cur == null) {
                if (charRetries++ < 10) {
                    main.postDelayed({ loadKernelCharacter() }, 2000)
                } else {
                    main.post { personaCount.text = "内核未就绪——稍后重进本页再试" }
                }
                return@Thread
            }
            var text = cur
            if (text.isBlank()) text = DEFAULT_PERSONA   // 空文件 = 尚无人设，播种（幂等）
            kernelCharacter = text
            main.post {
                if (personaField.text.isBlank()) personaField.setText(text)
                personaCount.text = "已用 ${personaField.text.length} 字 · 随 system prompt 注入，下一轮对话生效"
            }
            if (text != cur) putCharacter(text)
        }.start()
    }

    /** 人设文本 → PUT 内核（失焦传 null 取当前字段；播种传已知文本绕开 UI 竞态）。 */
    private fun putCharacter(text: String) {
        val ok = runCatching {
            OmniAgentRemote.putJson(com.hachimi.app.ui.ChatPage.OMNI_HOST_BASE, "",
                "/api/runtime/character",
                org.json.JSONObject().put("character", text))
        }.getOrDefault(org.json.JSONObject()).optBoolean("ok", false)
        if (ok) kernelCharacter = text
        main.post {
            Toast.makeText(this,
                if (ok) "人设已保存（下一轮对话生效）" else "人设保存失败（内核未就绪？）",
                Toast.LENGTH_SHORT).show()
        }
    }

    /** 失焦保存：字段与内核已知值不同才写盘。 */
    private fun saveCharacter() {
        val text = personaField.text.toString()
        if (text == kernelCharacter) return   // 未改动不写盘
        putCharacter(text)
    }

    // ---------------- System Prompt 另两层（画像/纪律编辑入口） ----------------

    private fun promptRow(title: String, desc: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ds.dp(this@RoleSettingsActivity, 8), 0, Ds.dp(this@RoleSettingsActivity, 8))
            setOnClickListener { promptEditor(title, desc) }
        }.also { row ->
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = title; textSize = 14f; setTextColor(Ds.TEXT)
            })
            col.addView(Ds.small(this, desc).apply { setPadding(0, Ds.dp(this@RoleSettingsActivity, 1), 0, 0) })
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "编辑"; textSize = 13f; setTextColor(Ds.PRIMARY)
                setPadding(Ds.dp(this@RoleSettingsActivity, 10), 0, 0, 0)
            })
        }

    /** 内核层全文编辑弹窗（原 SettingsPage.promptEditor 迁入：人设配置单入口）。 */
    private fun promptEditor(title: String, desc: String) {
        val path = when {
            title.contains("画像") -> "/api/runtime/profile" to "profile"
            else -> "/api/runtime/instructions" to "content"
        }
        Thread {
            val cur = runCatching {
                OmniAgentRemote.getJson(com.hachimi.app.ui.ChatPage.OMNI_HOST_BASE, "",
                    path.first, 12000).optString(path.second)
            }.getOrElse { "" }
            main.post {
                val edit = EditText(this).apply {
                    setText(cur); textSize = 12f
                    gravity = Gravity.TOP
                    minLines = 8; maxLines = 22
                    setHorizontallyScrolling(false)
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle("$title（全文）")
                    .setView(ScrollView(this).apply { addView(edit) })
                    .setPositiveButton("保存") { _, _ ->
                        val text = edit.text.toString()
                        Thread {
                            val ok = runCatching {
                                OmniAgentRemote.putJson(com.hachimi.app.ui.ChatPage.OMNI_HOST_BASE,
                                    "", path.first, org.json.JSONObject().put(path.second, text))
                            }.getOrDefault(org.json.JSONObject()).optBoolean("ok", false)
                            main.post {
                                Toast.makeText(this,
                                    if (ok) "已保存（下一轮对话生效）" else "保存失败（内核未就绪？）",
                                    Toast.LENGTH_SHORT).show()
                            }
                        }.start()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }.start()
    }

    // ---------------- 小部件 ----------------

    private fun sectionTitle(text: String, topPad: Int = 0): View = Ds.h2(this, text).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, topPad, 0, Ds.dp(this@RoleSettingsActivity, 6)) }
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(Ds.TEXT_2)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, Ds.dp(this@RoleSettingsActivity, 10), 0, Ds.dp(this@RoleSettingsActivity, 4))
    }

    private fun field(host: LinearLayout, value: String, hint: String): EditText =
        Ds.field(this, hint).apply {
            setText(value)
            host.addView(this)
        }

    companion object {
        private const val REQ_PICK = 2001
    }
}
