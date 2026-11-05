package com.sahayak.app

/** Chhote order AI ke bina turant pehchan leta hai. Na samjhe to order AI ko jata hai. */
object LocalCommands {
    sealed class Command {
        data class Open(val app: String) : Command()
        data class WhatsApp(val contact: String, val message: String) : Command()
        data class Quick(val action: String, val value: String = "", val extra: String = "") : Command()
    }

    private val END = "[.!?।]*$"
    private val WHATSAPP = Regex(
        "^(.+?)\\s+(?:ko|को|la|ला|to)\\s+(?:whatsapp|whats app|व्हाट्सएप|व्हाट्सअप|व्हॉट्सॲप|व्हॉट्सअॅप)" +
            "\\s*(?:par|pe|pr|पर|पे|वर|on)?\\s+(.+?)\\s+" +
            "(?:bhejo|bhej do|bhejna|send|भेजो|भेज दो|भेजना|पाठव|पाठवा)" + END,
        RegexOption.IGNORE_CASE
    )
    private val OPEN_AFTER = Regex("^(.+?)\\s+(?:kholo|khol do|open|खोलो|खोल दो|उघड|उघडा)$END", RegexOption.IGNORE_CASE)
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
    private val POLITE = Regex("^(?:(?:hey |ok |okay )?sahayak[,.]? |please |can you |could you |would you |i want to |i want you to )+", IC)
    private val PLEASE_END = Regex("(?:,? please)$", IC)

    fun parse(raw: String): Command? {
        val cleaned = raw.trim().trim('"').trimEnd('.', '!', '?', '।').trim()
        val text = PLEASE_END.replace(POLITE.replace(cleaned, ""), "").trim()
        if (text.isEmpty() || text.length > 120) return null
        if (!COMPOUND.containsMatchIn(text)) {
            ShortcutBook.match(text)?.let { hit ->
                return Command.Quick(hit.action, hit.value, hit.extra)
            }
        }
        WHATSAPP.find(text)?.let {
            val message = it.groupValues[2].trim().trim('"')
            if (COMPOUND.containsMatchIn(it.groupValues[1])) return null
            return Command.WhatsApp(it.groupValues[1].trim(), message)
        }
        if (COMPOUND.containsMatchIn(text)) return null
        val app = OPEN_AFTER.find(text)?.groupValues?.get(1)?.trim()
            ?.removePrefix("the ")?.removeSuffix(" app")?.trim() ?: return null
        if (app.split(Regex("\\s+")).size > 3) return null
        return Command.Open(APP_NAMES[app] ?: app)
    }
}
