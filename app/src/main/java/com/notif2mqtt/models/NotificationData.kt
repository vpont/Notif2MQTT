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
    val icon: String? = null, // Base64 encoded icon (128x128 PNG)
    val previewImage: String? = null // Base64 encoded preview image (256x256 JPEG)
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
        if (icon != null) {
            json.put("icon", icon)
        }
        if (previewImage != null) {
            json.put("previewImage", previewImage)
        }
        return json.toString()
    }

    companion object {
        fun fromNotification(
            packageName: String,
            appName: String,
            title: String?,
            text: String?,
            importance: Int = 3,
            category: String? = null,
            icon: String? = null,
            previewImage: String? = null
        ): NotificationData {
            return NotificationData(
                packageName = packageName,
                appName = appName,
                title = title,
                text = text,
                timestamp = System.currentTimeMillis(),
                importance = importance,
                category = category,
                icon = icon,
                previewImage = previewImage
            )
        }
    }
}
