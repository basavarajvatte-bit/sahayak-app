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
 * (Fish Audio, ElevenLabs, ya apna server jisme Fish ka open-source model chalta hai) ko jaata hai.
 * Key aur server ka password sirf phone mein, taale mein rehte hain.
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

    /** Apna server free GPU par dheere chalta hai, isliye zyada intezaar. */
    private val slowClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(OWN_TIMEOUT_S, TimeUnit.SECONDS).build()
    }

    private const val OWN_TIMEOUT_S = 360L

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun provider(c: Context): String = prefs(c).getString("provider", VoiceLogic.FISH) ?: VoiceLogic.FISH

    fun setProvider(c: Context, provider: String) = prefs(c).edit().putString("provider", provider).apply()

    fun key(c: Context, provider: String = provider(c)): String = SecureStore(prefs(c), "key_$provider").load()

    fun saveKey(c: Context, provider: String, key: String) = SecureStore(prefs(c), "key_$provider").save(key.trim())

    /** Chuni hui awaaz ka is service par bana clone. Khaali = abhi nahi bana. */
    fun voiceId(c: Context, provider: String = provider(c)): String = prefs(c).getString(cloneKey(c, provider), "").orEmpty()

    /** Har awaaz ka har service par alag clone. */
    private fun cloneKey(c: Context, provider: String, voice: String = selected(c)) = "voice_${provider}_$voice"

    // ---------- Kai awaazein ----------

    private fun voicesDir(c: Context) = File(c.filesDir, "voices").apply { mkdirs() }

    private fun fileOf(c: Context, id: String) = File(voicesDir(c), "$id.wav")

    private fun saveVoices(c: Context, list: List<VoiceLogic.SavedVoice>) =
        prefs(c).edit().putString("voices", VoiceLogic.voicesToJson(list)).apply()

    /** Pehle ek hi recording hoti thi: use pehli awaaz bana do (ek baar). */
    private fun migrate(c: Context) {
        val p = prefs(c)
        if (p.getBoolean("voices_ready", false)) return
        val old = File(c.filesDir, "voice_sample.wav")
        val list = VoiceLogic.voicesFromJson(p.getString("voices", "[]") ?: "[]").toMutableList()
        val e = p.edit()
        if (old.exists() && old.length() > 44) {
            val id = "v" + System.currentTimeMillis().toString(36)
            val moved = old.renameTo(fileOf(c, id)) || try {
                old.copyTo(fileOf(c, id), overwrite = true); old.delete(); true
            } catch (x: IOException) {
                false
            }
            if (moved) {
                list.add(VoiceLogic.SavedVoice(id, "My voice", p.getString("sample_text", "").orEmpty()))
                e.putString("voice_sel", id)
                listOf(VoiceLogic.FISH, VoiceLogic.ELEVEN, VoiceLogic.OWN).forEach { prov ->
                    p.getString("voice_$prov", null)?.let { e.putString("voice_${prov}_$id", it) }
                    e.remove("voice_$prov")
                }
            }
        }
        e.putString("voices", VoiceLogic.voicesToJson(list)).putBoolean("voices_ready", true).apply()
    }

    /** Saari bachi hui awaazein, recording ke saath. */
    fun voices(c: Context): List<VoiceLogic.SavedVoice> {
        migrate(c)
        val all = VoiceLogic.voicesFromJson(prefs(c).getString("voices", "[]") ?: "[]")
        if (recording) return all
        // Adhoori / khaali recording wali awaaz hata do
        val kept = all.filter { fileOf(c, it.id).let { f -> f.exists() && VoiceLogic.seconds(f.length()) >= 1 } }
        if (kept.size != all.size) {
            all.filter { it !in kept }.forEach { fileOf(c, it.id).delete() }
            saveVoices(c, kept)
            if (kept.none { it.id == prefs(c).getString("voice_sel", "") }) select(c, kept.firstOrNull()?.id.orEmpty())
        }
        return kept
    }

    fun selected(c: Context): String {
        migrate(c)
        return prefs(c).getString("voice_sel", "").orEmpty()
    }

    fun select(c: Context, id: String) = prefs(c).edit().putString("voice_sel", id).apply()

    fun selectedVoice(c: Context): VoiceLogic.SavedVoice? = selected(c).let { id -> voices(c).firstOrNull { it.id == id } }

    /** Nayi awaaz ki jagah banao aur use chuno. Recording ya clip isi mein jaayegi. */
    fun newVoice(c: Context, typedLabel: String): String {
        val list = voices(c).toMutableList()
        val id = "v" + System.currentTimeMillis().toString(36)
        list.add(VoiceLogic.SavedVoice(id, VoiceLogic.voiceLabel(typedLabel, list.map { it.label })))
        saveVoices(c, list)
        select(c, id)
        return id
    }

    fun rename(c: Context, id: String, label: String) {
        val list = voices(c)
        saveVoices(c, list.map { if (it.id == id) it.copy(label = VoiceLogic.voiceLabel(label, list.filter { v -> v.id != id }.map { v -> v.label })) else it })
    }

    /** Ek awaaz hatao (service par bana clone bhi). Peeche ke dhaage se bulao. */
    fun removeVoice(c: Context, id: String) {
        val keep = selected(c)
        select(c, id)
        listOf(VoiceLogic.FISH, VoiceLogic.ELEVEN, VoiceLogic.OWN).forEach { deleteRemote(c, it) }
        fileOf(c, id).delete()
        val rest = voices(c).filter { it.id != id }
        saveVoices(c, rest)
        select(c, if (keep != id && rest.any { it.id == keep }) keep else rest.firstOrNull()?.id.orEmpty())
    }

    fun secondsOf(c: Context, id: String): Int = fileOf(c, id).let { if (it.exists()) VoiceLogic.seconds(it.length()) else 0 }

    fun fileFor(c: Context, id: String): File = fileOf(c, id)

    /** Apne server ka link (notebook se). Khaali = abhi nahi diya. */
    fun serverUrl(c: Context): String = prefs(c).getString("server_url", "").orEmpty()

    /** Notebook wali line se link aur password dono rakho. Galat line ho to false. */
    fun saveServerLine(c: Context, line: String): Boolean {
        val (url, pass) = VoiceLogic.serverLine(line) ?: return false
        prefs(c).edit().putString("server_url", url).apply()
        saveKey(c, VoiceLogic.OWN, pass)
        return true
    }

    fun name(c: Context): String = prefs(c).getString("name", "").orEmpty()

    fun setName(c: Context, name: String) = prefs(c).edit().putString("name", name.trim().take(40)).apply()

    /** ElevenLabs ka model: v2 Hindi ke liye, v3 mein Marathi jaisi aur bhashaein. */
    fun elevenModel(c: Context): String = prefs(c).getString("eleven_model", VoiceLogic.ELEVEN_V2) ?: VoiceLogic.ELEVEN_V2

    fun setElevenModel(c: Context, model: String) = prefs(c).edit().putString("eleven_model", model).apply()

    /** Chuni hui awaaz ki recording. */
    fun sample(c: Context): File = selected(c).let { if (it.isEmpty()) File(c.filesDir, "voice_sample.wav") else fileOf(c, it) }

    fun spoken(c: Context) = File(c.cacheDir, "voice_out.mp3")

    /** Namoone mein kya bola gaya hai. Apni clip daali ho to uske shabd, warna padhne wala script. */
    fun sampleText(c: Context): String = clipText(c).ifBlank { VoiceLogic.spokenScript(name(c)) }

    /** Chuni hui awaaz ki clip ke shabd. Khaali = mic wali recording (script). */
    fun clipText(c: Context): String = selectedVoice(c)?.text.orEmpty()

    fun setClipText(c: Context, text: String) {
        val id = selected(c)
        val list = VoiceLogic.voicesFromJson(prefs(c).getString("voices", "[]") ?: "[]")
        if (list.none { it.id == id }) return
        saveVoices(c, list.map { if (it.id == id) it.copy(text = text.trim().take(2000)) else it })
    }

    private fun minSeconds(c: Context) = if (clipText(c).isNotEmpty()) VoiceLogic.CLIP_MIN_SECONDS else VoiceLogic.MIN_SECONDS

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
        setClipText(c, "")
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

    // ---------- Apni clip ----------

    /**
     * Phone ki audio clip ko namoona banao, aur Gemini se uske shabd likhwao (awaaz copy karne ke liye zaroori).
     * Jawab user ko dikhane ke liye. Peeche ke dhaage se bulao.
     */
    fun importClip(c: Context, uri: Uri): String {
        stopPlaying()
        val temp = File(c.cacheDir, "clip_import.wav")
        val seconds = AudioImport.toWav(c, uri, temp, VoiceLogic.MAX_SECONDS)
        if (seconds < 0) return AudioImport.lastError
        if (seconds < VoiceLogic.CLIP_MIN_SECONDS) {
            temp.delete()
            return "This clip is only $seconds seconds. Choose a clip of at least " + VoiceLogic.CLIP_MIN_SECONDS + " seconds where only you speak"
        }
        if (!temp.renameTo(sample(c))) {
            try {
                temp.copyTo(sample(c), overwrite = true)
                temp.delete()
            } catch (e: IOException) {
                return "Could not save the clip"
            }
        }
        // Purana clone purani awaaz ka hai; naye namoone se dobara banana padega
        prefs(c).edit().remove(cloneKey(c, VoiceLogic.FISH)).remove(cloneKey(c, VoiceLogic.OWN)).apply()
        setClipText(c, "")
        val words = transcribe(c, sample(c))
        val cut = if (seconds >= VoiceLogic.MAX_SECONDS) " (only the first " + VoiceLogic.MAX_SECONDS + " seconds are used)" else ""
        return if (words.isNotEmpty()) {
            setClipText(c, words)
            "✓ Clip added: $seconds seconds$cut. Check the words below, then tap Make my voice clone"
        } else {
            "✓ Clip added: $seconds seconds$cut. " + lastError + ". Type below exactly what you say in the clip"
        }
    }

    /** Gemini se clip ke shabd. Na ho paye to khaali (tab lastError). */
    private fun transcribe(c: Context, wav: File): String {
        lastError = ""
        val key = SecureStore(c.getSharedPreferences("sahayak", Context.MODE_PRIVATE)).load()
        if (key.isEmpty()) {
            lastError = "No Gemini key in Sahayak, so the words could not be written automatically"
            return ""
        }
        val data = try {
            android.util.Base64.encodeToString(wav.readBytes(), android.util.Base64.NO_WRAP)
        } catch (e: IOException) {
            return ""
        }
        val parts = org.json.JSONArray()
            .put(JSONObject().put("text", "Write down exactly what is spoken in this audio, word for word, in the same language and script the speaker would use when typing on a phone (Hindi or Marathi in Roman letters is fine, English as English). Only the words, nothing else. If nobody speaks, answer NONE."))
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", data)))
        val body = JSONObject()
            .put("contents", org.json.JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.0).put("maxOutputTokens", 2048))
            .toString().toRequestBody("application/json".toMediaType())
        for (model in listOf("gemini-2.5-flash", "gemini-flash-latest")) {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", key).post(body).build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = if (response.code == 429) "Gemini limit is used up for now, so the words could not be written automatically"
                        else "Gemini could not write the words"
                        return@use
                    }
                    val parts2 = JSONObject(response.body?.string().orEmpty()).optJSONArray("candidates")
                        ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val text = StringBuilder()
                    for (i in 0 until (parts2?.length() ?: 0)) {
                        val part = parts2?.optJSONObject(i) ?: continue
                        if (!part.optBoolean("thought")) text.append(part.optString("text"))
                    }
                    val words = text.toString().trim()
                    if (words.isEmpty() || words.equals("NONE", true)) lastError = "No speech was found in the clip"
                    else return words.replace(Regex("\\s+"), " ")
                }
            } catch (e: IOException) {
                lastError = "No internet, so the words could not be written automatically"
            } catch (e: org.json.JSONException) {
                lastError = "Gemini could not write the words"
            }
        }
        return ""
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
        (if (provider == VoiceLogic.OWN) slowClient else client).newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (response.isSuccessful) bytes else {
                lastError = VoiceLogic.problem(provider, response.code, String(bytes, Charsets.UTF_8))
                null
            }
        }
    } catch (e: java.net.SocketTimeoutException) {
        lastError = if (provider == VoiceLogic.OWN) "Your server took too long. Try a shorter text" else VoiceLogic.label(provider) + " took too long. Try again"
        null
    } catch (e: IOException) {
        lastError = if (provider == VoiceLogic.OWN) "Could not reach your server. Check the internet, or run the notebook again and paste the new line"
        else "No internet, or " + VoiceLogic.label(provider) + " did not answer. Try again"
        null
    }

    private fun auth(builder: Request.Builder, provider: String, key: String) = when {
        provider == VoiceLogic.ELEVEN -> builder.header("xi-api-key", key)
        key.isEmpty() -> builder
        else -> builder.header("Authorization", "Bearer $key")
    }

    /** Apna server chalu hai ya nahi. Khaali = theek; warna galti. Peeche ke dhaage se bulao. */
    fun checkServer(c: Context): String {
        val url = serverUrl(c)
        if (url.isEmpty()) return "Paste the line from the notebook first"
        val request = auth(Request.Builder().url("$url/v1/health"), VoiceLogic.OWN, key(c, VoiceLogic.OWN)).get().build()
        lastError = ""
        call(VoiceLogic.OWN, request) ?: return lastError
        return ""
    }

    /** Record kiye namoone se clone banao. Jawab user ko dikhane ke liye. Peeche ke dhaage se bulao. */
    fun create(c: Context): String {
        val provider = provider(c)
        val key = key(c, provider)
        val file = sample(c)
        lastError = ""
        if (provider == VoiceLogic.OWN) {
            if (sampleSeconds(c) < minSeconds(c)) return "Record at least " + minSeconds(c) + " seconds first, or add an audio clip"
            // Apne server par kuch save nahi hota: recording phone mein rehti hai aur har baar saath jaati hai.
            val problem = checkServer(c)
            if (problem.isNotEmpty()) return problem
            prefs(c).edit().putString(cloneKey(c, provider), VoiceLogic.FISH_LOCAL).apply()
            return "✓ Your voice is ready on your own server. Your recording is sent along each time you speak"
        }
        if (key.isEmpty()) return "Add your " + VoiceLogic.label(provider) + " key first"
        if (sampleSeconds(c) < minSeconds(c)) return "Record at least " + minSeconds(c) + " seconds first, or add an audio clip"
        if (provider == VoiceLogic.FISH) {
            // Fish free plan par clone save karna mana hai (0/0), isliye recording phone mein rehti hai
            // aur har baar bolwate waqt saath jaati hai. Free model isi tarah awaaz copy karta hai.
            deleteRemote(c, provider)
            prefs(c).edit().putString(cloneKey(c, provider), VoiceLogic.FISH_LOCAL).apply()
            return "✓ Your voice is ready. On the free Fish Audio plan your recording is sent along each time you speak"
        }
        val title = name(c).ifEmpty { "My voice" } + " - " + (selectedVoice(c)?.label ?: "voice") + " (Sahayak)"
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
        prefs(c).edit().putString(cloneKey(c, provider), id).apply()
        return "Your voice is ready on " + VoiceLogic.label(provider)
    }

    /** Likha hua apni awaaz mein bolwao. File mile to wahi, warna null aur lastError. Peeche ke dhaage se bulao. */
    fun speak(c: Context, words: String): File? {
        val provider = provider(c)
        val key = key(c, provider)
        var voice = voiceId(c, provider)
        val text = VoiceLogic.cleanText(words)
        lastError = ""
        // Apna server aur Fish free: recording hi saath jaati hai, alag clone ki zaroorat nahi
        if (voice.isEmpty() && provider != VoiceLogic.ELEVEN && sampleSeconds(c) >= minSeconds(c)) voice = VoiceLogic.FISH_LOCAL
        if (provider == VoiceLogic.OWN && serverUrl(c).isEmpty()) {
            lastError = "Paste the line from the notebook first"
            return null
        }
        if (key.isEmpty() && provider != VoiceLogic.OWN) {
            lastError = "Add your " + VoiceLogic.label(provider) + " key first"
            return null
        }
        if (voice.isEmpty()) {
            lastError = if (sampleSeconds(c) == 0) "Record a voice first" else "Make the voice clone for " + (selectedVoice(c)?.label ?: "this voice") + " first"
            return null
        }
        if (text.isEmpty()) {
            lastError = "Write something to say"
            return null
        }
        val json = "application/json".toMediaType()
        val request = if (provider == VoiceLogic.OWN) {
            val audio = try {
                sample(c).readBytes()
            } catch (e: IOException) {
                lastError = "Record your voice again first"
                return null
            }
            val body = VoiceLogic.fishBody(text, audio, sampleText(c)).toRequestBody("application/msgpack".toMediaType())
            auth(Request.Builder().url(serverUrl(c) + "/v1/tts"), provider, key).post(body).build()
        } else if (provider == VoiceLogic.ELEVEN) {
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
                VoiceLogic.fishBody(text, audio, sampleText(c)).toRequestBody("application/msgpack".toMediaType())
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
            prefs(c).edit().remove(cloneKey(c, provider)).apply()
            return
        }
        if (voice.isEmpty() || key.isEmpty()) return
        val url = if (provider == VoiceLogic.ELEVEN) "https://api.elevenlabs.io/v1/voices/$voice" else "https://api.fish.audio/model/$voice"
        val keep = lastError
        call(provider, auth(Request.Builder().url(url), provider, key).delete().build())
        lastError = keep
        prefs(c).edit().remove(cloneKey(c, provider)).apply()
    }

    /** Clone aur recording dono hatao (service se bhi). Peeche ke dhaage se bulao. */
    fun deleteAll(c: Context) {
        voices(c).forEach { removeVoice(c, it.id) }
        File(c.filesDir, "voice_sample.wav").delete()
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
