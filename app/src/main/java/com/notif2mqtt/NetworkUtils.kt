package com.notif2mqtt

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkUtils {
    /**
     * Checks if the device is connected to a WiFi network.
     * Returns true if connected to WiFi, false otherwise.
     */
    fun isConnectedToWifi(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return networkInfo.isConnected && networkInfo.type == ConnectivityManager.TYPE_WIFI
        }
    }

    /** True if the current network satisfies the user's WiFi-only setting. */
    fun isNetworkAllowed(context: Context, settings: SettingsManager): Boolean =
        !settings.wifiOnly || isConnectedToWifi(context)
}
