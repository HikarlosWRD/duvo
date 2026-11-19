/*
 * Duvo - remote control and streaming for a Windows PC.
 * Copyright (C) 2026 Duvo contributors
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

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/** Plays the PCM that moonlight-common-c decoded from the host's Opus stream. */
interface AudioSink {
    fun init(channelCount: Int, sampleRate: Int, samplesPerFrame: Int): Boolean
    fun play(pcm: ShortArray, sampleCount: Int)
    fun release()
}

/** Low-latency streaming [AudioTrack]. Writes never block the audio thread: late audio is dropped. */
class AudioTrackSink : AudioSink {
    @Volatile private var track: AudioTrack? = null

    override fun init(channelCount: Int, sampleRate: Int, samplesPerFrame: Int): Boolean {
        val mask = when (channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> return false
        }
        return try {
            val min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            val frameBytes = samplesPerFrame * channelCount * 2
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(maxOf(min, frameBytes * 2))
                .build()
            t.play()
            track = t
            true
        } catch (e: Exception) {
            Log.w("DuvoAudio", "AudioTrack indisponível: $e")
            false
        }
    }

    override fun play(pcm: ShortArray, sampleCount: Int) {
        track?.write(pcm, 0, sampleCount, AudioTrack.WRITE_NON_BLOCKING)
    }

    override fun release() {
        val t = track ?: return
        track = null
        runCatching { t.pause(); t.flush(); t.release() }
    }
}
