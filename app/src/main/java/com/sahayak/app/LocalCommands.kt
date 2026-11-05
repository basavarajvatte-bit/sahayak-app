package com.sahayak.app

/** Chhote order AI ke bina turant pehchan leta hai. Na samjhe to order AI ko jata hai. */
object LocalCommands {
    sealed class Command {
        data class Open(val app: String) : Command()
        data class WhatsApp(val contact: String, val message: String) : Command()
        data class Quick(val action: String, val value: String = "", val thenPlay: Boolean = false) : Command()
    }

    private val END = "[.!?।]*$"
    private val WHATSAPP = Regex(
        "^(.+?)\\s+(?:ko|को|la|ला|to)\\s+(?:whatsapp|whats app|व्हाट्सएप|व्हाट्सअप|व्हॉट्सॲप|व्हॉट्सअॅप)" +
            "\\s*(?:par|pe|pr|पर|पे|वर|on)?\\s+(.+?)\\s+" +
            "(?:bhejo|bhej do|bhejna|send|भेजो|भेज दो|भेजना|पाठव|पाठवा)" + END,
        RegexOption.IGNORE_CASE
    )
    private val OPEN_AFTER = Regex("^(.+?)\\s+(?:kholo|khol do|open|खोलो|खोल दो|उघड|उघडा)$END", RegexOption.IGNORE_CASE)
    private val OPEN_BEFORE = Regex("^open\\s+(.+?)$END", RegexOption.IGNORE_CASE)
    private val COMPOUND = Regex("\\b(aur|and|phir|then)\\b|और|फिर|आणि", RegexOption.IGNORE_CASE)
    private val APP_NAMES = mapOf(
        "व्हाट्सएप" to "WhatsApp", "व्हाट्सअप" to "WhatsApp", "क्रोम" to "Chrome", "जीमेल" to "Gmail",
        "यूट्यूब" to "YouTube", "युट्यूब" to "YouTube", "कैमरा" to "Camera", "कॅमेरा" to "Camera",
        "इंस्टाग्राम" to "Instagram", "फेसबुक" to "Facebook", "मैप्स" to "Maps", "चैटजीपीटी" to "ChatGPT"
    )

    private val REPEAT = Regex(
        "^(?:phir se |fir se |dobara |wapas |please )?(.*?)\\s*" +
            "(?:dohrao|dohra do|dobara karo|fir se karo|phir se karo|wapas karo|repeat|repeat it|दोहराओ|दोहरा दो|दोबारा करो|फिर से करो|पुन्हा कर|पुन्हा करा)" + END,
        RegexOption.IGNORE_CASE
    )

    /** "dohrao" ya "Mausam dekhna dohrao": null matlab repeat order nahi. */
    fun repeatTarget(raw: String): String? =
        REPEAT.find(raw.trim().trim('"'))?.groupValues?.get(1)?.trim()?.removeSuffix("ko")?.trim()

    private val IC = RegexOption.IGNORE_CASE
    private val YOUTUBE = Regex("^(play|search for|search|find) (.+?) on youtube$", IC)
    private val WEB = Regex("^(?:google|search for|search the web for|search) (.+)$", IC)
    private val NAVIGATE = Regex("^(?:navigate to|directions to|take me to) (.+)$", IC)
    private val CALL = Regex("^(?:call|dial) (.+)$", IC)
    private val ALARM = Regex("^set (?:an )?alarm (?:for|at) (\\d{1,2})(?:[:.](\\d{2}))? ?(a\\.?m\\.?|p\\.?m\\.?)$", IC)
    private val TIMER = Regex("^set (?:a )?timer (?:for )?(\\d+) ?(second|seconds|minute|minutes|hour|hours)$", IC)
    private val SETTINGS = Regex("^open (wifi|wi-fi|bluetooth|battery|location|display|sound|mobile data|apps|app) settings$", IC)
    private val TORCH = Regex("^(?:turn )?(?:the )?(?:flashlight|torch) (on|off)$|^turn (on|off) (?:the )?(?:flashlight|torch)$", IC)
    private val VOLUME = Regex("^(?:turn )?(?:the )?volume (up|down)$", IC)
    private val POLITE = Regex("^(?:(?:hey |ok |okay )?sahayak[,.]? |please |can you |could you |would you )+", IC)
    private val PLEASE_END = Regex("(?:,? please)$", IC)
    private val STORE = Regex("^(?:search|find|search for) (.+?) on (play store|amazon|flipkart|google maps|maps|youtube music|spotify)$", IC)
    private val NEAR = Regex("^(?:find|show|search|where is)(?: the| a| an)? (?:nearest )?(.+?) (?:near me|nearby)$", IC)
    private val SMS = Regex("^(?:text|sms|send sms to|send a message to) (.+?) (?:saying|that|message) (.+)$", IC)
    private val EVENT = Regex("^(?:add|create) (?:a )?(?:calendar )?(?:event|meeting|appointment)(?: called| named| for)? (.+)$", IC)
    private val MATH = Regex("^(?:what is|what's|calculate) (\\d+(?:\\.\\d+)?) ?(plus|\\+|minus|-|times|x|\\*|into|multiplied by|divided by|/) ?(\\d+(?:\\.\\d+)?)$", IC)
    private val MEDIA = Regex("^(pause|resume|next|previous)(?: music| song| video)?$", IC)

    /** Android ke direct kaam: screen par tap ki zaroorat nahi. */
    private fun quick(text: String): Command.Quick? {
        val lower = text.lowercase()
        when (lower) {
            "take a photo", "take a picture", "take a selfie", "open camera" -> return Command.Quick("camera")
            "record a video", "record video", "take a video" -> return Command.Quick("video")
            "what time is it", "what's the time", "what is the time", "tell me the time" -> return Command.Quick("time")
            "what's the date", "what is the date", "what is today's date", "what day is it", "what is today" -> return Command.Quick("date")
            "battery level", "how much battery", "how much battery is left", "what is the battery level" -> return Command.Quick("battery")
            "show notifications", "open notifications" -> return Command.Quick("notifications")
            "open quick settings", "show quick settings" -> return Command.Quick("quick_settings")
            "show recent apps", "open recent apps", "recent apps" -> return Command.Quick("recents")
            "open power menu", "show power menu" -> return Command.Quick("power_menu")
            "silent mode", "turn on silent mode", "put phone on silent", "silent the phone" -> return Command.Quick("ringer_silent")
            "vibrate mode", "turn on vibrate mode", "put phone on vibrate" -> return Command.Quick("ringer_vibrate")
            "normal mode", "ring mode", "turn off silent mode", "ringer on" -> return Command.Quick("ringer_normal")
            "mute", "mute the phone", "mute volume" -> return Command.Quick("volume_mute")
            "unmute", "unmute the phone", "unmute volume" -> return Command.Quick("volume_unmute")
            "take a screenshot", "take screenshot", "screenshot" -> return Command.Quick("screenshot")
            "lock the phone", "lock phone", "lock the screen", "lock screen" -> return Command.Quick("lock")
            "go home", "home screen" -> return Command.Quick("home")
            "go back" -> return Command.Quick("back")
        }
        MATH.find(text)?.let {
            return Command.Quick("calculate", it.groupValues[1] + "|" + it.groupValues[2].lowercase() + "|" + it.groupValues[3])
        }
        STORE.find(text)?.let {
            val store = it.groupValues[2].lowercase().replace(" ", "_").replace("google_maps", "maps")
            return Command.Quick("search_$store", it.groupValues[1])
        }
        NEAR.find(text)?.let { return Command.Quick("maps_search", it.groupValues[1] + " near me") }
        SMS.find(text)?.let { return Command.Quick("sms", it.groupValues[1] + "|" + it.groupValues[2]) }
        EVENT.find(text)?.let { return Command.Quick("calendar", it.groupValues[1]) }
        YOUTUBE.find(text)?.let {
            return Command.Quick("youtube_search", it.groupValues[2], it.groupValues[1].equals("play", true))
        }
        NAVIGATE.find(text)?.let { return Command.Quick("navigate", it.groupValues[1]) }
        CALL.find(text)?.let { return Command.Quick("dial", it.groupValues[1]) }
        ALARM.find(text)?.let {
            var hour = it.groupValues[1].toInt()
            if (hour !in 1..12) return null
            val pm = it.groupValues[3].lowercase().startsWith("p")
            hour = hour % 12 + if (pm) 12 else 0
            return Command.Quick("alarm", hour.toString() + ":" + it.groupValues[2].ifEmpty { "00" })
        }
        TIMER.find(text)?.let {
            val unit = it.groupValues[2].lowercase()
            val scale = if (unit.startsWith("hour")) 3600 else if (unit.startsWith("min")) 60 else 1
            return Command.Quick("timer", (it.groupValues[1].toInt() * scale).toString())
        }
        SETTINGS.find(text)?.let { return Command.Quick("settings", it.groupValues[1].lowercase()) }
        TORCH.find(text)?.let {
            return Command.Quick("torch_" + (it.groupValues[1] + it.groupValues[2]).lowercase())
        }
        VOLUME.find(text)?.let { return Command.Quick("volume_" + it.groupValues[1].lowercase()) }
        MEDIA.find(text)?.let { return Command.Quick("media_" + it.groupValues[1].lowercase()) }
        WEB.find(text)?.let { return Command.Quick("web_search", it.groupValues[1]) }
        return null
    }

    fun parse(raw: String): Command? {
        val cleaned = raw.trim().trim('"').trimEnd('.', '!', '?', '।').trim()
        val text = PLEASE_END.replace(POLITE.replace(cleaned, ""), "").trim()
        if (text.isEmpty() || text.length > 120) return null
        if (!COMPOUND.containsMatchIn(text)) quick(text)?.let { return it }
        WHATSAPP.find(text)?.let {
            val message = it.groupValues[2].trim().trim('"')
            if (COMPOUND.containsMatchIn(it.groupValues[1])) return null
            return Command.WhatsApp(it.groupValues[1].trim(), message)
        }
        if (COMPOUND.containsMatchIn(text)) return null
        val app = (OPEN_AFTER.find(text) ?: OPEN_BEFORE.find(text))?.groupValues?.get(1)?.trim()
            ?.removePrefix("the ")?.removeSuffix(" app")?.trim() ?: return null
        if (app.split(Regex("\\s+")).size > 3) return null
        return Command.Open(APP_NAMES[app] ?: app)
    }
}
