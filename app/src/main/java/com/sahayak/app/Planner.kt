package com.sahayak.app

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Sahayak ka sochne wala dimag: kaam se pehle poora plan aur likhne wala content taiyar karta hai. */
class Planner(http: OkHttpClient, private val apiKey: String, timeoutSeconds: Long = TIMEOUT_SECONDS) {
    private val client = http.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()

    /** Plan na ban paye to null. Tab order seedha chalega. */
    fun plan(order: String, app: String, facts: List<String>): String? {
        val parts = JSONArray().put(JSONObject().put("text",
            "Known facts: " + facts.joinToString("; ") + "\nOpen app: $app\nOrder: $order"))
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

    /** Din ke orders se aadatein nikalta hai. */
    fun learn(facts: List<String>, journal: List<String>): JSONObject? {
        val text = "Current memory:\n" + facts.joinToString("\n") + "\n\nToday's activity:\n" + journal.joinToString("\n")
        val answer = ask(LEARN_RULES, JSONArray().put(JSONObject().put("text", text)), 0.1, 900) ?: return null
        val clean = answer.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            JSONObject(clean)
        } catch (e: JSONException) {
            null
        }
    }

    /** Ek ya kai demos se saaf, general kaam banata hai. */
    fun digest(name: String, demos: String, lessons: List<String>): JSONObject? {
        val text = "Task name: $name\nDemonstrations: $demos\nLessons: " + lessons.joinToString("; ")
        val answer = ask(DIGEST_RULES, JSONArray().put(JSONObject().put("text", text)), 0.1, 1200) ?: return null
        val clean = answer.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            JSONObject(clean).takeIf { (it.optJSONArray("steps")?.length() ?: 0) > 0 }
        } catch (e: JSONException) {
            null
        }
    }

    /** Practice ke baad steps ko chhota aur pakka banata hai. */
    fun improve(name: String, steps: List<String>, used: String): List<String>? {
        val text = "Task: $name\nCurrent steps:\n" + steps.joinToString("\n") + "\nSteps that just worked:\n$used"
        val answer = ask(IMPROVE_RULES, JSONArray().put(JSONObject().put("text", text)), 0.1, 700) ?: return null
        val lines = answer.lines().map { it.trim().trimStart('-', ' ') }.filter { it.isNotEmpty() }
        return lines.takeIf { it.isNotEmpty() && it.size <= 40 }
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
        private const val LEARN_RULES = """You maintain the memory of a personal phone assistant. Read today's activity and the current memory. Reply with JSON only:
{"add":["short fact"],"remove":["exact old fact"],"summary":"one simple Hinglish sentence on what was learned today"}

Keep only practical habits that help future orders: preferred apps, who they usually message and how, usual times, preferred language, reply length, repeated tasks, and corrections.
Rules:
- Add a habit only if it appears at least twice, or the person clearly stated it.
- State it as what they do, never a judgement of their personality.
- Remove facts that are outdated, duplicates or contradicted. Merge similar ones.
- Never save passwords, PINs, OTPs, card or bank numbers, or health, religion, politics, money, relationships or other sensitive matters.
- If nothing is worth saving, use empty lists."""

        private const val DIGEST_RULES = """You turn phone task demonstrations into a reliable reusable procedure. Reply with JSON only:
{"goal":"one sentence on what the task achieves","steps":["step one","step two"]}

Rules:
- Compare all demonstrations and keep what they share. Remove mistakes, repeated taps and wandering.
- Replace details that change each time with placeholders such as {contact}, {message}, {date} or {search_text}. Keep fixed things like app names and button words exact.
- Use short steps: open app X, tap "words", type "{message}", press enter, wait. Prefer visible words over screen positions.
- Keep "screen shows" notes as check steps where useful, so the agent can confirm it is on the right screen.
- Apply the lessons.
- At most 25 steps. Never include a password, PIN, OTP, card number or payment step."""

        private const val IMPROVE_RULES = """You improve a saved phone task after a successful practice run.
Write the shortest reliable step list for the same goal, one step per line, with no numbers and no explanation.
Use the same style: open app X, tap "words", type "text", press enter, wait. Prefer visible words over screen positions.
Keep every detail of the goal unchanged. If there is no real improvement, return the current steps unchanged.
Never include a password, PIN, OTP or card number, and never include a payment step."""

        private const val REVIEW_RULES = """You check the work of an AI agent on an Android phone. You see the order, the steps it took and the current screen.

Reply with exactly one line:
DONE, if the order is fully finished, or a future job was saved with schedule_work, or the agent correctly stopped at a payment, password, PIN or OTP screen, or correctly asked the person for missing details.
FIX: one short specific next action, if something is unfinished or wrong, for example a message typed but not sent.

Never suggest paying, entering a password, PIN or OTP, or inventing an address or number. If unsure, reply DONE."""

        private const val RULES = """You plan work for an AI agent that operates an Android phone. Think like a smart, practical person who avoids wasted movement.

First work out the true goal, what is known and missing, and the likely options with their risks and costs. Pick the most effective one, then check: is this sound, what could go wrong, is there a simpler way? Prefer accuracy over speed when a mistake would be costly. Do not show this reasoning.

Write only this format, in English, under 12 lines:
CONTENT: any text the order needs written, complete and ready to use. Write it yourself. Use the language of the order. Omit this line if nothing needs writing.
STEPS: the fewest numbered steps, each one action.
MISSING: exact details that are missing, or none.
RISK: one short warning only if the order seems mistaken or could cause real loss, otherwise omit.

Rules:
- Never use ChatGPT or any other AI app to write something unless the order explicitly names that app. You write it in CONTENT.
- If the order names an app for writing, use it only once: send the prompt, wait, read the whole reply once with read_full_text, then leave it for good.
- Never go back to an app after its step is done. Every app is opened at most once unless truly required.
- If a Known fact is a Taught task that matches the order, use its steps as the plan and only change the details.
- Use compose_email for email and open_whatsapp_chat for WhatsApp messages.
- Never make up an email address, phone number or name. A company name is not an address, so list it under MISSING.
- If the order is for a future time, the only step is schedule_work now. Unclear time such as "12 baje" without day or night goes under MISSING.
- If something is MISSING, the agent must ask first before doing any steps.
- The agent never performs a payment or enters a password, PIN or OTP."""
    }
}
