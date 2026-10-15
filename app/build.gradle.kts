plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// On aarch64 hosts (e.g. building the APK directly on an ARM64 Android device/Termux/Alpine)
// Google's x86_64 aapt2 cannot run. If a native arm64 aapt2 is installed and the property has
// not been set on the command line, point AGP at it. On x86_64 CI runners this is a no-op.
val osArch = System.getProperty("os.arch") ?: ""
if (osArch in listOf("aarch64", "arm64") && !project.hasProperty("android.aapt2FromMavenOverride")) {
    val candidates = listOf(
        System.getenv("PREFIX")?.let { "$it/bin/aapt2" },
        "/usr/bin/aapt2",
        "/usr/local/bin/aapt2"
    ).filterNotNull()
    val nativeAapt2 = candidates.firstOrNull { file(it).exists() }
    if (nativeAapt2 != null) {
        logger.lifecycle("aarch64 host: using native aapt2 at $nativeAapt2")
        project.extensions.extraProperties["android.aapt2FromMavenOverride"] = nativeAapt2
    } else {
        logger.lifecycle("aarch64 host: no native aapt2 found (install one or pass -Pandroid.aapt2FromMavenOverride=/path/to/aapt2)")
    }
}

android {
    namespace = "np.opd.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "np.opd.assistant"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        // Only ship 64-bit ARM native libraries: sherpa-onnx and executorch also bundle
        // x86/x86_64/armeabi-v7a .so files for emulators and legacy devices, which inflate the APK.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // A fixed TEST key kept in the repo so each new build installs over the previous one.
        // Prototype only - never use this key for a published app.
        create("testing") {
            storeFile = rootProject.file("keystore/testing.jks")
            storePassword = "opdtesting"
            keyAlias = "opd"
            keyPassword = "opdtesting"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("testing")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("testing")
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
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":engine"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Sherpa-ONNX (Local Whisper STT with VAD)
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")

    // ExecuTorch (Local LLM)
    implementation("org.pytorch:executorch-android:1.0.0")

    // Apache Commons Compress for tar.bz2 extraction
    implementation("org.apache.commons:commons-compress:1.26.1")
}
