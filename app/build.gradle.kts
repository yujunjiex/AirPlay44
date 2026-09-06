plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

import java.util.Properties

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "com.localair.airplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.yujunjiex.airplay44"
        minSdk = 19
        // Sideload-only legacy target for the Android 4.4 TV firmware.
        targetSdk = 28
        versionCode = 5
        versionName = "0.1.4-rc1"
    }

    if (localProps.containsKey("storeFile")) {
        signingConfigs {
            create("release") {
                storeFile = file(localProps.getProperty("storeFile"))
                storePassword = localProps.getProperty("storePassword")
                keyAlias = localProps.getProperty("keyAlias")
                keyPassword = localProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }

    lint {
        // This APK is deliberately sideloaded on Android 4.4, not published
        // through Google Play, where the target-SDK deadline would apply.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(project(":airplay"))
    implementation("org.jmdns:jmdns:3.5.9")
    implementation("org.slf4j:slf4j-android:1.7.36")
    testImplementation("junit:junit:4.13.2")
}
