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
5. These test APKs use the build runner's standard debug signing. If Android reports a signature
   mismatch, uninstall the previous prototype before installing the new build. Release signing keys
   are never stored in this repository.

If the newest build is missing, open the **Actions** tab: a red ✗ means the build failed (tell OmniRush.ai and paste the error).

The repository workflow is the automatic build/publish step: a push to `main` runs the tests,
builds the APK, and publishes a GitHub Release. Do **not** put a GitHub private key, API key or
password in this folder, a commit, or chat. Authenticate Git locally with GitHub's credential
manager or an SSH agent, then push normally:

```bash
git add .
git commit -m "feat: describe the change"
git push origin main
```

The workflow is the only place that publishes the APK; it does not silently push source code back
to GitHub. This keeps source changes reviewable and prevents a leaked key from granting write access.

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

### Privacy and governance gates

- The bundled catalogue is marked `SIMULATION_ONLY` and `UNVERIFIED`. There is no real-patient or
  clinician-approved mode in this build, and sharing/printing is disabled even after a doctor
  confirms a document.
- Before recording, Settings requires a local consent record. Choose either **external AI allowed**
  or **on-device AI only**; the app blocks a provider that does not match the selected path.
- Text prompts sent to external providers redact common names, phone numbers and email addresses by
  default. Audio cannot be redacted, so external audio processing requires explicit consent.
- Raw provider responses are hidden unless Developer mode is enabled. Keep Developer mode off for
  ordinary simulated consultations.
- Choose an audio retention policy in Settings. Audio can be deleted after transcription, after the
  consultation, immediately after processing, or automatically after a short period. **Delete all
  recorded audio now** is always available.
- Custom servers must use HTTPS by default. The application-level HTTP option is restricted to
  explicit private/LAN opt-in; the Android network security configuration remains cleartext-blocked,
  so HTTPS is required for a working custom server in this build.

## 3. Run a test consultation

1. **New consultation** → **Live assist** (help while you talk) or **Scribe only** (fewer requests).
2. Age and sex are optional: if you leave them blank, the app fills them from the conversation ("४५ वर्ष", "दिदी", "बुबा", "मेरो श्रीमान्") and marks them **(heard)** so you can check them.
3. **Start recording**, place the phone between doctor and "patient". A microphone foreground
   service keeps capture alive if you briefly switch apps or the screen turns off; Android shows a
   persistent recording notification.
4. Talk normally in Nepali / English. The live screen shows, from top to bottom:
   - **Patient chips** — age, sex, vitals (tap to edit), allergies, current medicines.
   - **Ask next** — red flags the patient may have mentioned (Present / Absent), red flags to **rule out**, pregnancy check, protocol checklist questions (Asked), the AI's suggested next questions, "don't forget" basics (allergy, current medicines), and conditions that **sound likely from keywords** (Add).
   - **Problem cards** — while taking history: the **likely prescription** (greyed) and what is still needed before it can be given; in Plan: the suggested prescription that passed all safety checks, with Accept / Dismiss.
   - **Live transcript** — doctor / patient / attendant lines in colour, a "listening…" line and parts being written down.
5. **💡 Suggest** cuts the current audio and updates suggestions straight away. **⏸ Pause** does the same and shows a "Paused" summary: what to ask next and the likely prescription so far.
6. **■ End** → the last audio is transcribed, failed parts are retried, and the notes model writes the final note from the full transcript. If the AI note fails, a **note built on the phone** from everything captured is shown instead (never an empty note), with **Try AI note again**.
7. On **Review** you can settle conflicts, confirm pregnancy status, enter vitals, mark questions asked, clear red flags (with a reason), override an escalation, accept/dismiss medicines, and inspect the governance metadata. Sharing is intentionally blocked while the catalogue is unverified.

Tips: say vitals aloud ("BP 150 by 95, SpO2 96"), say exam findings aloud, and say "अब औषधि लेखिदिन्छु" when moving to treatment.

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

The built-in catalogue (20 common adult OPD conditions with keywords in Nepali and English, question
checklists, red flags, regimens and a formulary) runs entirely on the phone. It gives instant
guidance as soon as a condition is recognised or added, and keeps working when the AI is slow or down;
the AI adds understanding of free speech on top.

Key safety rules (all enforced in code and covered by tests):

- The AI never chooses a medicine or writes instructions. It reports what was said; the app picks the regimen from the catalogue and fills doses and Nepali instructions from fixed templates.
- Keyword hints never change the case by themselves; the doctor confirms them with one tap.
- Only the doctor can loosen safety: clear a red flag, confirm "not pregnant", remove an allergy, override an escalation (with a reason in the audit log).
- Unknown is never treated as safe (e.g. unknown pregnancy status blocks teratogenic drugs; unassessed CRB-65 items are shown; unknown/under-18 age blocks this adult-only catalogue).
- Measured red flags (RR, SpO2, BP) are confirmed only by numbers, and a later normal reading never silently clears them.

## 5. Repository layout

| Path | What it is |
|---|---|
| `engine/src/main/resources/catalogues/v1/` | **All clinical content** (conditions, red flags, regimens, formulary, interactions). Review these JSON files clinically. |
| `engine/src/main/kotlin/` | Safety engine: reducer, safety checks, Nepali instruction templates, prompt + schema builder. No dependencies. |
| `engine/src/test/kotlin/` | 62 tests incl. catalogue integrity, keyword hints with Nepali negation, adult-only dosing guards, persistence round-trips, and a randomized "AI can never loosen safety" check. |
| `app/` | Android app (Kotlin + Jetpack Compose). |
| `.github/workflows/build.yml` | Runs the tests, builds the APK, publishes it as a Release. |
| `app/src/main/res/xml/network_security_config.xml` | Cleartext-blocked Android network policy; HTTPS is the default. |

## 6. Known limitations of this prototype

- The foreground recording service keeps the microphone alive in the background, but Android may
  still revoke microphone access or kill the process; the app reports a dead recorder and offers a
  restart attempt. Verify this on the target phone.
- Consultation snapshots and configured retention metadata are stored locally; audio cleanup is
  policy-driven and can be forced from Settings. Do not treat the local snapshot as a patient record.
- No PDF / thermal printing yet. Text sharing is implemented but blocked until catalogue approval
  evidence and doctor sign-off exist.
- Speaker labels come from the AI and can be wrong (Whisper gives none; the notes model infers them).
- Free model tiers have daily limits; a 10-minute live consultation uses roughly 40-80 requests.
- The catalogue covers 20 adult OPD conditions. Children, pregnancy-specific care and emergencies are out of scope; age is now required before a protocol regimen can be suggested.
- Brand names and safety metadata are best-effort placeholders.

## 7. Reproducible developer setup

```bash
make test                       # offline engine tests after dependencies are cached
make apk                        # debug APK (needs Android SDK)
make check                      # test + APK build
docker build --tag opd-assistant:local .  # clean Linux/JDK/Android SDK build
```

The Gradle wrapper pins Gradle 8.11.1 and `.java-version`/CI/Docker pin Java 17. The Docker image
installs Android API 35, build tools 35.0.0, NDK 27.0.12077973 and CMake 3.22.1. After the image
has been built once, `make test` and the Docker `CMD` use Gradle's offline mode. On Windows without
GNU Make, run the equivalent wrapper commands directly:

```powershell
./gradlew.bat :engine:test --offline --no-daemon --stacktrace
./gradlew.bat :app:assembleDebug --offline --no-daemon --stacktrace
```

Open the folder in Android Studio to work on the app. The engine can be edited in any IDE.

Release signing, when needed, is supplied through CI-only environment variables:
`OPD_RELEASE_STORE_FILE`, `OPD_RELEASE_STORE_PASSWORD`, `OPD_RELEASE_KEY_ALIAS`, and
`OPD_RELEASE_KEY_PASSWORD`. Never commit the keystore or these values.
