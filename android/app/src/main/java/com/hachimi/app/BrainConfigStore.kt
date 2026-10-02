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
 * BYOK 配置存储 v4（红线 R8）：规划/视觉双端点各自独立三栏（mockup S1/S7 形态），
 * 经 Android Keystore AES/GCM 加密后落 App 私有文件（brain_config.enc）。
 * 视觉端点留空 = 视觉能力关闭（观察兜底退 P0，DoD 语义不变）。
 * 兼容读：v1/v2（regex/六字段 JSON）、v3（单端点 + vision_enabled 开关——开关为真时
 * 视觉字段继承规划端点，即"共用端点"实测形态），无需迁移。
 */
object BrainConfigStore {

    private const val FILE = "brain_config.enc"
    private const val ALIAS = "hachimi_brain_key"
    private const val TAG = "HachimiBrain"

    data class BrainConfig(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val visionBaseUrl: String = "",
        val visionApiKey: String = "",
        val visionModel: String = ""
    ) {
        /** 视觉能力开关 = 视觉端点三栏齐全（v3 vision_enabled 的 v4 语义）。 */
        val visionEnabled: Boolean
            get() = visionBaseUrl.isNotBlank() && visionModel.isNotBlank()
    }

    /** 兼容 v1 调用方（DebugBridgeReceiver set_brain）：视觉字段沿用已存状态。 */
    fun save(context: Context, baseUrl: String, apiKey: String, model: String): Boolean =
        load(context)?.let { save(context, it.copy(baseUrl = baseUrl, apiKey = apiKey, model = model)) }
            ?: save(context, BrainConfig(baseUrl, apiKey, model))

    fun save(context: Context, cfg: BrainConfig): Boolean {
        return try {
            val plain = JSONObject()
                .put("base_url", cfg.baseUrl)
                .put("api_key", cfg.apiKey)
                .put("model", cfg.model)
                // vision_enabled 保留写出：旧版本代码/研究脚本读此开关
                .put("vision_enabled", cfg.visionEnabled)
                .put("vision_base_url", cfg.visionBaseUrl)
                .put("vision_api_key", cfg.visionApiKey)
                .put("vision_model", cfg.visionModel)
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
                    "vision=${if (cfg.visionEnabled) cfg.visionModel else "off"}, key encrypted)")
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
            val baseUrl = o.optString("base_url")
            val apiKey = o.optString("api_key")
            // v3 兼容：无 vision_* 字段且 vision_enabled=true → 视觉=规划端点（共用形态）
            var vUrl = o.optString("vision_base_url")
            var vKey = o.optString("vision_api_key")
            var vModel = o.optString("vision_model")
            if (vUrl.isEmpty() && vModel.isEmpty() && o.optBoolean("vision_enabled")) {
                vUrl = baseUrl; vKey = apiKey; vModel = o.optString("model")
            }
            BrainConfig(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = o.optString("model"),
                visionBaseUrl = vUrl,
                visionApiKey = vKey,
                visionModel = vModel
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
