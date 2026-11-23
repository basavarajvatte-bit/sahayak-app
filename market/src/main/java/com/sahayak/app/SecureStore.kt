package com.sahayak.app

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Gemini key ko Android Keystore se encrypt karke rakhta hai. */
class SecureStore(private val prefs: SharedPreferences, private val sealedName: String = SEALED) {
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun save(value: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray())
        prefs.edit().putString(sealedName, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply { if (sealedName == SEALED) remove(OLD_PLAIN) }.apply()
    }

    fun load(): String {
        if (sealedName == SEALED) {
            prefs.getString(OLD_PLAIN, null)?.let {
                save(it)
                return it
            }
        }
        val sealed = prefs.getString(sealedName, null) ?: return ""
        return try {
            val bytes = Base64.decode(sealed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE))
        } catch (e: GeneralSecurityException) {
            ""
        } catch (e: IllegalArgumentException) {
            ""
        }
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "sahayak_gemini"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val SEALED = "gemini_key_sealed"
        private const val OLD_PLAIN = "gemini_key"
        private const val IV_SIZE = 12
    }
}
