/*
 * Duvo - remote control and streaming for a Windows PC.
 * Copyright (C) 2026 Duvo contributors
 *
 * JNI bridge between moonlight-common-c and app.duvo.remote.stream.MoonlightBridge.
 * Adapted from Moonlight Android's moonlight-core/callbacks.c and simplejni.c
 * (https://github.com/moonlight-stream/moonlight-android, GPLv3, Copyright (C) Moonlight contributors).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <unistd.h>

#include <android/log.h>
#include <opus_multistream.h>

#include <Limelight.h>

#if defined(__aarch64__)
#include <asm/hwcap.h>
#elif defined(__arm__)
#include <asm/hwcap.h>
#endif

/* Version of the contract with MoonlightBridge/StreamCallbacks; bump when either side changes. */
#define DUVO_BRIDGE_PROTOCOL_VERSION 3

#define LOG_TAG "DuvoNative"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static JavaVM *JVM;
static pthread_key_t JniEnvKey;
static pthread_once_t JniEnvKeyInitOnce = PTHREAD_ONCE_INIT;

/*
 * Callbacks go to static methods of MoonlightBridge, which forward them to the current session's
 * StreamCallbacks. The class reference is created once in JNI_OnLoad and never released, so a late
 * caller can never touch a deleted reference: moonlight-common-c runs connectionTerminated on a
 * detached "AsyncTerm" thread that LiStopConnection() does not join. Static method IDs stay valid
 * for as long as the class is loaded.
 */
static jclass Bridge;
static pthread_once_t DispatchInitOnce = PTHREAD_ONCE_INIT;
static int DispatchReady;
static jmethodID CbStageStarting;
static jmethodID CbStageFailed;
static jmethodID CbConnectionStarted;
static jmethodID CbConnectionTerminated;
static jmethodID CbConnectionStatusUpdate;
static jmethodID CbVideoSetup;
static jmethodID CbVideoCleanup;
static jmethodID CbVideoSubmit;
static jmethodID CbAudioInit;
static jmethodID CbAudioPlay;
static jmethodID CbAudioCleanup;
static jmethodID CbRumble;
static jmethodID CbRumbleTriggers;
static jmethodID CbSetMotionEventState;

/* Host processing latency of the last frame, in 0.1 ms (0 when the host does not report it). */
static atomic_int LastHostLatency;

/*
 * Generation of the live session (MoonlightBridge numbers sessions from 1), 0 when none is live.
 * Each callback reads it on entry and passes it along; MoonlightBridge drops a callback whose
 * generation is not the current session's, so a late callback of one session never reaches the next.
 */
static atomic_int LiveGeneration;

static jbyteArray FrameBuffer;
static jshortArray PcmBuffer;
static OpusMSDecoder *Opus;
static OPUS_MULTISTREAM_CONFIGURATION OpusConfig;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    JVM = vm;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    /* FindClass does not initialize the class, so this is safe from MoonlightBridge's <clinit>. */
    jclass cls = (*env)->FindClass(env, "app/duvo/remote/stream/MoonlightBridge");
    if (cls == NULL) {
        return JNI_ERR; /* NoClassDefFoundError pending: System.loadLibrary() throws */
    }
    Bridge = (*env)->NewGlobalRef(env, cls); /* never released */
    (*env)->DeleteLocalRef(env, cls);
    return JNI_VERSION_1_6;
}

static void DetachThread(void *context) {
    (*JVM)->DetachCurrentThread(JVM);
}

static void JniEnvKeyInit(void) {
    pthread_key_create(&JniEnvKey, DetachThread);
}

/* moonlight-common-c calls us on its own threads: attach once per thread, detach when it exits. */
static JNIEnv *GetThreadEnv(void) {
    JNIEnv *env;
    if ((*JVM)->GetEnv(JVM, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
    pthread_once(&JniEnvKeyInitOnce, JniEnvKeyInit);
    env = pthread_getspecific(JniEnvKey);
    if (env) {
        return env;
    }
    (*JVM)->AttachCurrentThread(JVM, &env, NULL);
    pthread_setspecific(JniEnvKey, env);
    return env;
}

/*
 * Looked up on first use rather than in JNI_OnLoad: GetStaticMethodID initializes MoonlightBridge,
 * whose <clinit> is what loads this library. Runs on a Java thread (nativeStart or a test hook),
 * before any moonlight-common-c thread exists; a NoSuchMethodError stays pending there.
 */
static void DispatchInit(void) {
    JNIEnv *env = GetThreadEnv();
    CbStageStarting = (*env)->GetStaticMethodID(env, Bridge, "onStageStarting", "(ILjava/lang/String;)V");
    CbStageFailed = (*env)->GetStaticMethodID(env, Bridge, "onStageFailed", "(ILjava/lang/String;I)V");
    CbConnectionStarted = (*env)->GetStaticMethodID(env, Bridge, "onConnectionStarted", "(I)V");
    CbConnectionTerminated = (*env)->GetStaticMethodID(env, Bridge, "onConnectionTerminated", "(II)V");
    CbConnectionStatusUpdate = (*env)->GetStaticMethodID(env, Bridge, "onConnectionStatusUpdate", "(II)V");
    CbVideoSetup = (*env)->GetStaticMethodID(env, Bridge, "onVideoSetup", "(IIIII)I");
    CbVideoCleanup = (*env)->GetStaticMethodID(env, Bridge, "onVideoCleanup", "(I)V");
    CbVideoSubmit = (*env)->GetStaticMethodID(env, Bridge, "onVideoSubmit", "(I[BIIIIJ)I");
    CbAudioInit = (*env)->GetStaticMethodID(env, Bridge, "onAudioInit", "(IIII)I");
    CbAudioPlay = (*env)->GetStaticMethodID(env, Bridge, "onAudioPlay", "(I[SI)V");
    CbAudioCleanup = (*env)->GetStaticMethodID(env, Bridge, "onAudioCleanup", "(I)V");
    CbRumble = (*env)->GetStaticMethodID(env, Bridge, "onRumble", "(IIII)V");
    CbRumbleTriggers = (*env)->GetStaticMethodID(env, Bridge, "onRumbleTriggers", "(IIII)V");
    CbSetMotionEventState = (*env)->GetStaticMethodID(env, Bridge, "onSetMotionEventState", "(IIII)V");
    DispatchReady = !(*env)->ExceptionCheck(env);
}

/* 0 when the callbacks are resolved; otherwise an exception is pending in env. */
static int EnsureDispatch(JNIEnv *env) {
    pthread_once(&DispatchInitOnce, DispatchInit);
    if (DispatchReady) {
        return 0;
    }
    if (!(*env)->ExceptionCheck(env)) {
        jclass ise = (*env)->FindClass(env, "java/lang/IllegalStateException");
        (*env)->ThrowNew(env, ise, "MoonlightBridge callbacks missing (see the first NoSuchMethodError)");
    }
    return -1;
}

/* A Kotlin callback threw: log it and keep the stream alive instead of crashing the process. */
static int ClearException(JNIEnv *env, const char *where) {
    if ((*env)->ExceptionCheck(env)) {
        LOGW("exception in %s", where);
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return 1;
    }
    return 0;
}

static void CallStage(jmethodID method, int stage, int errorCode, int withError) {
    jint gen = atomic_load(&LiveGeneration);
    if (gen == 0) {
        return;
    }
    JNIEnv *env = GetThreadEnv();
    jstring name = (*env)->NewStringUTF(env, LiGetStageName(stage));
    if (withError) {
        (*env)->CallStaticVoidMethod(env, Bridge, method, gen, name, errorCode);
    } else {
        (*env)->CallStaticVoidMethod(env, Bridge, method, gen, name);
    }
    ClearException(env, "stage callback");
    (*env)->DeleteLocalRef(env, name);
}

static void ClStageStarting(int stage) {
    CallStage(CbStageStarting, stage, 0, 0);
}

static void ClStageComplete(int stage) {
}

static void ClStageFailed(int stage, int errorCode) {
    CallStage(CbStageFailed, stage, errorCode, 1);
}

/* A void callback whose only argument is the generation; skipped when no session is live. */
static void CallSimple(jmethodID method, const char *where) {
    jint gen = atomic_load(&LiveGeneration);
    if (gen == 0) {
        return;
    }
    JNIEnv *env = GetThreadEnv();
    (*env)->CallStaticVoidMethod(env, Bridge, method, gen);
    ClearException(env, where);
}

static void ClConnectionStarted(void) {
    CallSimple(CbConnectionStarted, "connectionStarted");
}

static void DispatchConnectionTerminated(jint gen, int errorCode) {
    if (gen == 0) {
        return;
    }
    JNIEnv *env = GetThreadEnv();
    (*env)->CallStaticVoidMethod(env, Bridge, CbConnectionTerminated, gen, errorCode);
    ClearException(env, "connectionTerminated");
}

/* Runs on moonlight-common-c's detached AsyncTerm thread, possibly after LiStopConnection() returned. */
static void ClConnectionTerminated(int errorCode) {
    DispatchConnectionTerminated(atomic_load(&LiveGeneration), errorCode);
}

static void ClConnectionStatusUpdate(int status) {
    jint gen = atomic_load(&LiveGeneration);
    if (gen == 0) {
        return;
    }
    JNIEnv *env = GetThreadEnv();
    (*env)->CallStaticVoidMethod(env, Bridge, CbConnectionStatusUpdate, gen, status);
    ClearException(env, "connectionStatusUpdate");
}

/* Controller feedback callbacks: (generation, controller, a, b). */
static void CallFour(jmethodID method, int a, int b, int c, const char *where) {
    jint gen = atomic_load(&LiveGeneration);
    if (gen == 0) {
        return;
    }
    JNIEnv *env = GetThreadEnv();
    (*env)->CallStaticVoidMethod(env, Bridge, method, gen, a, b, c);
    ClearException(env, where);
}

static void ClRumble(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) {
    CallFour(CbRumble, controllerNumber, lowFreqMotor, highFreqMotor, "rumble");
}

static void ClRumbleTriggers(uint16_t controllerNumber, uint16_t leftTriggerMotor, uint16_t rightTriggerMotor) {
    CallFour(CbRumbleTriggers, controllerNumber, leftTriggerMotor, rightTriggerMotor, "rumbleTriggers");
}

static void ClSetMotionEventState(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) {
    CallFour(CbSetMotionEventState, controllerNumber, motionType, reportRateHz, "setMotionEventState");
}

static void ClLogMessage(const char *format, ...) {
    va_list va;
    va_start(va, format);
    __android_log_vprint(ANDROID_LOG_INFO, "moonlight-common-c", format, va);
    va_end(va);
}

static int DrSetup(int videoFormat, int width, int height, int redrawRate, void *context, int drFlags) {
    jint gen = atomic_load(&LiveGeneration);
    if (gen == 0) {
        return -1;
    }
    JNIEnv *env = GetThreadEnv();
    int err = (*env)->CallStaticIntMethod(env, Bridge, CbVideoSetup, gen, videoFormat, width, height, redrawRate);
    if (ClearException(env, "videoSetup")) {
        return -1;
    }
    if (err != 0) {
        return err;
    }
    /* Starts at 256 KB and grows to the largest frame seen. */
    jbyteArray local = (*env)->NewByteArray(env, 256 * 1024);
    FrameBuffer = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    return 0;
}

static void DrCleanup(void) {
    JNIEnv *env = GetThreadEnv();
    if (FrameBuffer != NULL) {
        (*env)->DeleteGlobalRef(env, FrameBuffer);
        FrameBuffer = NULL;
    }
    CallSimple(CbVideoCleanup, "videoCleanup");
}

/*
 * Flattens one frame into FrameBuffer: parameter sets (VPS/SPS/PPS) first, then the picture data,
 * and hands both to Kotlin in a single call so the decoder can queue them back to back.
 */
static int DrSubmitDecodeUnit(PDECODE_UNIT du) {
    jint gen = atomic_load(&LiveGeneration);
    if (gen == 0) {
        return DR_OK; /* stopping: nobody to decode it */
    }
    JNIEnv *env = GetThreadEnv();
    atomic_store(&LastHostLatency, du->frameHostProcessingLatency);

    if ((*env)->GetArrayLength(env, FrameBuffer) < du->fullLength) {
        (*env)->DeleteGlobalRef(env, FrameBuffer);
        jbyteArray local = (*env)->NewByteArray(env, du->fullLength);
        FrameBuffer = (*env)->NewGlobalRef(env, local);
        (*env)->DeleteLocalRef(env, local);
    }

    int offset = 0;
    for (PLENTRY e = du->bufferList; e != NULL; e = e->next) {
        if (e->bufferType != BUFFER_TYPE_PICDATA) {
            (*env)->SetByteArrayRegion(env, FrameBuffer, offset, e->length, (jbyte *)e->data);
            offset += e->length;
        }
    }
    int csdLength = offset;
    for (PLENTRY e = du->bufferList; e != NULL; e = e->next) {
        if (e->bufferType == BUFFER_TYPE_PICDATA) {
            (*env)->SetByteArrayRegion(env, FrameBuffer, offset, e->length, (jbyte *)e->data);
            offset += e->length;
        }
    }

    int ret = (*env)->CallStaticIntMethod(env, Bridge, CbVideoSubmit, gen, FrameBuffer, csdLength, offset,
                                          du->frameNumber, du->frameType, (jlong)du->enqueueTimeUs);
    if (ClearException(env, "videoSubmit")) {
        return DR_OK;
    }
    return ret;
}

static int ArInit(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void *context, int arFlags) {
    jint gen = atomic_load(&LiveGeneration);
    if (gen == 0) {
        return -1;
    }
    JNIEnv *env = GetThreadEnv();
    int err = (*env)->CallStaticIntMethod(env, Bridge, CbAudioInit, gen, opusConfig->channelCount,
                                          opusConfig->sampleRate, opusConfig->samplesPerFrame);
    if (ClearException(env, "audioInit")) {
        return -1;
    }
    if (err != 0) {
        return err;
    }
    memcpy(&OpusConfig, opusConfig, sizeof(*opusConfig));
    Opus = opus_multistream_decoder_create(opusConfig->sampleRate, opusConfig->channelCount,
                                           opusConfig->streams, opusConfig->coupledStreams,
                                           opusConfig->mapping, &err);
    if (Opus == NULL) {
        LOGW("opus_multistream_decoder_create failed: %d", err);
        (*env)->CallStaticVoidMethod(env, Bridge, CbAudioCleanup, gen);
        ClearException(env, "audioCleanup");
        return -1;
    }
    jshortArray local = (*env)->NewShortArray(env, opusConfig->channelCount * opusConfig->samplesPerFrame);
    PcmBuffer = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    return 0;
}

static void ArCleanup(void) {
    JNIEnv *env = GetThreadEnv();
    if (Opus != NULL) {
        opus_multistream_decoder_destroy(Opus);
        Opus = NULL;
    }
    if (PcmBuffer != NULL) {
        (*env)->DeleteGlobalRef(env, PcmBuffer);
        PcmBuffer = NULL;
    }
    CallSimple(CbAudioCleanup, "audioCleanup");
}

static void ArDecodeAndPlaySample(char *sampleData, int sampleLength) {
    jint gen = atomic_load(&LiveGeneration);
    if (gen == 0) {
        return;
    }
    JNIEnv *env = GetThreadEnv();
    jshort *pcm = (*env)->GetPrimitiveArrayCritical(env, PcmBuffer, NULL);
    int frames = opus_multistream_decode(Opus, (const unsigned char *)sampleData, sampleLength, pcm,
                                         OpusConfig.samplesPerFrame, 0);
    if (frames > 0) {
        (*env)->ReleasePrimitiveArrayCritical(env, PcmBuffer, pcm, 0);
        (*env)->CallStaticVoidMethod(env, Bridge, CbAudioPlay, gen, PcmBuffer, frames * OpusConfig.channelCount);
        ClearException(env, "audioPlay");
    } else {
        (*env)->ReleasePrimitiveArrayCritical(env, PcmBuffer, pcm, JNI_ABORT);
    }
}

static DECODER_RENDERER_CALLBACKS VideoCallbacks = {
    .setup = DrSetup,
    .cleanup = DrCleanup,
    .submitDecodeUnit = DrSubmitDecodeUnit,
    .capabilities = 0,
};

static AUDIO_RENDERER_CALLBACKS AudioCallbacks = {
    .init = ArInit,
    .cleanup = ArCleanup,
    .decodeAndPlaySample = ArDecodeAndPlaySample,
    .capabilities = CAPABILITY_SUPPORTS_ARBITRARY_AUDIO_DURATION,
};

static CONNECTION_LISTENER_CALLBACKS ListenerCallbacks = {
    .stageStarting = ClStageStarting,
    .stageComplete = ClStageComplete,
    .stageFailed = ClStageFailed,
    .connectionStarted = ClConnectionStarted,
    .connectionTerminated = ClConnectionTerminated,
    .logMessage = ClLogMessage,
    .connectionStatusUpdate = ClConnectionStatusUpdate,
    .rumble = ClRumble,
    .rumbleTriggers = ClRumbleTriggers,
    .setMotionEventState = ClSetMotionEventState,
};

/* Like Moonlight's hasFastAes(): only ask the host to encrypt audio/video with AES hardware. */
static int HasFastAes(void) {
    if (sysconf(_SC_NPROCESSORS_CONF) <= 2) {
        return 0;
    }
#if defined(__aarch64__)
    return (getauxval(AT_HWCAP) & HWCAP_AES) != 0;
#elif defined(__arm__)
    return (getauxval(AT_HWCAP2) & HWCAP2_AES) != 0;
#else
    return 1;
#endif
}

JNIEXPORT jint JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeProtocolVersion(JNIEnv *env, jclass clazz) {
    return DUVO_BRIDGE_PROTOCOL_VERSION;
}

JNIEXPORT jstring JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeStageName(JNIEnv *env, jclass clazz, jint stage) {
    return (*env)->NewStringUTF(env, LiGetStageName(stage));
}

JNIEXPORT jint JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeStart(
        JNIEnv *env, jclass clazz,
        jstring address, jstring appVersion, jstring rtspSessionUrl, jint serverCodecModeSupport,
        jint width, jint height, jint fps, jint bitrateKbps,
        jint packetSize, jint streamingRemotely, jint audioConfiguration,
        jint supportedVideoFormats, jint clientRefreshRateX100,
        jbyteArray riAesKey, jbyteArray riAesIv,
        jint colorSpace, jint colorRange,
        jint generation) {
    if (EnsureDispatch(env) != 0) {
        return -1;
    }

    const char *rtspUrl = (*env)->GetStringUTFChars(env, rtspSessionUrl, 0);
    SERVER_INFORMATION serverInfo = {
        .address = (*env)->GetStringUTFChars(env, address, 0),
        .serverInfoAppVersion = (*env)->GetStringUTFChars(env, appVersion, 0),
        .serverInfoGfeVersion = NULL,
        .rtspSessionUrl = rtspUrl[0] != '\0' ? rtspUrl : NULL,
        .serverCodecModeSupport = serverCodecModeSupport,
    };

    STREAM_CONFIGURATION streamConfig;
    LiInitializeStreamConfiguration(&streamConfig);
    streamConfig.width = width;
    streamConfig.height = height;
    streamConfig.fps = fps;
    streamConfig.bitrate = bitrateKbps;
    streamConfig.packetSize = packetSize;
    streamConfig.streamingRemotely = streamingRemotely;
    streamConfig.audioConfiguration = audioConfiguration;
    streamConfig.supportedVideoFormats = supportedVideoFormats;
    streamConfig.clientRefreshRateX100 = clientRefreshRateX100;
    streamConfig.colorSpace = colorSpace;
    streamConfig.colorRange = colorRange;
    streamConfig.encryptionFlags = HasFastAes() ? ENCFLG_ALL : ENCFLG_AUDIO;
    (*env)->GetByteArrayRegion(env, riAesKey, 0, sizeof(streamConfig.remoteInputAesKey),
                               (jbyte *)streamConfig.remoteInputAesKey);
    (*env)->GetByteArrayRegion(env, riAesIv, 0, sizeof(streamConfig.remoteInputAesIv),
                               (jbyte *)streamConfig.remoteInputAesIv);

    atomic_store(&LiveGeneration, generation);
    int ret = LiStartConnection(&serverInfo, &streamConfig, &ListenerCallbacks,
                                &VideoCallbacks, &AudioCallbacks, NULL, 0, NULL, 0);
    if (ret != 0) {
        /* A failed LiStartConnection() cleans up after itself; LiStopConnection() is not called. */
        atomic_store(&LiveGeneration, 0);
    }

    (*env)->ReleaseStringUTFChars(env, address, serverInfo.address);
    (*env)->ReleaseStringUTFChars(env, appVersion, serverInfo.serverInfoAppVersion);
    (*env)->ReleaseStringUTFChars(env, rtspSessionUrl, rtspUrl);
    return ret;
}

JNIEXPORT void JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeInterrupt(JNIEnv *env, jclass clazz) {
    LiInterruptConnection();
}

JNIEXPORT void JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeStop(JNIEnv *env, jclass clazz) {
    /* From here on callbacks (including a pending AsyncTerm) carry generation 0 and are dropped. */
    atomic_store(&LiveGeneration, 0);
    LiStopConnection();
}

JNIEXPORT void JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeSendMousePosition(JNIEnv *env, jclass clazz, jshort x, jshort y,
                                                                     jshort refWidth, jshort refHeight) {
    LiSendMousePositionEvent(x, y, refWidth, refHeight);
}

JNIEXPORT jint JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeSendTouch(JNIEnv *env, jclass clazz, jint eventType, jint pointerId,
                                                             jfloat x, jfloat y, jfloat pressure,
                                                             jfloat major, jfloat minor, jint rotation) {
    return LiSendTouchEvent((uint8_t)eventType, (uint32_t)pointerId, x, y, pressure, major, minor, (uint16_t)rotation);
}

JNIEXPORT jint JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeSendControllerArrival(JNIEnv *env, jclass clazz, jint number,
                                                                         jint activeMask, jint type, jint buttons,
                                                                         jint capabilities) {
    return LiSendControllerArrivalEvent((uint8_t)number, (uint16_t)activeMask, (uint8_t)type, (uint32_t)buttons,
                                        (uint16_t)capabilities);
}

JNIEXPORT jint JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeSendController(JNIEnv *env, jclass clazz, jint number, jint activeMask,
                                                                  jint buttons, jint leftTrigger, jint rightTrigger,
                                                                  jshort lx, jshort ly, jshort rx, jshort ry) {
    return LiSendMultiControllerEvent((short)number, (short)activeMask, buttons, (unsigned char)leftTrigger,
                                      (unsigned char)rightTrigger, lx, ly, rx, ry);
}

JNIEXPORT jint JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeSendControllerMotion(JNIEnv *env, jclass clazz, jint number,
                                                                        jint motionType, jfloat x, jfloat y, jfloat z) {
    return LiSendControllerMotionEvent((uint8_t)number, (uint8_t)motionType, x, y, z);
}

JNIEXPORT jint JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeHostFeatureFlags(JNIEnv *env, jclass clazz) {
    return (jint)LiGetHostFeatureFlags();
}

/* (rtt << 32) | variance, in ms; -1 when not known yet. */
JNIEXPORT jlong JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeRtt(JNIEnv *env, jclass clazz) {
    uint32_t rtt, var;
    if (!LiGetEstimatedRttInfo(&rtt, &var)) {
        return -1;
    }
    return ((jlong)rtt << 32) | var;
}

JNIEXPORT jint JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeLastHostLatency(JNIEnv *env, jclass clazz) {
    return atomic_load(&LastHostLatency);
}

#ifdef DUVO_TEST_HOOKS
/*
 * Debug-only test hooks for NativeBridgeStressTest: reproduce moonlight-common-c's AsyncTerm
 * thread (a detached thread running connectionTerminated) without a host.
 */
static atomic_int TestPendingTerminations;

struct TestTermination {
    int errorCode;
    int delayUs;
    int generationDelta;
};

static void *TestTerminationThread(void *arg) {
    struct TestTermination t = *(struct TestTermination *)arg;
    free(arg);
    if (t.delayUs > 0) {
        usleep(t.delayUs);
    }
    if (t.generationDelta == 0) {
        ClConnectionTerminated(t.errorCode); /* exactly what AsyncTerm runs */
    } else {
        DispatchConnectionTerminated(atomic_load(&LiveGeneration) + t.generationDelta, t.errorCode);
    }
    atomic_fetch_sub(&TestPendingTerminations, 1);
    return NULL; /* the JniEnvKey destructor detaches this thread */
}

JNIEXPORT void JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeTestSimulateTermination(
        JNIEnv *env, jclass clazz, jint errorCode, jint delayUs, jint generationDelta) {
    if (EnsureDispatch(env) != 0) {
        return;
    }
    struct TestTermination *t = malloc(sizeof(*t));
    t->errorCode = errorCode;
    t->delayUs = delayUs;
    t->generationDelta = generationDelta;
    atomic_fetch_add(&TestPendingTerminations, 1);
    pthread_t thread;
    if (pthread_create(&thread, NULL, TestTerminationThread, t) != 0) {
        atomic_fetch_sub(&TestPendingTerminations, 1);
        free(t);
        return;
    }
    pthread_detach(thread);
}

JNIEXPORT jint JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeTestPendingTerminations(JNIEnv *env, jclass clazz) {
    return atomic_load(&TestPendingTerminations);
}
#endif

JNIEXPORT void JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeSendMouseMove(JNIEnv *env, jclass clazz, jshort dx, jshort dy) {
    LiSendMouseMoveEvent(dx, dy);
}

JNIEXPORT void JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeSendMouseButton(JNIEnv *env, jclass clazz, jboolean down, jint button) {
    LiSendMouseButtonEvent(down ? BUTTON_ACTION_PRESS : BUTTON_ACTION_RELEASE, button);
}

JNIEXPORT void JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeSendKeyboard(JNIEnv *env, jclass clazz, jshort keyCode,
                                                                jboolean down, jbyte modifiers) {
    LiSendKeyboardEvent(keyCode, down ? KEY_ACTION_DOWN : KEY_ACTION_UP, (char)modifiers);
}

JNIEXPORT void JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeSendScroll(JNIEnv *env, jclass clazz, jshort amount) {
    LiSendHighResScrollEvent(amount);
}

JNIEXPORT void JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeSendHScroll(JNIEnv *env, jclass clazz, jshort amount) {
    LiSendHighResHScrollEvent(amount);
}

JNIEXPORT void JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeSendText(JNIEnv *env, jclass clazz, jstring text) {
    if (text == NULL) {
        return;
    }
    /* GetStringUTFChars yields modified UTF-8 (supplementary characters as surrogate pairs, which a
     * UTF-8 decoder rejects), so encode from the UTF-16 data ourselves. */
    jsize len = (*env)->GetStringLength(env, text);
    const jchar *src = (*env)->GetStringChars(env, text, NULL);
    if (src == NULL) {
        return;
    }
    char *out = malloc((size_t)len * 3 + 1);
    if (out == NULL) {
        (*env)->ReleaseStringChars(env, text, src);
        return;
    }
    size_t n = 0;
    for (jsize i = 0; i < len; i++) {
        unsigned int c = src[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len && src[i + 1] >= 0xDC00 && src[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (src[i + 1] - 0xDC00);
            i++;
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            c = 0xFFFD; /* lone surrogate */
        }
        if (c < 0x80) {
            out[n++] = (char)c;
        } else if (c < 0x800) {
            out[n++] = (char)(0xC0 | (c >> 6));
            out[n++] = (char)(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            out[n++] = (char)(0xE0 | (c >> 12));
            out[n++] = (char)(0x80 | ((c >> 6) & 0x3F));
            out[n++] = (char)(0x80 | (c & 0x3F));
        } else {
            out[n++] = (char)(0xF0 | (c >> 18));
            out[n++] = (char)(0x80 | ((c >> 12) & 0x3F));
            out[n++] = (char)(0x80 | ((c >> 6) & 0x3F));
            out[n++] = (char)(0x80 | (c & 0x3F));
        }
    }
    (*env)->ReleaseStringChars(env, text, src);
    out[n] = '\0';
    LiSendUtf8TextEvent(out, (unsigned int)n);
    free(out);
}

JNIEXPORT void JNICALL
Java_app_duvo_remote_stream_MoonlightBridge_nativeSendKey(JNIEnv *env, jclass clazz, jshort vk,
                                                           jboolean down, jbyte modifiers, jbyte flags) {
    LiSendKeyboardEvent2(vk, down ? KEY_ACTION_DOWN : KEY_ACTION_UP, (char)modifiers, (char)flags);
}
