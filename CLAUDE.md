# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Notif2MQTT is a lightweight Android application that captures device notifications and relays them to an MQTT broker. It also includes a Linux Python daemon that receives and displays these notifications on desktop. The project bridges Android and Linux ecosystems using MQTT as the transport layer.

**Tech Stack**: Kotlin (Android), Python 3 (Linux integration), Eclipse Paho MQTT, Material Design 3

## Common Commands

### Building

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (with minification)
./gradlew assembleRelease

# Build and install on connected device
./gradlew installDebug

# Build with verbose logging
./gradlew assembleDebug -i

# Clean build artifacts
./gradlew clean

# View dependencies
./gradlew dependencies
```

### Development

```bash
# Run linting checks (if configured)
./gradlew lint

# Format Kotlin code (if ktlint is configured)
./gradlew ktlintFormat

# Run unit tests (currently not configured)
./gradlew test

# Run instrumentation tests on device (currently not configured)
./gradlew connectedAndroidTest
```

### Linux Components

```bash
# Initialize Linux receiver configuration
python3 notif2mqtt.py --init-config

# Run Linux receiver in foreground (debug mode)
python3 notif2mqtt.py

# Run as daemon (systemd mode)
python3 notif2mqtt.py --daemon

# Install as systemd service
sudo ./install.sh

# Uninstall systemd service
sudo ./uninstall.sh
```

## Architecture Overview

The application uses a **service-based, event-driven architecture** with clear separation between Android and Linux components:

### Android Side (Kotlin)

```
Notification System
        ↓
NotificationListenerService (captures notifications)
        ↓
NotificationData (models notification with metadata)
        ↓
MqttService (foreground service, manages lifecycle)
        ↓
MqttManager (MQTT client, publishes messages)
        ↓
Settings Management (SharedPreferences via SettingsManager)
        ↑
MainActivity (UI layer for configuration)
```

**Key Components**:

- **NotificationListenerService** (`app/src/main/java/com/notif2mqtt/NotificationListener.kt`): System service that intercepts all notifications via Android's `onNotificationPosted()` callback. Filters excluded apps, extracts metadata (title, text, importance, category), converts icons to Base64, and forwards to MqttService.

- **MqttService** (`app/src/main/java/com/notif2mqtt/mqtt/MqttService.kt`): Foreground service that maintains MQTT broker connection. Publishes notification data as JSON payloads. Auto-reconnects on failure. Must be foreground to prevent Android from terminating it.

- **MqttManager** (`app/src/main/java/com/notif2mqtt/mqtt/MqttManager.kt`): Lightweight wrapper around Eclipse Paho MQTT client. Handles connection setup, authentication, and message publishing with QoS 1 (at-least-once delivery).

- **SettingsManager** (`app/src/main/java/com/notif2mqtt/SettingsManager.kt`): Centralized configuration using SharedPreferences. Manages MQTT broker settings, topic, credentials, app exclusion list, and service state.

- **MainActivity** (`app/src/main/java/com/notif2mqtt/MainActivity.kt`): Configuration UI. RecyclerView for app exclusion list, TextInputLayout fields for MQTT settings, permission status display. Updates MqttService when settings change.

- **NotificationData** (`app/src/main/java/com/notif2mqtt/models/NotificationData.kt`): Data class representing a captured notification. Provides `toJson()` for MQTT transmission and `getUrgencyLevel()` to map Android importance levels to standard urgency levels.

- **BootReceiver** (`app/src/main/java/com/notif2mqtt/BootReceiver.kt`): Broadcast receiver that auto-starts MqttService on device boot.

### Linux Side (Python)

```
MQTT Broker
    ↓
notif2mqtt.py (subscribes to topic)
    ↓
libnotify (native notification system)
    ↓
Desktop Notification
```

**Key Features**:

- XDG Base Directory compliant configuration (`~/.config/notif2mqtt/config.ini`)
- Parses MQTT JSON payloads, decodes Base64 icons
- Maps importance levels to notification urgency
- Systemd integration with auto-restart policy
- Color-coded console output for debugging

## Data Flow

1. **Notification Capture**: Android system notification arrives → `NotificationListenerService.onNotificationPosted()`
2. **Extraction**: Extract package name, app name, title, text, importance, category, icon (as Base64)
3. **Modeling**: Create `NotificationData` object, calculate urgency level
4. **Publishing**: Intent sent to `MqttService` with serialized notification
5. **MQTT Transmission**: `MqttManager` publishes JSON to configured MQTT topic (QoS 1)
6. **Linux Reception**: `notif2mqtt.py` receives JSON, creates notification via libnotify
7. **Display**: Desktop shows notification with urgency level, app icon, title, text

## Data Model

Notifications are transmitted as JSON:

```json
{
  "package": "com.whatsapp",
  "app": "WhatsApp",
  "title": "John Doe",
  "text": "New message",
  "timestamp": 1703001234567,
  "icon": "iVBORw0KGgo...",
  "importance": 4,
  "urgency": "high",
  "category": "msg"
}
```

**Importance Levels** (Android): 0 (none) → 5 (max)
**Urgency Mapping**: 4-5 = "high" (CRITICAL), 3 = "normal" (NORMAL), 1-2 = "low" (LOW), 0 = "minimal" (LOW)

## Build System

**Gradle Configuration**:
- **Root**: `build.gradle.kts` - Kotlin, Android Gradle Plugin version management
- **App Module**: `app/build.gradle.kts` - Dependencies, build types, product flavors
- **Namespace**: `com.notif2mqtt`
- **Target SDK**: 34 (Android 14), **Min SDK**: 24 (Android 7.0)
- **Kotlin Version**: 1.9.24, **JVM Target**: 1.8

**Key Dependencies**:
- `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5` - MQTT client
- `androidx.appcompat:appcompat` - Core AndroidX libraries
- `com.google.android.material:material` - Material Design 3
- `androidx.recyclerview:recyclerview` - List UI component

**Optimization**:
- R8/ProGuard minification enabled for release builds
- Resource shrinking enabled
- Gradle parallel builds and caching enabled

## Key Android Permissions

Located in `AndroidManifest.xml`:
- `INTERNET` - MQTT broker communication
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` - Foreground service operation
- `WAKE_LOCK` - Prevent sleep during MQTT operations
- `POST_NOTIFICATIONS` - Display service notification (Android 13+)
- `ACCESS_NETWORK_STATE` - Monitor connectivity
- `RECEIVE_BOOT_COMPLETED` - Auto-start on device boot

## Important Design Decisions

1. **Foreground Service**: MqttService runs as foreground service (not a sticky service alone) to prevent Android from terminating it during operation. Shows continuous notification to user.

2. **MQTT QoS 1**: Ensures at-least-once message delivery. May result in duplicate notifications if reconnection occurs, but guarantees no lost messages.

3. **SharedPreferences**: Simple key-value storage for settings. Not encrypted by default—credentials stored in plain text. For sensitive deployments, consider using EncryptedSharedPreferences.

4. **Base64 Icons**: Notification icons encoded as Base64 strings in JSON payload to self-contain notification data within MQTT message. Icons resized to 128x128 for performance.

5. **App Exclusion List**: Stored as pipe-separated package names in SharedPreferences. Checked before processing each notification.

6. **XDG Compliance**: Linux receiver uses `$XDG_CONFIG_HOME` (or `~/.config`) for configuration, following Linux standards.

## Testing

**Current Status**: No automated tests configured.

**Recommendations**:
- Unit tests for `NotificationData` JSON serialization
- Unit tests for `SettingsManager` persistence
- Integration tests for `MqttManager` connection/publishing
- Instrumentation tests for `MainActivity` UI interactions
- Mock tests for `NotificationListenerService` callbacks
- Python tests for `notif2mqtt.py` JSON parsing and systemd integration

## Troubleshooting Development Issues

### "Notification listener not receiving notifications"
- Verify `NotificationListenerService` is declared in `AndroidManifest.xml` with correct intent filter
- Check user granted `NOTIFICATION_LISTENER_SERVICE` permission in Settings > Apps
- Ensure `onNotificationPosted()` callback is not throwing exceptions

### "MQTT connection fails"
- Verify broker URL includes protocol (`tcp://` not just IP:port)
- Check network connectivity and firewall rules
- Ensure credentials are correct if broker requires authentication
- Verify `MqttManager` QoS and timeout settings

### "Service stops after app backgrounding"
- Confirm `MqttService` is a foreground service with proper notification
- Verify `START_STICKY` return value in `onStartCommand()`
- Check device battery optimization settings aren't killing the app

### "Linux receiver doesn't show notifications"
- Verify libnotify is installed: `sudo apt install libnotify-bin` (Ubuntu) or `pacman -S libnotify` (Arch)
- Check PyGObject installed: `pip install PyGObject`
- Verify MQTT broker connectivity and topic subscribed correctly
- Test icon decoding by checking temp `/tmp/notif_*.png` files

## File Organization

```
app/src/main/
├── java/com/notif2mqtt/          # Kotlin source code
│   ├── MainActivity.kt            # UI configuration & app list
│   ├── NotificationListener.kt    # System notification capture
│   ├── SettingsManager.kt         # Configuration persistence
│   ├── BootReceiver.kt            # Auto-start on boot
│   ├── mqtt/
│   │   ├── MqttService.kt         # Background foreground service
│   │   └── MqttManager.kt         # MQTT client wrapper
│   └── models/
│       └── NotificationData.kt    # Notification data model
└── res/
    ├── layout/                    # XML layout files
    ├── values/strings.xml         # UI strings
    └── drawable/                  # App icons
```

## Linux Integration

The Python component (`notif2mqtt.py`) is a standalone daemon that:
1. Reads MQTT configuration from `~/.config/notif2mqtt/config.ini`
2. Subscribes to the MQTT topic
3. Parses JSON notification payloads
4. Decodes Base64 icons to temporary PNG files
5. Creates and displays libnotify notifications
6. Maps urgency levels appropriately

The `install.sh` script automates installation as a systemd user service, ensuring the receiver persists across reboots.
