package com.sahayak.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Apni awaaz ka clone. Awaaz ka namoona phone par record hota hai, phir chuni hui service
 * (Fish Audio ya ElevenLabs) ko jaata hai. Key sirf phone mein, taale mein rehti hai.
 */
object VoiceClone {
    private const val PREFS = "sahayak_voice"
    private const val TIMEOUT_S = 120L

    @Volatile
    var lastError = ""
        private set

    @Volatile
    var recording = false
        private set

    @Volatile
    var recordedMs = 0L
        private set

    private var recorder: Thread? = null
    private var player: MediaPlayer? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(TIMEOUT_S, TimeUnit.SECONDS).writeTimeout(TIMEOUT_S, TimeUnit.SECONDS).build()
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun provider(c: Context): String = prefs(c).getString("provider", VoiceLogic.FISH) ?: VoiceLogic.FISH

    fun setProvider(c: Context, provider: String) = prefs(c).edit().putString("provider", provider).apply()

    fun key(c: Context, provider: String = provider(c)): String = SecureStore(prefs(c), "key_$provider").load()

    fun saveKey(c: Context, provider: String, key: String) = SecureStore(prefs(c), "key_$provider").save(key.trim())

    /** Is service par bana clone. Khaali = abhi nahi bana. */
    fun voiceId(c: Context, provider: String = provider(c)): String = prefs(c).getString("voice_$provider", "").orEmpty()

    fun name(c: Context): String = prefs(c).getString("name", "").orEmpty()

    fun setName(c: Context, name: String) = prefs(c).edit().putString("name", name.trim().take(40)).apply()

    /** ElevenLabs ka model: v2 Hindi ke liye, v3 mein Marathi jaisi aur bhashaein. */
    fun elevenModel(c: Context): String = prefs(c).getString("eleven_model", VoiceLogic.ELEVEN_V2) ?: VoiceLogic.ELEVEN_V2

    fun setElevenModel(c: Context, model: String) = prefs(c).edit().putString("eleven_model", model).apply()

    fun sample(c: Context) = File(c.filesDir, "voice_sample.wav")

    fun spoken(c: Context) = File(c.cacheDir, "voice_out.mp3")

    fun sampleSeconds(c: Context): Int = sample(c).let { if (it.exists()) VoiceLogic.seconds(it.length()) else 0 }

    // ---------- Record ----------

    /** Mic se namoona record karo (WAV). Mic ki ijazat pehle mil chuki honi chahiye. */
    @SuppressLint("MissingPermission")
    fun startRecording(c: Context): Boolean {
        if (recording) return true
        stopPlaying()
        val min = AudioRecord.getMinBufferSize(VoiceLogic.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) {
            lastError = "This phone could not start the microphone"
            return false
        }
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, VoiceLogic.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
        } catch (e: RuntimeException) {
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            lastError = "Microphone is busy. Close other apps that use the mic and try again"
            return false
        }
        val file = sample(c)
        recording = true
        recordedMs = 0L
        recorder = Thread({
            var total = 0
            try {
                RandomAccessFile(file, "rw").use { out ->
                    out.setLength(0)
                    out.write(VoiceLogic.wavHeader(0))
                    val buffer = ByteArray(min)
                    record.startRecording()
                    val limit = VoiceLogic.MAX_SECONDS * VoiceLogic.RATE * 2
                    while (recording && total < limit) {
                        val n = record.read(buffer, 0, buffer.size)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        total += n
                        recordedMs = total * 1000L / (VoiceLogic.RATE * 2)
                    }
                    out.seek(0)
                    out.write(VoiceLogic.wavHeader(total))
                }
            } catch (e: IOException) {
                lastError = "Could not save the recording"
            } catch (e: RuntimeException) {
                lastError = "Recording stopped by the phone"
            } finally {
                try {
                    record.stop()
                } catch (e: RuntimeException) {
                    // Pehle hi band
                }
                record.release()
                recording = false
            }
        }, "voice-record").apply { start() }
        return true
    }

    fun stopRecording() {
        recording = false
        try {
            recorder?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        recorder = null
    }

    // ---------- Sunna ----------

    fun play(file: File, done: () -> Unit = {}): Boolean {
        stopPlaying()
        if (!file.exists() || file.length() < 100) return false
        return try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { done() }
                prepare()
                start()
            }
            true
        } catch (e: IOException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    fun stopPlaying() {
        try {
            player?.release()
        } catch (e: RuntimeException) {
            // Pehle hi band
        }
        player = null
    }

    // ---------- Service se baat ----------

    private fun call(provider: String, request: Request): ByteArray? = try {
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (response.isSuccessful) bytes else {
                lastError = VoiceLogic.problem(provider, response.code, String(bytes, Charsets.UTF_8))
                null
            }
        }
    } catch (e: IOException) {
        lastError = "No internet, or " + VoiceLogic.label(provider) + " did not answer. Try again"
        null
    }

    private fun auth(builder: Request.Builder, provider: String, key: String) =
        if (provider == VoiceLogic.ELEVEN) builder.header("xi-api-key", key) else builder.header("Authorization", "Bearer $key")

    /** Record kiye namoone se clone banao. Jawab user ko dikhane ke liye. Peeche ke dhaage se bulao. */
    fun create(c: Context): String {
        val provider = provider(c)
        val key = key(c, provider)
        val file = sample(c)
        lastError = ""
        if (key.isEmpty()) return "Add your " + VoiceLogic.label(provider) + " key first"
        if (sampleSeconds(c) < VoiceLogic.MIN_SECONDS) return "Record at least " + VoiceLogic.MIN_SECONDS + " seconds first"
        if (provider == VoiceLogic.FISH) {
            // Fish free plan par clone save karna mana hai (0/0), isliye recording phone mein rehti hai
            // aur har baar bolwate waqt saath jaati hai. Free model isi tarah awaaz copy karta hai.
            deleteRemote(c, provider)
            prefs(c).edit().putString("voice_$provider", VoiceLogic.FISH_LOCAL).apply()
            return "✓ Your voice is ready. On the free Fish Audio plan your recording is sent along each time you speak"
        }
        val title = name(c).ifEmpty { "My voice" } + " (Sahayak)"
        val audio = file.asRequestBody("audio/wav".toMediaType())
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
        val url = if (provider == VoiceLogic.ELEVEN) {
            form.addFormDataPart("name", title).addFormDataPart("files", "sample.wav", audio)
            "https://api.elevenlabs.io/v1/voices/add"
        } else {
            form.addFormDataPart("type", "tts").addFormDataPart("title", title).addFormDataPart("train_mode", "fast")
                .addFormDataPart("visibility", "private").addFormDataPart("voices", "sample.wav", audio)
            "https://api.fish.audio/model"
        }
        // Purana clone ho to pehle hata do, taaki service par jagah na bhare
        deleteRemote(c, provider)
        val bytes = call(provider, auth(Request.Builder().url(url), provider, key).post(form.build()).build()) ?: return lastError
        val id = VoiceLogic.voiceId(provider, String(bytes, Charsets.UTF_8))
        if (id.isEmpty()) return VoiceLogic.label(provider) + " did not give a voice. Try again"
        prefs(c).edit().putString("voice_$provider", id).apply()
        return "Your voice is ready on " + VoiceLogic.label(provider)
    }

    /** Likha hua apni awaaz mein bolwao. File mile to wahi, warna null aur lastError. Peeche ke dhaage se bulao. */
    fun speak(c: Context, words: String): File? {
        val provider = provider(c)
        val key = key(c, provider)
        val voice = voiceId(c, provider)
        val text = VoiceLogic.cleanText(words)
        lastError = ""
        if (key.isEmpty()) {
            lastError = "Add your " + VoiceLogic.label(provider) + " key first"
            return null
        }
        if (voice.isEmpty()) {
            lastError = "Make your voice clone first"
            return null
        }
        if (text.isEmpty()) {
            lastError = "Write something to say"
            return null
        }
        val json = "application/json".toMediaType()
        val request = if (provider == VoiceLogic.ELEVEN) {
            val body = JSONObject().put("text", text).put("model_id", elevenModel(c)).toString().toRequestBody(json)
            auth(Request.Builder().url("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=mp3_44100_128"), provider, key).post(body).build()
        } else {
            val body = if (voice == VoiceLogic.FISH_LOCAL) {
                val audio = try {
                    sample(c).readBytes()
                } catch (e: IOException) {
                    lastError = "Record your voice again first"
                    return null
                }
                VoiceLogic.fishBody(text, audio, VoiceLogic.spokenScript(name(c))).toRequestBody("application/msgpack".toMediaType())
            } else {
                JSONObject().put("text", text).put("reference_id", voice).put("format", "mp3").toString().toRequestBody(json)
            }
            auth(Request.Builder().url("https://api.fish.audio/v1/tts"), provider, key).header("model", VoiceLogic.FISH_MODEL).post(body).build()
        }
        val bytes = call(provider, request) ?: return null
        if (bytes.size < 200) {
            lastError = VoiceLogic.label(provider) + " gave no sound. Try again"
            return null
        }
        return try {
            spoken(c).also { it.writeBytes(bytes) }
        } catch (e: IOException) {
            lastError = "Could not save the sound"
            null
        }
    }

    private fun deleteRemote(c: Context, provider: String) {
        val voice = voiceId(c, provider)
        val key = key(c, provider)
        if (voice == VoiceLogic.FISH_LOCAL) {
            prefs(c).edit().remove("voice_$provider").apply()
            return
        }
        if (voice.isEmpty() || key.isEmpty()) return
        val url = if (provider == VoiceLogic.ELEVEN) "https://api.elevenlabs.io/v1/voices/$voice" else "https://api.fish.audio/model/$voice"
        val keep = lastError
        call(provider, auth(Request.Builder().url(url), provider, key).delete().build())
        lastError = keep
        prefs(c).edit().remove("voice_$provider").apply()
    }

    /** Clone aur recording dono hatao (service se bhi). Peeche ke dhaage se bulao. */
    fun deleteAll(c: Context) {
        listOf(VoiceLogic.FISH, VoiceLogic.ELEVEN).forEach { deleteRemote(c, it) }
        sample(c).delete()
        spoken(c).delete()
    }

    /** Bani hui awaaz phone ke Music/Sahayak mein rakho, taaki bhej sako. Android 10 se. */
    fun saveToMusic(c: Context): Uri? {
        val file = spoken(c)
        if (!file.exists() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "my-voice-" + System.currentTimeMillis() + ".mp3")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Sahayak")
            }
            val uri = c.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            c.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            uri
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }
}
