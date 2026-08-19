# ProGuard / R8 rules for LeanType Voice Plugin

# Keep AIDL IPC contracts and Parcelable models
-keep class com.leanbitlab.leantype.voice.** { *; }
-keep interface com.leanbitlab.leantype.voice.** { *; }

# Keep Parcelable CREATORs
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep JNI native bindings and WhisperNative
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.leanbitlab.leantype.voice.offline.engine.WhisperNative { *; }
-keep class com.leanbitlab.leantype.voice.offline.engine.WhisperEngine { *; }

# Keep Service entry point and ModelManager
-keep class com.leanbitlab.leantype.voice.offline.VoiceEngineService { *; }
-keep class com.leanbitlab.leantype.voice.offline.model.ModelManager { *; }

# Keep Enum values and valueOf methods
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

