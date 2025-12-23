# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Notif2MQTT is a lightweight Android application that captures device notifications and relays them to an MQTT broker. The app bridges Android notifications to any MQTT consumer using a standardized JSON format over MQTT.

**Tech Stack**: Kotlin (Android), Eclipse Paho MQTT with TLS/SSL support, Material Design 3

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

## Architecture Overview

The application uses a **service-based, event-driven architecture**:

### Android Components (Kotlin)

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

- **MqttManager** (`app/src/main/java/com/notif2mqtt/mqtt/MqttManager.kt`): Lightweight wrapper around Eclipse Paho MQTT client. Handles connection setup, authentication, SSL/TLS configuration, and message publishing with QoS 1 (at-least-once delivery). Supports both unencrypted TCP and encrypted SSL connections.

- **SettingsManager** (`app/src/main/java/com/notif2mqtt/SettingsManager.kt`): Centralized configuration using EncryptedSharedPreferences with AES256-GCM encryption. Manages MQTT broker settings, topic, credentials, app exclusion list, and service state with secure encryption.

- **MainActivity** (`app/src/main/java/com/notif2mqtt/MainActivity.kt`): Configuration UI. RecyclerView for app exclusion list, TextInputLayout fields for MQTT settings, permission status display. Updates MqttService when settings change.

- **NotificationData** (`app/src/main/java/com/notif2mqtt/models/NotificationData.kt`): Data class representing a captured notification. Provides `toJson()` for MQTT transmission and `getUrgencyLevel()` to map Android importance levels to standard urgency levels.

- **BootReceiver** (`app/src/main/java/com/notif2mqtt/BootReceiver.kt`): Broadcast receiver that auto-starts MqttService on device boot.

## Data Flow

1. **Notification Capture**: Android system notification arrives → `NotificationListenerService.onNotificationPosted()`
2. **Extraction**: Extract package name, app name, title, text, importance, category, icon (as Base64)
3. **Modeling**: Create `NotificationData` object, calculate urgency level
4. **Publishing**: Intent sent to `MqttService` with serialized notification
5. **MQTT Transmission**: `MqttManager` publishes JSON to configured MQTT topic (QoS 1)

**Note**: For consuming these notifications on Linux desktop, see the companion project **[mqtt2notif](https://github.com/yourusername/mqtt2notif)**

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
- **Android Gradle Plugin**: 8.5.2
- **Kotlin Version**: 2.2.21, **JVM Target**: 17

**Key Dependencies**:
- `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5` - MQTT client
- `androidx.appcompat:appcompat` - Core AndroidX libraries
- `com.google.android.material:material` - Material Design 3
- `androidx.recyclerview:recyclerview` - List UI component
- `androidx.security:security-crypto:1.1.0-alpha06` - Encrypted preferences for sensitive credentials

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

3. **SSL/TLS Support**: MQTT connections support both unencrypted TCP (`tcp://`) and encrypted SSL (`ssl://`) protocols. SSL connections use trust-all certificates by default to accommodate self-signed certificates common in MQTT deployments.

4. **EncryptedSharedPreferences**: Credentials and settings encrypted using AES256-GCM via AndroidX Security Crypto library. MasterKey uses AES256-GCM key scheme with SIV key encryption and GCM value encryption for maximum security.

5. **Base64 Icons**: Notification icons encoded as Base64 strings in JSON payload to self-contain notification data within MQTT message. Icons resized to 128x128 for performance.

6. **App Exclusion List**: Stored as pipe-separated package names in SharedPreferences. Checked before processing each notification.

## Testing

**Current Status**: No automated tests configured.

**Recommendations**:
- Unit tests for `NotificationData` JSON serialization
- Unit tests for `SettingsManager` persistence
- Integration tests for `MqttManager` connection/publishing
- Instrumentation tests for `MainActivity` UI interactions
- Mock tests for `NotificationListenerService` callbacks

## Troubleshooting Development Issues

### "Notification listener not receiving notifications"
- Verify `NotificationListenerService` is declared in `AndroidManifest.xml` with correct intent filter
- Check user granted `NOTIFICATION_LISTENER_SERVICE` permission in Settings > Apps
- Ensure `onNotificationPosted()` callback is not throwing exceptions

### "MQTT connection fails"
- Verify broker URL includes protocol (`tcp://` for unencrypted or `ssl://` for encrypted connections)
- For SSL connections, ensure the broker uses a valid certificate or self-signed certificates are accepted
- Check network connectivity and firewall rules
- Ensure credentials are correct if broker requires authentication
- Verify `MqttManager` QoS and timeout settings

### "Service stops after app backgrounding"
- Confirm `MqttService` is a foreground service with proper notification
- Verify `START_STICKY` return value in `onStartCommand()`
- Check device battery optimization settings aren't killing the app

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

## Recent Updates

**Latest Changes**:
- **TLS/SSL Support**: Added secure MQTT connections with `ssl://` protocol support and self-signed certificate handling
- **Build System Updates**: Updated Android Gradle Plugin to 8.5.2 for Gradle 10 compatibility
- **Enhanced UI**: Added TLS connection status indicators and improved broker URL validation

## Related Projects

**mqtt2notif**: Python daemon for receiving MQTT notifications and displaying them on Linux desktop via libnotify. Complements this Android app to complete the notification bridge.

- Repository: https://github.com/yourusername/mqtt2notif
- Together they form: Android → Notif2MQTT → MQTT → mqtt2notif → Linux Desktop
