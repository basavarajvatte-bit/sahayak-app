package com.sahayak.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteOrder

/** Phone ki koi bhi audio clip (mp3, m4a, WhatsApp voice note…) ko 24 kHz mono WAV mein badlo. */
object AudioImport {
    var lastError = ""
        private set

    /** Bani file ke second, ya -1 (tab lastError). Peeche ke dhaage se bulao. */
    fun toWav(c: Context, uri: Uri, out: File, maxSeconds: Int): Int {
        lastError = ""
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(c, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) {
                lastError = "This file has no sound in it"
                return -1
            }
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            var mono = ShortArray(rate * 10)
            var count = 0
            var limit = rate * maxSeconds
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone && count < limit) {
                if (!inputDone) {
                    val inIndex = dec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = dec.getInputBuffer(inIndex)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = dec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (count == 0 && newRate != rate) {
                            rate = newRate
                            limit = rate * maxSeconds
                        }
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4
                    }
                    outIndex >= 0 -> {
                        idle = 0
                        val buf = dec.getOutputBuffer(outIndex)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        val ch = channels.coerceAtLeast(1)
                        if (floatPcm) {
                            val fb = buf.asFloatBuffer()
                            val frames = fb.remaining() / ch
                            for (k in 0 until frames) {
                                var sum = 0f
                                for (j in 0 until ch) sum += fb.get()
                                if (count == mono.size) mono = mono.copyOf(mono.size * 2)
                                mono[count++] = (sum / ch * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            val frames = sb.remaining() / ch
                            for (k in 0 until frames) {
                                var sum = 0
                                for (j in 0 until ch) sum += sb.get()
                                if (count == mono.size) mono = mono.copyOf(mono.size * 2)
                                mono[count++] = (sum / ch).toShort()
                            }
                        }
                        dec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    else -> if (inputDone && ++idle > 200) outputDone = true
                }
            }
            val pcm = VoiceLogic.resample(mono.copyOf(minOf(count, limit)), rate, VoiceLogic.RATE)
            if (pcm.isEmpty()) {
                lastError = "Could not read the sound in this file"
                return -1
            }
            RandomAccessFile(out, "rw").use { f ->
                f.setLength(0)
                f.write(VoiceLogic.wavHeader(pcm.size * 2))
                val bytes = ByteArray(pcm.size * 2)
                for (i in pcm.indices) {
                    bytes[2 * i] = (pcm[i].toInt() and 0xFF).toByte()
                    bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
                }
                f.write(bytes)
            }
            return pcm.size / VoiceLogic.RATE
        } catch (e: IOException) {
            lastError = "Could not open this file"
            return -1
        } catch (e: RuntimeException) {
            lastError = "This phone cannot read this kind of audio file. Try an mp3 or m4a file"
            return -1
        } finally {
            try {
                codec?.stop()
            } catch (e: RuntimeException) {
                // Pehle hi band
            }
            codec?.release()
            extractor.release()
        }
    }
}
