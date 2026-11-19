/*
 * Duvo - remote control and streaming for a Windows PC.
 * Copyright (C) 2026 Duvo contributors
 *
 * Portions ported from Moonlight Android's MoonBridge.java and NvConnection.java
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

package app.duvo.remote.stream

import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Everything one stream needs: the negotiated video mode plus what /launch returned. */
@Suppress("ArrayInDataClass")
data class StreamConfig(
    val host: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val mime: String,
    val riKey: ByteArray,
    val riKeyId: Int,
    val rtspUrl: String,
    /** serverinfo `appversion` (moonlight-common-c picks protocol variants from it). */
    val appVersion: String = "7.1.431.-1",
    /** serverinfo `ServerCodecModeSupport` (SCM_* mask). */
    val serverCodecModeSupport: Int = 0,
    /** Client display refresh rate x 100 (frame pacing hint for the host). */
    val refreshRateX100: Int = fps * 100,
) {
    companion object {
        /**
         * Moonlight-like default: 20 Mbps for 1080p60, 30 Mbps above 1080p, 10 Mbps up to 720p;
         * scaled linearly with fps up to 60 and by sqrt above it (120 fps = x1.41).
         */
        fun defaultBitrateKbps(width: Int, height: Int, fps: Int): Int {
            val pixels = width.toLong() * height
            val at60 = when {
                pixels <= 1280L * 720 -> 10_000
                pixels <= 1920L * 1080 -> 20_000
                else -> 30_000
            }
            val factor = if (fps <= 60) fps / 60.0 else sqrt(fps / 60.0)
            return (at60 * factor).roundToInt()
        }

        /** Frame rate asked for an input-only session (the host encodes one dummy frame, then idles). */
        const val INPUT_ONLY_FPS = 30

        /** Nominal bitrate of an input-only session: no real video flows, keep the host's encoder small. */
        const val INPUT_ONLY_BITRATE_KBPS = 1_000

        /**
         * An input-only connection (Apollo REMOTE_INPUT_UUID): H.264, which every host encodes, at the
         * device screen size; video callbacks accept and discard whatever arrives. [serverCodecModeSupport]
         * must be the value from pinned HTTPS serverinfo (0 trips an assert in debug moonlight-common-c).
         */
        fun inputOnly(
            host: String,
            width: Int,
            height: Int,
            riKey: ByteArray,
            riKeyId: Int,
            rtspUrl: String,
            appVersion: String,
            serverCodecModeSupport: Int,
        ) = StreamConfig(
            host = host,
            width = width,
            height = height,
            fps = INPUT_ONLY_FPS,
            bitrateKbps = INPUT_ONLY_BITRATE_KBPS,
            mime = DecoderProbe.MIME_AVC,
            riKey = riKey,
            riKeyId = riKeyId,
            rtspUrl = rtspUrl,
            appVersion = appVersion,
            serverCodecModeSupport = serverCodecModeSupport,
        )
    }
}

/**
 * Events from moonlight-common-c, called on its own threads (video/audio/control). They arrive
 * through MoonlightBridge's static on* methods (looked up by duvo_jni.c by name and signature),
 * only while the start() that registered these callbacks is the current session.
 */
interface StreamCallbacks {
    fun stageStarting(stage: String) {}
    fun stageFailed(stage: String, errorCode: Int) {}
    fun connectionStarted()
    fun connectionTerminated(errorCode: Int)
    fun connectionStatusUpdate(status: Int) {}

    /** Returns 0 when the decoder is ready for [videoFormat] (a VIDEO_FORMAT_* bit). */
    fun videoSetup(videoFormat: Int, width: Int, height: Int, fps: Int): Int
    fun videoCleanup() {}

    /**
     * One frame: bytes [0, csdLength) are the parameter sets of an IDR frame (VPS/SPS/PPS, may be
     * empty), [csdLength, length) the picture data. Returns DR_OK or DR_NEED_IDR.
     */
    fun videoSubmit(data: ByteArray, csdLength: Int, length: Int, frameNumber: Int, frameType: Int, enqueueTimeUs: Long): Int

    /** Returns 0 when audio output is ready for interleaved 16-bit PCM in this format. */
    fun audioInit(channelCount: Int, sampleRate: Int, samplesPerFrame: Int): Int = -1
    fun audioPlay(pcm: ShortArray, sampleCount: Int) {}
    fun audioCleanup() {}

    /** Host asks controller [controller] to rumble; motors 0..65535 (0/0 = stop). */
    fun rumble(controller: Int, lowFreq: Int, highFreq: Int) {}
    fun rumbleTriggers(controller: Int, left: Int, right: Int) {}

    /** Host wants [motionType] (MOTION_ACCEL/MOTION_GYRO) reports at [rateHz] (0 = stop). */
    fun setMotionEventState(controller: Int, motionType: Int, rateHz: Int) {}
}

/** moonlight-common-c connection (start/stop) and input. Expanded with touch/gamepad input in M2. */
interface NativeBridge {
    fun start(cfg: StreamConfig, cb: StreamCallbacks): Int
    fun stop()
    fun sendMouseMove(dx: Short, dy: Short)
    fun sendMouseButton(down: Boolean, button: Int)
    fun sendKeyboard(keyCode: Short, down: Boolean, modifiers: Byte)

    /** High-resolution vertical scroll; 120 = one wheel notch up (negative = down). */
    fun sendScroll(amount: Short) {}

    /** High-resolution horizontal scroll; 120 = one notch right. */
    fun sendHScroll(amount: Short) {}

    /** Types [text] on the PC (UTF-8, any Unicode). */
    fun sendText(text: String) {}

    /** Windows virtual-key [vk] press/release ([flags] = SS_KBE_FLAG_*, see [FLAG_NON_NORMALIZED]) with [MOD_SHIFT]/[MOD_CTRL]/[MOD_ALT]/[MOD_META] flags. */
    fun sendKey(vk: Short, down: Boolean, modifiers: Byte, flags: Byte = 0) {}

    /** Absolute cursor position [x],[y] inside a [refWidth]x[refHeight] reference (the video size). */
    fun sendMousePosition(x: Short, y: Short, refWidth: Short, refHeight: Short) {}

    /** Native touch (TOUCH_*); [x]/[y] normalized 0..1. Returns LI_ERR_UNSUPPORTED (<0) when the host lacks it. */
    fun sendTouch(type: Int, pointerId: Int, x: Float, y: Float, pressure: Float = 1f): Int = -1

    /** Announces gamepad [number] (CTYPE_*, supported BUTTON flags, CCAP_* capabilities). */
    fun sendControllerArrival(number: Int, activeMask: Int, type: Int, buttons: Int, capabilities: Int): Int = -1

    /** Full gamepad state: triggers 0..255, sticks -32768..32767 (up = positive Y). */
    fun sendController(
        number: Int, activeMask: Int, buttons: Int, leftTrigger: Int, rightTrigger: Int,
        lx: Short, ly: Short, rx: Short, ry: Short,
    ): Int = -1

    fun sendControllerMotion(number: Int, motionType: Int, x: Float, y: Float, z: Float): Int = -1

    /** LI_FF_* flags of the connected host. */
    fun hostFeatureFlags(): Int = 0

    /** Estimated network round trip in ms, or -1 when unknown. */
    fun rttMs(): Int = -1

    /** Host processing latency of the last frame, 0.1 ms units (0 = not reported). */
    fun lastHostLatencyTenthsMs(): Int = 0

    companion object {
        /** SS_KBE_FLAG_NON_NORMALIZED (Limelight.h): host uses the VK itself, not a US-layout scan code. */
        const val FLAG_NON_NORMALIZED: Byte = 0x01
        const val FF_PEN_TOUCH_EVENTS = 0x01
        const val TOUCH_HOVER = 0x00
        const val TOUCH_DOWN = 0x01
        const val TOUCH_UP = 0x02
        const val TOUCH_MOVE = 0x03
        const val TOUCH_CANCEL = 0x04
        const val TOUCH_CANCEL_ALL = 0x07
        const val CTYPE_XBOX = 0x01
        const val CTYPE_PS = 0x02
        const val CTYPE_NINTENDO = 0x03
        const val CCAP_ANALOG_TRIGGERS = 0x01
        const val CCAP_RUMBLE = 0x02
        const val CCAP_TRIGGER_RUMBLE = 0x04
        const val CCAP_ACCEL = 0x10
        const val CCAP_GYRO = 0x20
        const val MOTION_ACCEL = 0x01
        const val MOTION_GYRO = 0x02
        const val VIDEO_FORMAT_H264 = 0x0001
        const val VIDEO_FORMAT_H265 = 0x0100
        const val VIDEO_FORMAT_AV1_MAIN8 = 0x1000
        const val VIDEO_FORMAT_MASK_H264 = 0x000F
        const val VIDEO_FORMAT_MASK_H265 = 0x0F00
        const val VIDEO_FORMAT_MASK_AV1 = 0xF000

        const val FRAME_TYPE_PFRAME = 0
        const val FRAME_TYPE_IDR = 1

        const val DR_OK = 0
        const val DR_NEED_IDR = -1

        const val CONN_STATUS_OKAY = 0
        const val CONN_STATUS_POOR = 1

        const val ML_ERROR_GRACEFUL_TERMINATION = 0
        const val ML_ERROR_NO_VIDEO_TRAFFIC = -100
        const val ML_ERROR_NO_VIDEO_FRAME = -101
        const val ML_ERROR_UNEXPECTED_EARLY_TERMINATION = -102
        const val ML_ERROR_PROTECTED_CONTENT = -103
        const val ML_ERROR_FRAME_CONVERSION = -104

        const val BUTTON_LEFT = 0x01
        const val BUTTON_MIDDLE = 0x02
        const val BUTTON_RIGHT = 0x03

        const val MOD_SHIFT: Byte = 0x01
        const val MOD_CTRL: Byte = 0x02
        const val MOD_ALT: Byte = 0x04
        const val MOD_META: Byte = 0x08

        const val STREAM_CFG_LOCAL = 0
        const val COLORSPACE_REC_709 = 1
        const val COLOR_RANGE_LIMITED = 0

        /** MAKE_AUDIO_CONFIGURATION(2, 0x3) */
        const val AUDIO_CONFIGURATION_STEREO = (0x3 shl 16) or (2 shl 8) or 0xCA

        /** The format we ask for, plus H.264 as the host-side fallback (the decoder re-selects in videoSetup). */
        fun videoFormatsFor(mime: String): Int = when (mime) {
            DecoderProbe.MIME_HEVC -> VIDEO_FORMAT_H265 or VIDEO_FORMAT_H264
            DecoderProbe.MIME_AV1 -> VIDEO_FORMAT_AV1_MAIN8 or VIDEO_FORMAT_H264
            else -> VIDEO_FORMAT_H264
        }

        fun mimeFor(videoFormat: Int): String = when {
            videoFormat and VIDEO_FORMAT_MASK_H265 != 0 -> DecoderProbe.MIME_HEVC
            videoFormat and VIDEO_FORMAT_MASK_AV1 != 0 -> DecoderProbe.MIME_AV1
            else -> DecoderProbe.MIME_AVC
        }

        /** The remote input AES IV is rikeyid as a big-endian int in the first 4 of 16 bytes (as Moonlight). */
        fun riIv(riKeyId: Int): ByteArray = ByteBuffer.allocate(16).putInt(riKeyId).array()
    }
}

/** libmoonlight-common-c through JNI (src/main/cpp/duvo_jni.c). One connection at a time. */
object MoonlightBridge : NativeBridge {
    init {
        System.loadLibrary("duvo")
    }

    private val lock = Any()

    @Volatile private var running = false

    /** One start() call: the callbacks native events go to while [generation] is live. */
    private class Session(val generation: Int, val callbacks: StreamCallbacks)

    /**
     * Where native callbacks are forwarded (null between sessions). duvo_jni.c calls the static
     * on* methods below, never a StreamCallbacks reference it could outlive: moonlight-common-c
     * delivers connectionTerminated on a detached thread that may run after stop() returned.
     */
    @Volatile private var current: Session? = null

    /** Last generation handed out (guarded by [lock]); 0 means "no session" on the native side. */
    private var lastGeneration = 0

    override fun start(cfg: StreamConfig, cb: StreamCallbacks): Int = synchronized(lock) {
        check(!running) { "a stream is already running" }
        lastGeneration = if (lastGeneration == Int.MAX_VALUE) 1 else lastGeneration + 1
        val session = Session(lastGeneration, cb)
        current = session
        var ret = -1
        try {
            ret = nativeStart(
                cfg.host, cfg.appVersion, cfg.rtspUrl, cfg.serverCodecModeSupport,
                cfg.width, cfg.height, cfg.fps, cfg.bitrateKbps,
                PACKET_SIZE, NativeBridge.STREAM_CFG_LOCAL, NativeBridge.AUDIO_CONFIGURATION_STEREO,
                NativeBridge.videoFormatsFor(cfg.mime), cfg.refreshRateX100,
                cfg.riKey, NativeBridge.riIv(cfg.riKeyId),
                NativeBridge.COLORSPACE_REC_709, NativeBridge.COLOR_RANGE_LIMITED,
                session.generation,
            )
        } finally {
            if (ret != 0) current = null
        }
        running = ret == 0
        ret
    }

    override fun stop() {
        // Unblocks a pending LiStartConnection(); the lock then waits for it to return.
        nativeInterrupt()
        synchronized(lock) {
            // Before nativeStop: whatever arrives from now on (a pending AsyncTerm thread, the
            // decoder/audio cleanups inside LiStopConnection) is dropped. The caller releases its
            // decoder and audio itself after stop() (see StreamSession.teardown).
            current = null
            if (running) {
                running = false
                nativeStop()
            }
        }
    }

    /** The live session's callbacks if [generation] is still the current one, else null (drop). */
    private fun callbacksFor(generation: Int): StreamCallbacks? =
        current?.takeIf { it.generation == generation }?.callbacks

    // Called from duvo_jni.c (looked up by name and signature): keep in sync with DispatchInit().
    @JvmStatic private fun onStageStarting(generation: Int, stage: String) {
        callbacksFor(generation)?.stageStarting(stage)
    }

    @JvmStatic private fun onStageFailed(generation: Int, stage: String, errorCode: Int) {
        callbacksFor(generation)?.stageFailed(stage, errorCode)
    }

    @JvmStatic private fun onConnectionStarted(generation: Int) {
        callbacksFor(generation)?.connectionStarted()
    }

    @JvmStatic private fun onConnectionTerminated(generation: Int, errorCode: Int) {
        callbacksFor(generation)?.connectionTerminated(errorCode)
    }

    @JvmStatic private fun onConnectionStatusUpdate(generation: Int, status: Int) {
        callbacksFor(generation)?.connectionStatusUpdate(status)
    }

    @JvmStatic private fun onVideoSetup(generation: Int, videoFormat: Int, width: Int, height: Int, fps: Int): Int =
        callbacksFor(generation)?.videoSetup(videoFormat, width, height, fps) ?: -1

    @JvmStatic private fun onVideoCleanup(generation: Int) {
        callbacksFor(generation)?.videoCleanup()
    }

    @JvmStatic private fun onVideoSubmit(
        generation: Int,
        data: ByteArray,
        csdLength: Int,
        length: Int,
        frameNumber: Int,
        frameType: Int,
        enqueueTimeUs: Long,
    ): Int = callbacksFor(generation)?.videoSubmit(data, csdLength, length, frameNumber, frameType, enqueueTimeUs)
        ?: NativeBridge.DR_OK

    @JvmStatic private fun onAudioInit(generation: Int, channelCount: Int, sampleRate: Int, samplesPerFrame: Int): Int =
        callbacksFor(generation)?.audioInit(channelCount, sampleRate, samplesPerFrame) ?: -1

    @JvmStatic private fun onAudioPlay(generation: Int, pcm: ShortArray, sampleCount: Int) {
        callbacksFor(generation)?.audioPlay(pcm, sampleCount)
    }

    @JvmStatic private fun onAudioCleanup(generation: Int) {
        callbacksFor(generation)?.audioCleanup()
    }

    @JvmStatic private fun onRumble(generation: Int, controller: Int, low: Int, high: Int) {
        callbacksFor(generation)?.rumble(controller, low, high)
    }

    @JvmStatic private fun onRumbleTriggers(generation: Int, controller: Int, left: Int, right: Int) {
        callbacksFor(generation)?.rumbleTriggers(controller, left, right)
    }

    @JvmStatic private fun onSetMotionEventState(generation: Int, controller: Int, type: Int, rateHz: Int) {
        callbacksFor(generation)?.setMotionEventState(controller, type, rateHz)
    }

    override fun sendMousePosition(x: Short, y: Short, refWidth: Short, refHeight: Short) =
        nativeSendMousePosition(x, y, refWidth, refHeight)

    override fun sendTouch(type: Int, pointerId: Int, x: Float, y: Float, pressure: Float): Int =
        nativeSendTouch(type, pointerId, x, y, pressure, 0f, 0f, ROT_UNKNOWN)

    override fun sendControllerArrival(number: Int, activeMask: Int, type: Int, buttons: Int, capabilities: Int): Int =
        nativeSendControllerArrival(number, activeMask, type, buttons, capabilities)

    override fun sendController(
        number: Int, activeMask: Int, buttons: Int, leftTrigger: Int, rightTrigger: Int,
        lx: Short, ly: Short, rx: Short, ry: Short,
    ): Int = nativeSendController(number, activeMask, buttons, leftTrigger, rightTrigger, lx, ly, rx, ry)

    override fun sendControllerMotion(number: Int, motionType: Int, x: Float, y: Float, z: Float): Int =
        nativeSendControllerMotion(number, motionType, x, y, z)

    override fun hostFeatureFlags(): Int = nativeHostFeatureFlags()

    override fun rttMs(): Int = nativeRtt().let { if (it < 0) -1 else (it ushr 32).toInt() }

    override fun lastHostLatencyTenthsMs(): Int = nativeLastHostLatency()

    private const val ROT_UNKNOWN = 0xFFFF

    override fun sendMouseMove(dx: Short, dy: Short) = nativeSendMouseMove(dx, dy)

    override fun sendMouseButton(down: Boolean, button: Int) = nativeSendMouseButton(down, button)

    override fun sendKeyboard(keyCode: Short, down: Boolean, modifiers: Byte) =
        nativeSendKeyboard(keyCode, down, modifiers)

    override fun sendScroll(amount: Short) = nativeSendScroll(amount)

    override fun sendHScroll(amount: Short) = nativeSendHScroll(amount)

    override fun sendText(text: String) = nativeSendText(text)

    override fun sendKey(vk: Short, down: Boolean, modifiers: Byte, flags: Byte) =
        nativeSendKey(vk, down, modifiers, flags)

    /** Version of the duvo_jni.c / Kotlin contract (bumped whenever [StreamCallbacks] changes). */
    fun protocolVersion(): Int = nativeProtocolVersion()

    fun stageName(stage: Int): String = nativeStageName(stage)

    /** Like Moonlight for LAN streams (STREAM_CFG_LOCAL); remote streams cap it at 1024. */
    private const val PACKET_SIZE = 1392

    @JvmStatic private external fun nativeProtocolVersion(): Int

    @JvmStatic private external fun nativeStageName(stage: Int): String

    @JvmStatic private external fun nativeStart(
        address: String,
        appVersion: String,
        rtspSessionUrl: String,
        serverCodecModeSupport: Int,
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
        packetSize: Int,
        streamingRemotely: Int,
        audioConfiguration: Int,
        supportedVideoFormats: Int,
        clientRefreshRateX100: Int,
        riAesKey: ByteArray,
        riAesIv: ByteArray,
        colorSpace: Int,
        colorRange: Int,
        generation: Int,
    ): Int

    @JvmStatic private external fun nativeInterrupt()

    @JvmStatic private external fun nativeStop()

    @JvmStatic private external fun nativeSendMouseMove(dx: Short, dy: Short)

    @JvmStatic private external fun nativeSendMouseButton(down: Boolean, button: Int)

    @JvmStatic private external fun nativeSendKeyboard(keyCode: Short, down: Boolean, modifiers: Byte)

    @JvmStatic private external fun nativeSendScroll(amount: Short)

    @JvmStatic private external fun nativeSendHScroll(amount: Short)

    @JvmStatic private external fun nativeSendText(text: String)

    @JvmStatic private external fun nativeSendKey(vk: Short, down: Boolean, modifiers: Byte, flags: Byte)

    @JvmStatic private external fun nativeSendMousePosition(x: Short, y: Short, refWidth: Short, refHeight: Short)

    @JvmStatic private external fun nativeSendTouch(
        type: Int, pointerId: Int, x: Float, y: Float, pressure: Float, major: Float, minor: Float, rotation: Int,
    ): Int

    @JvmStatic private external fun nativeSendControllerArrival(
        number: Int, activeMask: Int, type: Int, buttons: Int, capabilities: Int,
    ): Int

    @JvmStatic private external fun nativeSendController(
        number: Int, activeMask: Int, buttons: Int, leftTrigger: Int, rightTrigger: Int,
        lx: Short, ly: Short, rx: Short, ry: Short,
    ): Int

    @JvmStatic private external fun nativeSendControllerMotion(number: Int, motionType: Int, x: Float, y: Float, z: Float): Int

    @JvmStatic private external fun nativeHostFeatureFlags(): Int

    @JvmStatic private external fun nativeRtt(): Long

    @JvmStatic private external fun nativeLastHostLatency(): Int

    /**
     * Test hook, debug builds only (DUVO_TEST_HOOKS in CMakeLists.txt): after [delayUs], runs the
     * connectionTerminated callback on a new detached native thread, exactly like moonlight-common-c's
     * "AsyncTerm" thread. [generationDelta] != 0 tags it with the live generation plus that delta
     * (-1 = a late callback from the previous session).
     */
    internal fun simulateAsyncTermination(errorCode: Int, delayUs: Int, generationDelta: Int = 0) =
        nativeTestSimulateTermination(errorCode, delayUs, generationDelta)

    /** Test hook: simulated terminations whose thread has not finished its callback yet. */
    internal fun pendingSimulatedTerminations(): Int = nativeTestPendingTerminations()

    @JvmStatic private external fun nativeTestSimulateTermination(errorCode: Int, delayUs: Int, generationDelta: Int)

    @JvmStatic private external fun nativeTestPendingTerminations(): Int
}
