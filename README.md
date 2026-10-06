# OPD Assistant (prototype)

An Android app that listens to an outpatient consultation, writes a draft note, and suggests
protocol medicines **only after deterministic safety checks**. Built for internal testing with
**role-played / simulated consultations only**.

> ⚠ **Prototype.** Every regimen, dose, interaction rule and brand name in this repository is
> **UNVERIFIED** placeholder content. Do not use with real patients until a clinician has checked
> the catalogues against current MoHP Nepal guidance and consent, privacy and ethics steps are in place.

---

## 1. Get the app on your phone (no coding needed)

Every time code changes, GitHub builds the app automatically (about 5–10 minutes).

1. On your Android phone (Android 10 or newer), open **github.com** in the browser and sign in.
2. Go to this repository → **Releases** (right side of the page, or `…/opd-assistant/releases`).
3. Open the newest **Test build N** and tap the file `opd-assistant-build-N.apk` to download it.
4. Open the downloaded file. If Android asks, allow **"Install unknown apps"** for your browser, then tap **Install**.
5. Later builds install over the old one (same test signing key), so your settings are kept.

If the newest build is missing, open the **Actions** tab: a red ✗ means the build failed (tell Claude and paste the error).

## 2. Add a key and choose models

The app downloads the **current model list** from each provider, so model names never go out of date.
Settings has a card for each provider; tap it, paste the key, **Save** (models load automatically).

| Provider | Key from | Good for |
|---|---|---|
| **Google Gemini** (recommended) | aistudio.google.com/apikey (free) | Speech **and** notes. Flash models hear Nepali audio well. |
| **OpenRouter** | openrouter.ai/keys | Notes. Hundreds of models, many **FREE** (≈20 requests/min, 50/day; 1,000/day after a $10 top-up). |
| **OpenCode Zen** | opencode.ai/zen | Notes. One key for a curated model set; some free for a limited time. |
| **Groq** | console.groq.com/keys | Speech via **Whisper** (fast speech-to-text) plus fast open models for notes. |
| **Custom** | your server | Any OpenAI-compatible server (LM Studio, Ollama, vLLM…): enter its address (…/v1). |

Models are tagged **AUDIO** (hears audio), **SPEECH-TO-TEXT** (Whisper-style, transcribes only), **FREE / PAID** and **JSON**.

**Three model slots**
- **Speech model** — hears the audio and writes the live transcript. Needs **AUDIO** or **SPEECH-TO-TEXT**.
- **Notes model** — reads the transcript and produces the live suggestions and the final note. "Use the speech model for this too" works for Gemini; a Whisper speech model always needs a separate notes model.
- **Backup model** (recommended) — used automatically when a model is busy (HTTP 503 "high demand", 429 rate limit) or retired. When Gemini models load, the app picks one for you.

The app also adapts to models with restrictions: if a model refuses system ("developer") instructions or JSON mode, the instructions are moved into the message and the format is described in text, and the app remembers this for that model.

Tap **Test selected models** to check all three. Keys are encrypted on the phone with the Android Keystore; requests go directly from the phone to the provider.

### On-device Nepali speech (optional, no key, works offline)

Speech can also be recognised **on the phone** instead of by a cloud model: no API key, no rate limit, audio never
leaves the phone. Settings → **Speech recognition** → choose *On-device Nepali*, *Automatic* (on-device first, cloud only
as a fallback) or *Cloud* (the previous behaviour, and what *Automatic* does until a model is installed). The notes
model above is still needed: it reads the transcript text. This needs the APK built **with** the on-device engine
(the **`opd-assistant-local-asr-build-N`** artifact of the *Build APK* run, or `-PenableNativeAsr=true`) and a Whisper model
file you convert and import yourself. **Read `docs/ON_DEVICE_ASR.md` first**: it states exactly what has and has not been
verified (not yet run on a phone; no NPU path; the Nepali model's source and licence are unverified and reported as
non-commercial).

## 3. Run a test consultation

1. **New consultation** → **Live assist** (help while you talk) or **Scribe only** (fewer requests).
2. Age and sex are optional: if you leave them blank, the app fills them from the conversation ("४५ वर्ष", "दिदी", "बुबा", "मेरो श्रीमान्") and marks them **(heard)** so you can check them.
3. **Start recording**, place the phone between doctor and "patient", keep the app open (screen stays on).
4. Talk normally in Nepali / English. The live screen shows, from top to bottom:
   - **Patient chips** — age, sex, vitals (tap to edit), allergies, current medicines.
   - **Ask next** — red flags the patient may have mentioned (Present / Absent), red flags to **rule out**, pregnancy check, protocol checklist questions (Asked), the AI's suggested next questions, "don't forget" basics (allergy, current medicines), and conditions that **sound likely from keywords** (Add).
   - **Problem cards** — while taking history: the **likely prescription** (greyed) and what is still needed before it can be given; in Plan: the suggested prescription that passed all safety checks, with Accept / Dismiss.
   - **Live transcript** — doctor / patient / attendant lines in colour, a "listening…" line and parts being written down.
   - **Working diagnosis** — a one-line strip that is always visible; it updates as the conversation changes (see below).
5. **💡 Suggest** cuts the current audio and updates suggestions straight away. **⏸ Pause** does the same and shows a "Paused" summary: what to ask next and the likely prescription so far.
6. **■ End** (asks you to confirm, so a mis-tap does not stop the recording) → the last audio is transcribed, failed parts are retried, and the notes model writes the final note from the full transcript. If the AI note fails, a **note built on the phone** from everything captured is shown instead (never an empty note), with **Try AI note again**.
7. On **Review** you can settle conflicts, confirm pregnancy status, enter vitals, mark questions asked, clear red flags (with a reason), override an escalation, accept/dismiss medicines, and **Share** the slip as text.

Tips: say vitals aloud ("BP 150 by 95, SpO2 96"), say exam findings aloud, and say "अब औषधि लेखिदिन्छु" when moving to treatment.

### Working diagnosis and the OPD sheet (live)

The strip under the patient chips lists the leading **working diagnoses** with a band — **Likely**, **Possible**, **Less likely** —
plus **Don't miss** conditions implied by suspected or confirmed red flags. It is recomputed on the phone every time the
transcript, vitals, history or your confirmations change (no network, no AI needed), and shows what moved: **new**, **↑ / ↓**.
Tap the strip to open the **layer** (on tablets and wide screens it is a **side pane** that stays open):

- **Diagnosis tab** — for each condition, *why*: the problem you filed, keywords heard, measured values that support it,
  and what was **denied** ("no fever" counts against fever). Nothing you filed is ever hidden. Age, sex, comorbidity or medicines alone never
  raise a diagnosis; only symptoms, measurements and history do. This is an aid to your thinking, **not** a diagnosis.
- **OPD sheet tab** — the whole consultation as one sheet that fills in as you talk: patient, alerts, history, examination (as spoken),
  provisional diagnosis, AI suggestions (labelled unverified), don't-miss, red flags, still to cover, investigations to consider and
  treatment so far. **Share sheet as text** sends it.

When the AI notes model is on, it may add its own differential; it is validated against the catalogue and shown with the same bands.

**Scope.** The protocols are written for **adults**. Under 18, or with no age recorded, the app says so on the Live and Review
screens and on the slip, and does not dose as if the patient were an adult.

**If the app is interrupted.** The facts of the consultation (transcript, vitals, history, your confirmations — not derived advice,
which is recomputed) are saved on the phone about 1.5 s after each change. After a call, a crash or Android reclaiming the app,
**Home → Unfinished consultation** reopens it on the Review screen (the audio itself is not resumed). Starting a new consultation or
**Delete all audio** removes it. If the microphone goes silent or is taken by another app, the Live screen says so and offers **Restart microphone**.

## 4. How it works

```
Mic → gap-free WAV parts (cut at pauses; silent parts skipped)
   │
   ├─► speech model ─► live transcript ─┬─► on-phone keyword hints (instant, offline):
   │   (backup model if busy)           │     possible conditions, red-flag mentions, age/sex
   │                                    │
   │                                    └─► notes model: "what is NEW?" (JSON, batched) ─┐
   │                                                                                     ▼
   │                                           Safety engine (pure Kotlin, no AI)
   │                                           • merges facts, keeps first-heard age/sex
   │                                           • AI can only ADD facts or TIGHTEN safety
   │                                           • red flags, CRB-65, checklists → phase
   │                                           • picks the protocol regimen itself and runs
   │                                             allergy / pregnancy / kidney / age /
   │                                             interaction / cumulative-paracetamol checks
   ▼
End: full transcript → notes model writes the final note (phone-built note if it fails) → Review
```

The built-in catalogue (14 common OPD conditions with keywords in Nepali and English, question
checklists, red flags, regimens and a formulary) runs entirely on the phone. It gives instant
guidance as soon as a condition is recognised or added, and keeps working when the AI is slow or down;
the AI adds understanding of free speech on top.

Key safety rules (all enforced in code and covered by tests):

- The AI never chooses a medicine or writes instructions. It reports what was said; the app picks the regimen from the catalogue and fills doses and Nepali instructions from fixed templates.
- Keyword hints never change the case by themselves; the doctor confirms them with one tap.
- Only the doctor can loosen safety: clear a red flag, confirm "not pregnant", remove an allergy, override an escalation (with a reason in the audit log).
- Unknown is never treated as safe (e.g. unknown pregnancy status blocks teratogenic drugs; unassessed CRB-65 items are shown).
- Measured red flags (RR, SpO2, BP) are confirmed only by numbers, and a later normal reading never silently clears them.

## 5. Repository layout

| Path | What it is |
|---|---|
| `engine/src/main/resources/catalogues/v1/` | **All clinical content** (conditions, red flags, regimens, formulary, interactions). Review these JSON files clinically. |
| `engine/src/main/kotlin/` | Safety engine: reducer, safety checks, Nepali instruction templates, prompt + schema builder. No dependencies. |
| `engine/src/test/kotlin/` | 120 tests incl. catalogue integrity, keyword hints with Nepali negation, the adult-only scope guard, the live differential, provenance rules, crash-safe state, and a randomized "AI can never loosen safety" check. |
| `speech/` | Pure-Kotlin on-device speech layer: routing (on-device / cloud / fallback), model installer, serial engine wrapper, Nepali-safe output filter, model manifest. 78 tests. |
| `asr-native/` | Optional Android library: whisper.cpp (ARM64 CPU) + JNI. Built only with `-PenableNativeAsr=true`. |
| `tools/asr/` | Model conversion, accuracy validation (WER) and the JNI self-test. |
| `docs/ON_DEVICE_ASR.md` | On-device speech: architecture, verification status, model source/licence, conversion, device test procedure. |
| `app/` | Android app (Kotlin + Jetpack Compose). |
| `.github/workflows/build.yml` | Runs the tests, builds the APK, publishes it as a Release. |
| `keystore/testing.jks` | **Test-only** signing key so updates install over each other. Never use for a published app. |

## 6. Known limitations of this prototype

- Recording only works while the app is open on screen (no background recording service yet).
- No patient records are kept: the audio and the one unfinished-consultation file live in app-private storage (not separately encrypted) until you start a new consultation or delete them.
- On-device speech is **not yet verified on a phone**: it compiles and its logic and JNI bridge are tested, but real inference, Nepali accuracy, speed and battery still have to be measured on your device (procedure in `docs/ON_DEVICE_ASR.md`). It runs on the CPU; there is no Snapdragon NPU path yet.
- No PDF / thermal printing yet (Share as text only).
- Speaker labels come from the AI and can be wrong (Whisper gives none; the notes model infers them).
- Free model tiers have daily limits; a 10-minute live consultation uses roughly 40-80 requests.
- The catalogue covers 14 adult OPD conditions. Children, pregnancy-specific care and emergencies are out of scope.
- Brand names and safety metadata are best-effort placeholders.
- **Nepal STP guidelines are not in the catalogue yet.** The source documents could not be downloaded from where this was built. The app now tracks the status of every catalogue entry (**unverified / matched to a source / clinician-verified**; summary on Home) and a catalogue check refuses to let an entry claim more verification than its evidence supports, but the content itself is still the original placeholder set. See `engine/.../catalogues/v1/conditions.json` → `reference_documents`.
- The new layouts (diagnosis strip, layer, side pane) compile against the real Android framework in a host test harness and in the CI build, but have not been rendered or looked at on a phone.

## 7. For developers

```bash
./gradlew :engine:test :speech:test   # run the safety engine and speech-layer tests (no Android SDK needed)
./gradlew :app:assembleDebug          # build the APK (needs Android SDK)
./gradlew :app:assembleDebug -PenableNativeAsr=true   # APK with the on-device speech engine (needs NDK + CMake)
tools/asr/bridge_selftest.sh          # check the native bridge on this computer
```

Open the folder in Android Studio to work on the app. The engine can be edited in any IDE.
