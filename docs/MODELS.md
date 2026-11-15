# Voice Models for LeanType Voice Plugin

The LeanType Offline Voice Plugin uses [whisper.cpp](https://github.com/ggerganov/whisper.cpp) for on-device speech recognition. Any quantized Whisper model in GGML format (`.bin`) can be imported into LeanType.

---

## 🎙️ LeanType Whisper Models (Built-in & Direct Download)

Official models built, quantized (Q5_1), and verified with quality gates via [LeanBitLab/leantype-whisper-models](https://github.com/LeanBitLab/leantype-whisper-models). These models are available as built-in 1-tap downloads within LeanType keyboard (`Standard` flavor) or via direct download below. RAM is the peak private memory measured on a POCO M2 Pro (Snapdragon 720G, 4 threads), see the [benchmarks](https://github.com/LeanBitLab/leantype-whisper-models/tree/main/bench).

### English-Only Models
Best for users who dictate exclusively in English. Because all attention heads and embeddings are dedicated to English acoustics, these provide higher accuracy and faster inference.

| Model Variant | File Size | Speed | RAM | Best For | Direct Download |
| :--- | :---: | :---: | :---: | :--- | :---: |
| **Tiny (English)** (`whisper-tiny.en-q5_1`) | ~31 MB | ⚡ Ultra-Fast | ~130 MB | Low-end devices & quick voice typing | [Download .bin](https://github.com/LeanBitLab/leantype-whisper-models/releases/download/v1.0.0/leantype-whisper-tiny.en-q5_1.bin) |
| **Base (English)** (`whisper-base.en-q5_1`) | ~57 MB | ⚡ Fast | ~215 MB | High accuracy English dictation | [Download .bin](https://github.com/LeanBitLab/leantype-whisper-models/releases/download/v1.0.0/leantype-whisper-base.en-q5_1.bin) |
| **Small (English)** (`whisper-small.en-q5_1`) | ~182 MB | 🟡 Moderate | ~435 MB | Maximum English accuracy for complex terminology | [Download .bin](https://github.com/LeanBitLab/leantype-whisper-models/releases/download/v1.0.0/leantype-whisper-small.en-q5_1.bin) |

### Multilingual Models
Supports 99 languages with automatic language detection or explicit language selection.

| Model Variant | File Size | Speed | RAM | Best For | Direct Download |
| :--- | :---: | :---: | :---: | :--- | :---: |
| **Tiny (Multilingual)** (`whisper-tiny-q5_1`) | ~31 MB | ⚡ Ultra-Fast | ~130 MB | Quick multilingual typing on entry devices | [Download .bin](https://github.com/LeanBitLab/leantype-whisper-models/releases/download/v1.0.0/leantype-whisper-tiny-q5_1.bin) |
| **Base (Multilingual)** (`whisper-base-q5_1`) | ~57 MB | ⚡ Fast | ~220 MB | Balanced multilingual accuracy across 99 languages | [Download .bin](https://github.com/LeanBitLab/leantype-whisper-models/releases/download/v1.0.0/leantype-whisper-base-q5_1.bin) |
| **Small (Multilingual)** (`whisper-small-q5_1`) | ~182 MB | 🟡 Moderate | ~440 MB | Superior accuracy across 99 languages | [Download .bin](https://github.com/LeanBitLab/leantype-whisper-models/releases/download/v1.0.0/leantype-whisper-small-q5_1.bin) |

---

## ⚡ FUTO ACFT Models

FUTO's **Audio-Context Fine-Tuning (ACFT)** Whisper models are fine-tuned using self-distillation on variable-length audio contexts.

### English-Only Models
Best for users who dictate exclusively in English.

| Model Variant | File Size | Speed | RAM | Best For | Direct Download |
| :--- | :---: | :---: | :---: | :--- | :---: |
| **English-39 (Tiny)** | ~42 MB | ⚡ Ultra-Fast | ~150 MB | Low-end devices & quick voice typing | [Download .bin](https://dl.keyboard.futo.org/voice-input-english-39.bin) |
| **English-74 (Base)** | ~78 MB | ⚡ Fast | ~250 MB | Great balance of speed & accuracy | [Download .bin](https://dl.keyboard.futo.org/voice-input-english-74.bin) |
| **English-244 (Small)** | ~252 MB | 🟡 Moderate | ~650 MB | Highest English accuracy on modern devices | [Download .bin](https://dl.keyboard.futo.org/voice-input-english-244.bin) |

### Multilingual Models
Supports 99+ languages with automatic language detection or explicit language selection.

| Model Variant | File Size | Speed | RAM | Best For | Direct Download |
| :--- | :---: | :---: | :---: | :--- | :---: |
| **Multilingual-39 (Tiny)** | ~42 MB | ⚡ Ultra-Fast | ~160 MB | Quick multilingual typing on entry devices | [Download .bin](https://dl.keyboard.futo.org/voice-input-multilingual-39.bin) |
| **Multilingual-74 (Base)** | ~78 MB | ⚡ Fast | ~260 MB | Balanced multilingual typing | [Download .bin](https://dl.keyboard.futo.org/voice-input-multilingual-74.bin) |
| **Multilingual-244 (Small)** | ~252 MB | 🟡 Moderate | ~700 MB | Best accuracy for international languages | [Download .bin](https://dl.keyboard.futo.org/voice-input-multilingual-244.bin) |

---

## 📥 How to Import a Model into LeanType

1. Tap one of the **Download .bin** links above to download the model file to your Android device (usually saved to your `Downloads` folder).
2. Open **LeanType Settings → Plugins → Voice Input**.
3. Tap **Whisper Models**.
4. Tap **Import** on **Custom GGML Model**.
5. Select the downloaded `.bin` file from your device storage.
6. The model is imported into the plugin and ready to use ("Imported & Ready"). Use **Replace** to import another file or remove the custom model.
