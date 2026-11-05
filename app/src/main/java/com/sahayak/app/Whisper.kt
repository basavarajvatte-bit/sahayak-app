package com.sahayak.app

import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Whisper (Groq par) se awaaz ko sahi shabdon mein badalta hai. */
class Whisper(http: OkHttpClient, private val apiKey: String) {
    private val client = http.newBuilder().dispatcher(Dispatcher()).callTimeout(8, TimeUnit.SECONDS).build()

    /** Dot chhodte hi adhuri likhai roko, taaki poora order pehle jaye. */
    fun cancelPartial() = client.dispatcher.cancelAll()

    fun transcribe(pcm: ByteArray): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("language", "en")
            .addFormDataPart("prompt", HINT)
            .addFormDataPart("file", "order.wav", wav(pcm).toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder().url(URL).header("Authorization", "Bearer $apiKey").post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("whisper ${response.code}")
            return JSONObject(response.body?.string().orEmpty()).optString("text").trim()
        }
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        header.putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size)
        return ByteArrayOutputStream(44 + pcm.size).apply {
            write(header.array())
            write(pcm)
        }.toByteArray()
    }

    companion object {
        private const val URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val MODEL = "whisper-large-v3-turbo"
        private const val RATE = 16_000
        private const val HINT = "Phone commands in English: WhatsApp, Gmail, Chrome, YouTube, IRCTC, open, send, search."
    }
}
