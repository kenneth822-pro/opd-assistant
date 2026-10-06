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

## 2. Get a Gemini API key

1. Go to **aistudio.google.com/apikey** and create an API key.
2. Use a **paid-tier** key for testing. Free-tier rate limits are too low for live mode, and Google may use free-tier data to improve its products.
3. In the app: **Settings → paste the key → Save → Test connection**. You should see "Connected".

The key is encrypted on the phone with the Android Keystore. Audio goes directly from the phone to Google; there is no other server.

## 3. Run a test consultation

1. **New consultation** → choose **Live assist** (suggestions while you talk) or **Scribe only**.
2. Optionally enter age and sex (this lets the safety engine apply pregnancy and age rules from the start).
3. **Start recording**, place the phone between doctor and "patient", keep the app open (screen stays on).
4. Talk normally in Nepali / English. In live mode, cards appear every ~20 s:
   - **Taking history**: the next questions to ask and red flags to rule out
   - **Plan**: a protocol medicine that passed all safety checks, with Accept / Dismiss
   - **Escalated**: a red flag or high CRB-65 score; OPD medicines are hidden
   - A red/amber banner appears for any confirmed red flag, whatever the complaint
5. **End consultation** → the full audio is sent once more for the final note.
6. On **Review** you can settle conflicts, confirm pregnancy status, enter vitals, mark questions asked, clear red flags (with a reason), override an escalation, accept/dismiss medicines, and **Share** the slip as text.

Tips: say vitals aloud ("BP 150 by 95, SpO2 96"), say exam findings aloud, and say "अब औषधि लेखिदिन्छु" when moving to treatment.

## 4. How it works

```
Mic → 20-second AAC segments ──(live mode)──► Gemini: "what is NEW in this segment?" (JSON)
                         │                                │
                         │                                ▼
                         │                   Safety engine (pure Kotlin, no AI)
                         │                   • merges facts, keeps first-heard age/sex
                         │                   • AI can only ADD facts or TIGHTEN safety
                         │                   • red flags, CRB-65, checklists → phase
                         │                   • picks the protocol regimen itself and
                         │                     runs allergy / pregnancy / kidney / age /
                         │                     interaction / cumulative-paracetamol checks
                         ▼
            End: all segments → Gemini final note → same engine → Review screen
```

Key safety rules (all enforced in code and covered by tests):

- The AI never chooses a medicine or writes instructions. It reports what was said; the app picks the regimen from the catalogue and fills doses and Nepali instructions from fixed templates.
- Only the doctor can loosen safety: clear a red flag, confirm "not pregnant", remove an allergy, override an escalation (with a reason in the audit log).
- Unknown is never treated as safe (e.g. unknown pregnancy status blocks teratogenic drugs; unassessed CRB-65 items are shown).
- Measured red flags (RR, SpO2, BP) are confirmed only by numbers, and a later normal reading never silently clears them.

## 5. Repository layout

| Path | What it is |
|---|---|
| `engine/src/main/resources/catalogues/v1/` | **All clinical content** (conditions, red flags, regimens, formulary, interactions). Review these JSON files clinically. |
| `engine/src/main/kotlin/` | Safety engine: reducer, safety checks, Nepali instruction templates, prompt + schema builder. No dependencies. |
| `engine/src/test/kotlin/` | 38 tests incl. catalogue integrity and a randomized "AI can never loosen safety" check. |
| `app/` | Android app (Kotlin + Jetpack Compose). |
| `.github/workflows/build.yml` | Runs the tests, builds the APK, publishes it as a Release. |
| `keystore/testing.jks` | **Test-only** signing key so updates install over each other. Never use for a published app. |

## 6. Known limitations of this prototype

- Recording only works while the app is open on screen (no background recording service yet).
- Switching between 20-second segments can drop a fraction of a second of audio.
- No patient records are saved; starting a new consultation deletes the previous audio.
- No PDF / thermal printing yet (Share as text only).
- The catalogue covers 14 adult OPD conditions. Children, pregnancy-specific care and emergencies are out of scope.
- Brand names and safety metadata are best-effort placeholders.

## 7. For developers

```bash
./gradlew :engine:test          # run the safety engine tests
./gradlew :app:assembleDebug    # build the APK (needs Android SDK)
```

Open the folder in Android Studio to work on the app. The engine can be edited in any IDE.
