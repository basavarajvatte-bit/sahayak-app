package com.sahayak.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Sahayak ka sochne wala dimag: kaam se pehle poora plan aur likhne wala content taiyar karta hai. */
class Planner(http: OkHttpClient, private val apiKey: String) {
    private val client = http.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    /** Plan na ban paye to null. Tab order seedha chalega. */
    fun plan(order: String, app: String): String? {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", RULES))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts",
                JSONArray().put(JSONObject().put("text", "Open app: $app\nOrder: $order")))))
            .put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 1500))
            .toString().toRequestBody("application/json".toMediaType())
        for (model in MODELS) {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey).post(body).build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val parts = JSONObject(response.body?.string().orEmpty()).optJSONArray("candidates")
                            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        val text = StringBuilder()
                        for (i in 0 until (parts?.length() ?: 0)) text.append(parts?.optJSONObject(i)?.optString("text"))
                        if (text.isNotBlank()) return text.toString().trim()
                    }
                }
            } catch (e: IOException) {
                return null
            }
        }
        return null
    }

    companion object {
        private const val TIMEOUT_SECONDS = 6L
        private val MODELS = listOf("gemini-flash-latest", "gemini-2.5-flash")
        private const val RULES = """You plan work for an AI agent that operates an Android phone. Think like a smart, practical person who avoids wasted movement.

Write only this format, in English, under 12 lines:
CONTENT: any text the order needs written, complete and ready to use. Write it yourself. Use the language of the order. Omit this line if nothing needs writing.
STEPS: the fewest numbered steps, each one action.
MISSING: exact details that are missing, or none.

Rules:
- Never use ChatGPT or any other AI app to write something unless the order explicitly names that app. You write it in CONTENT.
- If the order names an app for writing, use it only once: send the prompt, wait, read the whole reply once with read_full_text, then leave it for good.
- Never go back to an app after its step is done. Every app is opened at most once unless truly required.
- Use compose_email for email and open_whatsapp_chat for WhatsApp messages.
- Never make up an email address, phone number or name. A company name is not an address, so list it under MISSING.
- If something is MISSING, the agent must ask first before doing any steps.
- The agent never performs a payment or enters a password, PIN or OTP."""
    }
}
