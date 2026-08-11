plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "cc.moon.internet"
    compileSdk = 35

    defaultConfig {
        applicationId = "cc.moon.internet"
        minSdk = 24                     // Android 7.0 вЂ” VpnService + modern TLS
        targetSdk = 35
        // has to go up on every published APK, or the phone refuses to install over the old one
        versionCode = 24
        versionName = "0.9.3.15"

        // The core is built for arm64 only, so shipping other ABIs would just be an app that
        // crashes the moment it tries to connect.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            // The keystore is never in the repo. Locally it sits next to this file; on CI it
            // arrives as a secret and its path comes in through the environment.
            val ks = file(System.getenv("MOON_KEYSTORE") ?: "moon-release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("MOON_KEYSTORE_PASSWORD")?.ifBlank { null } ?: "moonbeta"
                keyAlias = System.getenv("MOON_KEY_ALIAS")?.ifBlank { null } ?: "moon"
                keyPassword = System.getenv("MOON_KEY_PASSWORD")?.ifBlank { null } ?: "moonbeta"
            }
        }
    }

    buildTypes {
        release {
            // Attach the signing config only when the key is really there. A config with no
            // storeFile does not yield an unsigned APK вЂ” packageRelease dies with
            // 'SigningConfig "release" is missing required property "storeFile"', which would
            // turn "no key on this machine" into a broken build.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // buildConfig so the version lives in one place вЂ” versionName above вЂ” instead of being
    // typed into the About page and the update check separately and drifting apart.
    buildFeatures { compose = true; buildConfig = true }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub that throws; the real one lets config tests run on the JVM
    testImplementation("org.json:json:20240303")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // QR codes for sharing servers
    implementation("com.google.zxing:core:3.5.3")

    // camera preview + frame analysis for the QR scanner
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // sing-box engine (libbox AAR) вЂ” the single core that speaks every protocol we need.
    // Dropped into app/libs by build/get-libbox.ps1
    implementation(fileTree("libs") { include("*.aar") })
}

