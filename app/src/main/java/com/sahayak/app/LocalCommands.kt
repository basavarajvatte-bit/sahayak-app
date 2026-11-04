package com.sahayak.app

/** Chhote order AI ke bina turant pehchan leta hai. Na samjhe to order AI ko jata hai. */
object LocalCommands {
    sealed class Command {
        data class Open(val app: String) : Command()
        data class WhatsApp(val contact: String, val message: String) : Command()
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

    fun parse(raw: String): Command? {
        val text = raw.trim().trim('"')
        if (text.isEmpty() || text.length > 120) return null
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
