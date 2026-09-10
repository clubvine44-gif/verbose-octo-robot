package ru.mayak.client

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    fun get(): String {
        val encoded = prefs.getString(KEY_TOKEN, null) ?: return ""
        return try {
            val data = Base64.decode(encoded, Base64.DEFAULT)
            val buffer = ByteBuffer.wrap(data)
            val iv = ByteArray(buffer.getInt())
            buffer.get(iv)
            val ciphertext = ByteArray(buffer.remaining())
            buffer.get(ciphertext)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun put(token: String) {
        if (token.isBlank()) {
            prefs.edit().remove(KEY_TOKEN).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val buffer = ByteBuffer.allocate(4 + iv.size + ciphertext.size)
        buffer.putInt(iv.size)
        buffer.put(iv)
        buffer.put(ciphertext)
        prefs.edit().putString(KEY_TOKEN, Base64.encodeToString(buffer.array(), Base64.NO_WRAP)).apply()
    }

    private fun getKey(): SecretKey {
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance("AES", ANDROID_KEY_STORE)
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "mayak.relay.token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS = "mayak_secure"
        private const val KEY_TOKEN = "relay_token"
    }
}
