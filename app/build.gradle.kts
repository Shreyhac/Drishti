import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.drishti.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.drishti.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Add OPENROUTER_API_KEY=sk-or-xxx to local.properties
        val localProps = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) localProps.load(localPropsFile.inputStream())
        buildConfigField(
            "String", "OPENROUTER_API_KEY",
            "\"${localProps.getProperty("OPENROUTER_API_KEY", "")}\""
        )
        buildConfigField(
            "String", "SARVAM_API_KEY",
            "\"${localProps.getProperty("SARVAM_API_KEY", "")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { pickFirsts += "**/*.so" }
    }

    // TFLite model must stay uncompressed to be memory-mapped at runtime
    androidResources { noCompress += "tflite" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

    // CameraX
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Google Play Services — fused location for emergency SOS GPS
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // ML Kit — OCR (Latin + Devanagari for Hindi)
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.0")

    // ML Kit — Image Labeling (object / currency detection)
    implementation("com.google.mlkit:image-labeling:17.0.7")

    // ML Kit — Barcode / QR scanning
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // ML Kit — Face detection (position, smile, eyes)
    implementation("com.google.mlkit:face-detection:16.1.5")

    // ML Kit — Object detection with bounding boxes (obstacle assistant, find-my-object)
    implementation("com.google.mlkit:object-detection:17.0.2")

    // MediaPipe — On-device Gemma LLM
    implementation("com.google.mediapipe:tasks-genai:0.10.14")

    // MediaPipe — Gesture recognizer (deaf communication, sign language)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // TensorFlow Lite — FaceNet embeddings for recognizing saved people
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

    // OkHttp — OpenRouter cloud fallback
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
