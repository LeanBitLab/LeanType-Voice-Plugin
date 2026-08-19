# LeanType Voice Plugin

Offline voice input plugin for [LeanType Keyboard](https://github.com/LeanBitLab/LeanType).

## Overview
This plugin provides fast, on-device offline speech-to-text capabilities using [whisper.cpp](https://github.com/ggerganov/whisper.cpp) for the LeanType Keyboard via an IPC AIDL contract (`IVoiceEngine`).

## Architecture
- **Engine**: Whisper.cpp with ARM64 NEON optimizations & JNI bridge
- **AIDL Interface**: `com.leanbitlab.leantype.voice.IVoiceEngine`
- **Service Action**: `com.leanbitlab.leantype.voice.offline.ENGINE`

## Building
```bash
./gradlew assembleDebug
```

## License
Licensed under the [GNU General Public License v3.0](LICENSE).

