package com.notif2mqtt

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
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
import com.notif2mqtt.mqtt.MqttService

class MainActivity : AppCompatActivity() {
    private lateinit var settings: SettingsManager
    private lateinit var appsAdapter: AppsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsManager(this)

        setupViews()
        updatePermissionStatus()
        loadInstalledApps()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupViews() {
        // Load current settings
        findViewById<TextInputEditText>(R.id.brokerInput).setText(settings.mqttBroker)
        findViewById<TextInputEditText>(R.id.topicInput).setText(settings.mqttTopic)
        findViewById<TextInputEditText>(R.id.usernameInput).setText(settings.mqttUsername)
        findViewById<TextInputEditText>(R.id.passwordInput).setText(settings.mqttPassword)

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
        val broker = findViewById<TextInputEditText>(R.id.brokerInput).text.toString()
        val topic = findViewById<TextInputEditText>(R.id.topicInput).text.toString()
        val username = findViewById<TextInputEditText>(R.id.usernameInput).text.toString()
        val password = findViewById<TextInputEditText>(R.id.passwordInput).text.toString()

        if (broker.isEmpty() || topic.isEmpty()) {
            Toast.makeText(this, "Broker and Topic are required", Toast.LENGTH_SHORT).show()
            return
        }

        settings.mqttBroker = broker
        settings.mqttTopic = topic
        settings.mqttUsername = username
        settings.mqttPassword = password

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()

        // Restart MQTT service with new settings
        MqttService.stopService(this)
        MqttService.startService(this)
    }

    private fun updatePermissionStatus() {
        val hasPermission = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)

        val statusText = findViewById<TextView>(R.id.permissionStatus)
        val statusDisplay = findViewById<TextView>(R.id.statusText)

        if (hasPermission) {
            statusText.text = getString(R.string.permission_granted)
            statusText.setTextColor(getColor(android.R.color.holo_green_dark))
            statusDisplay.text = getString(R.string.service_running)
        } else {
            statusText.text = getString(R.string.permission_not_granted)
            statusText.setTextColor(getColor(android.R.color.holo_red_dark))
            statusDisplay.text = getString(R.string.service_stopped)
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    private fun loadInstalledApps() {
        val packageManager = packageManager
        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 } // Only user apps
            .map { appInfo ->
                AppInfo(
                    packageName = appInfo.packageName,
                    name = packageManager.getApplicationLabel(appInfo).toString(),
                    icon = packageManager.getApplicationIcon(appInfo)
                )
            }
            .sortedBy { it.name }

        appsAdapter.updateApps(apps)
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
        holder.checkbox.isChecked = settings.isAppExcluded(app.packageName)

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
