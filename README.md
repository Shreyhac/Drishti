# Drishti — AI Vision Companion

> Built at **iQOO Hackathon 2026**, Bengaluru — 30-hour sprint  
> Native Android · Kotlin + Jetpack Compose · Offline-first

Drishti (दृष्टि, "vision") helps visually impaired users understand their surroundings in real time. Point the camera at anything — the app describes what it sees out loud in **Hindi, English, or Kannada**.

## Demo

**Camera → OCR + Object Detection → LLM → Sarvam AI TTS**

| Feature | How |
|---|---|
| Scan scene | Tap button or press **Vol↓** |
| Ask question | Hold **Vol↑** (push to talk) or tap mic |
| Switch language | Tap language button (HI / EN / KN) |
| Surveillance | Haptic alert when scene changes significantly |

## AI Stack

| Tier | Model | When |
|---|---|---|
| Cloud vision | Gemini 3.1 Flash | Online + image |
| Cloud text (free) | DeepSeek V3 | Online fallback |
| Local network | Ollama (any model) | Same WiFi as laptop |
| On-device | Gemma 3 1B INT4 | Fully offline |

**TTS:** Sarvam AI `bulbul:v1` (online) → Android TTS (offline)  
**STT:** Google Speech Recognition (online) → offline fallback

## Setup

### 1. Clone & open in Android Studio

```bash
git clone https://github.com/<you>/drishti-iqoo.git
```

Open in Android Studio Jellyfish or later.

### 2. Add API keys to `local.properties`

```properties
sdk.dir=/path/to/Android/Sdk
OPENROUTER_API_KEY=sk-or-v1-...
SARVAM_API_KEY=sk_...
```

Get keys from:
- OpenRouter: https://openrouter.ai/keys
- Sarvam AI: https://dashboard.sarvam.ai

### 3. Build & run

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires a **physical device** — CameraX does not work on emulators.

## Architecture

```
Camera (CameraX) ──► OCR + Labels (ML Kit, parallel)
                              │
                     buildPrompt()
                              │
         ┌────────────────────┼────────────────────┐
         ▼                    ▼                    ▼
   Ollama (WiFi)     Gemini/DeepSeek V3     Gemma 3 1B
         └────────────────────┼────────────────────┘
                              │
                   Sarvam TTS / Android TTS
                              │
                          🔊 Speaks
```

## Key Files

```
app/src/main/java/com/drishti/app/
├── MainActivity.kt              # Volume key handling, permissions
├── AppState.kt                  # State machine + language enum
├── ui/DrishtiScreen.kt          # Full Compose UI
├── viewmodel/DrishtiViewModel.kt
├── camera/CameraManager.kt
├── camera/SurveillanceAnalyzer.kt
├── ml/CloudFallback.kt          # OpenRouter (Gemini + DeepSeek)
├── ml/OnDeviceLlm.kt            # MediaPipe Gemma
├── ml/ModelDownloader.kt
├── ocr/OcrEngine.kt
├── speech/SpeechInput.kt        # Google STT
├── speech/SpeechOutput.kt       # Sarvam + Android TTS
└── speech/SarvamTts.kt          # Sarvam AI TTS client
```

## Tech Stack

Kotlin 2.0 · Jetpack Compose · CameraX 1.3.4 · ML Kit · MediaPipe tasks-genai 0.10.14 · OkHttp · Android TTS/STT · Sarvam AI

---

*Built for iQOO Hackathon 2026 — Team Drishti*
