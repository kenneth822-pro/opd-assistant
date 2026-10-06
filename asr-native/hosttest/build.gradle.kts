plugins {
    kotlin("jvm") version "2.0.21"
    application
}

// The real sources under test: the engine's TranscriptLine, the speech layer, and the Kotlin half of the bridge.
kotlin {
    sourceSets["main"].kotlin.srcDirs(
        "../../engine/src/main/kotlin",
        "../../speech/src/main/kotlin",
        "../src/main/kotlin"
    )
}
sourceSets["main"].resources.srcDir("../../speech/src/main/resources")

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

val nativeDir = (project.findProperty("nativeDir") as String?) ?: "native"

application {
    mainClass.set("BridgeCheckKt")
    // -Xcheck:jni makes the JVM reject JNI misuse that would crash a phone.
    applicationDefaultJvmArgs = listOf("-Djava.library.path=$nativeDir", "-Xcheck:jni")
}
