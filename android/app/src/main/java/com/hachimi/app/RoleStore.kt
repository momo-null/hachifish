package com.hachimi.app

import android.content.Context
import java.io.File

/**
 * 角色显示配置（大肥鱼名字 / 对用户的称呼 / 用户头像）。
 *
 * 人设与 System prompt 不在这里：角色设置页直读写内核层（character.md /
 * user_profile.md / AGENTS.md 经 /api/runtime 接口），单一配置处、内核零改动生效。
 * （2026-11-04 整合：原 personaPrompt + filesDir/character.md 同步链随 mini_loop
 * 退役成死配置——内核读的是 .omniagent/character.md，两条路径从未对上——整段删除。）
 */
object RoleStore {

    private const val PREFS = "hachimi_role"
    private const val KEY_ROLE_NAME = "role_name"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_AVATAR = "user_avatar_path"

    data class Role(
        val roleName: String,
        val userName: String,
        /** filesDir 相对路径；null = 未上传（头像取 userName 首字）。 */
        val userAvatarPath: String?
    ) {
        /** 用户头像占位字：称呼首字（未配置时兜底「你」）。 */
        val userInitial: String get() = userName.trim().firstOrNull()?.toString() ?: "你"
    }

    fun read(c: Context): Role {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Role(
            roleName = p.getString(KEY_ROLE_NAME, "大肥鱼") ?: "大肥鱼",
            userName = p.getString(KEY_USER_NAME, "主人") ?: "主人",
            userAvatarPath = p.getString(KEY_USER_AVATAR, null)
        )
    }

    /** 保存角色显示配置；返回是否成功落盘（失败不阻断 UI）。 */
    fun write(c: Context, role: Role): Boolean {
        return try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ROLE_NAME, role.roleName.trim().ifEmpty { "大肥鱼" })
                .putString(KEY_USER_NAME, role.userName.trim().ifEmpty { "主人" })
                .putString(KEY_USER_AVATAR, role.userAvatarPath)
                .apply()
            true
        } catch (e: Exception) {
            android.util.Log.w("RoleStore", "write: ${e.message}")
            false
        }
    }

    /** 用户头像文件绝对路径（上传裁剪后写 PNG；null = 未上传）。 */
    fun userAvatarFile(c: Context, relative: String?): File? =
        relative?.let { File(c.filesDir, it) }?.takeIf { it.isFile }
}
