/*
 * Duvo - remote control and streaming for a Windows PC.
 * Copyright (C) 2026 Duvo contributors
 *
 * Low-latency decoder options follow Moonlight Android's MediaCodecHelper.java and
 * MediaCodecDecoderRenderer.java (https://github.com/moonlight-stream/moonlight-android, GPLv3,
 * Copyright (C) Moonlight contributors).
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

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Frames shown and mean decode time (input queued -> output ready) since the last [VideoDecoder.takeStats]. */
data class DecoderStats(val framesRendered: Int, val avgDecodeMs: Float)

/** Video decoding for a stream; the real one is [MediaCodecVideoDecoder]. */
interface VideoDecoder {
    /** The decoder that would be used for [mime], or null if this device cannot decode it. */
    fun capsFor(mime: String): DecoderCaps?

    /** Creates and starts the decoder; false if no configuration was accepted. */
    fun setup(mime: String, width: Int, height: Int, fps: Int): Boolean

    /** See [StreamCallbacks.videoSubmit]. Returns [NativeBridge.DR_OK] or [NativeBridge.DR_NEED_IDR]. */
    fun submit(data: ByteArray, csdLength: Int, length: Int, keyFrame: Boolean): Int

    fun takeStats(): DecoderStats

    /** Name of the codec in use (after [setup]). */
    val codecName: String?

    /** Stops and frees the codec. Idempotent. */
    fun release()

    /** The picture surface went away (app minimized, screen off): free the codec but keep the stream going, dropping frames. */
    fun detachSurface() {}

    /** A surface is back: rebuild the codec and wait for the next key frame. */
    fun attachSurface(surface: Surface) {}

    /** [listener] is told (once) when the codec hits a fatal, non-transient error and cannot decode any more. */
    fun setErrorListener(listener: (String) -> Unit) {}
}

/** MediaFormat keys for low-latency decoding, with fallbacks if configure() rejects a combination. */
object DecoderTuning {
    const val ATTEMPTS = 3

    private const val KEY_LOW_LATENCY = "low-latency" // MediaFormat.KEY_LOW_LATENCY (API 30)
    private const val KEY_PRIORITY = "priority" // MediaFormat.KEY_PRIORITY
    private const val KEY_OPERATING_RATE = "operating-rate" // MediaFormat.KEY_OPERATING_RATE
    private const val QTI_LOW_LATENCY = "vendor.qti-ext-dec-low-latency.enable"

    fun isQualcomm(codecName: String) =
        codecName.startsWith("c2.qti", ignoreCase = true) || codecName.startsWith("omx.qcom", ignoreCase = true)

    /**
     * Attempt 0: KEY_LOW_LATENCY (API 30+), realtime priority, operating rate Short.MAX_VALUE (as
     * Moonlight) and the vendor key. Attempt 1 drops priority/operating rate (Moonlight saw some
     * Qualcomm decoders reject them together); the last attempt keeps only KEY_LOW_LATENCY.
     */
    fun formatKeys(codecName: String, sdkInt: Int, attempt: Int): Map<String, Int> {
        val keys = LinkedHashMap<String, Int>()
        if (sdkInt >= Build.VERSION_CODES.R) keys[KEY_LOW_LATENCY] = 1
        if (attempt == 0) {
            keys[KEY_PRIORITY] = 0
            keys[KEY_OPERATING_RATE] = Short.MAX_VALUE.toInt()
        }
        if (attempt <= 1 && isQualcomm(codecName)) keys[QTI_LOW_LATENCY] = 1
        // TODO(M5): Unisoc (c2.unisoc.*, Vaio TL10) has no known vendor low-latency key and does not
        //  report FEATURE_LowLatency; measure and look for one (list the codec's vendor parameters).
        return keys
    }
}

/**
 * Asynchronous [MediaCodec] decoding straight to the stream [surface]. Every output buffer is
 * rendered as soon as it is ready (`releaseOutputBuffer(index, true)`): no frame queue of our own.
 */
class MediaCodecVideoDecoder(
    private val caps: List<DecoderCaps>,
    surface: Surface,
) : VideoDecoder {
    @Volatile private var surface: Surface? = surface
    @Volatile private var suspended = false
    @Volatile private var needKeyFrame = false
    private var lastSetup: IntArray? = null
    private var lastMime: String? = null
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private val freeInputs = LinkedBlockingQueue<Int>()
    private var lastTimestampUs = 0L

    @Volatile override var codecName: String? = null
        private set

    @Volatile private var errorListener: ((String) -> Unit)? = null

    /** Set by a fatal codec error: the codec takes no more input, so stop queueing. */
    @Volatile private var failed = false

    private val rendered = AtomicInteger()
    private val decodeUsSum = AtomicLong()
    private val decodeCount = AtomicInteger()

    override fun capsFor(mime: String): DecoderCaps? = DecoderProbe.pickDecoder(caps, mime)

    override fun detachSurface() = synchronized(lock) {
        suspended = true
        surface = null
        releaseLocked()
    }

    override fun attachSurface(surface: Surface) {
        val m = lastMime
        val p = lastSetup
        synchronized(lock) { this.surface = surface }
        if (m != null && p != null) {
            suspended = false
            needKeyFrame = true
            setup(m, p[0], p[1], p[2])
        }
    }

    override fun setup(mime: String, width: Int, height: Int, fps: Int): Boolean = synchronized(lock) {
        releaseLocked()
        lastMime = mime
        lastSetup = intArrayOf(width, height, fps)
        val surf = surface
        if (surf == null) {
            suspended = true // minimized when the host asked for a (re)setup: frames are dropped until a surface returns
            return true
        }
        val chosen = capsFor(mime) ?: return false
        val t = HandlerThread("duvo-video", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        for (attempt in 0 until DecoderTuning.ATTEMPTS) {
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height, 1 shl 20))
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                for ((k, v) in DecoderTuning.formatKeys(chosen.codecName, Build.VERSION.SDK_INT, attempt)) setInteger(k, v)
            }
            var c: MediaCodec? = null
            try {
                c = MediaCodec.createByCodecName(chosen.codecName)
                c.setCallback(callback, handler)
                c.configure(format, surf, null, 0)
                c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                c.start()
                codec = c
                codecName = chosen.codecName
                Log.i(TAG, "decoder ${chosen.codecName} ${width}x$height@$fps tentativa $attempt: $format")
                if (!DecoderTuning.isQualcomm(chosen.codecName) && !chosen.lowLatency) {
                    Log.i(TAG, "sem chave de baixa latência conhecida para ${chosen.codecName} (ver M5)")
                }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "configure falhou (${chosen.codecName}, tentativa $attempt): $e")
                runCatching { c?.release() }
                freeInputs.clear()
            }
        }
        t.quitSafely()
        thread = null
        false
    }

    override fun setErrorListener(listener: (String) -> Unit) {
        errorListener = listener
    }

    override fun submit(data: ByteArray, csdLength: Int, length: Int, keyFrame: Boolean): Int {
        if (failed || suspended) return NativeBridge.DR_OK // ending, or no surface: drop frames, don't spin on IDR requests
        if (needKeyFrame) {
            if (!keyFrame) return NativeBridge.DR_NEED_IDR
            needKeyFrame = false
        }
        if (csdLength > 0 && !queue(data, 0, csdLength, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 0L)) {
            return NativeBridge.DR_NEED_IDR
        }
        // Monotonic, strictly increasing timestamps: the output callback measures decode time with them.
        val ts = maxOf(System.nanoTime() / 1000, lastTimestampUs + 1)
        lastTimestampUs = ts
        val flags = if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        return if (queue(data, csdLength, length - csdLength, flags, ts)) NativeBridge.DR_OK else NativeBridge.DR_NEED_IDR
    }

    private fun queue(data: ByteArray, offset: Int, size: Int, flags: Int, timestampUs: Long): Boolean {
        val index = freeInputs.poll(INPUT_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: run {
            Log.w(TAG, "nenhum buffer de entrada livre em ${INPUT_TIMEOUT_MS} ms")
            return false
        }
        synchronized(lock) {
            val c = codec ?: return false
            return try {
                val buf = c.getInputBuffer(index) ?: return false
                if (size > buf.capacity()) {
                    Log.w(TAG, "quadro de $size bytes não cabe no buffer de ${buf.capacity()}")
                    c.queueInputBuffer(index, 0, 0, timestampUs, 0)
                    return false
                }
                buf.clear()
                buf.put(data, offset, size)
                c.queueInputBuffer(index, 0, size, timestampUs, flags)
                true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "queueInputBuffer: $e")
                false
            }
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            freeInputs.offer(index)
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                c.releaseOutputBuffer(index, true)
            } catch (e: IllegalStateException) {
                return // released concurrently
            }
            if (info.presentationTimeUs > 0) {
                decodeUsSum.addAndGet(System.nanoTime() / 1000 - info.presentationTimeUs)
                decodeCount.incrementAndGet()
            }
            rendered.incrementAndGet()
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "erro do decoder $codecName: ${e.diagnosticInfo} transient=${e.isTransient} recoverable=${e.isRecoverable}", e)
            if (e.isTransient || failed) return
            // Recoverable errors would need stop/configure/start off this callback thread and a new IDR;
            // for now any non-transient error ends the stream (M5 may add the reset path).
            failed = true
            errorListener?.invoke("O decoder $codecName falhou: ${e.diagnosticInfo}")
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "formato de saída: $format")
        }
    }

    override fun takeStats(): DecoderStats {
        val n = decodeCount.getAndSet(0)
        val us = decodeUsSum.getAndSet(0)
        return DecoderStats(rendered.getAndSet(0), if (n == 0) 0f else us / 1000f / n)
    }

    override fun release() = synchronized(lock) { releaseLocked() }

    private fun releaseLocked() {
        failed = false
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
        thread?.quitSafely()
        thread = null
        freeInputs.clear()
    }

    private companion object {
        const val TAG = "DuvoVideo"
        const val INPUT_TIMEOUT_MS = 100L
    }
}
