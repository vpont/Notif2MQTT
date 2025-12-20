# Notif2MQTT

A lightweight native Android app that captures all device notifications and sends them to an MQTT server.

## Features

- ✨ **Native & Lightweight**: Pure Kotlin with Android SDK
- 🔔 **Notification Capture**: Captures all system notifications
- 📡 **MQTT Client**: Real-time sending to MQTT broker
- 🚫 **App Exclusions**: Filter specific applications
- ⚡ **Background Service**: Continuous operation
- 🔋 **Optimized**: Minimal battery consumption
- 🎯 **Urgency Levels**: Captures notification importance

## Requirements

- Android 7.0 (API 24) or higher
- MQTT Broker (e.g., Mosquitto)
- Network connection

## Installation

### Build from Source

```bash
# Clone repository
git clone <repository-url>
cd Notif2MQTT

# Build APK
./gradlew assembleDebug

# Install on device
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Configuration

### 1. Configure MQTT Broker

In the app, configure:

- **Broker URL**: `tcp://IP:PORT` (e.g., `tcp://192.168.1.100:1883`)
- **Topic**: MQTT topic for notifications (e.g., `android/notifications`)
- **Username/Password**: (Optional) Broker credentials

### 2. Grant Permissions

The app requires notification access:

1. Open the app
2. Tap "Open Settings" in the permissions section
3. Enable "Notif2MQTT" in the notification services list

### 3. Exclude Apps (Optional)

In the "App Exclusions" section, select apps whose notifications you don't want to send.

### 4. Disable Battery Optimization

For continuous operation:

1. Go to Settings > Battery > Battery Optimization
2. Find "Notif2MQTT"
3. Select "Don't optimize"

## MQTT Message Format

Notifications are sent in JSON format:

```json
{
  "package": "com.whatsapp",
  "app": "WhatsApp",
  "title": "New message",
  "text": "Hello, how are you?",
  "timestamp": 1703001234567,
  "icon": "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACA...",
  "importance": 4,
  "urgency": "high"
}
```

### Urgency Levels

- **high**: Critical notifications (calls, alarms)
- **normal**: Standard messages (WhatsApp, Telegram)
- **low**: Low importance notifications
- **minimal**: Silent notifications

## Linux Integration

### Install Mosquitto (MQTT Broker)

```bash
# Arch Linux
sudo pacman -S mosquitto
sudo systemctl start mosquitto
sudo systemctl enable mosquitto

# Ubuntu/Debian
sudo apt install mosquitto mosquitto-clients
sudo systemctl start mosquitto
```

### Subscribe to Notifications

```bash
mosquitto_sub -h localhost -t "android/notifications" -v
```

### Python Script to Display Notifications

Use the included `linux_receiver.py` script:

```bash
# Install dependency
pip install paho-mqtt

# Run receiver
./linux_receiver.py
```

The script will display Android notifications on your Linux desktop using `notify-send` with appropriate urgency levels.

## Architecture

```
┌─────────────────────────────────────┐
│   NotificationListenerService       │
│   - Captures notifications          │
│   - Filters excluded apps           │
│   - Extracts importance             │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│   MqttService (Foreground)          │
│   - Maintains MQTT connection       │
│   - Publishes messages              │
│   - Automatic reconnection          │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│   MQTT Broker                       │
│   - Mosquitto / HiveMQ / etc        │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│   Linux Receiver (linux_receiver.py)│
│   - Displays notifications          │
│   - Respects urgency levels         │
└─────────────────────────────────────┘
```

## Troubleshooting

### App doesn't capture notifications

- Verify notification access permission is granted
- Restart the application
- Check Settings > Apps > Notif2MQTT for active permissions

### Can't connect to MQTT broker

- Verify broker URL is correct (must include `tcp://`)
- Ensure Android device is on the same network as the broker
- Verify port is open (default 1883)
- Check credentials if broker requires authentication

### Service stops

- Disable battery optimization for the app
- Some manufacturers (Xiaomi, Huawei) have additional restrictions in Settings > Battery > App Permissions

### Not receiving all notifications

- Some apps don't allow their notifications to be read for security reasons
- Verify the app is not in the exclusion list

## Dependencies

- Eclipse Paho MQTT Client (1.2.5)
- AndroidX Core, AppCompat, Material Design
- RecyclerView

## License

MIT License

## Contributing

Contributions are welcome. Please open an issue or pull request.
