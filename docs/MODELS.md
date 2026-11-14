# Voice Models for LeanType Voice Plugin

The LeanType Offline Voice Plugin uses [whisper.cpp](https://github.com/ggerganov/whisper.cpp) for on-device speech recognition. Any quantized Whisper model in GGML format (`.bin`) can be imported into LeanType.

---

## ⚡ Recommended: FUTO ACFT Models (Optimized for Mobile)

FUTO's **Audio-Context Fine-Tuning (ACFT)** Whisper models are fine-tuned using self-distillation on variable-length audio contexts. 

### Why ACFT?
- **No Infinite Loops or Stuttering:** Stock Whisper models are trained on fixed 30-second windows and often repeat words or hallucinate when processing short mobile voice dictations (2-5 seconds). ACFT models eliminate this behavior.
- **Faster Dictation:** The model emits clean end-of-transcript tokens immediately when speech ceases, finishing transcription faster.
- **100% Compatible:** Formatted as standard GGML binaries (`lmgg`), fully compatible with LeanType's offline engine.

### English-Only Models
Best for users who dictate exclusively in English. Because all attention heads and embeddings are dedicated to English acoustics, these provide higher accuracy and faster inference than multilingual variants of the same size.

| Model Variant | File Size | Speed | RAM | Best For | Direct Download |
| :--- | :---: | :---: | :---: | :--- | :---: |
| **English-39 (Tiny)** | ~42 MB | ⚡ Ultra-Fast | ~150 MB | Low-end devices & quick voice typing | [Download .bin](https://dl.keyboard.futo.org/voice-input-english-39.bin) |
| **English-74 (Base)** | ~78 MB | ⚡ Fast | ~250 MB | **Recommended for English** (Great balance of speed & accuracy) | [Download .bin](https://dl.keyboard.futo.org/voice-input-english-74.bin) |
| **English-244 (Small)** | ~252 MB | 🟡 Moderate | ~650 MB | Highest English accuracy on modern devices | [Download .bin](https://dl.keyboard.futo.org/voice-input-english-244.bin) |

### Multilingual Models
Supports 99+ languages with automatic language detection or explicit language selection.

| Model Variant | File Size | Speed | RAM | Best For | Direct Download |
| :--- | :---: | :---: | :---: | :--- | :---: |
| **Multilingual-39 (Tiny)** | ~42 MB | ⚡ Ultra-Fast | ~160 MB | Quick multilingual typing on entry devices | [Download .bin](https://dl.keyboard.futo.org/voice-input-multilingual-39.bin) |
| **Multilingual-74 (Base)** | ~78 MB | ⚡ Fast | ~260 MB | **Recommended for Multilingual** | [Download .bin](https://dl.keyboard.futo.org/voice-input-multilingual-74.bin) |
| **Multilingual-244 (Small)** | ~252 MB | 🟡 Moderate | ~700 MB | Best accuracy for international languages | [Download .bin](https://dl.keyboard.futo.org/voice-input-multilingual-244.bin) |

---

## 🏛️ Stock OpenAI Whisper Models (GGML Q5_1)

Canonical Whisper models quantized to Q5_1 by the `whisper.cpp` project. These models are available as built-in 1-tap downloads within LeanType.

| Model Variant | File Size | Speed | RAM | Best For | Direct Download |
| :--- | :---: | :---: | :---: | :--- | :---: |
| **OpenAI Tiny (Q5_1)** | ~32 MB | ⚡ Ultra-Fast | ~120 MB | Minimal storage & low RAM | [Download .bin](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin) |
| **OpenAI Base (Q5_1)** | ~57 MB | ⚡ Fast | ~200 MB | General voice typing | [Download .bin](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin) |
| **OpenAI Small (Q5_1)** | ~182 MB | 🟡 Moderate | ~550 MB | Higher accuracy | [Download .bin](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin) |

---

## 📥 How to Import a Model into LeanType

1. Tap one of the **Download .bin** links above to download the model file to your Android device (usually saved to your `Downloads` folder).
2. Open **LeanType Settings → Voice typing** (or **Settings → Plugins → Voice**).
3. Tap **Whisper Voice Models**.
4. Scroll to the bottom to **Custom GGML Model** and tap **Import**.
5. Select the downloaded `.bin` file from your device storage.
6. The model will be verified and loaded immediately.
