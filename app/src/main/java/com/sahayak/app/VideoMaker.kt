package com.sahayak.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Kahani se phone par hi MP4 banana: background, character, awaaz aur subtitle. Internet nahi chahiye. */
class VideoMaker(private val context: Context) {
    private class Pcm(val samples: ShortArray, val rate: Int)

    private class Sample(val data: ByteArray, val time: Long, val flags: Int)

    private val clips = object : LinkedHashMap<String, List<Bitmap>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Bitmap>>): Boolean {
            if (size > KEEP_CLIPS) eldest.value.forEach { it.recycle() }
            return size > KEEP_CLIPS
        }
    }

    fun make(story: Story, progress: (String) -> Unit): Uri {
        progress("Making the voice")
        val voices = speakAll(story)
        val rate = voices.firstOrNull { it != null }?.rate ?: DEFAULT_RATE
        val sceneSeconds = story.scenes.mapIndexed { i, scene ->
            val talk = voices[i]?.let { it.samples.size.toDouble() / it.rate } ?: 0.0
            if (story.poem) {
                // Poem ki har line dhun ki beat par shuru ho
                val need = max(scene.seconds, talk + POEM_PAD + LEAD_IN)
                ceil(need / Music.SOFT_BEAT - 0.001) * Music.SOFT_BEAT
            } else max(scene.seconds, talk + SPEECH_PAD + LEAD_IN)
        }
        val voice = mixAudio(voices, sceneSeconds, rate)
        val seed = story.title.hashCode()
        val tune = when {
            !story.music -> null
            story.poem -> Music.soft(sceneSeconds.sum(), rate, seed)
            else -> Music.make(sceneSeconds.sum(), rate, seed)
        }
        val audio = Music.mix(voice, tune, rate)
        val stamp = SimpleDateFormat("MMdd_HHmm", Locale.ENGLISH).format(Date())
        val name = StoryLogic.fileName(story.title, stamp)
        val temp = File(context.cacheDir, "render.mp4")
        temp.delete()
        progress("Making the animation")
        encode(story, voices, sceneSeconds, audio, rate, temp, progress)
        progress("Saving to your gallery")
        val uri = save(temp, name)
        temp.delete()
        clips.values.forEach { list -> list.forEach { it.recycle() } }
        clips.clear()
        return uri
    }

    // ---------- awaaz ----------

    private fun speakAll(story: Story): List<Pcm?> {
        val ready = CountDownLatch(1)
        var ok = false
        val tts = TextToSpeech(context) { ok = it == TextToSpeech.SUCCESS; ready.countDown() }
        ready.await(TTS_WAIT_S, TimeUnit.SECONDS)
        if (!ok) {
            tts.shutdown()
            return story.scenes.map { null }
        }
        tts.language = Locale("en", "IN")
        tts.setPitch(if (story.character == "baby") BABY_PITCH else CHHOTU_PITCH)
        tts.setSpeechRate(if (story.poem) POEM_RATE else VOICE_RATE)
        val result = story.scenes.mapIndexed { i, scene ->
            if (scene.line.isBlank()) return@mapIndexed null
            val file = File(context.cacheDir, "line_$i.wav")
            file.delete()
            val done = CountDownLatch(1)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = done.countDown()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = done.countDown()
            })
            tts.synthesizeToFile(scene.line, Bundle(), file, "line_$i")
            done.await(TTS_WAIT_S, TimeUnit.SECONDS)
            readWav(file).also { file.delete() }
        }
        tts.shutdown()
        return result
    }

    /** WAV se 16-bit mono awaaz. */
    private fun readWav(file: File): Pcm? {
        if (!file.exists() || file.length() < WAV_HEADER) return null
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = DEFAULT_RATE
        var channels = 1
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            if (id == "fmt ") {
                channels = buf.getShort(pos + 10).toInt()
                rate = buf.getInt(pos + 12)
                bits = buf.getShort(pos + 22).toInt()
            } else if (id == "data") {
                if (bits != 16) return null
                val end = min(bytes.size, pos + 8 + max(size, 0))
                val frames = (end - pos - 8) / (2 * channels)
                val out = ShortArray(frames) { f ->
                    var sum = 0
                    for (c in 0 until channels) sum += buf.getShort(pos + 8 + (f * channels + c) * 2)
                    (sum / channels).toShort()
                }
                return Pcm(out, rate)
            }
            pos += 8 + size + (size and 1)
        }
        return null
    }

    private fun mixAudio(voices: List<Pcm?>, seconds: List<Double>, rate: Int): ShortArray {
        val total = (seconds.sum() * rate).toInt()
        val out = ShortArray(total)
        var start = 0.0
        voices.forEachIndexed { i, pcm ->
            if (pcm != null) {
                val at = ((start + LEAD_IN) * rate).toInt()
                val step = pcm.rate.toDouble() / rate
                var j = 0
                while (at + j < total && (j * step).toInt() < pcm.samples.size) {
                    out[at + j] = pcm.samples[(j * step).toInt()]
                    j++
                }
            }
            start += seconds[i]
        }
        return out
    }

    // ---------- tasveer ----------

    private fun frames(character: String, clip: String): List<Bitmap> = clips.getOrPut(clip) {
        val folder = if (character == "baby") "baby" else "chhotu"
        (0 until MAX_FRAMES).mapNotNull { i ->
            try {
                context.assets.open(folder + "/" + clip + "_" + i.toString().padStart(2, '0') + ".webp").use { BitmapFactory.decodeStream(it) }
            } catch (e: IOException) {
                null
            }
        }
    }

    private val backgrounds = HashMap<String, Bitmap>()

    private fun background(place: String): Bitmap = backgrounds.getOrPut(place) { Backdrops.draw(place, WIDTH, HEIGHT) }

    private val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = SUB_SIZE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 46f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        setShadowLayer(10f, 0f, 3f, Color.argb(160, 0, 0, 0))
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * mouth: awaaz se chuna talk frame, ya -1. said: line ka kitna hissa bola ja chuka, 0 se 1, ya -1.
     */
    private fun drawFrame(
        canvas: Canvas, story: Story, scene: Scene, frame: Int, inScene: Double, talking: Boolean, mouth: Int, said: Double
    ) {
        canvas.drawBitmap(background(scene.place), 0f, 0f, null)
        // Props wali activity mein muh clip ke andar hi chalta hai
        val propAction = scene.action in StoryLogic.ACTIVITIES
        val clip = if (talking && !propAction) (if (story.character == "chhotu" && scene.action.startsWith("swag")) "swagtalk" else "talk") else scene.action
        val list = frames(story.character, clip).ifEmpty { frames(story.character, "idle") }
        val close = scene.close || scene.action == "feast"
        val size = if (close) CLOSE_SIZE else CHARACTER_SIZE
        val left = (WIDTH - size) / 2f
        val top = if (close) HEIGHT * 0.1f else HEIGHT * 0.33f
        if (!close) {
            shadow.shader = RadialGradient(WIDTH / 2f, top + size * 0.93f, size * 0.32f, Color.argb(110, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.save()
            canvas.scale(1f, 0.22f, WIDTH / 2f, top + size * 0.93f)
            canvas.drawCircle(WIDTH / 2f, top + size * 0.93f, size * 0.32f, shadow)
            canvas.restore()
        }
        val lip = talking && !propAction && mouth >= 0 && list.size == LipSync.OPEN.size
        val shown = if (lip) mouth else frame % max(list.size, 1)
        list.getOrNull(shown)?.let { canvas.drawBitmap(it, null, RectF(left, top, left + size, top + size), drawPaint) }
        Effects.draw(canvas, scene.effect, inScene, frame, WIDTH, HEIGHT)
        if (scene.pop.isNotBlank()) Effects.pop(canvas, scene.pop, inScene, WIDTH)
        else if (frame < FPS * TITLE_SECONDS) canvas.drawText(story.title, WIDTH / 2f, 120f, titlePaint)
        if (scene.line.isNotBlank() && inScene >= LEAD_IN * 0.5) {
            val lines = StoryLogic.wrap(scene.line, SUB_CHARS)
            var y = HEIGHT - 190f - (lines.size - 1) * 64f
            if (story.poem) {
                drawPoemLine(canvas, scene.line, lines, y, said)
                return
            }
            lines.forEach { text ->
                subtitle.style = Paint.Style.STROKE
                subtitle.strokeWidth = 10f
                subtitle.color = Color.BLACK
                canvas.drawText(text, WIDTH / 2f, y, subtitle)
                subtitle.style = Paint.Style.FILL
                subtitle.color = Color.WHITE
                canvas.drawText(text, WIDTH / 2f, y, subtitle)
                y += 64f
            }
        }
    }

    /** Poem ki line: jo shabd bola ja raha hai woh peela aur thoda bada. */
    private fun drawPoemLine(canvas: Canvas, line: String, lines: List<String>, top: Float, said: Double) {
        val active = if (said < 0) -1 else PoemLogic.activeWord(PoemLogic.wordEnds(line), said)
        var index = 0
        var y = top
        subtitle.textAlign = Paint.Align.LEFT
        lines.forEach { text ->
            val parts = text.split(" ").filter { it.isNotEmpty() }
            subtitle.textSize = SUB_SIZE
            var x = (WIDTH - subtitle.measureText(parts.joinToString(" "))) / 2f
            parts.forEach { word ->
                subtitle.textSize = SUB_SIZE
                val width = subtitle.measureText("$word ")
                val now = index == active
                if (now) subtitle.textSize = SUB_SIZE * 1.12f
                subtitle.style = Paint.Style.STROKE
                subtitle.strokeWidth = 10f
                subtitle.color = Color.BLACK
                canvas.drawText(word, x, y, subtitle)
                subtitle.style = Paint.Style.FILL
                subtitle.color = if (now) Color.rgb(255, 221, 51) else Color.WHITE
                canvas.drawText(word, x, y, subtitle)
                x += width
                index++
            }
            y += 64f
        }
        subtitle.textSize = SUB_SIZE
        subtitle.textAlign = Paint.Align.CENTER
    }

    // ---------- MP4 ----------

    private fun encode(
        story: Story, voices: List<Pcm?>, seconds: List<Double>, audio: ShortArray, rate: Int, out: File,
        progress: (String) -> Unit
    ) {
        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_CHUNK * 2)
        }
        val (audioOut, audioSamples) = encodeAudio(audioFormat, audio, rate)
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var written = 0L
        val info = MediaCodec.BufferInfo()

        fun drain(end: Boolean) {
            var idle = 0
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (end) DRAIN_WAIT_US else 0L)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!end || ++idle > MAX_IDLE_DRAINS) return else continue
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = muxer.addTrack(codec.outputFormat)
                    val audioTrack = muxer.addTrack(audioOut)
                    muxer.start()
                    val buffer = ByteBuffer.allocateDirect(audioSamples.maxOfOrNull { it.data.size } ?: 1)
                    val meta = MediaCodec.BufferInfo()
                    audioSamples.forEach {
                        buffer.clear()
                        buffer.put(it.data)
                        buffer.flip()
                        meta.set(0, it.data.size, it.time, it.flags)
                        muxer.writeSampleData(audioTrack, buffer, meta)
                    }
                    continue
                }
                if (index < 0) continue
                val data = codec.getOutputBuffer(index)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && videoTrack >= 0) {
                    // Har frame ka samay khud likho, taaki video ki speed sahi rahe
                    info.presentationTimeUs = written * 1_000_000L / FPS
                    written++
                    data.position(info.offset)
                    data.limit(info.offset + info.size)
                    muxer.writeSampleData(videoTrack, data, info)
                }
                val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(index, false)
                if (last) return
            }
        }

        val totalFrames = (seconds.sum() * FPS).toInt()
        var frame = 0
        story.scenes.forEachIndexed { i, scene ->
            val count = (seconds[i] * FPS).toInt()
            val talkLen = voices[i]?.let { it.samples.size.toDouble() / it.rate } ?: 0.0
            val talkEnd = if (voices[i] == null) -1.0 else LEAD_IN + talkLen
            val levels = voices[i]?.let { LipSync.levels(it.samples, it.rate, FPS) }
            var mouth = frame % LipSync.OPEN.size
            repeat(count) { k ->
                val inScene = k.toDouble() / FPS
                val talking = inScene in LEAD_IN..talkEnd
                // Muh awaaz ke zor ke saath khulta aur band hota hai
                if (talking && levels != null) {
                    val level = levels.getOrElse(((inScene - LEAD_IN) * FPS).toInt()) { 0f }
                    mouth = LipSync.next(mouth, frame, level)
                } else mouth = frame % LipSync.OPEN.size
                val said = when {
                    talkLen <= 0.0 || inScene < LEAD_IN || inScene > talkEnd + SAID_HOLD -> -1.0
                    else -> ((inScene - LEAD_IN) / talkLen).coerceAtMost(1.0)
                }
                val canvas = surface.lockHardwareCanvas()
                try {
                    drawFrame(canvas, story, scene, frame, inScene, talking, if (levels == null) -1 else mouth, said)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                frame++
                drain(false)
                if (frame % FPS == 0) progress("Making the animation " + (frame * 100 / max(totalFrames, 1)) + "%")
            }
        }
        codec.signalEndOfInputStream()
        drain(true)
        codec.stop()
        codec.release()
        surface.release()
        muxer.stop()
        muxer.release()
    }

    private fun encodeAudio(format: MediaFormat, pcm: ShortArray, rate: Int): Pair<MediaFormat, List<Sample>> {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { bytes.putShort(it) }
        val raw = bytes.array()
        val samples = ArrayList<Sample>()
        var outFormat = format
        var offset = 0
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(DRAIN_WAIT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    buffer.clear()
                    val size = min(min(buffer.capacity(), AUDIO_CHUNK * 2), raw.size - offset)
                    val time = (offset / 2).toLong() * 1_000_000L / rate
                    if (size <= 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        buffer.put(raw, offset, size)
                        codec.queueInputBuffer(inIndex, 0, size, time, 0)
                        offset += size
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, DRAIN_WAIT_US)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outFormat = codec.outputFormat
            } else if (outIndex >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val data = codec.getOutputBuffer(outIndex)!!
                    val copy = ByteArray(info.size)
                    data.position(info.offset)
                    data.get(copy)
                    samples.add(Sample(copy, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()))
                }
                val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(outIndex, false)
                if (last) break
            }
        }
        codec.stop()
        codec.release()
        return outFormat to samples
    }

    private fun save(file: File, name: String): Uri {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) throw IOException("Needs Android 10 or newer")
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Sahayak")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Gallery did not accept the video")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("Could not save the video")
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val FPS = 18
        private const val CHARACTER_SIZE = 640f
        private const val CLOSE_SIZE = 1180f
        private const val TITLE_SECONDS = 3
        private const val KEEP_CLIPS = 3
        private const val MAX_FRAMES = 54
        private const val VIDEO_BITRATE = 4_000_000
        private const val AUDIO_BITRATE = 64_000
        private const val AUDIO_CHUNK = 2048
        private const val DRAIN_WAIT_US = 10_000L
        private const val MAX_IDLE_DRAINS = 500
        private const val DEFAULT_RATE = 22_050
        private const val WAV_HEADER = 44L
        private const val TTS_WAIT_S = 20L
        private const val LEAD_IN = 0.3
        private const val SPEECH_PAD = 0.6
        private const val POEM_PAD = 0.45
        private const val SAID_HOLD = 0.15
        private const val POEM_RATE = 0.85f
        private const val SUB_SIZE = 54f
        private const val SUB_CHARS = 22
        private const val BABY_PITCH = 1.75f
        private const val CHHOTU_PITCH = 1.6f
        private const val VOICE_RATE = 0.95f
    }
}

/** Saade, rangeen background, code se bane. */
object Backdrops {
    fun draw(place: String, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun sky(top: Int, bottom: Int) {
            p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null
        }
        fun ground(color: Int, y: Float) {
            p.color = color
            c.drawOval(RectF(-w * 0.4f, y, w * 1.4f, h * 1.4f), p)
        }
        fun circle(x: Float, y: Float, r: Float, color: Int) {
            p.color = color
            c.drawCircle(x, y, r, p)
        }
        val rnd = java.util.Random(place.hashCode().toLong())
        when (place) {
            "jungle" -> {
                sky(Color.rgb(170, 230, 190), Color.rgb(60, 150, 90))
                for (i in 0 until 6) {
                    val x = rnd.nextFloat() * w
                    p.color = Color.rgb(110, 75, 45)
                    c.drawRect(x - 22f, h * 0.2f, x + 22f, h * 0.8f, p)
                    for (k in 0 until 5) circle(x + (rnd.nextFloat() - 0.5f) * 220f, h * 0.18f + rnd.nextFloat() * 160f, 90f + rnd.nextFloat() * 50f, Color.rgb(40 + rnd.nextInt(40), 130 + rnd.nextInt(60), 60))
                }
                ground(Color.rgb(70, 160, 70), h * 0.72f)
            }
            "beach" -> {
                sky(Color.rgb(120, 200, 255), Color.rgb(200, 240, 255))
                circle(w * 0.8f, h * 0.14f, 80f, Color.rgb(255, 220, 90))
                p.color = Color.rgb(40, 150, 220)
                c.drawRect(0f, h * 0.5f, w.toFloat(), h * 0.68f, p)
                ground(Color.rgb(245, 215, 150), h * 0.64f)
            }
            "night" -> {
                sky(Color.rgb(15, 20, 60), Color.rgb(60, 40, 110))
                repeat(70) { circle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.6f, 2f + rnd.nextFloat() * 3f, Color.argb(220, 255, 255, 230)) }
                circle(w * 0.78f, h * 0.15f, 70f, Color.rgb(250, 245, 210))
                circle(w * 0.75f, h * 0.14f, 66f, Color.rgb(25, 28, 70))
                ground(Color.rgb(30, 70, 60), h * 0.72f)
            }
            "stage" -> {
                sky(Color.rgb(70, 20, 90), Color.rgb(20, 5, 30))
                p.shader = RadialGradient(w / 2f, h * 0.55f, w * 0.7f, Color.argb(170, 255, 230, 160), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.shader = null
                p.color = Color.rgb(150, 30, 50)
                c.drawRect(0f, 0f, w * 0.12f, h.toFloat(), p)
                c.drawRect(w * 0.88f, 0f, w.toFloat(), h.toFloat(), p)
                ground(Color.rgb(120, 70, 40), h * 0.74f)
            }
            "sweet" -> {
                // garam peach rang, dhundhle gol ujale
                sky(Color.rgb(255, 226, 170), Color.rgb(255, 170, 150))
                repeat(26) {
                    val x = rnd.nextFloat() * w
                    val y = rnd.nextFloat() * h * 0.7f
                    val r = 20f + rnd.nextFloat() * 60f
                    p.shader = RadialGradient(x, y, r, Color.argb(120, 255, 255, 240), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    c.drawCircle(x, y, r, p)
                }
                p.shader = null
            }
            "kitchen" -> {
                sky(Color.rgb(255, 245, 225), Color.rgb(250, 230, 200))
                p.color = Color.rgb(200, 235, 230)
                for (row in 0 until 6) for (col in 0 until 6) {
                    if ((row + col) % 2 == 0) c.drawRect(col * w / 6f, h * 0.3f + row * 60f, (col + 1) * w / 6f, h * 0.3f + (row + 1) * 60f, p)
                }
                p.color = Color.rgb(240, 240, 240)
                c.drawRect(0f, h * 0.68f, w.toFloat(), h * 0.72f, p)
                p.color = Color.rgb(120, 200, 190)
                c.drawRect(0f, h * 0.72f, w.toFloat(), h.toFloat(), p)
            }
            "room" -> {
                sky(Color.rgb(255, 236, 210), Color.rgb(250, 210, 180))
                p.color = Color.rgb(170, 220, 255)
                c.drawRect(w * 0.12f, h * 0.12f, w * 0.42f, h * 0.36f, p)
                p.color = Color.rgb(200, 140, 90)
                c.drawRect(0f, h * 0.74f, w.toFloat(), h.toFloat(), p)
            }
            "space" -> {
                sky(Color.rgb(5, 5, 25), Color.rgb(40, 10, 60))
                repeat(110) { circle(rnd.nextFloat() * w, rnd.nextFloat() * h, 1.5f + rnd.nextFloat() * 3f, Color.WHITE) }
                circle(w * 0.2f, h * 0.18f, 90f, Color.rgb(255, 150, 90))
                ground(Color.rgb(150, 150, 170), h * 0.75f)
            }
            "snow" -> {
                sky(Color.rgb(190, 220, 245), Color.rgb(240, 248, 255))
                repeat(90) { circle(rnd.nextFloat() * w, rnd.nextFloat() * h, 3f + rnd.nextFloat() * 5f, Color.argb(230, 255, 255, 255)) }
                ground(Color.WHITE, h * 0.7f)
            }
            else -> {
                sky(Color.rgb(140, 210, 255), Color.rgb(220, 245, 255))
                repeat(3) { i ->
                    val x = w * (0.2f + 0.3f * i)
                    circle(x, h * (0.12f + 0.04f * i), 55f, Color.WHITE)
                    circle(x + 50f, h * (0.12f + 0.04f * i), 45f, Color.WHITE)
                }
                repeat(10) { circle(rnd.nextFloat() * w, h * 0.78f + rnd.nextFloat() * h * 0.2f, 10f, Color.rgb(255, 200 + rnd.nextInt(55), 80)) }
                ground(Color.rgb(110, 200, 90), h * 0.72f)
            }
        }
        return bmp
    }
}

/** Video ke upar ki chamak: dil, sitare, bulbule aur uchhalte shabd. */
object Effects {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 120f
    }
    private val heart = android.graphics.Path()

    private fun heartAt(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        heart.reset()
        heart.moveTo(x, y + r * 0.9f)
        heart.cubicTo(x - r * 1.6f, y - r * 0.2f, x - r * 0.6f, y - r * 1.3f, x, y - r * 0.4f)
        heart.cubicTo(x + r * 0.6f, y - r * 1.3f, x + r * 1.6f, y - r * 0.2f, x, y + r * 0.9f)
        paint.color = color
        canvas.drawPath(heart, paint)
    }

    private fun sparkle(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        paint.color = color
        heart.reset()
        heart.moveTo(x, y - r)
        heart.quadTo(x, y, x + r, y)
        heart.quadTo(x, y, x, y + r)
        heart.quadTo(x, y, x - r, y)
        heart.quadTo(x, y, x, y - r)
        canvas.drawPath(heart, paint)
    }

    fun draw(canvas: Canvas, effect: String, time: Double, frame: Int, w: Int, h: Int) {
        if (effect.isEmpty()) return
        val rnd = java.util.Random(effect.hashCode().toLong())
        repeat(14) { i ->
            val baseX = rnd.nextFloat() * w
            val speed = 0.15f + rnd.nextFloat() * 0.2f
            val offset = rnd.nextFloat()
            val twinkle = (kotlin.math.sin(frame * 0.5 + i) * 0.5 + 0.5).toFloat()
            when (effect) {
                "hearts" -> {
                    val life = ((time * speed + offset) % 1.0).toFloat()
                    val y = h * (0.85f - life * 0.7f)
                    val x = baseX + kotlin.math.sin(life * 12 + i).toFloat() * 20f
                    heartAt(canvas, x, y, 18f + rnd.nextFloat() * 14f, Color.argb((255 * (1 - life)).toInt(), 255, 90, 130))
                }
                "bubbles" -> {
                    val life = ((time * speed + offset) % 1.0).toFloat()
                    paint.color = Color.argb((140 * (1 - life)).toInt(), 200, 235, 255)
                    canvas.drawCircle(baseX, h * (0.95f - life * 0.9f), 14f + rnd.nextFloat() * 22f, paint)
                }
                else -> {
                    val y = rnd.nextFloat() * h * 0.75f
                    val color = if (effect == "stars") Color.argb((255 * twinkle).toInt(), 255, 215, 60) else Color.argb((255 * twinkle).toInt(), 255, 255, 230)
                    sparkle(canvas, baseX, y, 10f + 18f * twinkle, color)
                }
            }
        }
    }

    /** Bada shabd jo uchhal kar aata hai, jaise Yum! */
    fun pop(canvas: Canvas, word: String, time: Double, w: Int) {
        val t = ((time - 0.3) / 0.35).coerceIn(0.0, 1.0)
        if (t <= 0.0) return
        val scale = (if (t < 0.7) t / 0.7 * 1.2 else 1.2 - (t - 0.7) / 0.3 * 0.2).toFloat()
        canvas.save()
        canvas.translate(w / 2f, 230f)
        canvas.rotate(kotlin.math.sin(time * 6).toFloat() * 4f)
        canvas.scale(scale, scale)
        popPaint.style = Paint.Style.STROKE
        popPaint.strokeWidth = 16f
        popPaint.color = Color.WHITE
        canvas.drawText(word, 0f, 40f, popPaint)
        popPaint.style = Paint.Style.FILL
        popPaint.color = Color.rgb(255, 100, 20)
        canvas.drawText(word, 0f, 40f, popPaint)
        canvas.restore()
    }
}
