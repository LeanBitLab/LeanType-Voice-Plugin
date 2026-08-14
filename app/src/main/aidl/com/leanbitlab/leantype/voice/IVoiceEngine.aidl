package com.leanbitlab.leantype.voice;

import com.leanbitlab.leantype.voice.IVoiceCallback;
import com.leanbitlab.leantype.voice.VoiceEngineInfo;
import com.leanbitlab.leantype.voice.ModelState;
import com.leanbitlab.leantype.voice.ModelImportRequest;
import com.leanbitlab.leantype.voice.VoiceSessionConfig;
import android.os.ParcelFileDescriptor;

interface IVoiceEngine {
    VoiceEngineInfo getInfo();
    ModelState getModelState(String engineType);
    void importModel(in ModelImportRequest request);
    void unloadModel(String engineType);
    void deleteModel(String engineType);
    void startSession(
        in VoiceSessionConfig config,
        in ParcelFileDescriptor audioInput,
        IVoiceCallback callback
    );
    void stopSession();
    void cancelSession();
    void release();
}
