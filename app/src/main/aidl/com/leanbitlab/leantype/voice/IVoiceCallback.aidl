package com.leanbitlab.leantype.voice;

oneway interface IVoiceCallback {
    void onSessionStarted();
    void onPartial(String text);
    void onFinal(String text);
    void onError(int code, String message);
    void onSessionEnded();
}
