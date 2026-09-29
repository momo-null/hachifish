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
 * BYOK 配置存储 v3（红线 R8）：一组端点（规划+视觉共用，模型需兼具两种能力时开
 * 视觉开关）经 Android Keystore AES/GCM 加密后落 App 私有文件（brain_config.enc）。
 * v1/v2（三字段 regex / 六字段 JSON）读入兼容：JSONObject + opt 缺省，无需迁移。
 */
object BrainConfigStore {

    private const val FILE = "brain_config.enc"
    private const val ALIAS = "hachimi_brain_key"
    private const val TAG = "HachimiBrain"

    data class BrainConfig(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val visionEnabled: Boolean = false
    )

    /** 兼容 v1 调用方（DebugBridgeReceiver set_brain）：保留已有视觉开关状态。 */
    fun save(context: Context, baseUrl: String, apiKey: String, model: String): Boolean =
        save(context, BrainConfig(baseUrl, apiKey, model, load(context)?.visionEnabled ?: false))

    fun save(context: Context, cfg: BrainConfig): Boolean {
        return try {
            val plain = JSONObject()
                .put("base_url", cfg.baseUrl)
                .put("api_key", cfg.apiKey)
                .put("model", cfg.model)
                .put("vision_enabled", cfg.visionEnabled)
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
            Log.i(TAG, "brain config saved (model=${cfg.model}, " +
                    "vision=${cfg.visionEnabled}, key encrypted)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "save failed: ${e.message}")
            false
        }
    }

    fun load(context: Context): BrainConfig? {
        return try {
            val file = context.getFileStreamPath(FILE)
            if (!file.exists()) {
                Log.w(TAG, "load: no brain config file")
                return null
            }
            val all = file.readBytes()
            val ivLen = all[0].toInt()
            val iv = all.copyOfRange(1, 1 + ivLen)
            val ct = all.copyOfRange(1 + ivLen, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(context), GCMParameterSpec(128, iv))
            val o = JSONObject(String(cipher.doFinal(ct), Charsets.UTF_8))
            BrainConfig(
                baseUrl = o.optString("base_url"),
                apiKey = o.optString("api_key"),
                model = o.optString("model"),
                visionEnabled = o.optBoolean("vision_enabled")
            )
        } catch (e: Exception) {
            Log.e(TAG, "load failed: ${e.message}")
            null
        }
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
