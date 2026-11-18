package com.sahayak.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Teen (ya zyada) Gemini models ki line: har model ki free limit alag hoti hai.
 * Ek ki limit khatam (429) hote hi agla model apne aap kaam pe lagta hai.
 */
object GeminiModels {
    val FALLBACK = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-flash-latest", "gemini-flash-lite-latest")
    private val SKIP = Regex("(?i)(image|tts|audio|live|embedding|aqa|learnlm|gemma|computer-use|robotics|veo|imagen|nano|banana|search)")
    private const val CACHE_MS = 6 * 3600 * 1000L
    private const val MAX = 4

    @Volatile private var cached: List<String> = emptyList()
    @Volatile private var cachedAt = 0L

    /** Kaun pehle: flash > flash-lite > pro; naya version pehle; stable preview se pehle. */
    fun rank(names: List<String>): List<String> {
        val ok = names.map { it.removePrefix("models/") }.distinct()
            .filter { it.startsWith("gemini-") && !SKIP.containsMatchIn(it) }
            .filter { it.contains("flash") || it.contains("pro") }
        fun family(n: String) = when {
            n.contains("flash-lite") -> 1
            n.contains("flash") -> 0
            else -> 2
        }
        fun version(n: String) = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        fun shaky(n: String) = if (Regex("(?i)(preview|exp|latest|\\d{2}-\\d{2})").containsMatchIn(n)) 1 else 0
        val sorted = ok.sortedWith(compareBy<String>({ family(it) }, { -version(it) }, { shaky(it) }, { it.length }))
        // "-latest" sirf doosre model ka naam hai (alag limit nahi), isliye tabhi lo jab aur kuch na ho
        val real = sorted.filter { !it.endsWith("-latest") }
        return (if (real.size >= 2) real else sorted).take(MAX)
    }

    /** Is key ke liye models ki line (6 ghante yaad rakhta hai). */
    fun forKey(apiKey: String, client: OkHttpClient): List<String> {
        if (cached.isNotEmpty() && System.currentTimeMillis() - cachedAt < CACHE_MS) return cached
        val found = try {
            val request = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
                .header("x-goog-api-key", apiKey).build()
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("models") ?: return@use emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val m = arr.optJSONObject(i) ?: return@mapNotNull null
                    val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                    if (methods.contains("generateContent")) m.optString("name") else null
                }
            }
        } catch (e: IOException) {
            emptyList()
        } catch (e: org.json.JSONException) {
            emptyList()
        }
        val ranked = rank(found)
        cached = if (ranked.size >= 2) ranked else FALLBACK
        cachedAt = System.currentTimeMillis()
        return cached
    }

    /** Model ka chhota naam dikhane ke liye: "gemini-2.5-flash-lite" → "2.5 Flash Lite". */
    fun label(model: String) = model.removePrefix("gemini-").split("-")
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
}
