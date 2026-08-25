import java.util.Properties
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.forge.audiobookforge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.forge.audiobookforge"
        minSdk = 29
        targetSdk = 34
        versionCode = 52
        versionName = "0.4.6"

        // RedMagic 10S Pro and effectively all modern devices are arm64.
        // Add "x86_64" here if you want emulator support (requires matching .so files).
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Release signing: prefer keystore.properties (storeFile/storePassword/
    // keyAlias/keyPassword). Fallback reuses the auto-generated debug keystore so
    // a signed release updates over existing sideloaded builds seamlessly.
    val ksProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) } else {
            val dbg = File(
                System.getenv("ANDROID_USER_HOME")
                    ?: "/home/kingfish600/workspace/tools/gradle-home/android-user",
                "debug.keystore",
            )
            if (dbg.isFile) {
                setProperty("storeFile", dbg.absolutePath)
                setProperty("storePassword", "android")
                setProperty("keyAlias", "androiddebugkey")
                setProperty("keyPassword", "android")
            }
        }
    }

    signingConfigs {
        create("release") {
            ksProps.getProperty("storeFile")?.let {
                storeFile = file(it)
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.commons.compress)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
