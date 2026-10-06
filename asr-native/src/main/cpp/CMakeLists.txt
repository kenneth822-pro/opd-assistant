cmake_minimum_required(VERSION 3.18)
project(opd_asr_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# whisper.cpp, pinned to a release tag and fetched at configure time (needs network once).
# Bump the tag deliberately and re-run the device benchmark (docs/ON_DEVICE_ASR.md): the JNI file
# below uses whisper.h directly and was checked against exactly this version.
# ---------------------------------------------------------------------------
set(WHISPER_CPP_TAG "v1.8.3" CACHE STRING "whisper.cpp release tag")

set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2           OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL           OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)   # whisper + ggml are linked statically into libopd_asr.so
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)   # use ggml's own thread pool; no libomp dependency
set(GGML_CCACHE            OFF CACHE BOOL "" FORCE)

# CPU instruction set for ggml's kernels. Without this a cross-compiled build is plain armv8-a (no dot-product
# instructions), which is several times slower. The default runs on every ARMv8.2+ phone.
set(OPD_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "-march value for ggml's ARM kernels")
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "${OPD_ARM_ARCH}" CACHE STRING "" FORCE)
endif ()

include(FetchContent)
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG        ${WHISPER_CPP_TAG}
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(whisper_cpp)

add_library(opd_asr SHARED whisper_jni.cpp)
target_compile_definitions(opd_asr PRIVATE OPD_WHISPER_TAG="${WHISPER_CPP_TAG}" OPD_ARM_ARCH_NAME="${OPD_ARM_ARCH}")
target_link_libraries(opd_asr PRIVATE whisper)

if (ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(opd_asr PRIVATE ${LOG_LIB})
endif ()

target_compile_options(opd_asr PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(opd_asr PRIVATE -Wl,--gc-sections)
