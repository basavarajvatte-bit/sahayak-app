package com.sahayak.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Lambi video se Reels: awaaz Gemini ko bhejo, woh achhe hisse chunta hai,
 * phir phone par hi har hissa 9:16 video mein kaat kar upar title likh do.
 */
class Clipper(private val context: Context, private val apiKey: String) {
    var lastError = ""
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    class Source(val videoTrack: Int, val audioTrack: Int, val durationS: Double, val width: Int, val height: Int, val rotation: Int)

    fun inspect(uri: Uri): Source {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var video = -1
            var audio = -1
            var duration = 0L
            var w = 0
            var h = 0
            var rotation = 0
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && video < 0) {
                    video = i
                    w = f.getInteger(MediaFormat.KEY_WIDTH)
                    h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    if (f.containsKey(MediaFormat.KEY_ROTATION)) rotation = f.getInteger(MediaFormat.KEY_ROTATION)
                    if (f.containsKey(MediaFormat.KEY_DURATION)) duration = maxOf(duration, f.getLong(MediaFormat.KEY_DURATION))
                }
                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC && audio < 0) {
                    audio = i
                    if (f.containsKey(MediaFormat.KEY_DURATION)) duration = maxOf(duration, f.getLong(MediaFormat.KEY_DURATION))
                }
            }
            if (video < 0) throw IOException("No video found in this file")
            if (audio < 0) throw IOException("This video has no AAC sound. Use an MP4 video")
            return Source(video, audio, duration / 1_000_000.0, w, h, rotation)
        } finally {
            ex.release()
        }
    }

    // ---------- 1. Awaaz nikaalo aur Gemini se hisse chunwao ----------

    /** Video ki awaaz ko .aac file mein (bina dobara encode kiye). */
    fun extractAudio(uri: Uri, src: Source, progress: (String) -> Unit): File {
        val out = File(context.cacheDir, "clip_audio.aac")
        out.delete()
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            ex.selectTrack(src.audioTrack)
            val format = ex.getTrackFormat(src.audioTrack)
            val header = adtsInfo(format)
            val buffer = ByteBuffer.allocate(if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 64 * 1024)
            val totalUs = (src.durationS * 1_000_000).toLong().coerceAtLeast(1)
            var lastPct = -1
            out.outputStream().buffered().use { file ->
                while (true) {
                    buffer.clear()
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0) break
                    file.write(adts(header, size))
                    file.write(buffer.array(), buffer.arrayOffset(), size)
                    val pct = (ex.sampleTime * 100 / totalUs).toInt()
                    if (pct / 10 != lastPct / 10) {
                        lastPct = pct
                        progress("Taking out the sound $pct%")
                    }
                    ex.advance()
                }
            }
        } finally {
            ex.release()
        }
        return out
    }

    private fun adtsInfo(format: MediaFormat): IntArray {
        val csd = format.getByteBuffer("csd-0")
        if (csd != null && csd.remaining() >= 2) {
            val b0 = csd.get(csd.position()).toInt() and 0xFF
            val b1 = csd.get(csd.position() + 1).toInt() and 0xFF
            val profile = (b0 shr 3).coerceIn(1, 4)
            val freq = ((b0 and 0x07) shl 1) or (b1 shr 7)
            val channels = (b1 shr 3) and 0x0F
            return intArrayOf(profile, freq, channels)
        }
        val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val freq = rates.indexOf(rate).let { if (it < 0) 4 else it }
        return intArrayOf(2, freq, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
    }

    private fun adts(info: IntArray, size: Int): ByteArray {
        val len = size + 7
        val (profile, freq, ch) = Triple(info[0], info[1], info[2])
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            (((profile - 1) shl 6) or (freq shl 2) or (ch shr 2)).toByte(),
            (((ch and 3) shl 6) or (len shr 11)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte()
        )
    }

    /** Awaaz Gemini par chadhao aur clips ki list lo. */
    fun pick(audio: File, brief: String, count: Int, totalS: Double, progress: (String) -> Unit): List<ClipPick> {
        progress("Sending the sound to Gemini (" + audio.length() / (1024 * 1024) + " MB)")
        val file = upload(audio, "audio/aac") ?: return emptyList()
        try {
            progress("Gemini is listening to the video")
            val uri = waitActive(file) ?: return emptyList()
            val parts = JSONArray()
                .put(JSONObject().put("file_data", JSONObject().put("mime_type", "audio/aac").put("file_uri", uri)))
                .put(JSONObject().put("text", ClipLogic.prompt(brief, count, totalS)))
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 4096)
                    .put("responseMimeType", "application/json"))
                .toString()
            for (model in MODELS) {
                progress("Gemini is choosing the best parts")
                val reply = generate(model, body) ?: continue
                val picks = ClipLogic.parse(reply, totalS, count)
                if (picks.isNotEmpty()) return picks
                lastError = "Gemini found no part that fits the brief"
                RunLog.add(context, "clip pick empty: " + reply.take(300))
            }
            return emptyList()
        } finally {
            delete(file)
        }
    }

    private fun upload(audio: File, mime: String): String? {
        val start = Request.Builder().url(UPLOAD_URL)
            .header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", audio.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mime)
            .post(JSONObject().put("file", JSONObject().put("display_name", "sahayak_clip")).toString()
                .toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val url = client.newCall(start).execute().use { r ->
                if (!r.isSuccessful) {
                    lastError = errorText(r.code)
                    return null
                }
                r.header("x-goog-upload-url")
            } ?: run { lastError = "Gemini did not give an upload link"; return null }
            val send = Request.Builder().url(url)
                .header("X-Goog-Upload-Offset", "0")
                .header("X-Goog-Upload-Command", "upload, finalize")
                .post(audio.asRequestBody(mime.toMediaType()))
                .build()
            client.newCall(send).execute().use { r ->
                if (!r.isSuccessful) {
                    lastError = "Upload failed (" + r.code + ")"
                    return null
                }
                JSONObject(r.body?.string().orEmpty()).optJSONObject("file")?.optString("name")?.takeIf { it.isNotEmpty() }
            }
        } catch (e: IOException) {
            lastError = if (e is java.net.UnknownHostException) "No internet" else "Upload stopped: " + e.message
            null
        } catch (e: org.json.JSONException) {
            lastError = "Strange reply from Gemini"
            null
        }
    }

    private fun waitActive(name: String): String? {
        repeat(WAIT_TRIES) {
            try {
                val request = Request.Builder().url(API + name).header("x-goog-api-key", apiKey).build()
                client.newCall(request).execute().use { r ->
                    if (r.isSuccessful) {
                        val f = JSONObject(r.body?.string().orEmpty())
                        when (f.optString("state")) {
                            "ACTIVE" -> return f.optString("uri")
                            "FAILED" -> {
                                lastError = "Gemini could not read the sound"
                                return null
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                // Thodi der baad phir dekho
            } catch (e: org.json.JSONException) {
                // Thodi der baad phir dekho
            }
            Thread.sleep(WAIT_MS)
        }
        lastError = "Gemini took too long to read the sound"
        return null
    }

    private fun generate(model: String, body: String): String? {
        val request = Request.Builder().url(API + "models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType())).build()
        return try {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) {
                    lastError = errorText(r.code)
                    RunLog.add(context, "clip pick $model " + r.code)
                    return null
                }
                val parts = JSONObject(r.body?.string().orEmpty()).optJSONArray("candidates")
                    ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val text = StringBuilder()
                for (i in 0 until (parts?.length() ?: 0)) {
                    val p = parts?.optJSONObject(i) ?: continue
                    if (!p.optBoolean("thought")) text.append(p.optString("text"))
                }
                text.toString().ifBlank { null }
            }
        } catch (e: IOException) {
            lastError = if (e is java.net.UnknownHostException) "No internet" else "Gemini took too long"
            null
        } catch (e: org.json.JSONException) {
            lastError = "Strange reply from Gemini"
            null
        }
    }

    private fun delete(name: String) {
        try {
            client.newCall(Request.Builder().url(API + name).header("x-goog-api-key", apiKey).delete().build()).execute().close()
        } catch (e: IOException) {
            // Gemini khud 2 din mein mita deta hai
        }
    }

    private fun errorText(code: Int) = when (code) {
        429 -> "Gemini free limit reached. Try after some time"
        500, 503 -> "Gemini is busy right now. Try again after a minute"
        400, 403 -> "Gemini key problem ($code)"
        413 -> "Video sound is too big for Gemini"
        else -> "Gemini error $code"
    }

    // ---------- 2. Ek hissa kaat kar 9:16 Reel banao ----------

    fun cut(uri: Uri, src: Source, pick: ClipPick, zoom: Boolean, name: String, progress: (Int) -> Unit): Uri {
        val temp = File(context.cacheDir, "clip_render.mp4")
        temp.delete()
        val startUs = (pick.startS * 1_000_000).toLong()
        val endUs = (pick.endS * 1_000_000).toLong()
        val (audioFormat, audioSamples) = readAudio(uri, src, startUs, endUs)

        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        val muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var written = 0L
        val info = MediaCodec.BufferInfo()

        fun drain(end: Boolean) {
            var idle = 0
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, if (end) WAIT_US else 0L)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!end || ++idle > MAX_IDLE) return else continue
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = muxer.addTrack(encoder.outputFormat)
                    val audioTrack = muxer.addTrack(audioFormat)
                    muxer.start()
                    val buf = ByteBuffer.allocateDirect(audioSamples.maxOfOrNull { it.data.size } ?: 1)
                    val meta = MediaCodec.BufferInfo()
                    audioSamples.forEach {
                        buf.clear()
                        buf.put(it.data)
                        buf.flip()
                        meta.set(0, it.data.size, it.time, it.flags)
                        muxer.writeSampleData(audioTrack, buf, meta)
                    }
                    continue
                }
                if (index < 0) continue
                val data = encoder.getOutputBuffer(index)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && videoTrack >= 0) {
                    // Har frame ka samay khud likho, taaki speed sahi rahe
                    info.presentationTimeUs = written * 1_000_000L / FPS
                    written++
                    data.position(info.offset)
                    data.limit(info.offset + info.size)
                    muxer.writeSampleData(videoTrack, data, info)
                }
                val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                encoder.releaseOutputBuffer(index, false)
                if (last) return
            }
        }

        val painter = Painter(pick.hook, zoom, src)
        val totalFrames = ((endUs - startUs) * FPS / 1_000_000L).toInt().coerceAtLeast(1)
        var made = 0
        // Har nikle frame ko itni baar daalo ki 24 frame/second bane
        fun emitUntil(timeUs: Long) {
            while (made < totalFrames && made * 1_000_000L / FPS < timeUs) {
                val canvas = surface.lockHardwareCanvas()
                try {
                    painter.draw(canvas)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                made++
                drain(false)
                if (made % FPS == 0) progress(made * 100 / totalFrames)
            }
        }

        val ex = MediaExtractor()
        val format: MediaFormat
        try {
            ex.setDataSource(context, uri, null)
            ex.selectTrack(src.videoTrack)
            format = ex.getTrackFormat(src.videoTrack)
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val dInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var held = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(WAIT_US)
                    if (inIndex >= 0) {
                        val buf = decoder.getInputBuffer(inIndex)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0 || ex.sampleTime > endUs + END_SLACK_US) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(dInfo, WAIT_US)
                if (outIndex >= 0) {
                    val eos = dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val t = dInfo.presentationTimeUs - startUs
                    if (dInfo.size > 0 && t >= -FRAME_SLACK_US && t <= endUs - startUs) {
                        // Pichhla frame uske samay tak dikhao, phir naya frame pakdo
                        if (held) emitUntil(t)
                        decoder.getOutputImage(outIndex)?.let { image ->
                            painter.take(image)
                            image.close()
                            held = true
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (eos || t > endUs - startUs) outputDone = true
                }
            }
            decoder.stop()
            decoder.release()
        } finally {
            ex.release()
        }
        if (held) emitUntil(endUs - startUs + 1)
        encoder.signalEndOfInputStream()
        drain(true)
        encoder.stop()
        encoder.release()
        surface.release()
        if (videoTrack < 0) {
            muxer.release()
            throw IOException("Could not read the video frames")
        }
        muxer.stop()
        muxer.release()
        painter.recycle()
        val saved = save(temp, name)
        temp.delete()
        return saved
    }

    private class Sample(val data: ByteArray, val time: Long, val flags: Int)

    private fun readAudio(uri: Uri, src: Source, startUs: Long, endUs: Long): Pair<MediaFormat, List<Sample>> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            ex.selectTrack(src.audioTrack)
            val format = ex.getTrackFormat(src.audioTrack)
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buf = ByteBuffer.allocate(if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 64 * 1024)
            val list = ArrayList<Sample>()
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                if (t > endUs) break
                if (t >= startUs) list.add(Sample(buf.array().copyOfRange(buf.arrayOffset(), buf.arrayOffset() + size), t - startUs, MediaCodec.BUFFER_FLAG_KEY_FRAME))
                ex.advance()
            }
            return format to list
        } finally {
            ex.release()
        }
    }

    /** Frame ko 9:16 canvas par banata hai: video, upar title. */
    private class Painter(hook: String, private val zoom: Boolean, src: Source) {
        private var frame: Bitmap? = null
        private var pixels = IntArray(0)
        private val lines = ClipLogic.wrap(hook)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = if (zoom) 58f else 66f
        }
        private val stroke = Paint(fill).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeJoin = Paint.Join.ROUND
        }
        private val turn = src.rotation
        private val dest = Rect()

        /** Decoder ki YUV tasveer se sirf zaroori pixel uthao (seedhe chhote size mein). */
        fun take(image: Image) {
            val crop = image.cropRect
            val sideways = turn == 90 || turn == 270
            val shownW = if (sideways) crop.height() else crop.width()
            val shownH = if (sideways) crop.width() else crop.height()
            val r = ClipLogic.cropRect(shownW, shownH, zoom, WIDTH, HEIGHT)
            val outW = WIDTH
            val outH = if (zoom) HEIGHT else (WIDTH.toLong() * r[3] / r[2]).toInt().coerceIn(2, HEIGHT)
            val bmp = frame?.takeIf { it.width == outW && it.height == outH }
                ?: Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also { frame?.recycle(); frame = it }
            if (pixels.size != outW * outH) pixels = IntArray(outW * outH)
            val planes = image.planes
            val yBuf = planes[0].buffer
            val uBuf = planes[1].buffer
            val vBuf = planes[2].buffer
            val yStride = planes[0].rowStride
            val yPix = planes[0].pixelStride
            val cStride = planes[1].rowStride
            val cPix = planes[1].pixelStride
            val vStride = planes[2].rowStride
            val vPix = planes[2].pixelStride
            var p = 0
            for (oy in 0 until outH) {
                val ry = r[1] + (oy.toLong() * r[3] / outH).toInt()
                for (ox in 0 until outW) {
                    val rx = r[0] + (ox.toLong() * r[2] / outW).toInt()
                    // Ghumi hui video ko seedha karo
                    var sx: Int
                    var sy: Int
                    when (turn) {
                        90 -> { sx = ry; sy = crop.height() - 1 - rx }
                        180 -> { sx = crop.width() - 1 - rx; sy = crop.height() - 1 - ry }
                        270 -> { sx = crop.width() - 1 - ry; sy = rx }
                        else -> { sx = rx; sy = ry }
                    }
                    sx = (sx + crop.left).coerceIn(0, image.width - 1)
                    sy = (sy + crop.top).coerceIn(0, image.height - 1)
                    val y = (yBuf.get(sy * yStride + sx * yPix).toInt() and 0xFF) - 16
                    val u = (uBuf.get((sy / 2) * cStride + (sx / 2) * cPix).toInt() and 0xFF) - 128
                    val v = (vBuf.get((sy / 2) * vStride + (sx / 2) * vPix).toInt() and 0xFF) - 128
                    val yy = 1192 * maxOf(y, 0)
                    val red = ((yy + 1634 * v) shr 10).coerceIn(0, 255)
                    val green = ((yy - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                    val blue = ((yy + 2066 * u) shr 10).coerceIn(0, 255)
                    pixels[p++] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
            bmp.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        }

        fun draw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val bmp = frame ?: return
            val textTop: Float
            if (zoom) {
                dest.set(0, 0, WIDTH, HEIGHT)
                canvas.drawBitmap(bmp, null, dest, null)
                textTop = 200f
            } else {
                val top = (HEIGHT - bmp.height) / 2
                dest.set(0, top, WIDTH, top + bmp.height)
                canvas.drawBitmap(bmp, null, dest, null)
                textTop = (top - lines.size * fill.textSize * 1.15f - 40f).coerceAtLeast(120f)
            }
            val lineH = fill.textSize * 1.15f
            lines.forEachIndexed { i, line ->
                val y = textTop + i * lineH + fill.textSize
                canvas.drawText(line, WIDTH / 2f, y, stroke)
                canvas.drawText(line, WIDTH / 2f, y, fill)
            }
        }

        fun recycle() {
            frame?.recycle()
            frame = null
        }
    }

    private fun save(file: File, name: String): Uri {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) throw IOException("Needs Android 10 or newer")
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Sahayak/Clips")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Gallery did not accept the clip")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("Could not save the clip")
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val FPS = 24
        private const val VIDEO_BITRATE = 5_000_000
        private const val WAIT_US = 10_000L
        private const val MAX_IDLE = 500
        private const val END_SLACK_US = 500_000L
        private const val FRAME_SLACK_US = 50_000L
        private const val WAIT_TRIES = 60
        private const val WAIT_MS = 5_000L
        private const val API = "https://generativelanguage.googleapis.com/v1beta/"
        private const val UPLOAD_URL = "https://generativelanguage.googleapis.com/upload/v1beta/files"
        private val MODELS = listOf("gemini-2.5-flash", "gemini-flash-latest", "gemini-2.5-flash-lite")
    }
}
