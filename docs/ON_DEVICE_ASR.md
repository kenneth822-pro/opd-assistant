# On-device Nepali speech recognition

The app can turn the consultation audio into text **on the phone**, with no API key, no rate limit and no
internet. The transcript then flows into the existing pipeline unchanged:

```
 microphone
    ↓
 WavSegmentRecorder            (unchanged: gap-free, 16 kHz mono 16-bit WAV segments, cut at pauses)
    ↓
 SpeechRouting                 (per segment: on-device / cloud / on-device with explicit cloud fallback)
    ↓
 LocalAsrService ── GuardedRecognizer ── WhisperCppRecognizer ── whisper.cpp (ARM64 CPU)
    ↓                                            └── [reserved] QNN/HTP NPU backend
 TranscriptLine("unknown", text, offset)         (same type the cloud path produces)
    ↓
 Reducer ─┬─ LocalAssist (keyword hints, unchanged)
          └─ analysis AI (Gemini / OpenRouter / OpenCode / Groq / custom, unchanged; receives TEXT only)
                ↓
          live suggestions, Ask-next, safety engine, final note (all unchanged)
```

Only the speech step is new. Nothing below the transcript layer was changed.

---

## 1. What has and has not been verified

Be clear-eyed about this before relying on it. The environment this was built in had **no Android device, no
Snapdragon NPU, no Qualcomm SDK access and could not reach huggingface.co**.

| Item | Status | How |
|---|---|---|
| Routing, model manager, serial engine, error handling, Devanagari-safe output filter | ✅ verified | 74 JVM tests (`./gradlew :speech:test`); key behaviours mutation-checked |
| JNI bridge: symbol names/signatures, parameter marshalling, timestamp and log-prob conversion, UTF-8 safety, cancellation, lifecycle | ✅ verified on a host JVM | `tools/asr/bridge_selftest.sh` (28 checks; real JNI file + real `whisper.h` of the pinned release + a stub engine; JVM `-Xcheck:jni`) |
| Whole app type-checks | ✅ verified | host compile of every Kotlin file against the real Android framework classes |
| Android + ARM64 cross-compile in CI | ✅ verified | GitHub Actions run #5 on this branch: both jobs green. `build-local-asr` built with NDK 27.0.12077973 + CMake 3.22.1 (`configureCMakeDebug[arm64-v8a]`, `buildCMakeDebug[arm64-v8a]`), and `lib/arm64-v8a/libopd_asr.so` (4.36 MB, so whisper.cpp + ggml are really linked) is in the APK. The default job (no native engine) also passed, incl. `:engine:test` and `:speech:test`, and published no release (not `main`) |
| Does the APK *run* on the phone | ⏳ install `opd-assistant-local-asr-build-N` (artifact of the *Build APK* run, or of the `main` run once merged) on the OnePlus 15 | |
| **Real Whisper inference, Nepali accuracy, speed, memory, battery, thermal** | ❌ **not done: needs your OnePlus 15** | procedure in §8 |
| **Snapdragon NPU / HTP** | ❌ **not implemented** | see §4 |
| **The Nepali model's identity, files and licence** | ❌ **not verified** | see §3 |
| Works with the internet off | ❌ needs the airplane-mode test in §8 | |

## 2. Speech engine setting

Settings → **Speech recognition** (a card of its own; it is deliberately not one of the API-key provider cards).

| Choice | Behaviour |
|---|---|
| **On-device Nepali** | Audio never leaves the phone. If the engine or model is not ready, recording does not start and says why. A failed segment is marked failed with a reason and retried by the existing retry button. |
| **Automatic** *(default)* | On-device whenever it is ready. The cloud speech model is used **only** if the on-device engine is missing or fails on a segment, and only if a cloud speech model is configured; each such fallback is shown under "Recent problems". With no model installed this is identical to **Cloud**, so existing installs behave exactly as before. |
| **Cloud** | The previous behaviour. |

The analysis/notes model is chosen separately (as before) and receives transcript **text only**. A notes model is still
required, because on-device speech only writes the transcript.

Privacy: with *On-device*, raw consultation audio stays on the phone. Transcript text is still sent to the analysis
provider you chose, exactly as before; this change does not alter what is collected or sent.

## 3. The Nepali model: source and licence (unverified)

The intended model is the Nepali Whisper large-v3-turbo fine-tune **`sumanpaudel1997/nepali-asr-whisper-turbo`**, with
the licence reported as **CC-BY-NC-4.0**.

> ⚠ **Not verified.** The Hugging Face page could not be opened from the build environment, and a web search did not
> surface a model page with that exact name (it did confirm that the same author's `nepali-asr-indicwav2vec` is
> CC-BY-NC-4.0, and that a paper describing Nepali Whisper fine-tunes by the same author exists). Before installing it:
> 1. open the model page, confirm the repository name, the **licence**, and which files it contains;
> 2. copy the exact **commit hash** of the revision you will use;
> 3. record it in `speech/src/main/resources/asr/models.json` (`source.revision`) and set `verified: true` only for what you actually checked.

Licence consequences if CC-BY-NC-4.0 is confirmed: **non-commercial use only**, attribution required. The app shows
"non-commercial use only" and "licence/source not verified" next to the model until the manifest says otherwise. Do not
treat the model as commercially unrestricted. whisper.cpp and OpenAI's Whisper code/weights are MIT-licensed.

If that repository turns out not to exist or not to be a standard transformers Whisper checkpoint, the conversion in §6
will say so (it checks for `config.json`, `vocab.json`, `added_tokens.json` and weights) and nothing else changes: the
model identity lives only in the manifest.

## 4. Engines and backends

### Implemented: whisper.cpp on the ARM64 CPU (`CPU_GGML`)

* `asr-native/`: Android library, CMake fetches whisper.cpp **v1.8.3** (pinned), `arm64-v8a` only, ggml's own thread pool.
* Language `ne`, task **transcribe** (never translate), Devanagari preserved, Nepali/English code-switching left to the model.
* Every segment is decoded on its own (`no_context`); speaker is always `unknown` (Whisper does not label speakers; the existing AI analysis infers roles).
* The model is loaded **once** per engine lifetime and reused across segments and consultations; one inference runs at a time; inference never runs on the UI thread; a watchdog stops a hung inference.
* Opt-in build: `-PenableNativeAsr=true`. The default build and CI job do not need the NDK. The `build-local-asr` CI job builds the APK with it and uploads `opd-assistant-local-asr-build-N.apk` as an artifact (non-blocking).

### Not implemented: Qualcomm NPU (QNN / HTP)

This was the preferred target, and it is **not built**. What was established from Qualcomm's own sources
(`quic/qidk`, *NLPSolution3-AutomaticSpeechRecognition-Whisper*):

* the encoder is converted to a **quantised (a16w8) DLC** with the **QAIRT SDK** (Docker + a notebook) and runs on the NPU, with a **fixed** input of 30 s of audio; Snapdragon 8 Elite Gen 5 (SM8850) is listed as supported (Hexagon V81);
* the **decoder runs on the CPU** (TFLite), not on the NPU;
* the sample uses a small Whisper. Whisper large-v3(-turbo) uses **128 mel bins**, not 80, so the encoder input is `1×128×3000`;
* Qualcomm's SDK download host was not reachable here, there was no device, and quantising correctly needs **Nepali calibration audio**.

So putting the Nepali large-v3-turbo on the NPU is a real conversion project, not a flag. Recommended order:

1. Run the CPU path on the phone first and read the **encoder / decoder timing split** from the developer log (Review → technical log → *On-device speech*). If the encoder dominates (typical for large-v3-turbo, whose decoder has only 4 layers), moving only the encoder to the NPU gets most of the benefit.
2. Export the encoder to ONNX at the fixed shape, quantise with Nepali calibration audio, compile a DLC for V81, and validate Nepali WER against the f16 model (`tools/asr/eval_wer.py` works on any text output) before trusting it.
3. Integrate as a second `LocalSpeechRecognizer` with `backend = QNN_HTP` and a manifest entry with `"format": "qnn-dlc"` (the manifest, installer, routing, status and tests already accept both formats). Do not claim NPU use unless the runtime reports it: the CPU path prints its CPU features precisely so nobody mistakes it for the NPU.

Whisper pads every call to a 30 s window, so **encoder cost per call is the same for a 5 s and a 15 s segment**. This is why shorter segments are not automatically faster (§8, benchmark).

## 5. Building

```bash
./gradlew :engine:test :speech:test            # JVM tests (no Android SDK needed)
./gradlew :app:assembleDebug                   # default APK: no native engine (as before)
./gradlew :app:assembleDebug -PenableNativeAsr=true   # APK with the on-device engine (needs NDK + CMake + network once, for whisper.cpp)
tools/asr/bridge_selftest.sh                   # JNI contract check on this computer
```

Tuning knob for the CPU kernels, **measured on the phone, not guessed**:

```bash
-PasrArmArch=armv8.2-a+dotprod+fp16        # default: runs on every ARMv8.2+ phone
-PasrArmArch=armv8.6-a+i8mm+dotprod+fp16   # try on Snapdragon 8 Gen 1 and newer (incl. 8 Elite Gen 5)
```

A build with a CPU flag the phone does not support crashes with an illegal-instruction signal; use the default if unsure.
The engine's first log line (`adb logcat -s OpdAsr`) and the developer log show the version, the `-march` used and
which CPU features whisper.cpp detected.

## 6. Converting the Nepali model

`tools/asr/convert_nepali_to_ggml.sh` converts a Hugging Face Whisper checkpoint to GGML (f16), builds whisper.cpp's
quantiser, quantises (`q5_0` by default), and writes `conversion-manifest.json` recording the model revision, the
whisper.cpp and openai/whisper commits, the Python package versions, and the size and SHA-256 of every output.

```bash
HF_REVISION=<commit hash from the model page> tools/asr/convert_nepali_to_ggml.sh
# optional: QUANT_TYPES="q5_0 q8_0"   HF_REPO=...   WORK=...
```

It **refuses to run without `HF_REVISION`**: nothing about the repository could be verified when it was written, so you
pin it after checking the page. Needs git, git-lfs, python3 + venv, cmake, a C++ compiler and ~10 GB. It could not be run
in the build environment (no access to Hugging Face), so treat its first run as part of your verification.

**Validate before trusting a quantised file.** Quantising can cost Nepali accuracy, so compare against the f16 file on
held-out Nepali audio (16 kHz mono `.wav` plus a reference `.txt` each; convert with `ffmpeg -i in.m4a -ar 16000 -ac 1 x.wav`):

```bash
python3 tools/asr/eval_wer.py --cli build/asr-convert/whisper.cpp/build/bin/whisper-cli --audio-dir tests/nepali \
    --model ggml-nepali-whisper-turbo-f16.bin --model ggml-nepali-whisper-turbo-q5_0.bin --max-degradation 0.02
python3 -m unittest -C tools/asr 2>/dev/null || (cd tools/asr && python3 -m unittest test_eval_wer)   # the metric itself is tested
```

Use clinical-style sentences (symptoms, vitals spoken in Nepali, drug names in English) as the test set, not only read
speech. The threshold (2 WER points above) is a starting point; the decision is yours.

## 7. Installing the model on the phone

1. Convert and validate (§6), or obtain a GGML Whisper `.bin`. For a quick *plumbing* test only, the generic multilingual
   `ggml-large-v3-turbo-q5_0.bin` from the whisper.cpp model collection is listed in the manifest; its Nepali accuracy is far lower than the fine-tune's.
2. Copy the file to the phone (USB, cloud drive, …).
3. Settings → Speech recognition → **Import model file…** and pick it. The app checks it is a GGML Whisper file, shows its
   **sha256**, and installs it in app-private storage. It is never re-downloaded or re-hashed at launch.
4. **Test (speak 6 s)** reports the heard text, the real-time factor and the model load time.

**In-app download** stays disabled until `speech/src/main/resources/asr/models.json` has a `url` **and** a `sha256` for
the file (copy the hash from the import screen or from `conversion-manifest.json`). A download is installed only if its
SHA-256 matches; a mismatch is discarded. Do not invent or guess a hash.

Status shown in Settings: *Model not installed → Downloading → Verifying → Installing → Loading → Ready (CPU)*, or
*Model error / Engine error* with the reason, and *Engine not included in this build* for a default build.
"Delete model" frees the storage. Uninstalling the app removes the model too.

## 8. Device test procedure (OnePlus 15)

Run these on the phone; none of this could be done in the build environment.

**Smoke test.** `./gradlew :asr-native:connectedDebugAndroidTest -PenableNativeAsr=true` with a model pushed as described
in `asr-native/src/androidTest/.../WhisperSmokeTest.kt`; or just use **Test (speak 6 s)** in Settings. `adb logcat -s OpdAsr OpdAsrSmoke`.

**Phrases** (say each, close to the phone, then again at normal OPD distance; the output must stay Devanagari/Latin as spoken, never translated):

1. `नमस्ते, मलाई तीन दिनदेखि ज्वरो आएको छ।`
2. `तीन दिनदेखि टाउको दुखिरहेको छ र बेलाबेलामा वान्ता हुन्छ।`
3. `BP एक सय पचास बाइ पन्चानब्बे छ।`
4. `SpO2 छयानब्बे प्रतिशत छ।`
5. `मलाई cough पनि भइरहेको छ।`
6. `No history of diabetes, hypertension छैन।`
7. `Penicillin बाट allergy हुन्छ।`
8. `आज बिहानदेखि छाती दुखेको छ।`

Also: silence, room noise, a cough, a one-word answer, Nepali-only, English-only, mixed, and a natural doctor-patient
dialogue at OPD distance. Judge by the dialogue, not only close-mic single sentences. Check that nothing real is lost
(the output filter only drops blank output, `[BLANK_AUDIO]`-style annotations, repetition loops and stock "thanks for watching"
phrases) and that nothing is invented on silence.

**Offline proof.** Turn on airplane mode, start a consultation with *On-device Nepali*: transcript lines must still appear
(AI suggestions will report that they could not update, as expected: they need the internet). With a cloud speech model also
configured, the segment list in the technical log must say `on-device`, never a cloud model id.

**Record, don't guess** (Review → technical log → *On-device speech* has the first four):

| Measure | Where |
|---|---|
| model load time, first-segment time, real-time factor (last / median / worst) | developer log |
| encoder vs decoder milliseconds, CPU features in use | developer log / `adb logcat -s OpdAsr` |
| RAM | `adb shell dumpsys meminfo np.opd.assistant` |
| battery / thermal over a 30-minute consultation | `adb shell dumpsys battery`, `dumpsys thermalservice` before and after |
| end-of-speech → text on screen | stopwatch: segment wait + inference time |
| whether anything silently ran elsewhere | the CPU path prints its CPU features and never reports NPU; no GPU is requested |

**Benchmark matrix** (fill in on the device; segment length is Settings → Recording):

| Quantisation | `-march` | Threads | Segment | Audio (s) | Inference (s) | RTF | Notes |
|---|---|---|---|---|---|---|---|
| q5_0 | armv8.2-a+dotprod+fp16 | 6 | 15 s | | | | |
| q5_0 | armv8.6-a+i8mm+dotprod+fp16 | 6 | 15 s | | | | |
| q5_0 | (best) | 4 / 6 / 8 | 15 s | | | | |
| q5_0 | (best) | (best) | 8 / 10 / 12 / 15 s | | | | |
| q8_0 | (best) | (best) | 15 s | | | | accuracy vs q5_0 |

Target: real-time or faster (RTF ≤ 1.0), ideally well below. If the CPU path misses it, the encoder/decoder split tells
you whether the NPU encoder (§4) is worth the project.

## 9. Failure behaviour

| Situation | What the doctor sees | What happens |
|---|---|---|
| Model not installed / engine not in build | status line + start blocked (On-device) or cloud used (Automatic) | no empty transcript |
| Model damaged / too big for memory | *Engine error: … could not be loaded …* | each segment fails with the reason; retry or switch engine |
| One segment fails | "Segment N: Local ASR inference failed …" under Recent problems | segment marked failed, existing retry machinery; consultation continues; Automatic mode uses the cloud for that segment and says so |
| Hung inference | stopped after max(30 s, 6× the audio length) | reported as *took too long*; next segment tries again |
| Native library missing | *Local ASR runtime is not part of this build* | never a crash |

## 10. Layout of the code

| Path | Role |
|---|---|
| `speech/` | pure-Kotlin: routing, `LocalSpeechRecognizer` contract, `GuardedRecognizer`, `ModelInstaller`, `LocalAsrService`, `AsrOutput`, `AsrStats`, `asr/models.json`. No Android, no clinical logic |
| `asr-native/` | optional Android library: whisper.cpp + JNI + `WhisperCppRecognizer`; `hosttest/` JNI contract check; `androidTest/` device smoke test |
| `app/.../asr/`, `SpeechSettings.kt` | app glue: model location, downloader, reflection hook, settings card |
| `tools/asr/` | model conversion, accuracy validation, JNI self-test |
| `engine/` | unchanged clinical engine (only `TranscriptLine` is shared) |
