# Add project specific ProGuard rules here.
# Keep MQTT classes
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**

# Keep notification data models
-keep class com.notif2mqtt.models.** { *; }

# Fix R8 issues with Tink/javax.annotation
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.GuardedBy
