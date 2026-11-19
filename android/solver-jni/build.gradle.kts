plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.autodora.next.jni"
    compileSdk = 35
    buildToolsVersion = "35.0.1"
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = 29
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-O3")
                arguments += listOf("-DAUTODORA_BUILD_TESTS=OFF")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    api(project(":core-model"))
    implementation(libs.kotlinx.coroutines.core)
}
