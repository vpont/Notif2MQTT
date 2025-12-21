package com.notif2mqtt

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val PREFS_NAME = "notif2mqtt_settings"
        
        // MQTT Settings
        private const val KEY_MQTT_BROKER = "mqtt_broker"
        private const val KEY_MQTT_TOPIC = "mqtt_topic"
        private const val KEY_MQTT_USERNAME = "mqtt_username"
        private const val KEY_MQTT_PASSWORD = "mqtt_password"
        private const val KEY_MQTT_CLIENT_ID = "mqtt_client_id"
        private const val KEY_MQTT_ACCEPT_SELF_SIGNED_CERTS = "mqtt_accept_self_signed_certs"
        
        // App Settings
        private const val KEY_EXCLUDED_APPS = "excluded_apps"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        
        // Defaults (supports both tcp:// and ssl:// protocols)
        const val DEFAULT_BROKER = "tcp://192.168.1.100:1883"
        const val DEFAULT_TOPIC = "notif2mqtt/notifications"
    }

    // MQTT Configuration
    var mqttBroker: String
        get() = prefs.getString(KEY_MQTT_BROKER, DEFAULT_BROKER) ?: DEFAULT_BROKER
        set(value) = prefs.edit().putString(KEY_MQTT_BROKER, value).apply()

    var mqttTopic: String
        get() = prefs.getString(KEY_MQTT_TOPIC, DEFAULT_TOPIC) ?: DEFAULT_TOPIC
        set(value) = prefs.edit().putString(KEY_MQTT_TOPIC, value).apply()

    var mqttUsername: String
        get() = prefs.getString(KEY_MQTT_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_USERNAME, value).apply()

    var mqttPassword: String
        get() = prefs.getString(KEY_MQTT_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_PASSWORD, value).apply()

    var mqttClientId: String
        get() = prefs.getString(KEY_MQTT_CLIENT_ID, generateClientId()) ?: generateClientId()
        set(value) = prefs.edit().putString(KEY_MQTT_CLIENT_ID, value).apply()

    var mqttAcceptSelfSignedCerts: Boolean
        get() = prefs.getBoolean(KEY_MQTT_ACCEPT_SELF_SIGNED_CERTS, true) // Default to true for compatibility
        set(value) = prefs.edit().putBoolean(KEY_MQTT_ACCEPT_SELF_SIGNED_CERTS, value).apply()

    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    // Excluded Apps
    var excludedApps: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_EXCLUDED_APPS, value).apply()

    fun isAppExcluded(packageName: String): Boolean {
        return excludedApps.contains(packageName)
    }

    fun toggleAppExclusion(packageName: String) {
        val current = excludedApps.toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        excludedApps = current
    }

    private fun generateClientId(): String {
        return "android_${android.os.Build.MODEL.replace(" ", "_")}_${System.currentTimeMillis()}"
    }
}
