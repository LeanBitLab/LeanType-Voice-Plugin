# LeanType Voice Plugin

Offline voice input plugin for [LeanType Keyboard](https://github.com/LeanBitLab/LeanType).

## Overview
This plugin provides fast, on-device offline speech-to-text capabilities using [whisper.cpp](https://github.com/ggerganov/whisper.cpp) for the LeanType Keyboard via an IPC AIDL contract (`IVoiceEngine`).

## Architecture
- **Engine**: Whisper.cpp with ARM64 NEON optimizations & JNI bridge
- **AIDL Interface**: `com.leanbitlab.leantype.voice.IVoiceEngine`
- **Service Action**: `com.leanbitlab.leantype.voice.offline.ENGINE`

## 📋 System Requirements

- **Operating System**: Android 5.0 (API 21) or higher
- **Supported CPU Architectures**: `arm64-v8a`, `armeabi-v7a`, `x86_64`
- **Host Keyboard**: [LeanType](https://github.com/LeanBitLab/LeanType) v4.1.0+ (All flavors)

## Building
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
