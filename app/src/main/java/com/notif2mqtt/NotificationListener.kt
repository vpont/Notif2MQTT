package com.notif2mqtt

import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.notif2mqtt.models.NotificationData
import com.notif2mqtt.mqtt.MqttService

class NotificationListener : NotificationListenerService() {
    private lateinit var settings: SettingsManager

    companion object {
        private const val TAG = "NotificationListener"
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        Log.d(TAG, "NotificationListener service created")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            // Check if service is enabled
            if (!settings.serviceEnabled) {
                Log.d(TAG, "Service disabled, ignoring notification")
                return
            }

            val packageName = sbn.packageName
            
            // Ignore our own notifications
            if (packageName == applicationContext.packageName) {
                return
            }

            // Check if app is excluded
            if (settings.isAppExcluded(packageName)) {
                Log.d(TAG, "App excluded: $packageName")
                return
            }

            // Extract notification data
            val notification = sbn.notification
            val extras = notification.extras
            
            val title = extras.getCharSequence("android.title")?.toString()
            val text = extras.getCharSequence("android.text")?.toString()
            val appName = getAppName(packageName)
            
            // Extract priority and importance
            val priority = notification.priority  // -2 to 2 (MIN to MAX)
            val importance = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                sbn.notification.channelId?.let { channelId ->
                    try {
                        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                        notificationManager.getNotificationChannel(channelId)?.importance ?: 3
                    } catch (e: Exception) {
                        3 // Default importance
                    }
                } ?: 3
            } else {
                3 // Default for older Android versions
            }

            // Create notification data object
            val notificationData = NotificationData.fromNotification(
                packageName = packageName,
                appName = appName,
                title = title,
                text = text,
                priority = priority,
                importance = importance
            )

            Log.d(TAG, "Notification captured: ${notificationData.appName} - ${notificationData.title} (priority: $priority, importance: $importance)")

            // Send to MQTT service
            MqttService.publishMessage(this, notificationData.toJson())

        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Optional: Handle notification removal if needed
        Log.d(TAG, "Notification removed: ${sbn.packageName}")
    }

    private fun getAppName(packageName: String): String {
        return try {
            val packageManager = applicationContext.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListener connected")
        
        // Start MQTT service when listener is connected
        MqttService.startService(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListener disconnected")
    }
}
