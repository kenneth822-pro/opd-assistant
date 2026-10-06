// Standalone host build (NOT part of the app build): checks the JNI bridge on a normal JVM. See tools/asr/bridge_selftest.sh.
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "asr-bridge-hosttest"
