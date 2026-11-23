plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Market Watch ka apna code yahin (market/src) hai. Sahayak se ab kuch nahi aata (23 Nov 2026 se alag).

android {
    namespace = "com.sahayak.market"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sahayak.market"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.9"
    }

    // Do app: hi = Hinglish (pehle wala), mr = Marathi (alag app, dono saath install ho sakte hain)
    flavorDimensions += "lang"
    productFlavors {
        create("hi") { dimension = "lang" }
        create("mr") {
            dimension = "lang"
            applicationIdSuffix = ".mr"
        }
    }

    signingConfigs {
        create("test") {
            storeFile = file("../app/debug.keystore")
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
