# ProGuard / R8 rules for LeanType Voice Plugin

# Keep AIDL IPC contracts and Parcelable models
-keep class com.leanbitlab.leantype.voice.** { *; }
-keep interface com.leanbitlab.leantype.voice.** { *; }

# Keep JNI native bindings and WhisperNative
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.leanbitlab.leantype.voice.offline.engine.WhisperNative { *; }
-keep class com.leanbitlab.leantype.voice.offline.engine.WhisperEngine { *; }

# Keep Service entry point
-keep class com.leanbitlab.leantype.voice.offline.VoiceEngineService { *; }
