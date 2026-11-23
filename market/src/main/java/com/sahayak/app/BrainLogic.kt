package com.sahayak.app

/** Yaadasht ke seedhe niyam: secret hatana aur milte-julte kaam pehchanna. */
object BrainLogic {
    private val SECRET_WORDS = listOf("password", "passcode", "otp", "cvv", "upi pin", "card number", "पासवर्ड", "ओटीपी")
    private val LONG_NUMBER = Regex("\\d{6,}")
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val COMMON = setOf(
        "a", "an", "the", "to", "in", "on", "of", "for", "and", "or", "my", "me", "is", "it", "this", "that",
        "please", "can", "you", "do", "with", "at", "from", "ko", "ka", "ki", "ke", "se", "me", "par", "aur"
    )
    private const val MIN_SHARED = 2

    /** Secret wali line kabhi save nahi hoti, lambe number chhup jate hain. */
    fun scrub(text: String, limit: Int): String? {
        val lower = text.lowercase()
        if (text.isBlank() || SECRET_WORDS.any { it in lower }) return null
        return LONG_NUMBER.replace(text, "[number]").trim().take(limit)
    }

    fun tokens(text: String): List<String> =
        WORD.findAll(text.lowercase()).map { it.value }.filter { it !in COMMON && it.length > 1 }.distinct().toList()

    /** Kitne khaas shabd milte hain. Do se kam ho to 0. */
    fun overlap(order: String, topic: String): Int {
        val wanted = tokens(order).toSet()
        val shared = tokens(topic).count { it in wanted }
        return if (shared >= MIN_SHARED) shared else 0
    }

    fun cleanJson(answer: String): String =
        answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}
