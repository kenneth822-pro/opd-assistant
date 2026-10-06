// A stand-in for whisper.cpp that implements the REAL whisper.h API with controllable behaviour,
// so the real JNI file and the real Kotlin classes can be exercised end to end on the host.
#include "whisper.h"
#include <chrono>
#include <cstring>
#include <fstream>
#include <string>
#include <thread>
#include <vector>

struct whisper_context {
    struct Seg { std::string text; int64_t t0, t1; float no_speech; };
    std::vector<Seg> segs;
    whisper_timings tim{};
};

extern "C" {
void whisper_log_set(ggml_log_callback, void *) {}
whisper_context_params whisper_context_default_params(void) { whisper_context_params p{}; return p; }

whisper_context * whisper_init_from_file_with_params(const char * path, whisper_context_params) {
    std::ifstream f(path, std::ios::binary);
    char m[4];
    if (!f.read(m, 4) || std::memcmp(m, "lmgg", 4) != 0) return nullptr;   // ggml magic as stored on disk
    return new whisper_context();
}
void whisper_free(whisper_context * c) { delete c; }
whisper_full_params whisper_full_default_params(enum whisper_sampling_strategy s) { whisper_full_params p{}; p.strategy = s; p.n_threads = 4; return p; }
void whisper_reset_timings(whisper_context * c) { c->tim = {}; }
whisper_timings * whisper_get_timings(whisper_context * c) { return &c->tim; }
const char * whisper_print_system_info(void) { return "STUB | CPU : NEON = 1 | DOTPROD = 1"; }
whisper_token whisper_token_eot(whisper_context *) { return 50257; }

int whisper_full(whisper_context * c, whisper_full_params p, const float * samples, int n) {
    c->segs.clear();
    if (n > 0 && samples[0] < -0.9f) return -1;                                   // simulated inference failure
    if (n > 0 && samples[0] > 0.9f) {                                              // simulated hung inference
        while (!(p.abort_callback && p.abort_callback(p.abort_callback_user_data))) std::this_thread::sleep_for(std::chrono::milliseconds(5));
        return 0;
    }
    c->tim.encode_ms = 111; c->tim.decode_ms = 222;
    c->segs.push_back({"नमस्ते, मलाई ज्वरो छ।", 0, 150, 0.05f});
    c->segs.push_back({std::string("\xE0\xA4", 2), 150, 300, 0.10f});               // a Devanagari character cut in half
    std::string echo = std::string("lang=") + (p.language ? p.language : "?") + " translate=" + (p.translate ? "1" : "0") +
        " threads=" + std::to_string(p.n_threads) + " ctx=" + std::to_string(p.audio_ctx) + " prompt=" + (p.initial_prompt ? p.initial_prompt : "<none>") +
        " nocontext=" + (p.no_context ? "1" : "0") + " samples=" + std::to_string(n);
    c->segs.push_back({echo, 300, 450, 0.0f});
    return 0;
}
int whisper_full_n_segments(whisper_context * c) { return (int) c->segs.size(); }
const char * whisper_full_get_segment_text(whisper_context * c, int i) { return c->segs[i].text.c_str(); }
int64_t whisper_full_get_segment_t0(whisper_context * c, int i) { return c->segs[i].t0; }
int64_t whisper_full_get_segment_t1(whisper_context * c, int i) { return c->segs[i].t1; }
float whisper_full_get_segment_no_speech_prob(whisper_context * c, int i) { return c->segs[i].no_speech; }
int whisper_full_n_tokens(whisper_context *, int) { return 3; }
whisper_token_data whisper_full_get_token_data(whisper_context *, int, int j) {
    whisper_token_data d{};
    if (j == 0) { d.id = 100; d.plog = -0.2f; }
    else if (j == 1) { d.id = 101; d.plog = -0.4f; }
    else { d.id = 50257; d.plog = -5.0f; }                                         // end-of-text: must be excluded from the average
    return d;
}
}
