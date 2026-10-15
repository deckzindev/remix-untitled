package com.example.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoManager {
  private const val ANDROID_KEYSTORE = "AndroidKeyStore"
  private const val KEY_ALIAS = "studio_apk_github_pat_key"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val GCM_IV_LENGTH = 12
  private const val GCM_TAG_LENGTH = 128

  private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
    load(null)
  }

  private fun getOrCreateSecretKey(): SecretKey {
    val existingKey = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
    if (existingKey != null) {
      return existingKey.secretKey
    }

    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val parameterSpec = KeyGenParameterSpec.Builder(
      KEY_ALIAS,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)
      .setRandomizedEncryptionRequired(true)
      .build()

    keyGenerator.init(parameterSpec)
    return keyGenerator.generateKey()
  }

  fun encrypt(plainText: String): String {
    if (plainText.isBlank()) return ""
    return try {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
      val iv = cipher.iv
      val cipherBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))

      // Combine IV + ciphertext
      val combined = ByteArray(iv.size + cipherBytes.size)
      System.arraycopy(iv, 0, combined, 0, iv.size)
      System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)

      Base64.encodeToString(combined, Base64.NO_WRAP)
    } catch (_: Exception) {
      // In case of hardware keystore transient failure, return fallback prefix
      ""
    }
  }

  fun decrypt(encryptedText: String): String {
    if (encryptedText.isBlank()) return ""
    return try {
      val combined = Base64.decode(encryptedText, Base64.NO_WRAP)
      if (combined.size < GCM_IV_LENGTH) return ""

      val iv = ByteArray(GCM_IV_LENGTH)
      val cipherBytes = ByteArray(combined.size - GCM_IV_LENGTH)
      System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
      System.arraycopy(combined, GCM_IV_LENGTH, cipherBytes, 0, cipherBytes.size)

      val cipher = Cipher.getInstance(TRANSFORMATION)
      val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
      cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)

      val plainBytes = cipher.doFinal(cipherBytes)
      String(plainBytes, StandardCharsets.UTF_8)
    } catch (_: Exception) {
      // If unable to decrypt (or if it was an unencrypted legacy token during migration), return as-is
      if (encryptedText.startsWith("ghp_") || encryptedText.startsWith("github_pat_")) {
        encryptedText
      } else {
        ""
      }
    }
  }
}
