// Get version name from the latest git tag (e.g., "v1.2.3" -> "1.2.3")
val gitVersionName: Provider<String> = providers.exec {
    commandLine("git", "describe", "--tags", "--abbrev=0")
    isIgnoreExitValue = true
}.standardOutput.asText.map { output ->
    val result = output.trim()
    if (result.isNotEmpty()) result.removePrefix("v") else "0.0.0"
}.orElse("0.0.0")

// Get version code from the total number of commits
val gitVersionCode: Provider<Int> = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { output ->
    output.trim().toIntOrNull() ?: 1
}.orElse(1)

plugins {
    id("com.android.application")
}

android {
    namespace = "com.notif2mqtt"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.notif2mqtt"
        minSdk = 24
        targetSdk = 36
        versionCode = gitVersionCode.get()
        versionName = gitVersionName.get()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // Lifecycle and Coroutines
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // MQTT Client (lightweight)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.eclipse.paho:org.eclipse.paho.android.service:1.1.1")

    // JSON
    implementation("org.json:json:20251224")
}
