package com.notif2mqtt

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val crypto = PrefsCrypto(appContext)

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
        private const val KEY_DEBOUNCE_WINDOW_MS = "debounce_window_ms"
        private const val KEY_SKIP_WHEN_SCREEN_ON = "skip_when_screen_on"
        private const val KEY_WIFI_ONLY = "wifi_only"

        // Defaults (supports both tcp:// and ssl:// protocols)
        const val DEFAULT_TOPIC = "notif2mqtt/notifications"
        const val DEFAULT_DEBOUNCE_WINDOW_MS = 2000L // 2 seconds
    }

    // MQTT Configuration
    var mqttTopic: String
        get() = getEncryptedString(KEY_MQTT_TOPIC, DEFAULT_TOPIC)
        set(value) = setEncryptedString(KEY_MQTT_TOPIC, value)

    var mqttBroker: String
        get() = getEncryptedString(KEY_MQTT_BROKER, "")
        set(value) = setEncryptedString(KEY_MQTT_BROKER, value)

    var mqttUsername: String
        get() = getEncryptedString(KEY_MQTT_USERNAME, "")
        set(value) = setEncryptedString(KEY_MQTT_USERNAME, value)

    var mqttPassword: String
        get() = getEncryptedString(KEY_MQTT_PASSWORD, "")
        set(value) = setEncryptedString(KEY_MQTT_PASSWORD, value)

    var mqttClientId: String
        get() = getEncryptedString(KEY_MQTT_CLIENT_ID, generateClientId())
        set(value) = setEncryptedString(KEY_MQTT_CLIENT_ID, value)

    var mqttAcceptSelfSignedCerts: Boolean
        get() = prefs.getBoolean(KEY_MQTT_ACCEPT_SELF_SIGNED_CERTS, true) // Default to true for compatibility
        set(value) = prefs.edit().putBoolean(KEY_MQTT_ACCEPT_SELF_SIGNED_CERTS, value).apply()

    var debounceWindowMs: Long
        get() = prefs.getLong(KEY_DEBOUNCE_WINDOW_MS, DEFAULT_DEBOUNCE_WINDOW_MS)
        set(value) = prefs.edit().putLong(KEY_DEBOUNCE_WINDOW_MS, value).apply()

    var skipNotificationsWhenScreenOn: Boolean
        get() = prefs.getBoolean(KEY_SKIP_WHEN_SCREEN_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_SKIP_WHEN_SCREEN_ON, value).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

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

    private fun getEncryptedString(key: String, default: String): String {
        val stored = prefs.getString(key, null)
        if (stored.isNullOrEmpty()) {
            return default
        }
        check(crypto.isEncrypted(stored)) { "Unencrypted value stored for $key" }
        return crypto.decrypt(stored)
    }

    private fun setEncryptedString(key: String, value: String) {
        val encrypted = crypto.encrypt(value)
        prefs.edit().putString(key, encrypted).apply()
    }
}
