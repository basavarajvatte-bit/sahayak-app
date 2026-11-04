package com.sahayak.app

import android.util.Base64
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
        val parts = JSONArray().put(JSONObject().put("text", "Open app: $app\nOrder: $order"))
        return ask(RULES, parts, 0.3, 1500)
    }

    /** Doosra model kaam jaanchta hai. DONE ya agla sudhar deta hai. */
    fun review(order: String, steps: String, screen: ByteArray?, stuck: Boolean): String? {
        val note = if (stuck) "The worker seems stuck in a loop." else "The worker says it has finished."
        val parts = JSONArray().put(JSONObject().put("text", "Order: $order\n$note\nSteps taken:\n$steps"))
        if (screen != null) {
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg")
                .put("data", Base64.encodeToString(screen, Base64.NO_WRAP))))
        }
        return ask(REVIEW_RULES, parts, 0.1, 300)
    }

    private fun ask(system: String, parts: JSONArray, temperature: Double, maxTokens: Int): String? {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", temperature).put("maxOutputTokens", maxTokens))
            .toString().toRequestBody("application/json".toMediaType())
        for (model in MODELS) {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey).post(body).build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val reply = JSONObject(response.body?.string().orEmpty()).optJSONArray("candidates")
                            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        val text = StringBuilder()
                        for (i in 0 until (reply?.length() ?: 0)) text.append(reply?.optJSONObject(i)?.optString("text"))
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
        private const val REVIEW_RULES = """You check the work of an AI agent on an Android phone. You see the order, the steps it took and the current screen.

Reply with exactly one line:
DONE, if the order is fully finished or the agent correctly stopped at a payment, password, PIN or OTP screen, or correctly asked the person for missing details.
FIX: one short specific next action, if something is unfinished or wrong, for example a message typed but not sent.

Never suggest paying, entering a password, PIN or OTP, or inventing an address or number. If unsure, reply DONE."""

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
