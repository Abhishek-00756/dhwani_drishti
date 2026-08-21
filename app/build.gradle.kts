import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ============================================================
// Read local.properties
// ============================================================

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")

if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use {
        localProperties.load(it)
    }
}

val geminiApiKey =
    localProperties.getProperty("GEMINI_API_KEY") ?: ""


// ============================================================
// Android configuration
// ============================================================

android {
    namespace = "com.dhwanidrishti.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dhwanidrishti.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Gemini API key from local.properties
        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            "\"$geminiApiKey\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    // Required for BuildConfig fields
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
}


// ============================================================
// Dependencies
// ============================================================

dependencies {

    // --------------------------------------------------------
    // AndroidX
    // --------------------------------------------------------

    implementation("androidx.core:core-ktx:1.13.1")

    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation(
        "com.google.android.material:material:1.12.0"
    )

    implementation(
        "androidx.activity:activity-ktx:1.9.3"
    )

    implementation(
        "androidx.constraintlayout:constraintlayout:2.2.0"
    )


    // --------------------------------------------------------
    // CameraX
    // --------------------------------------------------------

    implementation(
        "androidx.camera:camera-core:1.4.0"
    )

    implementation(
        "androidx.camera:camera-camera2:1.4.0"
    )

    implementation(
        "androidx.camera:camera-lifecycle:1.4.0"
    )

    implementation(
        "androidx.camera:camera-view:1.4.0"
    )


    // --------------------------------------------------------
    // LiteRT / TensorFlow Lite
    // --------------------------------------------------------

    implementation(
        "com.google.ai.edge.litert:litert:1.0.1"
    )

    implementation(
        "com.google.ai.edge.litert:litert-gpu:1.0.1"
    )

    implementation(
        "com.google.ai.edge.litert:litert-support-api:1.0.1"
    )


    // --------------------------------------------------------
    // Kotlin Coroutines
    // --------------------------------------------------------

    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1"
    )


    // --------------------------------------------------------
    // ML Kit OCR
    // --------------------------------------------------------

    implementation(
        "com.google.mlkit:text-recognition:16.0.1"
    )


    // --------------------------------------------------------
    // OkHttp
    // Used for Gemini REST API communication
    // --------------------------------------------------------

    implementation(
        "com.squareup.okhttp3:okhttp:4.12.0"
    )
}