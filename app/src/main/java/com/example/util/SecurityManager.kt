package com.example.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object SecurityManager {

    private const val FIELD_KEY_ALIAS = "cartino_field_master_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private fun getOrCreateSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(FIELD_KEY_ALIAS)) {
                val entry = keyStore.getEntry(FIELD_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                if (entry != null) {
                    return entry.secretKey
                } else {
                    keyStore.deleteEntry(FIELD_KEY_ALIAS)
                }
            }
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                FIELD_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
            val entry = keyStore.getEntry(FIELD_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            entry?.secretKey
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Encrypts sensitive fields (cardNumber, cvv2, iban, nationalCode, documentNumber) using Android KeyStore AES-256 GCM.
     */
    fun encryptField(plainText: String): String {
        if (plainText.isBlank() || plainText.startsWith("ENC:")) return plainText
        return try {
            val secretKey = getOrCreateSecretKey() ?: return plainText
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
            "ENC:" + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Throwable) {
            plainText
        }
    }

    /**
     * Decrypts sensitive fields encrypted with Android KeyStore AES-256 GCM.
     */
    fun decryptField(encryptedText: String): String {
        if (encryptedText.isBlank() || !encryptedText.startsWith("ENC:")) return encryptedText
        return try {
            val base64Part = encryptedText.removePrefix("ENC:")
            val combined = Base64.decode(base64Part, Base64.NO_WRAP)
            if (combined.size < 12) return encryptedText

            val iv = combined.copyOfRange(0, 12)
            val cipherText = combined.copyOfRange(12, combined.size)

            val secretKey = getOrCreateSecretKey() ?: return encryptedText
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(cipherText)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Throwable) {
            encryptedText
        }
    }

    /**
     * Enables or disables FLAG_SECURE to prevent screenshots and screen recording on sensitive screens.
     */
    fun setScreenProtection(activity: Activity, enable: Boolean) {
        activity.runOnUiThread {
            if (enable) {
                activity.window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE
                )
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    /**
     * Checks if biometric authentication is available on the device.
     */
    fun isBiometricAvailable(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * Shows standard Biometric Prompt for login / access control.
     */
    fun authenticateBiometric(
        activity: FragmentActivity,
        title: String = "ورود به کارتینو",
        subtitle: String = "برای دسترسی به اطلاعات کارت‌ها اثر انگشت یا چهره خود را اسکن کنید",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("انصراف")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        val biometricPrompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onError(errString.toString())
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onError("احراز هویت بیومتریک ناموفق بود")
            }
        })

        biometricPrompt.authenticate(promptInfo)
    }

    /**
     * AES-256 GCM Encryption using a master key derived via PBKDF2 with GZIP payload compression.
     */
    fun encryptData(plainText: String, password: CharArray): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, 65536, 256)
        val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv

        // Compress string using GZIP before encryption to achieve ultra-compact payload
        val rawBytes = plainText.toByteArray(Charsets.UTF_8)
        val byteStream = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(byteStream).use { gz ->
            gz.write(rawBytes)
        }
        val compressedBytes = byteStream.toByteArray()

        val encryptedBytes = cipher.doFinal(compressedBytes)

        // Bundle salt + iv + ciphertext
        val combined = ByteArray(salt.size + iv.size + encryptedBytes.size)
        System.arraycopy(salt, 0, combined, 0, salt.size)
        System.arraycopy(iv, 0, combined, salt.size, iv.size)
        System.arraycopy(encryptedBytes, 0, combined, salt.size + iv.size, encryptedBytes.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * AES-256 GCM Decryption with automatic GZIP decompression support.
     */
    fun decryptData(encryptedBase64: String, password: CharArray): String {
        val cleanInput = encryptedBase64.trim().replace("\n", "").replace("\r", "")
        val combined = Base64.decode(cleanInput, Base64.NO_WRAP)
        if (combined.size < 28) throw IllegalArgumentException("فرمت فایل رمزنگاری شده معتبر نیست")

        val salt = combined.copyOfRange(0, 16)
        val iv = combined.copyOfRange(16, 28)
        val ciphertext = combined.copyOfRange(28, combined.size)

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, 65536, 256)
        val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val decryptedBytes = cipher.doFinal(ciphertext)

        // Try decompressing GZIP if present, otherwise fallback to UTF-8 string for legacy uncompressed backups
        return try {
            if (decryptedBytes.size >= 2 && decryptedBytes[0] == 0x1f.toByte() && decryptedBytes[1] == 0x8b.toByte()) {
                val gzStream = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(decryptedBytes))
                gzStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                String(decryptedBytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            String(decryptedBytes, Charsets.UTF_8)
        }
    }
}

fun Context.findActivity(): FragmentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is FragmentActivity) return context
        context = context.baseContext
    }
    return null
}
