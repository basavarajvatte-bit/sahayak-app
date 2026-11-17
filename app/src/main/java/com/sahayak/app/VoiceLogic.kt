package com.sahayak.app

import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Apni awaaz ka clone: hisaab wala hissa. Isme Android ka kuch nahi, isliye test ho sakta hai. */
object VoiceLogic {
    const val FISH = "fish"
    const val ELEVEN = "eleven"

    /** Apna server: Fish ka open-source model (S2-Pro) Kaggle ke free GPU par. */
    const val OWN = "own"

    /** Notebook jo apna server chalu karta hai (Kaggle mein Import Notebook se kholo). */
    const val NOTEBOOK = "https://github.com/basavarajvatte-bit/sahayak-app/blob/main/server/voice_server.ipynb"

    const val RATE = 24_000
    const val MIN_SECONDS = 20
    const val MAX_SECONDS = 90

    /** Ek baar mein itne akshar tak bolwao, taaki credit jaldi khatm na hon. */
    const val TEXT_MAX = 500

    const val FISH_MODEL = "s2.1-pro-free"

    /** Fish free plan par clone save nahi hota; recording har baar saath jaati hai. */
    const val FISH_LOCAL = "local"
    const val ELEVEN_V2 = "eleven_multilingual_v2"
    const val ELEVEN_V3 = "eleven_v3"

    fun label(provider: String) = when (provider) {
        ELEVEN -> "ElevenLabs"
        OWN -> "your own server"
        else -> "Fish Audio"
    }

    /**
     * Notebook ek line deta hai: SAHAYAK https://xyz.trycloudflare.com PASSWORD
     * Usme se link aur password nikalo. Galat ho to null.
     */
    fun serverLine(line: String): Pair<String, String>? {
        val parts = line.trim().split(Regex("[\\s|]+")).filter { it.isNotEmpty() && !it.equals("SAHAYAK", true) && !it.equals("SAHAYAK:", true) }
        val link = parts.firstOrNull { it.startsWith("http", true) || it.contains(".") } ?: return null
        val url = serverUrl(link) ?: return null
        val pass = parts.firstOrNull { it != link }.orEmpty()
        return url to pass
    }

    /** Link ko saaf karo: https lagao, aakhri / aur /v1/... hatao. */
    fun serverUrl(raw: String): String? {
        var u = raw.trim().trimEnd('/')
        if (u.isEmpty() || u.contains(" ")) return null
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        u = u.replace(Regex("/v1(/.*)?$"), "").trimEnd('/')
        val host = u.substringAfter("://").substringBefore("/").substringBefore(":")
        if (!host.contains(".") && host != "localhost") return null
        return u
    }

    /** Record karte waqt ye padhna hai. Pehli line ijazat hai: awaaz sirf apni hi clone karni hai. */
    fun script(name: String): String {
        val who = name.trim().ifEmpty { "______" }
        return "Mera naam $who hai. Ye meri apni awaaz hai aur main ise clone karne ki ijazat deta hoon.\n\n" +
            "Aaj ka din bahut achha hai. Subah uthkar maine chai pi aur thodi der akhbaar padha. " +
            "Phir main kaam par nikal gaya. Raaste mein ek purana dost mila, usse do minute baat hui. " +
            "Usne poochha, sab theek hai na? Maine kaha haan, sab badhiya hai! " +
            "Kal mujhe bank jaana hai, bijli ka bill bharna hai aur ghar ke liye sabzi laani hai. " +
            "Ek, do, teen, chaar, paanch, chhah, saat, aath, nau, das. " +
            "Agar koi kaam ho to mujhe phone kar dena, main shaam ko chhah baje ke baad free rahunga. Dhanyavaad."
    }

    /** WAV file ka 44 byte ka sir: mono, 16-bit. */
    fun wavHeader(dataBytes: Int, rate: Int = RATE): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes).put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        return b.array()
    }

    /** Recording kitne second ki hai (file ke size se). */
    fun seconds(fileBytes: Long, rate: Int = RATE): Int = ((fileBytes - 44).coerceAtLeast(0) / (rate * 2L)).toInt()

    /** Clone ban gaya: service ne jo id di. */
    fun voiceId(provider: String, body: String): String = try {
        JSONObject(body).optString(if (provider == ELEVEN) "voice_id" else "_id")
    } catch (e: JSONException) {
        ""
    }

    /** Service ke jawab mein se galti ka sandesh nikalo. */
    fun serverMessage(body: String): String = try {
        val o = JSONObject(body)
        val detail = o.opt("detail")
        when {
            detail is JSONObject -> detail.optString("message").ifEmpty { detail.optString("status") }
            detail is String -> detail
            else -> o.optString("message")
        }.take(200)
    } catch (e: JSONException) {
        ""
    }

    /** Galti ko aasaan shabdon mein batao. */
    fun problem(provider: String, code: Int, body: String): String {
        val said = serverMessage(body)
        val base = when (code) {
            401 -> if (provider == OWN) "The server password is not right. Paste the line from the notebook again" else "The " + label(provider) + " key is not right, or this plan does not allow this"
            402 -> if (provider == ELEVEN) "ElevenLabs credits are finished" else "Fish Audio credits are finished, or this needs a paid plan"
            403 -> label(provider) + " did not allow this on your plan"
            413 -> "The recording is too big. Record a shorter one"
            422 -> label(provider) + " did not accept the recording or the text"
            429 -> "Too many requests. Wait a minute and try again"
            404, 502, 530 -> if (provider == OWN) "Your server is off. Run the notebook again and paste the new line" else if (code == 502) label(provider) + " is busy right now. Try again in a minute" else label(provider) + " answered $code"
            in 500..599 -> if (provider == OWN) "Your server had a problem making the voice. Look at the notebook for the error, or try a shorter text" else label(provider) + " is busy right now. Try again in a minute"
            else -> label(provider) + " answered $code"
        }
        return if (said.isEmpty()) base else "$base. It said: $said"
    }

    /** Recording mein jo padha tha, wahi likha hua (Fish ko saath bhejna hai). */
    fun spokenScript(name: String): String = script(name).replace(Regex("\\s+"), " ").trim()

    /** Fish ke liye MessagePack: {text, references:[{audio, text}], format:"mp3"}. */
    fun fishBody(text: String, audio: ByteArray, audioText: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun len(small: Int, b8: Int, b16: Int, b32: Int, n: Int, fixMax: Int) {
            when {
                small >= 0 && n <= fixMax -> out.write(small or n)
                b8 >= 0 && n <= 0xFF -> { out.write(b8); out.write(n) }
                n <= 0xFFFF -> { out.write(b16); out.write(n shr 8); out.write(n and 0xFF) }
                else -> { out.write(b32); out.write(n ushr 24); out.write((n shr 16) and 0xFF); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
            }
        }
        fun str(v: String) {
            val b = v.toByteArray(Charsets.UTF_8)
            len(0xA0, 0xD9, 0xDA, 0xDB, b.size, 31)
            out.write(b)
        }
        fun bin(v: ByteArray) {
            len(-1, 0xC4, 0xC5, 0xC6, v.size, -1)
            out.write(v)
        }
        out.write(0x83)
        str("text"); str(text)
        str("references"); out.write(0x91)
        out.write(0x82); str("audio"); bin(audio); str("text"); str(audioText)
        str("format"); str("mp3")
        return out.toByteArray()
    }

    /** Bolne ke liye likha hua saaf karo. */
    fun cleanText(text: String): String = text.trim().replace(Regex("\\s+"), " ").take(TEXT_MAX)
}
