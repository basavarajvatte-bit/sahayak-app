package com.sahayak.app

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Call ke signal (ghanti, haan, kaato) ka hisaab. Isme Android ka kuch nahi, isliye test ho sakta hai. */
object CallLogic {
    /** Relay ek message mein 4096 byte hi leta hai, isliye lamba signal tukdon mein jaata hai. */
    const val PART = 1500

    /** Isse purana signal purani call ka hai (phone der se net par aaya), use mat bajao. */
    const val FRESH_MS = 60_000L

    data class Signal(val call: String, val kind: String, val video: Boolean, val body: String, val at: Long)

    /** Chhota karo (gzip) aur akshar bana do. */
    fun pack(text: String): String {
        if (text.isEmpty()) return ""
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    fun unpack(data: String): String? {
        if (data.isEmpty()) return ""
        return try {
            GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(data))).use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: java.io.IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Ek signal ke tukde, bhejne ke liye. */
    fun parts(call: String, kind: String, video: Boolean, body: String, at: Long, size: Int = PART): List<JSONObject> {
        val packed = pack(body)
        val pieces = if (packed.isEmpty()) listOf("") else packed.chunked(size)
        return pieces.mapIndexed { i, d ->
            JSONObject().put("id", "$call:$kind:$i").put("call", call).put("k", kind).put("i", i).put("n", pieces.size)
                .put("d", d).put("v", video).put("at", at)
        }
    }

    /** Aaye tukde jodta hai. Saare tukde aa jayein tab poora signal lautata hai. */
    class Joiner {
        private val waiting = HashMap<String, Array<String?>>()

        @Synchronized
        fun add(item: JSONObject): Signal? {
            val call = item.optString("call")
            val kind = item.optString("k")
            val n = item.optInt("n", 1)
            val i = item.optInt("i", 0)
            if (call.isEmpty() || kind.isEmpty() || n < 1 || n > MAX_PARTS || i < 0 || i >= n) return null
            val key = "$call:$kind"
            val have = waiting[key]?.takeIf { it.size == n } ?: arrayOfNulls<String>(n).also { waiting[key] = it }
            have[i] = item.optString("d")
            if (have.any { it == null }) return null
            waiting.remove(key)
            if (waiting.size > MAX_WAITING) waiting.clear()
            val body = unpack(have.joinToString("")) ?: return null
            return Signal(call, kind, item.optBoolean("v"), body, item.optLong("at"))
        }
    }

    fun fresh(at: Long, now: Long) = Math.abs(now - at) <= FRESH_MS

    /** Call kitni der se chal rahi hai: 1:05. */
    fun clock(seconds: Long): String = (seconds / 60).toString() + ":" + String.format(java.util.Locale.ENGLISH, "%02d", seconds % 60)

    /** Muft relay server (TURN) ka thodi der ka naam: khatm hone ka samay. */
    fun turnUser(nowSeconds: Long, ttlSeconds: Long = 86_400L): String = (nowSeconds + ttlSeconds).toString()

    /** Us naam ka password: HMAC-SHA1(secret, naam), base64. Ye TURN ka aam tareeka hai. */
    fun turnPass(secret: String, user: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(user.toByteArray(Charsets.UTF_8)))
    }

    private const val MAX_PARTS = 40
    private const val MAX_WAITING = 20
}
