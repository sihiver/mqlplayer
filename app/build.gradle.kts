import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

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

    implementation(libsCatalog.findLibrary("androidx-core-ktx").get())
    implementation(libsCatalog.findLibrary("androidx-appcompat").get())
    implementation(platform(libsCatalog.findLibrary("androidx-compose-bom").get()))
    implementation(libsCatalog.findLibrary("androidx-compose-ui").get())
    implementation(libsCatalog.findLibrary("androidx-compose-ui-graphics").get())
    implementation(libsCatalog.findLibrary("androidx-compose-ui-tooling-preview").get())
    implementation(libsCatalog.findLibrary("androidx-tv-foundation").get())
    implementation(libsCatalog.findLibrary("androidx-tv-material").get())
    implementation(libsCatalog.findLibrary("androidx-lifecycle-runtime-ktx").get())
    implementation(libsCatalog.findLibrary("androidx-activity-compose").get())

    // Material3 for phone UI
    implementation(libsCatalog.findLibrary("androidx-compose-material3").get())
    implementation(libsCatalog.findLibrary("androidx-compose-material-icons-extended").get())

    // ExoPlayer for IPTV streaming (Media3)
    implementation(libsCatalog.findLibrary("androidx-media3-exoplayer").get())
    implementation(libsCatalog.findLibrary("androidx-media3-exoplayer-hls").get())
    implementation(libsCatalog.findLibrary("androidx-media3-exoplayer-dash").get()) // DASH + DRM support
    implementation(libsCatalog.findLibrary("androidx-media3-ui").get())
    implementation(libsCatalog.findLibrary("androidx-media3-extractor").get())
    implementation(libsCatalog.findLibrary("androidx-media3-exoplayer-rtsp").get())

    // NextLib FFmpeg extension for MPEG-L2 audio codec support (like M3UAndroid)
    implementation(libsCatalog.findLibrary("io-github-anilbeesetti-nextlib-media3ext").get())

    // OkHttp DataSource for ExoPlayer network streaming
    implementation(libsCatalog.findLibrary("androidx-media3-datasource-okhttp").get())

    // ViewModel
    implementation(libsCatalog.findLibrary("androidx-lifecycle-viewmodel-compose").get())

    // Coil for image loading
    implementation(libsCatalog.findLibrary("coil-compose").get())

    // Coroutines
    implementation(libsCatalog.findLibrary("androidx-coroutines-android").get())

    // Encrypted storage for sensitive credentials
    implementation(libsCatalog.findLibrary("androidx-security-crypto").get())

    // Splash Screen API (backport sampai API 21)
    implementation(libsCatalog.findLibrary("androidx-core-splashscreen").get())

    // Android TV — saluran rekomendasi di beranda (API 26+)
    implementation(libsCatalog.findLibrary("androidx-tvprovider").get())
    
    // VLC for Android - libVLC
    implementation(libsCatalog.findLibrary("org-videolan-android-libvlc-all").get())
    
    androidTestImplementation(platform(libsCatalog.findLibrary("androidx-compose-bom").get()))
    androidTestImplementation(libsCatalog.findLibrary("androidx-compose-ui-test-junit4").get())
    debugImplementation(libsCatalog.findLibrary("androidx-compose-ui-tooling").get())
    debugImplementation(libsCatalog.findLibrary("androidx-compose-ui-test-manifest").get())
}
