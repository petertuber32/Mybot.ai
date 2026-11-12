// app/build.gradle.kts — BAC BO AI ANALYZER (Mybot.ai)
//
// SECURITY RULES (do not change):
//  - The release keystore is NEVER committed. It is provided at CI time
//    through the environment variables KEYSTORE_FILE / KEYSTORE_PASSWORD /
//    KEY_ALIAS / KEY_PASSWORD (sourced from GitHub Secrets).
//  - The applicationId below is PERMANENT: changing it would prevent
//    updates being installed over previous versions.
//  - Versioning lives in gradle.properties (APP_VERSION_CODE / APP_VERSION_NAME).

import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionCode: Int =
    (properties["APP_VERSION_CODE"] as? String)?.trim()?.toIntOrNull() ?: 1
val appVersionName: String =
    (properties["APP_VERSION_NAME"] as? String)?.trim() ?: "1.0.0"

android {
    namespace = "com.mybotai.bacboanalyzer"
    compileSdk = 34

    defaultConfig {
        // PERMANENT package name. Same value in every future release.
        applicationId = "com.mybotai.bacboanalyzer"
        minSdk = 26          // Android 8.0 (TYPE_APPLICATION_OVERLAY)
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            // Filled only when the CI provides the keystore via env vars.
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null && File(ksFile).exists()) {
                storeFile = File(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ksFile = System.getenv("KEYSTORE_FILE")
            signingConfig =
                if (ksFile != null && File(ksFile).exists()) {
                    signingConfigs.getByName("release")
                } else {
                    null // local build without secrets -> unsigned APK
                }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")

    // JSON persistence + GitHub update check (pure JVM, unit tested)
    implementation("com.google.code.gson:gson:2.11.0")

    // On-device OCR for screenshot import (bundled model, works offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
