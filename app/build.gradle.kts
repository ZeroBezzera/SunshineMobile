plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.sunshinemobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.sunshinemobile"
        minSdk = 29          // AudioPlaybackCapture needs API 29+
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources {
            excludes += setOf("META-INF/versions/**", "META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}
