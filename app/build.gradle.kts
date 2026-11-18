plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sahayak.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sahayak.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 154
        versionName = "16.6"
        // Call wali library ke sirf phone wale hisse, taaki app chhota rahe
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Test key: har build par same rehti hai, taaki app update ho sake.
    signingConfigs {
        create("test") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("test")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Awaaz aur video call (WebRTC)
    implementation("io.getstream:stream-webrtc-android:1.3.9")
    // PDF ke andar ke shabd padhne ke liye (search)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
