# Add project specific ProGuard rules here.
# Keep MQTT classes
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**

# Keep notification data models
-keep class com.notif2mqtt.models.** { *; }
