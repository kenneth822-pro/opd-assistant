#!/usr/bin/env bash
# Checks the JNI bridge (asr-native/src/main/cpp/whisper_jni.cpp + the Kotlin classes that call it) on this computer.
#
# It compiles the REAL jni file against the REAL whisper.h of the pinned whisper.cpp release, links it with a stub
# that implements the same API with controllable behaviour, loads it into a JVM with -Xcheck:jni and drives the real
# Kotlin classes (WhisperCppRecognizer -> GuardedRecognizer -> LocalAsrService) through it.
#
# What this proves:   JNI symbol names/signatures, parameter marshalling, result conversion (timestamps, avg log-prob),
#                     UTF-8 safety, error mapping, cancellation, lifecycle.
# What this does NOT: real Whisper inference, the ARM64 cross-compile, speed or memory. Those need the phone
#                     (see docs/ON_DEVICE_ASR.md, "Device test procedure").
#
# Needs: a JDK 17+, g++ (or clang++), curl, network access to raw.githubusercontent.com. Linux only.
set -euo pipefail
cd "$(dirname "$0")/../.."

TAG="${WHISPER_TAG:-v1.8.3}"                  # keep in sync with WHISPER_CPP_TAG in asr-native/src/main/cpp/CMakeLists.txt
OUT="${OUT:-build/asr-hosttest}"
CXX="${CXX:-$(command -v g++ || command -v clang++)}"
JAVA_ROOT="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"

mkdir -p "$OUT/include" "$OUT/native"
RAW="https://raw.githubusercontent.com/ggml-org/whisper.cpp/$TAG"
for f in include/whisper.h ggml/include/ggml.h ggml/include/ggml-cpu.h ggml/include/ggml-backend.h ggml/include/ggml-alloc.h ggml/include/gguf.h; do
  curl -fsSL "$RAW/$f" -o "$OUT/include/$(basename "$f")"
done

"$CXX" -std=c++17 -shared -fPIC -O1 -Wall -Wextra -Wno-unused-parameter \
  -DOPD_WHISPER_TAG="\"$TAG\"" -DOPD_ARM_ARCH_NAME='"host-check"' \
  -I "$OUT/include" -I "$JAVA_ROOT/include" -I "$JAVA_ROOT/include/linux" \
  asr-native/src/main/cpp/whisper_jni.cpp asr-native/hosttest/native/stub_whisper.cpp \
  -o "$OUT/native/libopd_asr.so"

./gradlew -p asr-native/hosttest run -PnativeDir="$(pwd)/$OUT/native" --no-daemon --console=plain
