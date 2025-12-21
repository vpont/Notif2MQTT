package com.notif2mqtt.mqtt

import android.content.Context
import android.util.Log
import com.notif2mqtt.SettingsManager
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class MqttManager(private val context: Context) {
    private var mqttClient: MqttClient? = null
    private val settings = SettingsManager(context)
    private var connectionCallback: ((Boolean, String?) -> Unit)? = null

    companion object {
        private const val TAG = "MqttManager"
        private const val QOS = 1
        private const val TIMEOUT = 10
        private const val KEEP_ALIVE = 60
    }

    private fun createSSLSocketFactory(): SSLSocketFactory {
        // Create a trust manager that accepts all certificates (for self-signed certificates)
        // TODO: Implement proper certificate validation when mqttAcceptSelfSignedCerts is false
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        return sslContext.socketFactory
    }

    fun connect(callback: (Boolean, String?) -> Unit) {
        this.connectionCallback = callback
        
        try {
            val broker = settings.mqttBroker
            val clientId = settings.mqttClientId
            
            Log.d(TAG, "Connecting to MQTT broker: $broker with client ID: $clientId")
            
            mqttClient = MqttClient(broker, clientId, MemoryPersistence())
            
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = TIMEOUT
                keepAliveInterval = KEEP_ALIVE
                isAutomaticReconnect = true

                // Configure SSL if using ssl:// protocol
                if (broker.startsWith("ssl://")) {
                    socketFactory = createSSLSocketFactory()
                }

                // Set credentials if provided
                val username = settings.mqttUsername
                val password = settings.mqttPassword
                if (username.isNotEmpty()) {
                    userName = username
                    if (password.isNotEmpty()) {
                        setPassword(password.toCharArray())
                    }
                }
            }
            
            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Connection lost: ${cause?.message}")
                    connectionCallback?.invoke(false, "Connection lost: ${cause?.message}")
                }
                
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    // Not used for publishing-only client
                }
                
                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    Log.d(TAG, "Message delivered")
                }
            })
            
            mqttClient?.connect(options)
            Log.i(TAG, "Connected to MQTT broker successfully")
            callback(true, null)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to MQTT broker", e)
            callback(false, e.message)
        }
    }

    fun publish(message: String) {
        try {
            if (mqttClient?.isConnected != true) {
                Log.w(TAG, "Cannot publish: MQTT client not connected")
                return
            }
            
            val topic = settings.mqttTopic
            val mqttMessage = MqttMessage(message.toByteArray()).apply {
                qos = QOS
                isRetained = false
            }
            
            mqttClient?.publish(topic, mqttMessage)
            Log.d(TAG, "Published message to topic: $topic")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish message", e)
        }
    }

    fun disconnect() {
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
            mqttClient = null
            Log.i(TAG, "Disconnected from MQTT broker")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting from MQTT broker", e)
        }
    }

    fun isConnected(): Boolean {
        return mqttClient?.isConnected == true
    }

    fun reconnect() {
        if (!isConnected()) {
            connect { success, error ->
                if (success) {
                    Log.i(TAG, "Reconnected successfully")
                } else {
                    Log.e(TAG, "Reconnection failed: $error")
                }
            }
        }
    }
}
