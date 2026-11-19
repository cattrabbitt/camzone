plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cameroncurrin.camzone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cameroncurrin.camzone"
        minSdk = 29
        targetSdk = 34
        // CI sets the run number so every build is newer than the last one
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "1.$run"
    }

    // One fixed key for every build, so a new APK installs over the old one
    signingConfigs {
        getByName("debug") {
            storeFile = file("camzone-debug.jks")
            storePassword = "camzone"
            keyAlias = "camzone"
            keyPassword = "camzone"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
