package com.quickssh.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object KeystoreManager {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "QuickSshCryptoAlias"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12 // GCM recommended IV size is 12 bytes
    private const val TAG_SIZE = 128 // Authentication tag size in bits

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(PROVIDER).apply {
            load(null)
        }
    }

    init {
        initKey()
    }

    private fun initKey() {
        if (!keyStore.containsAlias(ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                PROVIDER
            )
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        return (keyStore.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    /**
     * Encrypts plaintext string using KeyStore AES-GCM.
     * Returns a base64 encoded ciphertext consisting of: Base64(IV + Ciphertext)
     */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val ciphertextBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        
        // Combine IV and Ciphertext bytes
        val combinedBytes = ByteArray(iv.size + ciphertextBytes.size)
        System.arraycopy(iv, 0, combinedBytes, 0, iv.size)
        System.arraycopy(ciphertextBytes, 0, combinedBytes, iv.size, ciphertextBytes.size)
        
        return Base64.encodeToString(combinedBytes, Base64.DEFAULT)
    }

    /**
     * Decrypts a base64 encoded payload consisting of: Base64(IV + Ciphertext)
     */
    fun decrypt(encryptedPayload: String): String {
        try {
            val combinedBytes = Base64.decode(encryptedPayload, Base64.DEFAULT)
            if (combinedBytes.size <= IV_SIZE) return ""

            val iv = ByteArray(IV_SIZE)
            val ciphertextBytes = ByteArray(combinedBytes.size - IV_SIZE)
            
            System.arraycopy(combinedBytes, 0, iv, 0, IV_SIZE)
            System.arraycopy(combinedBytes, IV_SIZE, ciphertextBytes, 0, ciphertextBytes.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(TAG_SIZE, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            
            val decryptedBytes = cipher.doFinal(ciphertextBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }
}
