pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "opd-assistant"
include(":engine", ":speech", ":app")

// Native on-device speech engine (whisper.cpp). Opt-in so the default build never depends on the NDK/CMake
// toolchain: pass -PenableNativeAsr=true (see docs/ON_DEVICE_ASR.md).
if (providers.gradleProperty("enableNativeAsr").orNull == "true") {
    include(":asr-native")
}
