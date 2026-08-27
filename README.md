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
- **Host Keyboard**: [LeanType](https://github.com/LeanBitLab/LeanType) v4.1.0+ (All flavors)
- **Permissions**: Microphone permission (`RECORD_AUDIO`)

---

## 📥 Installation & Setup Guide

### 1. Install the Voice Plugin APK
1. Download the latest release APK (`voice_plugin-arm64-v8a.apk`, `voice_plugin-armeabi-v7a.apk`, or universal) from [GitHub Releases](https://github.com/LeanBitLab/LeanType-Voice-Plugin/releases/latest).
2. **Install the APK on your Android device** (Unlike dynamic plugin libraries, the Voice Plugin runs as an IPC AIDL service and is installed as an app).
3. Grant **Microphone permission** when prompted (or in Android Settings → Apps → LeanType Voice Plugin → Permissions).

### 2. Download Whisper Models in LeanType
1. Open LeanType keyboard settings.
2. Navigate to **Settings → Voice typing** (or **Settings → Plugins → Voice**).
3. Tap **Whisper Speech Models**:
   - **Online Flavors (`Standard` / `Standard Full`)**: Download models directly inside the app with 1 tap.
   - **Offline Flavors (`Offline` / `Offline Lite`)**: Download `.bin` models in your browser and use **Import Model** to select them from device storage.

### 3. Recommended Whisper Models

| Model | Size | Speed | RAM Usage | Best For |
| :--- | :---: | :---: | :---: | :--- |
| **`ggml-tiny.bin`** | ~39 MB | ⚡ Ultra-Fast | ~150 MB | Low-end devices & quick voice typing |
| **`ggml-base.bin`** *(Recommended)* | ~74 MB | ⚡ Fast | ~250 MB | Best balance of accuracy & speed |
| **`ggml-small.bin`** | ~244 MB | 🟡 Moderate | ~600 MB | High accuracy on modern devices |

### 4. Start Voice Typing
1. Bring up LeanType keyboard in any text field.
2. Tap the **Microphone icon** on the keyboard toolbar.
3. Speak clearly — text will stream directly into the active input field!

---

## 🏗️ Building From Source
```bash
./gradlew assembleDebug
```

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
