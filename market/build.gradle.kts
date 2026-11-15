plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Market Watch ka code Sahayak se hi aata hai, do copy nahi rakhni
val sharedCode = tasks.register<Copy>("sharedCode") {
    from("../app/src/main/java/com/sahayak/app") {
        include(
            "Market.kt", "MarketLogic.kt", "MarketActivity.kt", "MarketNewsActivity.kt", "ShareActivity.kt", "ChartView.kt", "FinanceView.kt",
            "GreenList.kt", "Patterns.kt", "PatternLogic.kt", "PatternActivity.kt", "PatternLockActivity.kt",
            "Funds.kt", "FundLogic.kt", "FundsActivity.kt", "FundActivity.kt", "BuyDialog.kt", "BuffettLogic.kt", "BuffettCard.kt",
            "Planner.kt", "StoryLogic.kt", "ShortsLogic.kt", "BrainLogic.kt", "WatchLogic.kt", "Watcher.kt", "WatchActivity.kt",
            "SecureStore.kt", "GeminiLive.kt"
        )
    }
    into(layout.buildDirectory.dir("shared/com/sahayak/app"))
}

android {
    namespace = "com.sahayak.market"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sahayak.market"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

tasks.named("preBuild") { dependsOn(sharedCode) }
