package com.sahayak.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Mic se awaaz leta hai aur speaker par Gemini ki awaaz bajata hai. */
class VoiceIO(context: Context, private val onMic: (ByteArray, Int) -> Unit) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echo: AcousticEchoCanceler? = null
    private var micThread: Thread? = null
    private var playThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var micOn = false

    @SuppressLint("MissingPermission")
    fun start() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        useSpeaker(true)

        val inputSize = max(
            AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            MIC_CHUNK * 4
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, MIC_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, inputSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("mic")
        }
        recorder = rec
        if (AcousticEchoCanceler.isAvailable()) {
            echo = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
        }

        val outputSize = max(
            AudioTrack.getMinBufferSize(SPEAKER_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2,
            SPEAKER_BUFFER
        )
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SPEAKER_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(outputSize)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = player

        running = true
        player.play()

        micThread = Thread({
            val buffer = ByteArray(MIC_CHUNK)
            while (running) {
                if (!micOn) {
                    Thread.sleep(15)
                    continue
                }
                val count = rec.read(buffer, 0, buffer.size)
                if (count > 0 && micOn) onMic(buffer, count)
            }
        }, "sahayak-mic").apply { start() }

        var applied = AssistState.volume
        player.setVolume(applied)
        playThread = Thread({
            while (running) {
                val chunk = try {
                    playQueue.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                } ?: continue
                val wanted = AssistState.volume
                if (wanted != applied) {
                    player.setVolume(wanted)
                    applied = wanted
                }
                player.write(chunk, 0, chunk.size)
            }
        }, "sahayak-speaker").apply { start() }
    }

    /** Mic sirf dot dabane par chalta hai. */
    fun setMic(on: Boolean) {
        val rec = recorder ?: return
        if (on == micOn) return
        if (on) {
            rec.startRecording()
            micOn = true
        } else {
            micOn = false
            rec.runCatching { stop() }
        }
    }

    fun play(pcm: ByteArray) {
        if (running) playQueue.offer(pcm)
    }

    /** Insaan beech mein bol pade to bachi hui awaaz turant band. */
    fun interrupt() {
        playQueue.clear()
        track?.let {
            it.pause()
            it.flush()
            it.play()
        }
    }

    private fun useSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audioManager.setCommunicationDevice(it) }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    fun stop() {
        running = false
        micOn = false
        micThread?.join(500)
        playThread?.join(500)
        playQueue.clear()
        echo?.release()
        recorder?.runCatching { stop() }
        recorder?.release()
        track?.runCatching { stop() }
        track?.release()
        echo = null
        recorder = null
        track = null
        useSpeaker(false)
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    companion object {
        private const val MIC_RATE = 16_000
        private const val SPEAKER_RATE = 24_000
        private const val MIC_CHUNK = 1_600 // 50 ms
        private const val SPEAKER_BUFFER = 4_800 // 100 ms
    }
}
