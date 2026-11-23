package com.sahayak.app

/**
 * Ek order ka poora hisaab: kaunse step ho gaye, kaunse fail, screen kya thi,
 * kitni baar sudhar try hua. Isse Sahayak chakkar nahi kaatta aur shuru se dobara nahi karta.
 * Android ke bina chalne wala logic, taaki unit test ho sake.
 */
class TaskTracker(
    val order: String,
    private val startedAt: Long,
    private val limits: Limits = Limits(),
) {
    data class Limits(
        val maxSteps: Int = 100,
        val sameTry: Int = 2,          // ek hi screen par ek hi kaam itni baar bekaar ho to band
        val noProgressSteps: Int = 7,  // itne kaam bina nayi screen ke = atka hua
        val maxRecoveries: Int = 5,    // itni baar sudhar ke baad insaan se madad
        val maxWaits: Int = 4,         // loading par itni baar wait
        val timeoutMs: Long = 10 * 60_000L,
    )

    enum class Verdict { OK, NO_CHANGE, FAILED, REPEAT, BOUNCE, NO_PROGRESS, GIVE_UP }

    enum class Situation(val advice: String) {
        PERMISSION("A permission popup is open. Tap Allow or While using the app, then continue the same step."),
        POPUP("A popup or ad is covering the screen. Close it with its Skip, Close, Not now or No thanks button, then continue the same step."),
        LOGIN("A sign-in screen is open. Do not type any password. Stop and ask the person to sign in, then continue."),
        CAPTCHA("A CAPTCHA or robot check is open. Stop and ask the person to solve it, then continue."),
        NETWORK("The app shows a network error. Use wait for 5 seconds, then tap Retry or Try again once. If it still fails, tell the person the internet is not working."),
        NOT_RESPONDING("The app is not responding. Tap Wait once. If it appears again, open the app again with open_app and continue from the unfinished step."),
        LOADING("The screen is still loading. Use wait for 2 or 3 seconds instead of tapping again."),
        NOT_FOUND("The search found nothing. Change the search words (shorter or spelled differently) and search again."),
        HOME_SCREEN("You are on the home screen, so the app closed. Open it again with open_app and continue from the unfinished step only."),
    }

    data class Feedback(
        val verdict: Verdict,
        val situation: Situation?,
        val advice: String?,
        val askPerson: Boolean,
    )

    private data class Step(val action: String, val target: String, val ok: Boolean, val changed: Boolean)

    private val steps = ArrayList<Step>()
    private val tries = HashMap<String, Int>()
    private val deadEnds = LinkedHashSet<String>()
    private val seenScreens = HashSet<String>()
    private val screenTrail = ArrayDeque<String>()
    private var lastScreen = ""
    private var sinceNewScreen = 0
    private var waits = 0
    var recoveries = 0
        private set
    var sendTaps = 0
        private set
    private var lastSendUncertain = false
    var finished = false
        private set

    val stepCount: Int get() = steps.size

    /** Ek tool call ke baad: kya hua, aur aage kya karna chahiye. */
    fun record(
        action: String,
        target: String,
        ok: Boolean,
        changed: Boolean?,
        screen: String,
        labels: List<String>,
        app: String,
        now: Long,
    ): Feedback {
        val before = lastScreen
        val acting = action in ACTING
        val key = action + "|" + target.lowercase().trim() + "|" + before
        val count = (tries[key] ?: 0) + 1
        tries[key] = count
        val moved = changed ?: (screen != before)
        steps.add(Step(action, target, ok, moved))

        val isNew = screen.isNotEmpty() && seenScreens.add(screen)
        if (isNew) sinceNewScreen = 0 else sinceNewScreen++
        if (screen.isNotEmpty() && screen != lastScreen) {
            screenTrail.addLast(screen)
            if (screenTrail.size > 6) screenTrail.removeFirst()
        }
        lastScreen = screen
        if (action == "wait") waits++ else if (isNew) waits = 0

        val situation = detect(labels, app)
        var advice: String? = null
        val sending = isSend(action, target, app)
        if (sending) {
            sendTaps++
            lastSendUncertain = !moved
        }

        var verdict = when {
            acting && !ok -> Verdict.FAILED
            acting && !moved -> Verdict.NO_CHANGE
            else -> Verdict.OK
        }
        if (verdict != Verdict.OK) deadEnds.add(describe(action, target))

        val bouncing = screenTrail.size >= 4 && screenTrail.toList().takeLast(4).let {
            it[0] == it[2] && it[1] == it[3] && it[0] != it[1]
        }
        when {
            action == "wait" && waits <= limits.maxWaits -> Unit
            count > limits.sameTry && (verdict != Verdict.OK || !acting) -> verdict = Verdict.REPEAT
            bouncing -> verdict = Verdict.BOUNCE
            sinceNewScreen >= limits.noProgressSteps && !(action == "wait" && waits <= limits.maxWaits) -> verdict = Verdict.NO_PROGRESS
        }

        if (verdict != Verdict.OK) recoveries++
        val tooLong = now - startedAt > limits.timeoutMs
        if (steps.size >= limits.maxSteps || recoveries > limits.maxRecoveries || tooLong) {
            verdict = Verdict.GIVE_UP
        }
        if (situation == Situation.LOGIN || situation == Situation.CAPTCHA) {
            return Feedback(verdict, situation, situation.advice, true)
        }

        advice = when (verdict) {
            Verdict.OK -> when {
                sending && sendTaps > 1 -> "A send button was already tapped in this order. Before sending again, check the chat: if the message is already there, do not send it again."
                situation in ACT_NOW -> situation?.advice
                else -> null
            }
            Verdict.FAILED, Verdict.NO_CHANGE -> situation?.advice ?: nextWay(action, target, verdict)
            Verdict.REPEAT, Verdict.BOUNCE, Verdict.NO_PROGRESS ->
                "You are repeating yourself. Stop doing: " + deadEnds.toList().takeLast(3).joinToString("; ") +
                    ". " + (situation?.advice ?: "Take a different route: go_back once, use search, open_url, quick_action, or another button.") +
                    " Do not redo finished steps."
            Verdict.GIVE_UP -> null
        }
        if (sending && lastSendUncertain && verdict != Verdict.GIVE_UP) {
            advice = "It is not certain the message was sent. Look at the chat first: if the message shows, it is sent. Never send it twice."
        }
        return Feedback(verdict, situation, advice, verdict == Verdict.GIVE_UP)
    }

    /** Agla tarika, jab ek tarika kaam na kare. Pehle text, phir element, phir position, phir doosra raasta. */
    private fun nextWay(action: String, target: String, verdict: Verdict): String {
        val previous = steps.dropLast(1).lastOrNull()
        val afterTyping = previous?.action == "type_text"
        return when {
            afterTyping && action in TAPS ->
                "The keyboard may be covering the button. Use press_enter, or go_back once to hide the keyboard, then tap again."
            action == "tap_text" && verdict == Verdict.FAILED ->
                "No text \"$target\" on screen. Read the screen list and use tap with the element number, or tap_position from the image, or scroll to find it."
            action == "tap_text" || action == "tap" ->
                "The tap did nothing. If the screen is loading, use wait. Otherwise use tap_position on the item from the image, or scroll a little and tap again."
            action == "tap_position" ->
                "The position tap did nothing. Try the item's text with tap_text, or go_back once and take another route."
            action == "type_text" ->
                "Typing failed. Tap the text box first, then type again."
            action == "open_app" ->
                "The app did not open. Try quick_action or open_url for the same thing, or check the app name."
            action == "scroll" ->
                "Scrolling did not move the page. You are at the end. Use what is on screen or search instead."
            else -> "That did not work. Try a different way to do the same step."
        }
    }

    /** Insaan ko seedhi baat: kya hua, kya ho gaya, kya try kiya, unhe kya karna hai. */
    fun helpMessage(situation: Situation?): String {
        val done = steps.filter { it.ok && it.changed && it.action in ACTING }.map { describe(it.action, it.target) }.distinct().takeLast(5)
        val tried = deadEnds.toList().takeLast(4)
        val need = when (situation) {
            Situation.LOGIN -> "please sign in yourself, then tell me to continue"
            Situation.CAPTCHA -> "please solve the robot check, then tell me to continue"
            Situation.NETWORK -> "please check the internet, then tell me to continue"
            else -> "please do the next step yourself or give me a different instruction"
        }
        return "I could not finish this. Done so far: " + (if (done.isEmpty()) "nothing yet" else done.joinToString(", ")) +
            ". I tried: " + (if (tried.isEmpty()) "a few ways" else tried.joinToString(", ")) + ". Now " + need + "."
    }

    /** Ab tak kya ho chuka, AI ko yaad dilane ke liye. */
    fun doneSoFar(): String = steps.filter { it.ok && it.changed && it.action in ACTING }
        .map { describe(it.action, it.target) }.distinct().takeLast(6).joinToString("; ")

    fun finish() { finished = true }

    companion object {
        private val ACTING = setOf("tap_text", "tap", "tap_position", "type_text", "press_enter", "scroll",
            "open_app", "open_url", "quick_action", "go_back", "compose_email", "open_whatsapp_chat")
        private val ACT_NOW = setOf(Situation.PERMISSION, Situation.POPUP, Situation.NETWORK,
            Situation.NOT_RESPONDING, Situation.NOT_FOUND)
        private val TAPS = setOf("tap_text", "tap", "tap_position")
        private val MESSAGING = listOf("whatsapp", "messaging", "mms", "telegram", "gmail", "android.gm")

        fun describe(action: String, target: String): String =
            if (target.isBlank()) action else action + " \"" + target.take(30) + "\""

        /** Screen ki pehchaan: app + dikhne wale shabd. Ghadi jaise badalte number hata diye. */
        fun signature(app: String, labels: List<String>): String {
            val words = labels.map { it.lowercase().replace(Regex("[0-9]"), "").trim() }.filter { it.isNotEmpty() }
            return app + "#" + words.joinToString("|").hashCode().toString(16)
        }

        fun isSend(action: String, target: String, app: String): Boolean {
            val t = target.lowercase().trim()
            val inChat = MESSAGING.any { it in app.lowercase() }
            return (action in TAPS && (t == "send" || t.startsWith("send ") || t == "bhejo")) ||
                (action == "press_enter" && inChat && "whatsapp" in app.lowercase())
        }

        fun detect(labels: List<String>, app: String): Situation? {
            val all = labels.map { it.lowercase().trim() }
            val text = all.joinToString(" | ")
            fun has(vararg words: String) = words.any { w -> all.any { it == w } }
            fun says(vararg words: String) = words.any { it in text }
            return when {
                says("not a robot", "captcha", "verify you are human") -> Situation.CAPTCHA
                says("enter your password", "sign in to continue", "log in to continue", "login to continue", "verify it's you") -> Situation.LOGIN
                says("isn't responding", "is not responding", "keeps stopping") -> Situation.NOT_RESPONDING
                says("no internet", "you're offline", "you are offline", "no connection", "check your connection",
                    "network error", "connection failed", "unable to connect") -> Situation.NETWORK
                has("allow", "while using the app", "only this time") && has("don't allow", "deny", "not now", "only this time") -> Situation.PERMISSION
                has("skip ad", "skip ads", "no thanks", "not now", "maybe later", "dismiss", "got it") -> Situation.POPUP
                says("no results", "no matches", "couldn't find", "could not find", "0 results", "nothing found") -> Situation.NOT_FOUND
                "launcher" in app.lowercase() || app.endsWith(".home") -> Situation.HOME_SCREEN
                all.isEmpty() || has("loading", "please wait", "loading…", "loading...") -> Situation.LOADING
                else -> null
            }
        }
    }
}
