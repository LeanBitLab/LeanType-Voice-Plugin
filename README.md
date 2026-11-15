# LeanType Voice Plugin

Offline voice input plugin for [LeanType Keyboard](https://github.com/LeanBitLab/LeanType).

## Overview
This plugin provides fast, on-device offline speech-to-text capabilities using [whisper.cpp](https://github.com/ggerganov/whisper.cpp) for the LeanType Keyboard via an IPC AIDL contract (`IVoiceEngine`).

## Architecture
- **Engine**: Whisper.cpp with ARM64 NEON optimizations & JNI bridge
- **AIDL Interface**: `com.leanbitlab.leantype.voice.IVoiceEngine`
- **Service Action**: `com.leanbitlab.leantype.voice.offline.ENGINE`

## ✨ Features

- **🎙️ 100% On-Device Speech Recognition**: Fast, private speech-to-text powered by `whisper.cpp` with ARM NEON acceleration.
- **🌍 99+ Languages Supported**: Multi-lingual transcription with auto-detection and language-specific Whisper models.
- **🛡️ Complete Privacy & Zero Internet**: Speech audio never leaves your device. Operates 100% offline.
- **⚡ Real-Time Audio Visualizer**: Live waveform visualization, silence threshold sliders, and background keep-alive.
- **🔄 Universal Compatibility**: Compatible across **all 4 LeanType flavors** (`Standard Full`, `Standard`, `Offline`, and `Offline Lite`).

## 📋 System Requirements

- **Operating System**: Android 5.0 (API 21) or higher
- **Supported CPU Architectures**: `arm64-v8a`, `armeabi-v7a`, `x86_64`
- **Host Keyboard**: [LeanType](https://github.com/LeanBitLab/LeanType) v4.1.0+ (All flavors); v4.3.3+ for the **Transcribe while speaking** option
- **Permissions**: Microphone permission (`RECORD_AUDIO`)

---

## 📥 Installation & Setup Guide

### 1. Install the Voice Plugin APK
1. Download the latest release APK (`voice_plugin-arm64-v8a.apk`, `voice_plugin-armeabi-v7a.apk`, or universal) from [GitHub Releases](https://github.com/LeanBitLab/LeanType-Voice-Plugin/releases/latest).
2. **Install the APK on your Android device** (Unlike dynamic plugin libraries, the Voice Plugin runs as an IPC AIDL service and is installed as an app).
3. Grant **Microphone permission** when prompted (or in Android Settings → Apps → LeanType Voice Plugin → Permissions).

### 2. Download Whisper Models in LeanType
1. Open LeanType keyboard settings.
2. Navigate to **Settings → Plugins → Voice Input**.
3. Tap **Whisper Models**:
   - **Online Flavors (`Standard` / `Standard Full`)**: Download models directly inside the app with 1 tap.
   - **Offline Flavors (`Offline` / `Offline Lite`)**: Download `.bin` models in your browser, then tap **Import** on **Custom GGML Model** and select the file from device storage.

### 3. Whisper Speech Models

LeanType provides official, optimized Whisper speech models with verifiable build provenance and quantization via [leantype-whisper-models](https://github.com/LeanBitLab/leantype-whisper-models). Third-party GGML models, including FUTO ACFT (Audio-Context Fine-Tuned) models, are also supported.

For direct download links and full comparisons across English and Multilingual variants, see the [Voice Models Catalog](docs/MODELS.md).

| Model | Size | Speed | RAM Usage | Best For |
| :--- | :---: | :---: | :---: | :--- |
| **LeanType Base (English)** | ~57 MB | ⚡ Fast | ~200 MB | High-accuracy English dictation |
| **LeanType Base (Multilingual)** | ~57 MB | ⚡ Fast | ~220 MB | Balanced multilingual accuracy across 99 languages |
| **LeanType Tiny (English)** | ~31 MB | ⚡ Ultra-Fast | ~120 MB | Low-end devices & fast English typing |
| **LeanType Tiny (Multilingual)** | ~31 MB | ⚡ Ultra-Fast | ~130 MB | Quick multilingual typing on entry devices |
| **LeanType Small (English / Multilingual)** | ~182 MB | 🟡 Moderate | ~550 MB | Maximum accuracy for complex terminology |
| **FUTO Base (ACFT)** | ~78 MB | ⚡ Fast | ~250 MB | Mobile dictation fine-tuned on variable audio contexts |
| **FUTO Tiny (ACFT)** | ~42 MB | ⚡ Ultra-Fast | ~150 MB | Quick mobile voice typing on entry devices |
| **FUTO Small (ACFT)** | ~252 MB | 🟡 Moderate | ~650 MB | High accuracy on modern devices |

See [docs/MODELS.md](docs/MODELS.md) for direct download links.

### 4. Start Voice Typing
1. Bring up LeanType keyboard in any text field.
2. Tap the **Microphone icon** on the keyboard toolbar.
3. Speak clearly: the text is inserted into the active input field when you stop or pause.
4. Tap the microphone again to stop, or the cancel button on the strip to discard the recording. While the text is being processed, tapping the microphone does not cancel it.

### 5. Voice Input Options
These options are in **Settings → Plugins → Voice Input** in LeanType:

- **Silence Timeout**: How long a pause ends the recording automatically (default 3 seconds).
- **Transcribe while speaking** *(LeanType v4.3.3+, off by default)*: Transcribes each part of a long dictation at every pause in speech, so less is left to process when you stop. Silence at the end of each part is cut before it is transcribed, which avoids invented trailing words. If the text field changes while you dictate, the rest is not inserted and a message is shown.
- **Keep screen on**: Prevents the screen timeout while voice typing is active.
- **Keep model in memory**: Keeps the model loaded for a chosen time (1, 5 or 15 minutes, or always) to avoid the reload delay, or unloads it right after the session.
- **Language**: Follow the keyboard language, auto-detect, or pick one of the supported Whisper languages.

### 6. Battery & Background Behavior
The plugin is an on-demand service. LeanType connects to it when you start voice typing or import a model and releases it again when voice typing is idle or when the screen turns off, so it does not keep running in the background. If the plugin is busy (for example while copying a model), the release is retried later instead of being skipped.

### 7. Troubleshooting
- **"Voice plugin is not installed"**: Install the plugin APK from the [releases](https://github.com/LeanBitLab/LeanType-Voice-Plugin/releases/latest) page.
- **"Failed to import model into voice plugin"**: Update the plugin to the latest release and import the `.bin` file again.
- **No text or only a part of it**: Choose a larger model, check the microphone permission of the plugin, and try a longer **Silence Timeout**.

---

## 🏗️ Building From Source
```bash
./gradlew assembleDebug
```
Debug builds use the package `com.leanbitlab.leantype.voice.offline.debug`, so they can be installed next to the release plugin. LeanType debug builds connect to the debug plugin when it is installed.

## License
Licensed under the [GNU General Public License v3.0](LICENSE).

## 💖 Support the Development

If you find this project helpful, consider supporting the continuous development of the LeanType ecosystem:

<div align="left">
  <a href="https://github.com/sponsors/LeanBitLab" target="_blank">
    <img src="https://img.shields.io/badge/Sponsor-GitHub%20Sponsors-EA4AAA?style=for-the-badge&logo=githubsponsors&logoColor=white" alt="GitHub Sponsors" />
  </a>
  <a href="https://opencollective.com/leanbitlab-org" target="_blank">
    <img src="https://img.shields.io/badge/Donate-Open%20Collective-7B93FE?style=for-the-badge&logo=opencollective&logoColor=white" alt="Open Collective" />
  </a>
</div>
