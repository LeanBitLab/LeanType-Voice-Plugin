import org.gradle.api.JavaVersion

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.parcelize")
}

import java.util.Properties

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
        versionCode = 102
        versionName = "1.0.2"

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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    splits {
        abi {
            isEnable = true
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
                output?.outputFileName = "voice_plugin.apk"
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    testImplementation("junit:junit:4.13.2")
}
