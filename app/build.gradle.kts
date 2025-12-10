plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sihiver.mqltv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sihiver.mqltv"
        minSdk = 21
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
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
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.0")
    
    // ExoPlayer for IPTV streaming (1.3.1 compatible with NextLib 0.7.1)
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-extractor:1.8.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.8.0")
    
    // NextLib FFmpeg extension for MPEG-L2 audio codec support (like M3UAndroid)
    // Replace 1.8.0 with the version of Media3 you are using
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.8.0-0.9.0")
    
    // OkHttp DataSource for ExoPlayer network streaming
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    
    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    
    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.7.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    
    // VLC for Android - libVLC
    implementation("org.videolan.android:libvlc-all:3.6.0")
    
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
