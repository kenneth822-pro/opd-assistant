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

## 3. Run a test consultation

1. **New consultation** → **Live assist** (help while you talk) or **Scribe only** (fewer requests).
2. Age and sex are optional: if you leave them blank, the app fills them from the conversation ("४५ वर्ष", "दिदी", "बुबा", "मेरो श्रीमान्") and marks them **(heard)** so you can check them.
3. **Start recording**, place the phone between doctor and "patient", keep the app open (screen stays on).
4. Talk normally in Nepali / English. The live screen shows, from top to bottom:
   - **Patient chips** — age, sex, vitals (tap to edit), allergies, current medicines.
   - **Ask next** — red flags the patient may have mentioned (Present / Absent), red flags to **rule out**, pregnancy check, protocol checklist questions (Asked), the AI's suggested next questions, "don't forget" basics (allergy, current medicines), and conditions that **sound likely from keywords** (Add).
   - **Problem cards** — while taking history: the **likely prescription** (greyed) and what is still needed before it can be given; in Plan: the suggested prescription that passed all safety checks, with Accept / Dismiss.
   - **Live transcript** — doctor / patient / attendant lines in colour, a "listening…" line and parts being written down.
5. **💡 Suggest** cuts the current audio and updates suggestions straight away. **⏸ Pause** does the same and shows a "Paused" summary: what to ask next and the likely prescription so far.
6. **■ End** → the last audio is transcribed, failed parts are retried, and the notes model writes the final note from the full transcript. If the AI note fails, a **note built on the phone** from everything captured is shown instead (never an empty note), with **Try AI note again**.
7. On **Review** you can settle conflicts, confirm pregnancy status, enter vitals, mark questions asked, clear red flags (with a reason), override an escalation, accept/dismiss medicines, and **Share** the slip as text.

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
| `engine/src/test/kotlin/` | 53 tests incl. catalogue integrity, keyword hints with Nepali negation, and a randomized "AI can never loosen safety" check. |
| `app/` | Android app (Kotlin + Jetpack Compose). |
| `.github/workflows/build.yml` | Runs the tests, builds the APK, publishes it as a Release. |
| `keystore/testing.jks` | **Test-only** signing key so updates install over each other. Never use for a published app. |

## 6. Known limitations of this prototype

- Recording only works while the app is open on screen (no background recording service yet).
- No patient records are saved; starting a new consultation deletes the previous audio.
- No PDF / thermal printing yet (Share as text only).
- Speaker labels come from the AI and can be wrong (Whisper gives none; the notes model infers them).
- Free model tiers have daily limits; a 10-minute live consultation uses roughly 40-80 requests.
- The catalogue covers 14 adult OPD conditions. Children, pregnancy-specific care and emergencies are out of scope.
- Brand names and safety metadata are best-effort placeholders.

## 7. For developers

```bash
./gradlew :engine:test          # run the safety engine tests
./gradlew :app:assembleDebug    # build the APK (needs Android SDK)
```

Open the folder in Android Studio to work on the app. The engine can be edited in any IDE.
# Build trigger
