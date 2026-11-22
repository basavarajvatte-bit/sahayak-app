package com.sahayak.app

/**
 * Sahayak 2.0, Phase 2: kai kaam ek saath.
 * Screen ek hai, isliye screen wale kaam line (queue) mein ek ke baad ek chalte hain.
 * Research wale kaam ko screen nahi chahiye, woh turant saath-saath chalte hain.
 * Is file mein Android ka kuch nahi, taaki test ho sake.
 */
class TaskBoard {
    enum class Kind { SCREEN, RESEARCH }
    enum class Status { WAITING, RUNNING, DONE, FAILED, STOPPED }

    class Task(val id: Int, val text: String, val kind: Kind, val silent: Boolean, val createdAt: Long) {
        @Volatile var status = Status.WAITING
        @Volatile var startedAt = 0L
        @Volatile var endedAt = 0L
        @Volatile var result = ""
    }

    private val tasks = ArrayList<Task>()
    private var nextId = 1

    @Synchronized
    fun add(text: String, kind: Kind, silent: Boolean, now: Long): Task {
        val task = Task(nextId++, text, kind, silent, now)
        tasks.add(task)
        while (tasks.size > KEEP) {
            val old = tasks.firstOrNull { it.status != Status.WAITING && it.status != Status.RUNNING } ?: break
            tasks.remove(old)
        }
        return task
    }

    @Synchronized
    fun start(task: Task, now: Long) {
        task.status = Status.RUNNING
        task.startedAt = now
    }

    @Synchronized
    fun end(task: Task, status: Status, now: Long, result: String = "") {
        if (task.status == Status.DONE || task.status == Status.FAILED || task.status == Status.STOPPED) return
        task.status = status
        task.endedAt = now
        task.result = result
    }

    /** Line mein sabse pehla screen kaam, use chalu mark karke deta hai. */
    @Synchronized
    fun takeNextScreen(now: Long): Task? {
        val next = tasks.firstOrNull { it.kind == Kind.SCREEN && it.status == Status.WAITING } ?: return null
        start(next, now)
        return next
    }

    @Synchronized
    fun runningScreen(): Task? = tasks.lastOrNull { it.kind == Kind.SCREEN && it.status == Status.RUNNING }

    @Synchronized
    fun waitingScreen(): Int = tasks.count { it.kind == Kind.SCREEN && it.status == Status.WAITING }

    @Synchronized
    fun runningResearch(): Int = tasks.count { it.kind == Kind.RESEARCH && it.status == Status.RUNNING }

    /** Dot ka chhota tap: sab kuch band. */
    @Synchronized
    fun stopAll(now: Long) {
        tasks.filter { it.status == Status.WAITING || it.status == Status.RUNNING }
            .forEach { end(it, Status.STOPPED, now) }
    }

    @Synchronized
    fun isStopped(task: Task): Boolean = task.status == Status.STOPPED

    /** Ek line mein haal: "1 chal raha, 2 line mein, 1 research". */
    @Synchronized
    fun summary(): String {
        val parts = ArrayList<String>()
        if (runningScreen() != null) parts.add("1 task running")
        val waiting = waitingScreen()
        if (waiting > 0) parts.add("$waiting waiting")
        val research = runningResearch()
        if (research > 0) parts.add("$research research")
        return parts.joinToString(", ")
    }

    @Synchronized
    fun all(): List<Task> = tasks.toList()

    companion object {
        private const val KEEP = 30

        private val RESEARCH_START = listOf(
            "find ", "search ", "google ", "research", "search about", "look up", "what is", "what are", "who is", "who are", "when is",
            "when will", "why ", "how much", "how many", "tell me about", "give me information", "information about",
            "list of", "complete list", "latest news", "news about", "explain"
        )
        private val RESEARCH_WORDS = listOf("research", "batao", "kya hai", "kaun hai", "kab hai", "jaankari", "jankari", "list nikalo")
        private val PHONE_WORDS = listOf(
            "open", "play", "send", "call", "message", "whatsapp", "youtube", "tap", "click", "type", "book", "upload",
            "install", "settings", "turn on", "turn off", "alarm", "timer", "remind", "reminder","email", "mail", "photo", "camera",
            "screen", "chalao", "kholo", "bhejo", "lagao", "dabao", "on karo", "off karo", "app"
        )

        private val DEEP_WORDS = listOf("research", "find", "search", "google", "list", "report", "compare", "details", "jaankari", "jankari")

        /**
         * Bada research kaam? Tab hi khaali Sahayak bhi use background mein bhejta hai.
         * Chhota sawaal ("what is the time in London") pehle ki tarah Gemini Live bolkar batata hai.
         */
        fun isDeep(order: String): Boolean {
            val text = order.lowercase()
            return DEEP_WORDS.any { Regex("\\b" + it).containsMatchIn(text) }
        }

        /**
         * Order screen ka hai ya sirf research ka? Shak ho to SCREEN, kyunki purana raasta wahi hai.
         * "Find the API key settings on Vercel" screen kaam hai (settings, kholna padega).
         */
        fun classify(order: String): Kind {
            val text = " " + order.lowercase().trim() + " "
            if (PHONE_WORDS.any { Regex("\\b" + Regex.escape(it.trim()) + "\\b").containsMatchIn(text) }) return Kind.SCREEN
            val clean = text.trim()
            if (RESEARCH_START.any { clean.startsWith(it.trim()) }) return Kind.RESEARCH
            if (RESEARCH_WORDS.any { it in text }) return Kind.RESEARCH
            return Kind.SCREEN
        }
    }
}
