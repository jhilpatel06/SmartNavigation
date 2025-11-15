plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.smartnavigation"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.smartnavigation"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11

        // Add this to prevent build errors with ARCore
        isCoreLibraryDesugaringEnabled = true // <--- ADD THIS
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        viewBinding = true
    }


    packaging {
        resources.excludes += "/META-INF/*"
    }
}

dependencies {
    // --- ADD ALL OF THESE --- //
    // ARCore (SLAM)
    implementation("com.google.ar:core:1.44.0")
    implementation("com.google.ar.sceneform:core:1.17.1")
    implementation("com.google.ar.sceneform.ux:sceneform-ux:1.17.1")

    // Desugaring, required for ARCore
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    // --- END OF ADDITIONS --- //


    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")

    // Jetpack Compose
    // Jetpack Compose Navigation
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // CameraX for SLAM (These are from your original file, now replaced by ARCore)
    implementation("androidx.camera:camera-core:1.3.3")
    implementation("androidx.camera:camera-camera2:1.3.3")
    implementation("androidx.camera:camera-lifecycle:1.3.3")
    implementation("androidx.camera:camera-view:1.3.3")

    // Accompanist Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")

    // Sensor + Coroutines (for real-time IMU)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ML Kit Vision (optional — for Visual SLAM / features)
    implementation("com.google.mlkit:vision-common:17.3.0")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}