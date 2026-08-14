# LeanType Voice Plugin

Offline voice input plugin for [LeanType Keyboard](https://github.com/LeanBitLab/LeanType).

## Overview
This plugin provides on-device speech-to-text capabilities using Vosk (with future Whisper support) for LeanType Keyboard via an IPC AIDL contract (`IVoiceEngine`).

## Architecture
- **AIDL Interface**: `com.leanbitlab.leantype.voice.IVoiceEngine`
- **Service Action**: `com.leanbitlab.leantype.voice.offline.ENGINE`
- **Permission**: `com.leanbitlab.leantype.permission.BIND_VOICE_PLUGIN` (signature-protected)

## Building
```bash
./gradlew assembleDebug
```
