package com.notif2mqtt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.notif2mqtt.mqtt.MqttService

class PackageReplacedReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PackageReplacedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val settings = SettingsManager(context)
        if (settings.mqttBroker.isBlank()) {
            return
        }

        Log.d(TAG, "App updated, starting MQTT service")
        MqttService.startService(context)
    }
}
