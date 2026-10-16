FROM eclipse-temurin:17-jdk-jammy

ARG ANDROID_SDK_ROOT=/opt/android-sdk
ARG CMDLINE_TOOLS_VERSION=11076708
ARG ANDROID_PLATFORM=android-35
ARG ANDROID_BUILD_TOOLS=35.0.0
ARG ANDROID_NDK=27.0.12077973
ARG CMAKE_VERSION=3.22.1

ENV ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT} \
    ANDROID_HOME=${ANDROID_SDK_ROOT} \
    PATH=${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS}:$PATH \
    GRADLE_USER_HOME=/opt/gradle-cache

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates unzip wget git make libc6-i386 lib32stdc++6 \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && wget --quiet --output-document=/tmp/cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && mv "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "platform-tools" "platforms;${ANDROID_PLATFORM}" "build-tools;${ANDROID_BUILD_TOOLS}" "ndk;${ANDROID_NDK}" "cmake;${CMAKE_VERSION}"

WORKDIR /workspace
COPY . .
RUN chmod +x gradlew \
    && ./gradlew :engine:test --no-daemon --stacktrace \
    && ./gradlew :app:assembleDebug --no-daemon --stacktrace

CMD ["./gradlew", ":app:assembleDebug", "--offline", "--no-daemon"]
