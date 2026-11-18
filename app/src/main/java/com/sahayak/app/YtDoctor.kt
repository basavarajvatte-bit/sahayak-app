package com.sahayak.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** YouTube Doctor: channel ki public videos padhta hai aur Gemini se har video dikhwa kar galtiyan nikalta hai. */
class YtDoctor(private val context: Context, private val apiKey: String) {
    var lastError = ""
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    private fun get(url: String): String? = try {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "SOCS=CAI; CONSENT=YES+1")
            .build()
        client.newCall(request).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else {
                lastError = "YouTube error " + r.code
                null
            }
        }
    } catch (e: IOException) {
        lastError = if (e is java.net.UnknownHostException) "No internet" else "YouTube did not answer"
        null
    }

    /** Link, @handle ya UC id se channel ka id. */
    fun resolve(input: String): String? {
        YtDoctorLogic.channelIdIn(input)?.let { return it }
        val handle = YtDoctorLogic.handleIn(input) ?: run {
            lastError = "Paste your channel link, like youtube.com/@yourname"
            return null
        }
        val page = get("https://www.youtube.com/$handle") ?: return null
        return YtDoctorLogic.channelIdFromPage(page) ?: run {
            lastError = "Channel not found for $handle"
            null
        }
    }

    fun videos(channelId: String): List<YtVideo> {
        val xml = get(YtDoctorLogic.feedUrl(channelId)) ?: return emptyList()
        return YtDoctorLogic.parseFeed(xml).also { if (it.isEmpty()) lastError = "No public videos found on the channel" }
    }

    /** Gemini video ko khud dekhta-sunta hai (YouTube link se). */
    fun check(v: YtVideo, now: Long, goal: String): YtReport? {
        val parts = JSONArray()
            .put(JSONObject().put("file_data", JSONObject().put("file_uri", v.url)))
            .put(JSONObject().put("text", YtDoctorLogic.videoPrompt(v, now, goal)))
        val reply = generate(parts, true) ?: return null
        return YtDoctorLogic.parseReport(reply).also { if (it == null) RunLog.add(context, "yt doctor bad reply: " + reply.take(200)) }
    }

    fun summary(prompt: String, images: List<ByteArray>): String? {
        val parts = JSONArray()
        images.forEach {
            parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(it, Base64.NO_WRAP))))
        }
        parts.put(JSONObject().put("text", prompt))
        return generate(parts, false)
    }

    private fun generate(parts: JSONArray, json: Boolean): String? {
        val config = JSONObject().put("temperature", 0.4).put("maxOutputTokens", 4096)
        if (json) config.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", config).toString()
        for (model in MODELS) {
            val request = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toRequestBody("application/json".toMediaType())).build()
            try {
                client.newCall(request).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        lastError = when (r.code) {
                            429 -> "Gemini free limit reached. Try after some time"
                            400 -> "Gemini could not open the video (it must be Public)"
                            403 -> "Gemini key problem"
                            else -> "Gemini error " + r.code
                        }
                        RunLog.add(context, "yt doctor $model ${r.code} ${text.take(150)}")
                        return@use null
                    }
                    val out = StringBuilder()
                    val p = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")
                    for (i in 0 until (p?.length() ?: 0)) {
                        val part = p?.optJSONObject(i) ?: continue
                        if (!part.optBoolean("thought")) out.append(part.optString("text"))
                    }
                    out.toString().ifBlank { null }
                }?.let { return it }
            } catch (e: IOException) {
                lastError = if (e is java.net.UnknownHostException) "No internet" else "Gemini took too long"
            } catch (e: org.json.JSONException) {
                lastError = "Strange reply from Gemini"
            }
        }
        return null
    }

    /** Studio ka screenshot chhota karke JPEG (Gemini ko bhejne layak). */
    fun shrink(uri: Uri): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        bmp?.let {
            val out = ByteArrayOutputStream()
            it.compress(Bitmap.CompressFormat.JPEG, 85, out)
            it.recycle()
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private val MODELS = listOf("gemini-2.5-flash", "gemini-flash-latest")
    }
}
