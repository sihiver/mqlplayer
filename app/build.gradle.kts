import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sihiver.mqltv"
    compileSdk = 36

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    defaultConfig {
        applicationId = "com.sihiver.mqltv"
        minSdk = 23
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"

    }

    signingConfigs {
        create("release") {
            storeFile = file("../mqltv-release-key.jks")
            storePassword = "mqltv2024"
            keyAlias = "mqltv-key"
            keyPassword = "mqltv2024"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Material3 for phone UI
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // ExoPlayer for IPTV streaming (Media3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash) // DASH + DRM support
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.androidx.media3.exoplayer.rtsp)

    // NextLib FFmpeg extension for MPEG-L2 audio codec support (like M3UAndroid)
    implementation(libs.io.github.anilbeesetti.nextlib.media3ext)

    // OkHttp DataSource for ExoPlayer network streaming
    implementation(libs.androidx.media3.datasource.okhttp)

    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Coil for image loading
    implementation(libs.coil.compose)

    // Coroutines
    implementation(libs.androidx.coroutines.android)

    // Encrypted storage for sensitive credentials
    implementation(libs.androidx.security.crypto)

    // Splash Screen API (backport sampai API 21)
    implementation(libs.androidx.core.splashscreen)

    // Android TV — saluran rekomendasi di beranda (API 26+)
    implementation(libs.androidx.tvprovider)
    
    // VLC for Android - libVLC
    implementation(libs.org.videolan.android.libvlc.all)
    
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
