package com.notif2mqtt

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.notif2mqtt.models.ConnectionState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.notif2mqtt.mqtt.MqttService

class MainActivity : AppCompatActivity() {
    private lateinit var settings: SettingsManager
    private lateinit var appsAdapter: AppsAdapter
    private lateinit var mqttManager: com.notif2mqtt.mqtt.MqttManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsManager(this)
        mqttManager = com.notif2mqtt.mqtt.MqttManager(this)

        setupViews()
        updatePermissionStatus()
        loadInstalledApps()

        // Observe connection state
        lifecycleScope.launch {
            mqttManager.connectionState.collect { state ->
                updateConnectionStatus(state)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        startMqttServiceIfConfigured()
    }

    private fun setupViews() {
        // Load current settings
        try {
            findViewById<TextInputEditText>(R.id.brokerInput).setText(settings.mqttBroker)
            findViewById<TextInputEditText>(R.id.usernameInput).setText(settings.mqttUsername)
            findViewById<TextInputEditText>(R.id.passwordInput).setText(settings.mqttPassword)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.encryption_failed, Toast.LENGTH_LONG).show()
        }

        // Load screen-on skip setting
        val skipScreenOnSwitch = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.skipScreenOnSwitch)
        skipScreenOnSwitch.isChecked = settings.skipNotificationsWhenScreenOn
        skipScreenOnSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.skipNotificationsWhenScreenOn = isChecked
        }

        // Load WiFi-only setting
        val wifiOnlySwitch = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.wifiOnlySwitch)
        wifiOnlySwitch.isChecked = settings.wifiOnly
        wifiOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.wifiOnly = isChecked
        }

        // Save button
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            saveSettings()
        }

        // Permission button
        findViewById<MaterialButton>(R.id.permissionButton).setOnClickListener {
            openNotificationSettings()
        }

        // Setup RecyclerView
        val recyclerView = findViewById<RecyclerView>(R.id.appsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        appsAdapter = AppsAdapter(emptyList(), settings)
        recyclerView.adapter = appsAdapter
    }

    private fun saveSettings() {
        val broker = findViewById<TextInputEditText>(R.id.brokerInput).text.toString().trim()
        val username = findViewById<TextInputEditText>(R.id.usernameInput).text.toString().trim()
        val password = findViewById<TextInputEditText>(R.id.passwordInput).text.toString().trim()

        if (broker.isEmpty()) {
            Toast.makeText(this, getString(R.string.broker_required), Toast.LENGTH_SHORT).show()
            return
        }

        // Validate broker URL format
        if (!isValidBrokerUrl(broker)) {
            Toast.makeText(this, getString(R.string.invalid_broker_url), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            settings.mqttBroker = broker
            settings.mqttUsername = username
            settings.mqttPassword = password
        } catch (e: Exception) {
            Toast.makeText(this, R.string.encryption_failed, Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()

        // Restart MQTT service with new settings
        MqttService.stopService(this)
        MqttService.startService(this)
    }

    private fun isValidBrokerUrl(url: String): Boolean {
        return try {
            val uri = java.net.URI(url)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            val port = uri.port

            // Check scheme
            if (scheme != "tcp" && scheme != "ssl") {
                return false
            }

            // Check host
            if (host.isNullOrEmpty()) {
                return false
            }

            // Check port
            if (port == -1 || port < 1 || port > 65535) {
                return false
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    private fun updatePermissionStatus() {
        val hasPermission = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)

        val permissionStatusText = findViewById<TextView>(R.id.permissionStatusText)

        if (hasPermission) {
            permissionStatusText.text = getString(R.string.permission_granted)
            permissionStatusText.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            permissionStatusText.text = getString(R.string.permission_not_granted)
            permissionStatusText.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    private fun startMqttServiceIfConfigured() {
        if (settings.mqttBroker.isBlank()) {
            return
        }
        MqttService.startService(this)
    }

    private fun loadInstalledApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val packageManager = packageManager

                // Get all installed applications
                val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

                // Filter to only show apps that are likely to emit notifications:
                // 1. Apps with launcher icon (user-facing apps)
                // 2. Non-system apps (user-installed apps)
                installedApps.filter { appInfo ->
                    // Check if app has a launcher icon
                    val hasLauncher = packageManager.getLaunchIntentForPackage(appInfo.packageName) != null

                    // Check if it's a user-installed app (not system app)
                    val isUserApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0

                    // Include if either condition is true
                    hasLauncher || isUserApp
                }.mapNotNull { appInfo ->
                    try {
                        AppInfo(
                            packageName = appInfo.packageName,
                            name = packageManager.getApplicationLabel(appInfo).toString(),
                            icon = packageManager.getApplicationIcon(appInfo)
                        )
                    } catch (e: PackageManager.NameNotFoundException) {
                        null // Skip if app info not found
                    } catch (e: Exception) {
                        null // Skip any other issues
                    }
                }.sortedBy { it.name.lowercase() }
            }

            // Update UI on main thread
            appsAdapter.updateApps(apps)
        }
    }

    private fun updateConnectionStatus(state: ConnectionState) {
        val statusText = when (state) {
            ConnectionState.CONNECTING -> getString(R.string.connecting)
            ConnectionState.CONNECTED -> {
                val useTls = try {
                    settings.mqttBroker.startsWith("ssl://")
                } catch (e: Exception) {
                    false
                }
                if (useTls) getString(R.string.connected_tls) else getString(R.string.connected)
            }
            ConnectionState.DISCONNECTED -> getString(R.string.disconnected)
            ConnectionState.ERROR -> getString(R.string.error)
        }

        findViewById<TextView>(R.id.statusText).text = statusText

        val color = when (state) {
            ConnectionState.CONNECTED -> android.R.color.holo_green_dark
            ConnectionState.ERROR -> android.R.color.holo_red_dark
            else -> android.R.color.darker_gray
        }
        findViewById<TextView>(R.id.statusText).setTextColor(getColor(color))
    }
}

data class AppInfo(
    val packageName: String,
    val name: String,
    val icon: android.graphics.drawable.Drawable
)

class AppsAdapter(
    private var apps: List<AppInfo>,
    private val settings: SettingsManager
) : RecyclerView.Adapter<AppsAdapter.AppViewHolder>() {

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val checkbox: MaterialCheckBox = view.findViewById(R.id.excludeCheckbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.name.text = app.name

        // Remove listener before setting checked state to avoid triggering it during recycling
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = settings.isAppExcluded(app.packageName)

        // Add listener after setting the initial state
        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != settings.isAppExcluded(app.packageName)) {
                settings.toggleAppExclusion(app.packageName)
            }
        }

        holder.itemView.setOnClickListener {
            holder.checkbox.isChecked = !holder.checkbox.isChecked
        }
    }

    override fun getItemCount() = apps.size

    fun updateApps(newApps: List<AppInfo>) {
        apps = newApps
        notifyDataSetChanged()
    }
}
