# Drishti — AI Vision Companion for the Visually Impaired

> Built at iQOO Hackathon 2026, Bengaluru — 30-hour sprint  
> Native Android (Kotlin + Jetpack Compose) | Offline-first | Hindi / English / Kannada

---

## What It Does

Drishti (दृष्टि — "vision" in Sanskrit) is a real-time AI companion that uses the phone camera and microphone to help visually impaired users understand their surroundings. Point the camera at anything; press the shutter (or a volume button); the app describes what it sees out loud in your chosen language.

**Core loop:**  
Camera frame → OCR + object detection → cloud / local LLM → Text-to-Speech

---

## Features

| Feature | Detail |
|---|---|
| **Camera scan** | Tap the on-screen button or press either volume key |
| **Voice query** | Hold the mic button and ask a follow-up question about the last scene |
| **Surveillance mode** | Continuous background ML Kit analysis; haptic double-pulse alert when the scene changes significantly — no cloud call needed |
| **Three languages** | Hindi (`hi-IN`), English (`en-IN`), Kannada (`kn-IN`) — cycle with one tap |
| **Screen always on** | `FLAG_KEEP_SCREEN_ON` — never sleeps mid-use |
| **Haptic feedback** | Every action has a distinct vibration pattern (capture, result, listen, alert, error) |
| **Online/offline indicator** | Live green/red dot in the top bar |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         MainActivity                            │
│  • Requests CAMERA + RECORD_AUDIO at runtime                    │
│  • Intercepts volume keys → scanTrigger flow                    │
│  • FLAG_KEEP_SCREEN_ON                                          │
└───────────────────────────┬─────────────────────────────────────┘
                            │ Compose
┌───────────────────────────▼─────────────────────────────────────┐
│                       DrishtiScreen                             │
│  • CameraX PreviewView full-screen                              │
│  • Bottom overlay: language selector, scan button, mic button   │
│  • Loading overlay while initializing                           │
└──────────┬──────────────────────────────────────────────────────┘
           │ viewModel()
┌──────────▼──────────────────────────────────────────────────────┐
│                     DrishtiViewModel                            │
│  AppState machine: Loading → Ready ↔ Scanning → Thinking →     │
│                    Speaking → Ready / Error                     │
│                                                                 │
│  Fallback chain (in order):                                     │
│    1. Cloud vision (Gemini 3.1 Flash — paid, cheap)             │
│    2. Cloud text free (Gemma 4 31B — 0 cost, Hindi-capable)     │
│    3. On-device LLM (Gemma 2B INT4 — offline last resort)       │
│    4. Raw OCR/label text read aloud                             │
└──────┬──────────────┬──────────────┬──────────────┬────────────┘
       │              │              │              │
  CameraManager  CloudFallback  OnDeviceLlm    OcrEngine
  SurveillanceAnalyzer           ModelDownloader  SpeechInput
                                                  SpeechOutput
```

---

## AI / ML Stack

### Cloud — OpenRouter Gateway

| Tier | Model | Cost | Use |
|---|---|---|---|
| 1 (vision) | `google/gemini-3.1-flash-image-preview` | ~$0.0000005/token | Sends actual image + prompt |
| 2 (free text) | `google/gemma-4-31b-it:free` | Free | Falls back when vision quota hit |

- Image pre-processed before upload: resized to max 720px, JPEG quality 60%
- `max_tokens = 80` (≈12 words response)
- Returns `QUOTA_EXCEEDED` sentinel on HTTP 429/402 → auto-falls to free model
- API key stored in `local.properties`, baked into `BuildConfig` at compile time (never committed)

### On-Device — MediaPipe LlmInference

| Item | Value |
|---|---|
| Model | Gemma 2B IT INT4 GPU (`gemma-2b-it-gpu-int4.bin`, ~1.4 GB) |
| Library | `com.google.mediapipe:tasks-genai:0.10.14` |
| Inference | Synchronous `generateResponse()` on `Dispatchers.Default` |
| Download | Android `DownloadManager` — background, resumable |
| Auto-start | ViewModel starts download on init if model absent and device is online |

> Note: Gemma 2B on-device has limited Hindi fluency and no vision capability. It is last-resort only. Cloud Gemma 4 31B free tier is significantly better quality at zero cost.

### On-Device — ML Kit

| Use case | Library | Notes |
|---|---|---|
| Latin OCR | `play-services-mlkit-text-recognition` | Reads English text, signs, labels |
| Devanagari OCR | `mlkit-text-recognition-devanagari` | Reads Hindi/Marathi text |
| Object labeling | `play-services-mlkit-image-labeling` | Returns confidence-weighted label set |

All three run in **parallel** using `coroutineScope { async {} }` — saves ~300-500ms vs sequential.

### Surveillance Mode

- `SurveillanceAnalyzer` implements `ImageAnalysis.Analyzer`
- Runs ML Kit image labeling on every camera frame, throttled to every 1500ms
- Waits 3 frames before alerting (avoids false positives at startup)
- Computes **Jaccard similarity** between current and previous label sets
- `intersection / union < 0.50` = significant scene change → ALERT haptic + status message
- Reset on every manual scan so the same scene doesn't re-trigger

---

## Project Structure

```
app/src/main/java/com/drishti/app/
├── MainActivity.kt              # Entry point, permissions, volume keys, screen-on
├── AppState.kt                  # AppState sealed class + AppLanguage enum
├── ui/
│   └── DrishtiScreen.kt         # Full Compose UI
├── viewmodel/
│   └── DrishtiViewModel.kt      # Business logic, state machine, fallback chain
├── camera/
│   ├── CameraManager.kt         # CameraX setup (Preview + ImageCapture + ImageAnalysis)
│   └── SurveillanceAnalyzer.kt  # Continuous scene-change detection
├── ml/
│   ├── CloudFallback.kt         # OpenRouter API client (vision + free text)
│   ├── OnDeviceLlm.kt           # MediaPipe LlmInference wrapper
│   └── ModelDownloader.kt       # DownloadManager + progress Flow
├── ocr/
│   └── OcrEngine.kt             # ML Kit Latin + Devanagari OCR + image labeling
└── speech/
    ├── SpeechInput.kt           # Android SpeechRecognizer (offline ASR)
    └── SpeechOutput.kt          # Android TextToSpeech (hi-IN / en-IN / kn-IN)
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0.0 |
| UI | Jetpack Compose BOM 2024.06.00 |
| Camera | CameraX 1.3.4 |
| On-device AI | MediaPipe tasks-genai 0.10.14 |
| Cloud AI | OpenRouter + OkHttp 4.12.0 |
| OCR | ML Kit text-recognition + devanagari + image-labeling |
| Speech in | Android SpeechRecognizer |
| Speech out | Android TextToSpeech |
| Build | AGP 8.4.1, Gradle Kotlin DSL |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 |

---

## Haptic Language

| Event | Pattern |
|---|---|
| Photo captured | Single 40ms pulse |
| Result ready | Waveform: 50ms, pause, 80ms (rising double) |
| Listening started | Waveform: 30ms, pause, 30ms (gentle double) |
| Scene change alert | Double-pulse: 120ms, pause, 120ms (strong double) |
| Error | Single long 300ms |

---

## Building from Source

1. Clone and open in Android Studio Jellyfish or later.
2. Add to `local.properties`:
   ```
   OPENROUTER_API_KEY=sk-or-v1-...
   ```
3. Build → Run on a physical device (camera required). Emulator does not support CameraX properly.
4. On first launch the app requests Camera and Microphone permissions and starts downloading the on-device Gemma model in the background (~1.4 GB — WiFi recommended).

---

## Demo Script (Hackathon Presentation)

1. **Open app** — camera preview fills screen, "Cloud ready — tap to scan"
2. **Point at printed text** → tap shutter or press volume down → app reads the text aloud in Hindi
3. **Tap mic** → ask "What colour is this?" → app answers using the last captured frame
4. **Tap language button** to cycle to English → tap shutter again → English description
5. **Cover the camera** then uncover to show something new → watch surveillance mode trigger haptic double-pulse without any button press
6. **Switch to airplane mode** → tap scan → falls through to on-device model (slower, shows offline capability)

---

## Known Limitations

- On-device Gemma 2B INT4 has limited Hindi and no vision — it is last-resort only
- Surveillance alerts pause while a scan is in progress to avoid overlapping audio
- Kannada TTS quality depends on the device's installed TTS engine; best on Pixel with Google TTS
- OpenRouter free tier (Gemma 4 31B) has rate limits; if hit, falls to on-device
