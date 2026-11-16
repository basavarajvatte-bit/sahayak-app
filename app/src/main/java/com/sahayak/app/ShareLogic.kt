package com.sahayak.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Do phone ke beech to-do list milane ka hisaab. Isme Android ka kuch nahi, isliye test ho sakta hai. */
object ShareLogic {
    private const val LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val CODE_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    /** Ek message mein itne byte tak ke kaam (relay 4096 byte se bada message nahi leta). */
    const val CHUNK_BYTES = 2200

    /** Ho chuke ya hataye kaam itne din baad list se saaf. */
    const val KEEP_MS = 30L * 86_400_000L

    fun newCode(random: SecureRandom = SecureRandom()): String =
        (1..CODE_SIZE).map { LETTERS[random.nextInt(LETTERS.length)] }.joinToString("")

    /** Likha hua code saaf karo: bade akshar, bina dash aur space. Galat ho to khaali. */
    fun cleanCode(typed: String): String {
        val code = typed.uppercase().filter { it.isLetterOrDigit() }
        return if (code.length == CODE_SIZE && code.all { it in LETTERS }) code else ""
    }

    /** Dikhane ke liye: ABCD-EFGH-JKLM-NPQR. */
    fun showCode(code: String): String = code.chunked(4).joinToString("-")

    private fun sha(words: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(words.toByteArray(Charsets.UTF_8))

    /** Relay par is list ka pata. Code se banta hai, par isse code wapas nahi nikalta. */
    fun topic(code: String): String = "skt" + sha("sahayak-topic:$code").joinToString("") { "%02x".format(it) }.take(40)

    private fun key(code: String) = SecretKeySpec(sha("sahayak-key:$code"), "AES")

    /** Taala lagao: sirf jiske paas code hai wahi padh sakta hai. */
    fun lock(code: String, plain: String, random: SecureRandom = SecureRandom()): String {
        val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(code), GCMParameterSpec(TAG_BITS, iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** Taala kholo. Galat code ya bigda message ho to null. */
    fun unlock(code: String, locked: String): String? = try {
        val raw = Base64.getDecoder().decode(locked.trim())
        if (raw.size <= IV_SIZE) null else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(code), GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, IV_SIZE)))
            String(cipher.doFinal(raw.copyOfRange(IV_SIZE, raw.size)), Charsets.UTF_8)
        }
    } catch (e: java.security.GeneralSecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    data class Line(val id: String, val message: String)

    /** Relay ki ek line. Sirf asli message lautao; "open" aur "keepalive" par null. */
    fun parseLine(line: String): Line? = try {
        val json = JSONObject(line)
        if (json.optString("event") == "message") Line(json.optString("id"), json.optString("message")) else null
    } catch (e: JSONException) {
        null
    }

    data class Packet(val from: String, val type: String, val items: List<JSONObject>)

    fun packet(from: String, type: String, items: List<JSONObject> = emptyList()): String =
        JSONObject().put("v", 1).put("from", from).put("t", type).put("items", JSONArray(items)).toString()

    fun parsePacket(plain: String): Packet? = try {
        val json = JSONObject(plain)
        val array = json.optJSONArray("items") ?: JSONArray()
        val items = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.filter { it.optString("id").isNotEmpty() }
        if (json.optString("from").isEmpty()) null else Packet(json.optString("from"), json.optString("t"), items)
    } catch (e: JSONException) {
        null
    }

    /** Kaamon ko chhote tukdon mein baanto, taaki har message relay ki seema mein rahe. */
    fun chunks(items: List<JSONObject>, limit: Int = CHUNK_BYTES): List<List<JSONObject>> {
        val out = mutableListOf<MutableList<JSONObject>>()
        var size = 0
        items.forEach { item ->
            val bytes = item.toString().toByteArray(Charsets.UTF_8).size + 1
            if (out.isEmpty() || (size + bytes > limit && out.last().isNotEmpty())) {
                out.add(mutableListOf())
                size = 0
            }
            out.last().add(item)
            size += bytes
        }
        return out
    }

    private fun gone(item: JSONObject) = item.optBoolean("del") || item.optBoolean("done")

    /** Kya aaya hua kaam hamare wale se naya hai? Baad mein badla hua jeetta hai; barabar ho to "ho gaya / hataya" jeetta hai. */
    fun newer(incoming: JSONObject, mine: JSONObject): Boolean {
        val a = incoming.optLong("mod", 0L)
        val b = mine.optLong("mod", 0L)
        return a > b || (a == b && gone(incoming) && !gone(mine))
    }

    data class Merged(val items: List<JSONObject>, val added: List<JSONObject>, val finished: List<JSONObject>, val changed: Boolean)

    /**
     * Doosre phone se aaye kaam apni list mein milao.
     * added = naye kaam jo abhi baaki hain; finished = jo kaam doosre ne abhi "ho gaya" kiya.
     */
    fun merge(mine: List<JSONObject>, incoming: List<JSONObject>, now: Long): Merged {
        val byId = LinkedHashMap<String, JSONObject>()
        mine.forEach { byId[it.optString("id")] = it }
        val added = mutableListOf<JSONObject>()
        val finished = mutableListOf<JSONObject>()
        var changed = false
        incoming.forEach { item ->
            val id = item.optString("id")
            val old = byId[id]
            if (old == null) {
                // Bahut purana ho chuka kaam wapas mat lao
                if (gone(item) && now - item.optLong("mod", 0L) > KEEP_MS) return@forEach
                byId[id] = item
                changed = true
                if (!gone(item)) added.add(item)
            } else if (newer(item, old)) {
                byId[id] = item
                changed = true
                if (item.optBoolean("done") && !item.optBoolean("del") && !gone(old)) finished.add(item)
            }
        }
        return Merged(byId.values.toList(), added, finished, changed)
    }

    /** Purane ho chuke / hataye kaam saaf karo. */
    fun prune(items: List<JSONObject>, now: Long): List<JSONObject> =
        items.filter { !(gone(it) && now - it.optLong("mod", 0L) > KEEP_MS) }
}
