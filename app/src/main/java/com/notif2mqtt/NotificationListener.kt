package com.notif2mqtt

import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.util.Base64
import java.io.ByteArrayOutputStream
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
            val category = notification.category
            val appName = getAppName(packageName)
            val iconBase64 = getNotificationIcon(notification, packageName)

            // Extract importance
            val importance = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                notification.channelId?.let { channelId ->
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
                importance = importance,
                category = category,
                icon = iconBase64
            )

            Log.d(TAG, "Notification captured: ${notificationData.appName} - ${notificationData.title} (importance: $importance)")

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

    private fun getNotificationIcon(notification: android.app.Notification, packageName: String): String? {
        return try {
            // Try to get large icon first
            val largeIcon = notification.getLargeIcon()
            val drawable = if (largeIcon != null) {
                largeIcon.loadDrawable(this) ?: packageManager.getApplicationIcon(packageName)
            } else {
                // Fallback to app icon
                packageManager.getApplicationIcon(packageName)
            }

            val bitmap = drawableToBitmap(drawable)
            // Resize to 128x128 with bilinear filtering for better quality
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 128, 128, true)
            bitmapToBase64(resizedBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing icon", e)
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.takeIf { it > 0 } ?: 64,
            drawable.intrinsicHeight.takeIf { it > 0 } ?: 64,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
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
