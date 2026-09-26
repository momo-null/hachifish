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

/**
 * BYOK 大脑配置存储（红线 R8）：base_url/model 明文 + api_key 经 Android Keystore
 * AES/GCM 加密后落 App 私有文件（brain_config.enc），明文 key 不落任何存储。
 * 读取仅限本应用（内核 /task 启动时注入）。
 */
object BrainConfigStore {

    private const val FILE = "brain_config.enc"
    private const val ALIAS = "hachimi_brain_key"
    private const val TAG = "HachimiBrain"

    data class BrainConfig(val baseUrl: String, val apiKey: String, val model: String)

    fun save(context: Context, baseUrl: String, apiKey: String, model: String): Boolean {
        return try {
            val plain = """{"base_url":"$baseUrl","api_key":"$apiKey","model":"$model"}"""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(context))
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            context.openFileOutput(FILE, Context.MODE_PRIVATE).use {
                it.write(iv.size.toByte().toInt())
                it.write(iv)
                it.write(ct)
            }
            Log.i(TAG, "brain config saved (model=$model, key encrypted)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "save failed: ${e.message}")
            false
        }
    }

    fun load(context: Context): BrainConfig? {
        return try {
            val file = context.getFileStreamPath(FILE)
            if (!file.exists()) return null
            val all = file.readBytes()
            val ivLen = all[0].toInt()
            val iv = all.copyOfRange(1, 1 + ivLen)
            val ct = all.copyOfRange(1 + ivLen, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(context), GCMParameterSpec(128, iv))
            val plain = String(cipher.doFinal(ct), Charsets.UTF_8)
            val re = Regex("\"base_url\":\"([^\"]*)\".*\"api_key\":\"([^\"]*)\".*\"model\":\"([^\"]*)\"")
            val m = re.find(plain) ?: return null
            BrainConfig(m.groupValues[1], m.groupValues[2], m.groupValues[3])
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
