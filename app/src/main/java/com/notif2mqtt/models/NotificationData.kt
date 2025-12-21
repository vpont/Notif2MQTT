package com.notif2mqtt.models

import org.json.JSONObject

data class NotificationData(
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val timestamp: Long,
    val importance: Int,     // 0 (NONE) to 5 (MAX)
    val category: String?,   // Notification category
    val icon: String? = null // Base64 encoded icon
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("package", packageName)
        json.put("app", appName)
        json.put("title", title ?: "")
        json.put("text", text ?: "")
        json.put("timestamp", timestamp)
        json.put("importance", importance)
        json.put("category", category ?: "")
        json.put("urgency", getUrgencyLevel())  // Helper field
        if (icon != null) {
            json.put("icon", icon)
        }
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
            importance: Int = 3,
            category: String? = null,
            icon: String? = null
        ): NotificationData {
            return NotificationData(
                packageName = packageName,
                appName = appName,
                title = title,
                text = text,
                timestamp = System.currentTimeMillis(),
                importance = importance,
                category = category,
                icon = icon
            )
        }
    }
}
