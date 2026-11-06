plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Chhotu aur to-do ka code Sahayak se hi aata hai, do copy nahi rakhni
val sharedCode = tasks.register<Copy>("sharedCode") {
    from("../app/src/main/java/com/sahayak/app") { include("MiniBuddy.kt", "Todos.kt", "TodoPopup.kt") }
    into(layout.buildDirectory.dir("shared/com/sahayak/app"))
}

android {
    namespace = "com.chhotu.todo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chhotu.todo"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
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

    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("shared"))
    sourceSets["main"].assets.srcDir("../app/src/main/assets")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.named("preBuild") { dependsOn(sharedCode) }
