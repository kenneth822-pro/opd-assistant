// On-device Nepali speech engine: whisper.cpp (ggml) on the ARM64 CPU, behind the LocalSpeechRecognizer contract.
//
// OPT-IN: this module is only part of the build with -PenableNativeAsr=true (see settings.gradle.kts), so the default
// build and CI never depend on the NDK/CMake toolchain or on network access to GitHub at configure time.
//
// Tuning knob, measured on the device (see docs/ON_DEVICE_ASR.md):
//   -PasrArmArch=armv8.2-a+dotprod+fp16        (default, runs on every ARMv8.2+ phone)
//   -PasrArmArch=armv8.6-a+i8mm+dotprod+fp16   (Snapdragon 8 Gen 1 and newer, incl. 8 Elite Gen 5)
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val armArch = (project.findProperty("asrArmArch") as String?) ?: "armv8.2-a+dotprod+fp16"

android {
    namespace = "np.opd.asr"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // arm64 only: the first target is the OnePlus 15 (Snapdragon 8 Elite Gen 5).
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DOPD_ARM_ARCH=$armArch")
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":speech"))

    // Device smoke test only (src/androidTest); nothing here ships in the app.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
