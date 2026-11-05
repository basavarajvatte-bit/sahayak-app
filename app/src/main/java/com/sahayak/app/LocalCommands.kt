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
    private val MEDIA = Regex("^(pause|resume|next|previous)(?: music| song| video)?$", IC)

    /** Android ke direct kaam: screen par tap ki zaroorat nahi. */
    private fun quick(text: String): Command.Quick? {
        val lower = text.lowercase()
        when (lower) {
            "take a screenshot", "take screenshot", "screenshot" -> return Command.Quick("screenshot")
            "lock the phone", "lock phone", "lock the screen", "lock screen" -> return Command.Quick("lock")
            "go home", "home screen" -> return Command.Quick("home")
            "go back" -> return Command.Quick("back")
        }
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
        val text = raw.trim().trim('"').trimEnd('.', '!', '?', '।').trim()
        if (text.isEmpty() || text.length > 120) return null
        if (!COMPOUND.containsMatchIn(text)) quick(text)?.let { return it }
        WHATSAPP.find(text)?.let {
            val message = it.groupValues[2].trim().trim('"')
            if (COMPOUND.containsMatchIn(it.groupValues[1])) return null
            return Command.WhatsApp(it.groupValues[1].trim(), message)
        }
        if (COMPOUND.containsMatchIn(text)) return null
        val app = (OPEN_AFTER.find(text) ?: OPEN_BEFORE.find(text))?.groupValues?.get(1)?.trim() ?: return null
        if (app.split(Regex("\\s+")).size > 3) return null
        return Command.Open(APP_NAMES[app] ?: app)
    }
}
