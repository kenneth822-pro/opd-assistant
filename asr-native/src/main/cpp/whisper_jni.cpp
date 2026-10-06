// JNI bridge between np.opd.asr.WhisperNative (Kotlin) and whisper.cpp.
//
// Deliberately thin: audio in, segments out. No clinical logic, no threading policy (the Kotlin side serialises
// calls), no UI. Written against whisper.h v1.8.3 (see CMakeLists.txt).
//
// Safety rules that matter on a phone:
//  * No C++ exception may cross the JNI boundary: loading a ~600 MB model can throw std::bad_alloc, and that must
//    become an error code, not a crashed app.
//  * Text is returned as raw UTF-8 BYTES and decoded in Kotlin. JNI's NewStringUTF expects *modified* UTF-8 and can
//    abort the process on invalid input, which Whisper can produce when a multi-byte (Devanagari) character is split
//    across tokens.
//  * Cancellation is cooperative (abort callback), so ending a consultation can stop a running inference.

#include <jni.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <exception>
#include <string>
#include <vector>

#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "OpdAsr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { std::fprintf(stderr, __VA_ARGS__); std::fputc('\n', stderr); } while (0)
#define LOGE(...) do { std::fprintf(stderr, __VA_ARGS__); std::fputc('\n', stderr); } while (0)
#endif

namespace {

constexpr int kErrBadHandle = -1;
constexpr int kErrInference = -2;
constexpr int kErrCancelled = -3;
constexpr int kErrBadAudio = -4;
constexpr int kErrException = -5;

struct Segment {
    std::string text;
    int64_t t0_ms = 0;
    int64_t t1_ms = 0;
    float no_speech = 0.f;
    float avg_logprob = 0.f;
};

struct Engine {
    whisper_context * ctx = nullptr;
    std::atomic<bool> cancel{false};
    std::vector<Segment> segments;
    float timings[5] = {0, 0, 0, 0, 0};   // sample, encode, decode, batch-decode, prompt (ms), of the last run
};

Engine * as_engine(jlong handle) { return reinterpret_cast<Engine *>(handle); }

bool abort_cb(void * data) { return static_cast<Engine *>(data)->cancel.load(std::memory_order_relaxed); }

void log_cb(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_INFO) {
        if (level == GGML_LOG_LEVEL_ERROR) LOGE("%s", text); else LOGI("%s", text);
    }
}

std::string to_std(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

extern "C" {

// Returns an opaque handle, or 0 if the model could not be loaded.
JNIEXPORT jlong JNICALL Java_np_opd_asr_WhisperNative_nativeInit(JNIEnv * env, jobject, jstring model_path, jboolean use_gpu) {
    try {
        static bool logging_installed = false;
        if (!logging_installed) { whisper_log_set(log_cb, nullptr); logging_installed = true; }

        const std::string path = to_std(env, model_path);
        whisper_context_params cparams = whisper_context_default_params();
        cparams.use_gpu = use_gpu == JNI_TRUE;   // false by default: the CPU path is the one validated here
        whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
        if (ctx == nullptr) { LOGE("could not load model '%s'", path.c_str()); return 0; }
        Engine * e = new Engine();
        e->ctx = ctx;
        return reinterpret_cast<jlong>(e);
    } catch (const std::exception & ex) {
        LOGE("nativeInit failed: %s", ex.what());
        return 0;
    } catch (...) {
        LOGE("nativeInit failed: unknown exception");
        return 0;
    }
}

JNIEXPORT void JNICALL Java_np_opd_asr_WhisperNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    Engine * e = as_engine(handle);
    if (e == nullptr) return;
    if (e->ctx != nullptr) whisper_free(e->ctx);
    delete e;
}

// Asks a running nativeTranscribe to stop soon. Safe from any thread.
JNIEXPORT void JNICALL Java_np_opd_asr_WhisperNative_nativeCancel(JNIEnv *, jobject, jlong handle) {
    Engine * e = as_engine(handle);
    if (e != nullptr) e->cancel.store(true, std::memory_order_relaxed);
}

// Transcribes 16 kHz mono float samples. Returns the number of segments (>= 0) or a negative error code.
JNIEXPORT jint JNICALL Java_np_opd_asr_WhisperNative_nativeTranscribe(
        JNIEnv * env, jobject, jlong handle, jfloatArray samples, jstring language, jboolean translate,
        jint threads, jint audio_ctx, jstring prompt) {
    Engine * e = as_engine(handle);
    if (e == nullptr || e->ctx == nullptr) return kErrBadHandle;
    try {
        const jsize n = samples == nullptr ? 0 : env->GetArrayLength(samples);
        if (n <= 0) return kErrBadAudio;
        std::vector<float> pcm(static_cast<size_t>(n));
        env->GetFloatArrayRegion(samples, 0, n, pcm.data());

        const std::string lang = to_std(env, language);
        const std::string prompt_text = to_std(env, prompt);

        whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        p.print_realtime = false;
        p.print_progress = false;
        p.print_timestamps = false;
        p.print_special = false;
        p.translate = translate == JNI_TRUE;        // always false for the Nepali path: transcribe, never translate
        p.language = lang.empty() ? "ne" : lang.c_str();
        p.detect_language = false;
        p.n_threads = threads > 0 ? threads : 4;
        p.no_context = true;                        // every segment is decoded on its own
        p.single_segment = false;
        p.suppress_blank = true;
        p.suppress_nst = true;
        p.audio_ctx = audio_ctx > 0 ? audio_ctx : 0;
        p.initial_prompt = prompt_text.empty() ? nullptr : prompt_text.c_str();
        p.abort_callback = abort_cb;
        p.abort_callback_user_data = e;

        // Reset AFTER the previous run is over, so a late cancel() from a watchdog cannot abort this one.
        e->cancel.store(false, std::memory_order_relaxed);
        e->segments.clear();
        whisper_reset_timings(e->ctx);

        const int rc = whisper_full(e->ctx, p, pcm.data(), static_cast<int>(pcm.size()));
        if (e->cancel.load(std::memory_order_relaxed)) return kErrCancelled;
        if (rc != 0) { LOGE("whisper_full failed: %d", rc); return kErrInference; }

        if (const whisper_timings * t = whisper_get_timings(e->ctx)) {
            e->timings[0] = t->sample_ms; e->timings[1] = t->encode_ms; e->timings[2] = t->decode_ms;
            e->timings[3] = t->batchd_ms; e->timings[4] = t->prompt_ms;
        }

        const whisper_token eot = whisper_token_eot(e->ctx);
        const int count = whisper_full_n_segments(e->ctx);
        e->segments.reserve(static_cast<size_t>(count));
        for (int i = 0; i < count; ++i) {
            Segment s;
            const char * text = whisper_full_get_segment_text(e->ctx, i);
            s.text = text ? text : "";
            s.t0_ms = whisper_full_get_segment_t0(e->ctx, i) * 10;   // whisper reports centiseconds
            s.t1_ms = whisper_full_get_segment_t1(e->ctx, i) * 10;
            s.no_speech = whisper_full_get_segment_no_speech_prob(e->ctx, i);
            double sum = 0.0; int tokens = 0;
            const int nt = whisper_full_n_tokens(e->ctx, i);
            for (int j = 0; j < nt; ++j) {
                const whisper_token_data td = whisper_full_get_token_data(e->ctx, i, j);
                if (td.id < eot && std::isfinite(td.plog)) { sum += td.plog; ++tokens; }
            }
            s.avg_logprob = tokens > 0 ? static_cast<float>(sum / tokens) : 0.f;
            e->segments.push_back(std::move(s));
        }
        return static_cast<jint>(e->segments.size());
    } catch (const std::exception & ex) {
        LOGE("nativeTranscribe failed: %s", ex.what());
        return kErrException;
    } catch (...) {
        LOGE("nativeTranscribe failed: unknown exception");
        return kErrException;
    }
}

JNIEXPORT jbyteArray JNICALL Java_np_opd_asr_WhisperNative_nativeSegmentTextBytes(JNIEnv * env, jobject, jlong handle, jint index) {
    Engine * e = as_engine(handle);
    if (e == nullptr || index < 0 || static_cast<size_t>(index) >= e->segments.size()) return env->NewByteArray(0);
    const std::string & t = e->segments[static_cast<size_t>(index)].text;
    jbyteArray out = env->NewByteArray(static_cast<jsize>(t.size()));
    if (out != nullptr && !t.empty()) env->SetByteArrayRegion(out, 0, static_cast<jsize>(t.size()), reinterpret_cast<const jbyte *>(t.data()));
    return out;
}

JNIEXPORT jlong JNICALL Java_np_opd_asr_WhisperNative_nativeSegmentStartMs(JNIEnv *, jobject, jlong handle, jint index) {
    Engine * e = as_engine(handle);
    return (e == nullptr || index < 0 || static_cast<size_t>(index) >= e->segments.size()) ? 0 : e->segments[static_cast<size_t>(index)].t0_ms;
}

JNIEXPORT jlong JNICALL Java_np_opd_asr_WhisperNative_nativeSegmentEndMs(JNIEnv *, jobject, jlong handle, jint index) {
    Engine * e = as_engine(handle);
    return (e == nullptr || index < 0 || static_cast<size_t>(index) >= e->segments.size()) ? 0 : e->segments[static_cast<size_t>(index)].t1_ms;
}

JNIEXPORT jfloat JNICALL Java_np_opd_asr_WhisperNative_nativeSegmentNoSpeech(JNIEnv *, jobject, jlong handle, jint index) {
    Engine * e = as_engine(handle);
    return (e == nullptr || index < 0 || static_cast<size_t>(index) >= e->segments.size()) ? 0.f : e->segments[static_cast<size_t>(index)].no_speech;
}

JNIEXPORT jfloat JNICALL Java_np_opd_asr_WhisperNative_nativeSegmentAvgLogProb(JNIEnv *, jobject, jlong handle, jint index) {
    Engine * e = as_engine(handle);
    return (e == nullptr || index < 0 || static_cast<size_t>(index) >= e->segments.size()) ? 0.f : e->segments[static_cast<size_t>(index)].avg_logprob;
}

// [sample, encode, decode, batch-decode, prompt] milliseconds of the last transcription. The encode/decode split
// shows which half is worth moving to the NPU.
JNIEXPORT jfloatArray JNICALL Java_np_opd_asr_WhisperNative_nativeLastTimings(JNIEnv * env, jobject, jlong handle) {
    jfloatArray out = env->NewFloatArray(5);
    Engine * e = as_engine(handle);
    if (out != nullptr && e != nullptr) env->SetFloatArrayRegion(out, 0, 5, e->timings);
    return out;
}

// Which CPU features this build uses (NEON, DOTPROD, ...), plus the pinned versions: proof of what is running.
JNIEXPORT jstring JNICALL Java_np_opd_asr_WhisperNative_nativeSystemInfo(JNIEnv * env, jobject) {
    std::string info = std::string("whisper.cpp ") + OPD_WHISPER_TAG + " | -march=" + OPD_ARM_ARCH_NAME + " | " + whisper_print_system_info();
    return env->NewStringUTF(info.c_str());   // ASCII only
}

}  // extern "C"
