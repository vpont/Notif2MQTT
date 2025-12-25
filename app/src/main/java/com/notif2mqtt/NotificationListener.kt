package com.notif2mqtt

import android.content.pm.PackageManager
import android.os.PowerManager
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

    // Debouncing cache: stores timestamp of last notification with same key
    private val notificationCache = mutableMapOf<NotificationKey, Long>()

    // Data class to uniquely identify a notification (excluding timestamp and icon)
    private data class NotificationKey(
        val packageName: String,
        val title: String?,
        val text: String?,
        val normalizedText: String?
    ) {
        // Override equals and hashCode to use normalized text for comparison
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NotificationKey) return false
            return packageName == other.packageName &&
                   title == other.title &&
                   normalizedText == other.normalizedText
        }

        override fun hashCode(): Int {
            var result = packageName.hashCode()
            result = 31 * result + (title?.hashCode() ?: 0)
            result = 31 * result + (normalizedText?.hashCode() ?: 0)
            return result
        }
    }

    companion object {
        private const val TAG = "NotificationListener"
        private const val CACHE_CLEANUP_INTERVAL_MS = 60000L // Clean cache every minute
        private const val FUZZY_SIMILARITY_THRESHOLD = 0.9 // 90% similarity for fuzzy matching

        // Regex pattern to normalize text: removes punctuation and extra whitespace
        private val NORMALIZATION_REGEX = "[•\\-_\\.,:;!?()\\[\\]{}\"']+".toRegex()
    }

    private var lastCacheCleanup = 0L

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

            // Check if we should skip notifications when screen is on
            if (settings.skipNotificationsWhenScreenOn && isScreenOn()) {
                Log.d(TAG, "Screen is on and skip setting is enabled, ignoring notification")
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
            val previewImageBase64 = getPreviewImage(notification)

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
                icon = iconBase64,
                previewImage = previewImageBase64
            )

            // Check if this notification should be debounced
            if (shouldDebounce(packageName, title, text)) {
                Log.d(TAG, "Notification debounced: ${notificationData.appName} - ${notificationData.title}")
                return
            }

            Log.d(TAG, "Notification captured: ${notificationData.appName} - ${notificationData.title} (importance: $importance)")

            // Send to MQTT service
            MqttService.publishMessage(this, notificationData.toJson())

            // Cleanup old cache entries periodically
            cleanupCacheIfNeeded()

        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Optional: Handle notification removal if needed
        Log.d(TAG, "Notification removed: ${sbn.packageName}")
    }

    /**
     * Checks if a notification should be debounced (ignored because it's a duplicate).
     * Returns true if the same notification was seen recently, false otherwise.
     *
     * Uses a two-tier approach:
     * 1. Exact match on normalized text (fast, O(1) lookup)
     * 2. Fuzzy similarity check if no exact match found (slower, but catches near-duplicates)
     */
    private fun shouldDebounce(packageName: String, title: String?, text: String?): Boolean {
        val now = System.currentTimeMillis()
        val debounceWindow = settings.debounceWindowMs
        val normalizedText = normalizeText(text)

        // Create key with normalized text for exact matching
        val key = NotificationKey(packageName, title, text, normalizedText)

        // First tier: exact match on normalized text (O(1) lookup)
        val lastSeen = notificationCache[key]
        if (lastSeen != null && (now - lastSeen) < debounceWindow) {
            // Exact match found within debounce window
            Log.d(TAG, "Notification debounced (exact match): $packageName")
            return true
        }

        // Second tier: fuzzy matching for near-duplicates
        // Only check if we have non-empty text to compare
        if (!normalizedText.isNullOrBlank()) {
            val matchingKey = findSimilarNotification(packageName, title, normalizedText, debounceWindow, now)
            if (matchingKey != null) {
                // Found a similar notification, update its timestamp and debounce
                notificationCache[matchingKey] = now
                Log.d(TAG, "Notification debounced (fuzzy match): $packageName - similarity detected")
                return true
            }
        }

        // No duplicate found, add to cache
        notificationCache[key] = now
        return false
    }

    /**
     * Normalizes notification text by removing special characters and extra whitespace.
     * This allows "text • more" and "text - more" to be considered identical.
     */
    private fun normalizeText(text: String?): String? {
        if (text.isNullOrBlank()) return text
        return text
            .replace(NORMALIZATION_REGEX, " ") // Replace punctuation with spaces
            .trim() // Remove leading/trailing whitespace
            .replace("\\s+".toRegex(), " ") // Collapse multiple spaces into one
            .lowercase() // Case-insensitive comparison
    }

    /**
     * Finds a similar notification in the cache using Jaccard similarity on word tokens.
     * Returns the matching key if found, null otherwise.
     * After normalization, only checks notifications from the same package with same title.
     */
    private fun findSimilarNotification(
        packageName: String,
        title: String?,
        normalizedText: String,
        debounceWindow: Long,
        now: Long
    ): NotificationKey? {
        // Tokenize the normalized text into words for Jaccard similarity
        val currentTokens = normalizedText.split(" ").filter { it.isNotBlank() }.toSet()

        // Only check recent notifications from same app with same title
        for ((cachedKey, timestamp) in notificationCache) {
            // Skip if outside debounce window
            if (now - timestamp >= debounceWindow) continue

            // Only compare notifications from same app with same title
            if (cachedKey.packageName != packageName || cachedKey.title != title) continue

            // Skip if no text to compare
            val cachedNormalized = cachedKey.normalizedText
            if (cachedNormalized.isNullOrBlank()) continue

            // Calculate Jaccard similarity: |A ∩ B| / |A ∪ B|
            val cachedTokens = cachedNormalized.split(" ").filter { it.isNotBlank() }.toSet()
            val intersection = currentTokens.intersect(cachedTokens).size
            val union = currentTokens.union(cachedTokens).size

            if (union > 0) {
                val similarity = intersection.toDouble() / union.toDouble()

                if (similarity >= FUZZY_SIMILARITY_THRESHOLD) {
                    Log.d(TAG, "Found similar notification (similarity: ${"%.2f".format(similarity * 100)}%)")
                    return cachedKey
                }
            }
        }

        return null
    }

    /**
     * Cleans up old entries from the cache to prevent memory leaks.
     * Called periodically after processing notifications.
     */
    private fun cleanupCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastCacheCleanup < CACHE_CLEANUP_INTERVAL_MS) {
            return
        }

        lastCacheCleanup = now
        val debounceWindow = settings.debounceWindowMs

        // Remove entries older than 2x the debounce window
        val threshold = now - (debounceWindow * 2)
        val iterator = notificationCache.iterator()
        var removedCount = 0

        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value < threshold) {
                iterator.remove()
                removedCount++
            }
        }

        if (removedCount > 0) {
            Log.d(TAG, "Cache cleanup: removed $removedCount old entries, ${notificationCache.size} remain")
        }
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
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(drawable)
            bitmapToBase64(bitmap)
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

    private fun getPreviewImage(notification: android.app.Notification): String? {
        return try {
            val extras = notification.extras

            // Try multiple sources for preview image
            var bitmap: Bitmap? = null

            // 1. Try BigPictureStyle picture (most common for media)
            bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable("android.picture", android.graphics.Bitmap::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable<android.graphics.Bitmap>("android.picture")
            }

            // 2. Try large icon from extras (fallback)
            if (bitmap == null) {
                bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    extras.getParcelable("android.largeIcon", android.graphics.Bitmap::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    extras.getParcelable<android.graphics.Bitmap>("android.largeIcon")
                }
            }

            // 3. Try MessagingStyle person icon (for chat apps like WhatsApp)
            if (bitmap == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                try {
                    val messages = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        extras.getParcelableArray("android.messages", android.os.Parcelable::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        extras.getParcelableArray("android.messages")
                    }
                    if (messages != null && messages.isNotEmpty()) {
                        val lastMessage = messages.last() as? android.os.Bundle
                        val senderPerson = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            lastMessage?.getParcelable("sender_person", android.app.Person::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            lastMessage?.getParcelable<android.app.Person>("sender_person")
                        }
                        val icon = senderPerson?.icon
                        if (icon != null) {
                            bitmap = icon.loadDrawable(this)?.let { drawableToBitmap(it) }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error extracting MessagingStyle icon", e)
                }
            }

            if (bitmap != null) {
                return bitmapToBase64(bitmap)
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting preview image", e)
            null
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

    /**
     * Checks if the device screen is currently on (interactive).
     * Returns true if the screen is on and the device is being actively used.
     */
    private fun isScreenOn(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return powerManager.isInteractive
    }
}
