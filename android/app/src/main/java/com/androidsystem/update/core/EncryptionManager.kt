package com.androidsystem.update.core

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptionManager @Inject constructor(private val context: Context) {
    private val secureRandom = SecureRandom()

    companion object {
        const val KEY_TELEMETRY = "telemetry_key"
        const val KEY_DATABASE = "database_key"
        const val KEY_NETWORK = "network_key"
        private const val TAG = "EncryptionManager"
        private const val PREFS_FILE = "key_export_prefs_enc"
    }

    fun encrypt(data: String, keyAlias: String = KEY_TELEMETRY): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = getOrCreateAESKey(keyAlias)
        // Android 12+ (API 31): AndroidKeyStore rejects caller-provided IVs for
        // GCM encryption (InvalidAlgorithmParameterException: "Caller-provided
        // IV not permitted") — the key store generates the IV itself and
        // exposes it via cipher.iv. The payload format (12-byte IV + ciphertext,
        // Base64) is unchanged so both versions decrypt the same data.
        val iv: ByteArray
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            iv = cipher.iv
        } else {
            iv = ByteArray(12).also { secureRandom.nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        }
        val encryptedBytes = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(iv + encryptedBytes, Base64.NO_WRAP)
    }

    fun decrypt(encryptedData: String, keyAlias: String = KEY_TELEMETRY): String {
        val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
        require(combined.size > 12) { "Invalid encrypted data: too short" }
        val iv = combined.sliceArray(0 until 12)
        val actualData = combined.sliceArray(12 until combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateAESKey(keyAlias), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(actualData), StandardCharsets.UTF_8)
    }

    fun encryptSafe(data: String, keyAlias: String = KEY_TELEMETRY): String? = try {
        encrypt(data, keyAlias)
    } catch (e: Exception) {
        Log.e(TAG, "encrypt failed", e); null
    }

    fun decryptSafe(encryptedData: String, keyAlias: String = KEY_TELEMETRY): String? = try {
        decrypt(encryptedData, keyAlias)
    } catch (e: Exception) {
        Log.e(TAG, "decrypt failed", e); null
    }

    // FIX: Use EncryptedSharedPreferences instead of plaintext SharedPreferences
    fun exportRawKey(keyAlias: String = KEY_TELEMETRY): ByteArray {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val stored = prefs.getString("export_$keyAlias", null)
        return if (stored != null) {
            Base64.decode(stored, Base64.NO_WRAP)
        } else {
            val raw = generateRandomKey(32)
            prefs.edit().putString("export_$keyAlias", Base64.encodeToString(raw, Base64.NO_WRAP)).commit()
            raw
        }
    }

    fun exportRawKeyHex(keyAlias: String = KEY_TELEMETRY): String {
        return exportRawKey(keyAlias).joinToString("") { "%02x".format(it) }
    }

    fun encryptWithRawKey(data: String, keyAlias: String = KEY_TELEMETRY): String {
        val rawKey = exportRawKey(keyAlias)
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = javax.crypto.spec.SecretKeySpec(rawKey, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        val enc = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(iv + enc, Base64.NO_WRAP)
    }

    private fun getOrCreateAESKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (!keyStore.containsAlias(alias)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            keyGenerator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            keyGenerator.generateKey()
        }
        return keyStore.getKey(alias, null) as SecretKey
    }

    suspend fun encryptFile(input: File, output: File): Boolean =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val encryptedFile = EncryptedFile.Builder(
                    context, output, masterKey,
                    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                ).build()
                encryptedFile.openFileOutput().use { out ->
                    input.inputStream().use { it.copyTo(out) }
                }
                true
            } catch (e: Exception) { false }
        }

    fun hideDataInImage(imageBytes: ByteArray, data: String): ByteArray {
        val dataBytes = data.toByteArray(StandardCharsets.UTF_8)
        val requiredBytes = dataBytes.size * 8
        require(imageBytes.size >= requiredBytes) {
            "Image too small: need $requiredBytes bytes, have ${imageBytes.size}"
        }
        val output = ByteArrayOutputStream()
        var dataIndex = 0
        var bitIndex = 0
        for (i in imageBytes.indices) {
            if (dataIndex < dataBytes.size) {
                val dataBit = (dataBytes[dataIndex].toInt() shr (7 - bitIndex)) and 1
                output.write((imageBytes[i].toInt() and 0xFE) or dataBit)
                if (++bitIndex == 8) { dataIndex++; bitIndex = 0 }
            } else {
                output.write(imageBytes[i].toInt())
            }
        }
        return output.toByteArray()
    }

    fun generateRandomKey(size: Int): ByteArray {
        return ByteArray(size).also { secureRandom.nextBytes(it) }
    }
}
