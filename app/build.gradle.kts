import org.gradle.api.JavaVersion

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.parcelize")
}

import java.util.Properties

// -PcompatCpu=true builds the arm64 APK for CPUs without the ARMv8.2 dot-product and FP16 instructions
val compatCpu = providers.gradleProperty("compatCpu").orNull == "true"
if (compatCpu) {
    // separate output, so that the fast and the compat build do not overwrite each other
    layout.buildDirectory.set(file("build-compat"))
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    compileSdk = 36
    namespace = "com.leanbitlab.leantype.voice.offline"

    defaultConfig {
        applicationId = "com.leanbitlab.leantype.voice.offline"
        minSdk = 21
        targetSdk = 35
        versionCode = 104
        versionName = "1.0.4"

        // true if the arm64 library of this build needs ARMv8.2 dot-product and FP16 (see CMakeLists.txt)
        buildConfigField("boolean", "ARM64_NEEDS_ARMV82", (!compatCpu).toString())

        externalNativeBuild {
            cmake {
                arguments("-DCMAKE_BUILD_TYPE=Release", "-DWHISPER_NO_OPENMP=ON", "-DVOICE_COMPAT_CPU=${if (compatCpu) "ON" else "OFF"}")
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
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
            if (compatCpu) buildStagingDirectory = file(".cxx-compat")
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            ndk {
                // the compat build is a single arm64 APK, there are no ABI splits (see below)
                abiFilters += if (compatCpu) listOf("arm64-v8a") else listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    splits {
        abi {
            isEnable = !compatCpu
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as? com.android.build.gradle.api.ApkVariantOutput
            val abi = output?.filters?.find { it.filterType == "ABI" }?.identifier
            if (abi != null) {
                output.outputFileName = "voice_plugin-$abi.apk"
            } else {
                output?.outputFileName = if (compatCpu) "voice_plugin-arm64-v8a-compat.apk" else "voice_plugin.apk"
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    testImplementation("junit:junit:4.13.2")
}
