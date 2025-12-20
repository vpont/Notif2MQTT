package com.notif2mqtt.models

import org.json.JSONObject

data class NotificationData(
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val timestamp: Long,
    val priority: Int,      // -2 (MIN) to 2 (MAX)
    val importance: Int     // 0 (NONE) to 5 (MAX)
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("package", packageName)
        json.put("app", appName)
        json.put("title", title ?: "")
        json.put("text", text ?: "")
        json.put("timestamp", timestamp)
        json.put("priority", priority)
        json.put("importance", importance)
        json.put("urgency", getUrgencyLevel())  // Helper field
        return json.toString()
    }
    
    private fun getUrgencyLevel(): String {
        return when {
            importance >= 4 -> "high"      // IMPORTANCE_HIGH or MAX
            importance == 3 -> "normal"    // IMPORTANCE_DEFAULT
            importance == 2 -> "low"       // IMPORTANCE_LOW
            else -> "minimal"              // IMPORTANCE_MIN or NONE
        }
    }

    companion object {
        fun fromNotification(
            packageName: String,
            appName: String,
            title: String?,
            text: String?,
            priority: Int = 0,
            importance: Int = 3
        ): NotificationData {
            return NotificationData(
                packageName = packageName,
                appName = appName,
                title = title,
                text = text,
                timestamp = System.currentTimeMillis(),
                priority = priority,
                importance = importance
            )
        }
    }
}
