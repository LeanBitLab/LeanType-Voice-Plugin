import org.gradle.api.JavaVersion

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.parcelize")
}

android {
    compileSdk = 36
    namespace = "com.leanbitlab.leantype.voice.offline"

    defaultConfig {
        applicationId = "com.leanbitlab.leantype.voice.offline"
        minSdk = 21
        targetSdk = 35
        versionCode = 100
        versionName = "1.0.0"

        externalNativeBuild {
            cmake {
                arguments("-DCMAKE_BUILD_TYPE=Release", "-DWHISPER_NO_OPENMP=ON")
                cFlags("-O3", "-DNDEBUG")
                cppFlags("-O3", "-DNDEBUG")
            }
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
        aidl = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as? com.android.build.gradle.api.ApkVariantOutput
            output?.outputFileName = "voice_plugin.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}
