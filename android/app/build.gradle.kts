plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Semantic Versioning 2.0.0 (MAJOR.MINOR.PATCH)
//  - MAJOR: architectural milestone (0 = pre-1.0 active development, 1+ = stable GA)
//  - MINOR: new feature phase / significant capability addition
//  - PATCH: bug fixes, vision/solver calibration, and stability improvements
// Android versionCode formula: MAJOR * 10000 + MINOR * 100 + PATCH (e.g. 0.3.0 -> 300, 0.3.1 -> 301, 1.0.0 -> 10000)
val appVersionMajor = 0
val appVersionMinor = 8
val appVersionPatch = 0
val appVersionCode = appVersionMajor * 10000 + appVersionMinor * 100 + appVersionPatch
val appVersionName = "$appVersionMajor.$appVersionMinor.$appVersionPatch"

android {
    namespace = "com.autodora.next"
    compileSdk = 35
    buildToolsVersion = "35.0.1"
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.autodora.next"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // Sign with debug key by default so GitHub Release APKs install & upgrade seamlessly on test devices
            // until a dedicated release keystore is supplied via AUTODORA_KEYSTORE_PATH.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("tflite", "onnx")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

base {
    archivesName.set("AutoDora-NEXT-v$appVersionName")
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-capture"))
    implementation(project(":core-vision"))
    implementation(project(":core-touch"))
    implementation(project(":core-data"))
    implementation(project(":solver-jni"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
