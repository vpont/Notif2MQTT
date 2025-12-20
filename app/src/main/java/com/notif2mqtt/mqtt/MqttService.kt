package com.notif2mqtt.mqtt

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.notif2mqtt.MainActivity
import com.notif2mqtt.R

class MqttService : Service() {
    private lateinit var mqttManager: MqttManager
    private var wakeLock: PowerManager.WakeLock? = null
    
    companion object {
        private const val TAG = "MqttService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mqtt_service_channel"
        const val ACTION_PUBLISH = "com.notif2mqtt.ACTION_PUBLISH"
        const val EXTRA_MESSAGE = "message"
        
        fun startService(context: Context) {
            val intent = Intent(context, MqttService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, MqttService::class.java)
            context.stopService(intent)
        }
        
        fun publishMessage(context: Context, message: String) {
            val intent = Intent(context, MqttService::class.java).apply {
                action = ACTION_PUBLISH
                putExtra(EXTRA_MESSAGE, message)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        mqttManager = MqttManager(this)
        
        // Acquire wake lock to keep service running
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Notif2MQTT::MqttServiceWakeLock"
        )
        wakeLock?.acquire()
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
        
        // Connect to MQTT broker
        connectToMqtt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PUBLISH -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE)
                if (message != null) {
                    mqttManager.publish(message)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        mqttManager.disconnect()
        wakeLock?.release()
    }

    private fun connectToMqtt() {
        mqttManager.connect { success, error ->
            if (success) {
                Log.i(TAG, "MQTT connected successfully")
                updateNotification("Connected")
            } else {
                Log.e(TAG, "MQTT connection failed: $error")
                updateNotification("Connection failed")
                
                // Retry connection after delay
                android.os.Handler(mainLooper).postDelayed({
                    if (!mqttManager.isConnected()) {
                        connectToMqtt()
                    }
                }, 5000)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MQTT Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps MQTT connection alive"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Notif2MQTT")
            .setContentText("MQTT Status: $status")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }
}
