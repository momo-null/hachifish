package com.hachimi.app

import android.content.Context
import java.io.File

/**
 * character.md 文档组装纯函数（A2 可测层，无 Android 依赖）：
 * 人设正文 + 称呼追加段，供 JVM 单测直接覆盖。
 *
 * 约定（与 K0 kernel 注入 + V9 设计对齐）：
 * - persona 截 500 字（PERSONA_LIMIT）；
 * - persona 未含「称呼」字样时补一行「称呼用户为「X」。」（双保险，防用户自写
 *   人设时漏掉称呼导致人设与称呼脱节）；
 * - 产出为 null 时调用方零写入（保持 kernel 缺省零注入语义）。
 */
object RoleDoc {

    fun compose(personaPrompt: String, userName: String, limit: Int = RoleStore.PERSONA_LIMIT): String {
        val persona = personaPrompt.trim().take(limit)
        if (persona.isEmpty()) return ""
        return if (!persona.contains("称呼")) {
            "$persona\n\n称呼用户为「${userName.trim().ifEmpty { "主人" }}」。"
        } else persona
    }

    /** 内容 hash 变化检测（幂等写盘判据，纯函数便于测试）。 */
    fun fingerprint(content: String): String = content.hashCode().toString()

    /** 是否需要写盘：hash 变化 或 文件缺失。 */
    fun needsWrite(storedHash: String?, content: String, fileExists: Boolean): Boolean =
        storedHash != fingerprint(content) || !fileExists
}

/**
 * 角色配置单一来源（redesign_plan A2）：大肥鱼 × 我 双侧四元组 + character.md 同步。
 *
 * - SharedPreferences "hachimi_role"（沿用项目持久化惯例，不引 DataStore）。
 * - [flushCharacterMd] 把 人设提示词 + 称呼 写入 filesDir/character.md ——
 *   与 kernel `runtime_paths.character_card()` 同一路径（HACHIMI_DATA_DIR=filesDir），
 *   K0 接线后随 system prompt 注入，**前端改配置 → kernel 零改动生效**。
 * - 文档组装逻辑抽到 [RoleDoc]（纯函数，JVM 可测；本类只做 IO 编排）。
 * - 人设 500 字上限（对齐 V9 设计；kernel 侧另有 20000 字符硬截断兜底）。
 */
object RoleStore {

    private const val PREFS = "hachimi_role"
    private const val KEY_ROLE_NAME = "role_name"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_AVATAR = "user_avatar_path"
    private const val KEY_PERSONA = "persona_prompt"
    private const val KEY_MD_HASH = "character_md_hash"

    const val PERSONA_LIMIT = 500

    /** 默认人设（V9 设计稿口径：勤快自嘲、适度卖萌、不承诺做不到的事）。 */
    val DEFAULT_PERSONA =
        "你是大肥鱼，一只蓝色的鲸鱼娘，用户的全能手机小帮手。性格：勤快但爱自嘲，" +
        "偶尔委屈，办成事会得意。说话风格：口语化、简短、爱用「主人」称呼用户，" +
        "可以适度卖萌。不要承诺做不到的事。闲聊或提问时直接文字回答；" +
        "需要操作手机时调用工具；全部做完才调用 task_done。"

    data class Role(
        val roleName: String,
        val userName: String,
        /** filesDir 相对路径；null = 未上传（头像取 userName 首字）。 */
        val userAvatarPath: String?,
        val personaPrompt: String
    ) {
        /** 用户头像占位字：称呼首字（未配置时兜底「你」）。 */
        val userInitial: String get() = userName.trim().firstOrNull()?.toString() ?: "你"
    }

    fun read(c: Context): Role {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Role(
            roleName = p.getString(KEY_ROLE_NAME, "大肥鱼") ?: "大肥鱼",
            userName = p.getString(KEY_USER_NAME, "主人") ?: "主人",
            userAvatarPath = p.getString(KEY_USER_AVATAR, null),
            personaPrompt = p.getString(KEY_PERSONA, DEFAULT_PERSONA) ?: DEFAULT_PERSONA
        )
    }

    /** 保存角色配置并同步 character.md；返回是否成功落盘（失败不阻断 UI）。 */
    fun write(c: Context, role: Role): Boolean {
        val persona = role.personaPrompt.take(PERSONA_LIMIT)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ROLE_NAME, role.roleName.trim().ifEmpty { "大肥鱼" })
            .putString(KEY_USER_NAME, role.userName.trim().ifEmpty { "主人" })
            .putString(KEY_USER_AVATAR, role.userAvatarPath)
            .putString(KEY_PERSONA, persona)
            .apply()
        return flushCharacterMd(c)
    }

    /** 启动幂等同步（MainActivity onCreate 调）：盘上文件缺失/漂移自动修复。 */
    fun ensure(c: Context) {
        flushCharacterMd(c)
    }

    /** 用户头像文件绝对路径（上传裁剪后写 PNG；null = 未上传）。 */
    fun userAvatarFile(c: Context, relative: String?): File? =
        relative?.let { File(c.filesDir, it) }?.takeIf { it.isFile }

    // ---------------- character.md 同步 ----------------

    /**
     * 组装并写入 filesDir/character.md（kernel 注入路径，K0）。
     * 组装/幂等判据在 [RoleDoc]（纯函数，单测覆盖）；本方法只做 IO。
     */
    private fun flushCharacterMd(c: Context): Boolean {
        val role = read(c)
        val content = RoleDoc.compose(role.personaPrompt, role.userName)
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = File(c.filesDir, "character.md")
        if (!RoleDoc.needsWrite(p.getString(KEY_MD_HASH, null), content, file.isFile)) {
            return true   // 内容未变且文件在：跳过写盘
        }
        return try {
            file.writeText(content, Charsets.UTF_8)
            p.edit().putString(KEY_MD_HASH, RoleDoc.fingerprint(content)).apply()
            true
        } catch (e: Exception) {
            android.util.Log.w("RoleStore", "character.md write: ${e.message}")
            false
        }
    }
}
