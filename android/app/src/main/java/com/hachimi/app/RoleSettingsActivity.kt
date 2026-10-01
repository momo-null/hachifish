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
 * 角色设置页（redesign_plan A5 / V9）：大肥鱼 × 我 双区。
 *
 * - 大肥鱼区：角色名 / 人设提示词（500 字计数；保存 → RoleStore.write →
 *   filesDir/character.md 同步，K0 注入链路下一轮对话即生效）
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

    // 待保存的用户头像（filesDir 相对路径；null = 沿用/默认）
    private var pendingAvatarPath: String? = null

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
        fishCard.addView(label("人设提示词"))
        personaField = EditText(this).apply {
            setText(role.personaPrompt)
            textSize = 12f; setTextColor(Ds.TEXT)
            background = Ds.round(this@RoleSettingsActivity, 10, Ds.PAGE)
            val p = Ds.dp(this@RoleSettingsActivity, 10)
            setPadding(p, p, p, p)
            minLines = 5
            gravity = Gravity.TOP
            setLineSpacing(Ds.dp(this@RoleSettingsActivity, 2).toFloat(), 1f)
            onFocusChangeListener = FocusSave()
        }
        fishCard.addView(personaField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        personaCount = Ds.small(this, "").apply { setPadding(0, Ds.dp(this@RoleSettingsActivity, 4), 0, 0) }
        fishCard.addView(personaCount)
        personaField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                personaCount.text = "已用 ${s?.length ?: 0}/${RoleStore.PERSONA_LIMIT} 字 · 作为 system prompt 注入每轮对话"
                personaCount.setTextColor(
                    if ((s?.length ?: 0) > RoleStore.PERSONA_LIMIT) Ds.RED_TXT else Ds.TEXT_2)
            }
        })
        fishCard.addView(Ds.button(this, "恢复默认人设", primary = false) {
            personaField.setText(RoleStore.DEFAULT_PERSONA)
            persist()
        }, Ds.vp(top = Ds.dp(this, 8)))
        box.addView(fishCard)

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

    /** 读当前 UI 状态持久化（成功静默，失败提示；character.md 同步由 RoleStore 负责）。 */
    private fun persist() {
        val ok = RoleStore.write(this, RoleStore.read(this).copy(
            roleName = roleNameField.text.toString().trim().ifEmpty { "大肥鱼" },
            userName = userNameField.text.toString().trim().ifEmpty { "主人" },
            userAvatarPath = pendingAvatarPath,
            personaPrompt = personaField.text.toString()
        ))
        if (!ok) Toast.makeText(this, "保存失败（写入异常），请重试", Toast.LENGTH_SHORT).show()
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
