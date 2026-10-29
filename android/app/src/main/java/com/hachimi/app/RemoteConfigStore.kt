package com.hachimi.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * 远程内核（OmniAgent）配置存储：base_url + token + 自动切换开关。
 *
 * 存储模式与 [BrainConfigStore] 同款（红线 R8）：Android Keystore AES/GCM 加密后落
 * App 私有文件（remote_config.enc），token 明文不落库。
 * 未配置（null）= 远程表面关闭，ChatPage 全走本地内核（Tier 1）。
 */
object RemoteConfigStore {

    private const val FILE = "remote_config.enc"
    private const val ALIAS = "hachimi_remote_key"
    private const val TAG = "HachimiRemote"

    data class RemoteConfig(
        val baseUrl: String,
        val token: String,
        /** 自动切换（C3）：每轮 /health 探活（1s），可达走远程，不可达回本地内核。 */
        val auto: Boolean = true,
        /** 回复语音播报（C1 对讲式）：结论气泡 TTS 朗读；按住 🎤 即打断。 */
        val speak: Boolean = true
    ) {
        val ready: Boolean get() = baseUrl.startsWith("http") && token.isNotBlank()
    }

    fun save(context: Context, cfg: RemoteConfig): Boolean = try {
        val plain = JSONObject()
            .put("base_url", cfg.baseUrl.trimEnd('/'))
            .put("token", cfg.token)
            .put("auto", cfg.auto)
            .put("speak", cfg.speak)
            .toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(context))
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        context.openFileOutput(FILE, Context.MODE_PRIVATE).use {
            it.write(iv.size.toByte().toInt())
            it.write(iv)
            it.write(ct)
        }
        Log.i(TAG, "remote config saved (base=${cfg.baseUrl}, auto=${cfg.auto})")
        true
    } catch (e: Exception) {
        Log.e(TAG, "save failed: ${e.message}")
        false
    }

    fun load(context: Context): RemoteConfig? = try {
        val file = context.getFileStreamPath(FILE)
        if (!file.exists()) null
        else {
            val all = file.readBytes()
            val ivLen = all[0].toInt()
            val iv = all.copyOfRange(1, 1 + ivLen)
            val ct = all.copyOfRange(1 + ivLen, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(context), GCMParameterSpec(128, iv))
            val o = JSONObject(String(cipher.doFinal(ct), Charsets.UTF_8))
            RemoteConfig(
                baseUrl = o.optString("base_url"),
                token = o.optString("token"),
                auto = o.optBoolean("auto", true),
                speak = o.optBoolean("speak", true)
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "load failed: ${e.message}")
        null
    }

    private fun key(context: Context): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }
}
