package com.sahayak.app

import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Apni awaaz ka clone: hisaab wala hissa. Isme Android ka kuch nahi, isliye test ho sakta hai. */
object VoiceLogic {
    const val FISH = "fish"
    const val ELEVEN = "eleven"

    const val RATE = 24_000
    const val MIN_SECONDS = 20
    const val MAX_SECONDS = 90

    /** Ek baar mein itne akshar tak bolwao, taaki credit jaldi khatm na hon. */
    const val TEXT_MAX = 500

    const val FISH_MODEL = "s2.1-pro-free"
    const val ELEVEN_V2 = "eleven_multilingual_v2"
    const val ELEVEN_V3 = "eleven_v3"

    fun label(provider: String) = if (provider == ELEVEN) "ElevenLabs" else "Fish Audio"

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
            401 -> "The " + label(provider) + " key is not right, or this plan does not allow this"
            402 -> if (provider == ELEVEN) "ElevenLabs credits are finished" else "Fish Audio credits are finished, or this needs a paid plan"
            403 -> label(provider) + " did not allow this on your plan"
            413 -> "The recording is too big. Record a shorter one"
            422 -> label(provider) + " did not accept the recording or the text"
            429 -> "Too many requests. Wait a minute and try again"
            in 500..599 -> label(provider) + " is busy right now. Try again in a minute"
            else -> label(provider) + " answered $code"
        }
        return if (said.isEmpty()) base else "$base. It said: $said"
    }

    /** Bolne ke liye likha hua saaf karo. */
    fun cleanText(text: String): String = text.trim().replace(Regex("\\s+"), " ").take(TEXT_MAX)
}
