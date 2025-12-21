package com.notif2mqtt.mqtt

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.notif2mqtt.MainActivity
import com.notif2mqtt.R
import com.notif2mqtt.models.ConnectionState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MqttService : Service() {
    private lateinit var mqttManager: MqttManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
    
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

        fun getConnectionState(context: Context): ConnectionState {
            // Return the shared connection state
            return MqttManager.connectionState.value
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
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.connecting)))

        // Launch connection coroutine
        serviceScope.launch {
            setupNetworkMonitoring()
            connectWithRetry()
        }
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
        serviceScope.cancel() // Cancel all coroutines
        mqttManager.disconnect()
        wakeLock?.release()
    }

    private suspend fun connectWithRetry() {
        while (true) {
                    if (mqttManager.connectionState.value != ConnectionState.CONNECTED) {
                val result = mqttManager.connectAsync()
                if (result.isSuccess) {
                    updateNotification(getString(R.string.connected))
                } else {
                    updateNotification(getString(R.string.disconnected))
                    delay(30000) // Wait 30 seconds before retry
                }
            } else {
                delay(30000) // Check every 30 seconds if still connected
            }
        }
    }

    private fun setupNetworkMonitoring() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val networkRequest = NetworkRequest.Builder().build()

        connectivityManager.registerNetworkCallback(networkRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                serviceScope.launch {
                    delay(5000) // 5 second delay before retrying
            if (mqttManager.connectionState.value != ConnectionState.CONNECTED) {
                        mqttManager.connectAsync()
                    }
                }
            }

            override fun onLost(network: Network) {
                mqttManager.connectionState.value = ConnectionState.DISCONNECTED
                updateNotification(getString(R.string.disconnected))
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_channel_description)
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
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_template, status))
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
