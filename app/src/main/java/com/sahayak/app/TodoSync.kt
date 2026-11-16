package com.sahayak.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Do phone ke beech ek to-do list. Ek phone apni list share karta hai (owner),
 * doosra code daal kar judta hai (guest) aur usme kaam jod sakta hai.
 * Message ek muft relay (ntfy.sh) se jaate hain, taala lage hue: relay unhe padh nahi sakta.
 */
object TodoSync {
    private const val PREFS = "sahayak_share"
    private const val SERVER = "https://ntfy.sh/"
    private const val CHANNEL = "sahayak_shared_todo"
    private const val READ_MS = 100_000
    private const val CONNECT_MS = 20_000
    private const val RETRY_MIN_MS = 3_000L
    private const val RETRY_MAX_MS = 30_000L
    private const val STALE_MS = 10L * 3_600_000L
    private const val HELLO_GAP_MS = 20_000L
    private const val SEEN_SAVE_MS = 60_000L
    private const val SEND_GAP_MS = 300L
    const val OWNER = "owner"
    const val GUEST = "guest"

    /** Screen khuli ho to list badalte hi taaza ho. */
    @Volatile
    var listener: (() -> Unit)? = null

    @Volatile
    var connected = false
        private set

    @Volatile
    var lastError = ""
        private set

    @Volatile
    private var generation = 0

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var stream: HttpURLConnection? = null

    @Volatile
    private var lastFull = 0L

    private val sender = Executors.newSingleThreadExecutor()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun role(c: Context): String = prefs(c).getString("role", "").orEmpty()

    fun linked(c: Context) = role(c).isNotEmpty() && code(c).isNotEmpty()

    fun code(c: Context): String = prefs(c).getString("code", "").orEmpty()

    /** Guest ke phone par: kiski list hai (jaise "Atul"). */
    fun name(c: Context): String = prefs(c).getString("name", "").orEmpty()

    /** Kaun sa dibba doosre phone se milta hai: owner ki apni list, guest ke paas uski copy. */
    fun box(c: Context) = if (role(c) == GUEST) Todos.PEER else ""

    /** Aakhri baar doosre phone se kab kuch mila (ms). 0 = abhi tak nahi. */
    fun lastHeard(c: Context) = prefs(c).getLong("heard", 0L)

    private fun device(c: Context): String {
        val p = prefs(c)
        return p.getString("device", null) ?: UUID.randomUUID().toString().take(8).also { p.edit().putString("device", it).apply() }
    }

    /** Apni list share karo. Naya code banta hai; wahi doosre phone mein daalna hai. */
    fun share(c: Context): String {
        val code = ShareLogic.newCode()
        chatPrefs(c).edit().clear().apply()
        prefs(c).edit().clear().putString("device", device(c)).putString("role", OWNER).putString("code", code).putBoolean("dirty", true).apply()
        restart(c)
        return code
    }

    /** Kisi ki list se judo. Code galat likha ho to false. */
    fun join(c: Context, name: String, typed: String): Boolean {
        val code = ShareLogic.cleanCode(typed)
        if (code.isEmpty()) return false
        chatPrefs(c).edit().clear().apply()
        prefs(c).edit().clear().putString("device", device(c)).putString("role", GUEST).putString("code", code)
            .putString("name", name.trim().take(30).ifEmpty { "Shared" }).putBoolean("hello", true).apply()
        c.getSharedPreferences("sahayak_todos_" + Todos.PEER, Context.MODE_PRIVATE).edit().clear().apply()
        restart(c)
        return true
    }

    /** Share band. Apni list waise hi rehti hai; guest ke phone se doosre ki copy hat jaati hai. */
    fun unlink(c: Context) {
        val guest = role(c) == GUEST
        prefs(c).edit().clear().apply()
        chatPrefs(c).edit().clear().apply()
        if (guest) c.getSharedPreferences("sahayak_todos_" + Todos.PEER, Context.MODE_PRIVATE).edit().clear().apply()
        generation++
        stream?.disconnect()
        connected = false
    }

    private fun restart(c: Context) {
        generation++
        stream?.disconnect()
        worker = null
        start(c)
    }

    /** Sunne wala dhaga chalu karo (pehle se chal raha ho to kuch nahi). */
    @Synchronized
    fun start(context: Context) {
        val c = context.applicationContext
        if (!linked(c) || worker?.isAlive == true) return
        val mine = generation
        worker = Thread({ listen(c, mine) }, "todo-sync").apply {
            isDaemon = true
            start()
        }
    }

    private fun listen(c: Context, mine: Int) {
        var wait = RETRY_MIN_MS
        var seenSaved = 0L
        while (mine == generation && linked(c)) {
            val code = code(c)
            try {
                val since = prefs(c).getString("last", null) ?: "12h"
                val conn = URL(SERVER + ShareLogic.topic(code) + "/json?since=" + since).openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_MS
                conn.readTimeout = READ_MS
                stream = conn
                if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Relay said " + conn.responseCode)
                conn.inputStream.bufferedReader().use { reader ->
                    connected = true
                    lastError = ""
                    wait = RETRY_MIN_MS
                    onConnected(c)
                    ping()
                    while (mine == generation) {
                        val line = reader.readLine() ?: break
                        val now = System.currentTimeMillis()
                        if (now - seenSaved > SEEN_SAVE_MS) {
                            seenSaved = now
                            prefs(c).edit().putLong("seen", now).apply()
                        }
                        ShareLogic.parseLine(line)?.let { handle(c, code, it) }
                    }
                }
            } catch (e: IOException) {
                lastError = e.message ?: "No internet"
            } catch (e: RuntimeException) {
                lastError = e.message ?: "Something went wrong"
            }
            stream = null
            if (connected) {
                connected = false
                ping()
            }
            if (mine != generation) break
            try {
                Thread.sleep(wait)
            } catch (e: InterruptedException) {
                break
            }
            wait = (wait * 2).coerceAtMost(RETRY_MAX_MS)
        }
    }

    /** Judte hi: jo bhejna baaki tha bhejo; bahut der baad jude ho to poori list maango. */
    private fun onConnected(c: Context) {
        val p = prefs(c)
        val away = System.currentTimeMillis() - p.getLong("seen", 0L) > STALE_MS
        if (p.getBoolean("hello", false) || away) send(c, "hello", emptyList()) { ok -> if (ok) p.edit().putBoolean("hello", false).apply() }
        if (p.getBoolean("dirty", false) || away) sendAll(c)
    }

    private fun handle(c: Context, code: String, line: ShareLogic.Line) {
        if (line.id.isNotEmpty()) prefs(c).edit().putString("last", line.id).apply()
        val packet = ShareLogic.unlock(code, line.message)?.let { ShareLogic.parsePacket(it) } ?: return
        if (packet.from == device(c)) return
        prefs(c).edit().putLong("heard", System.currentTimeMillis()).apply()
        if (packet.type == "hello") {
            // Doosra phone poori list maang raha hai
            if (System.currentTimeMillis() - lastFull > HELLO_GAP_MS) sendAll(c)
        } else if (packet.type == "call") {
            Calls.onSignal(c, packet.items)
        } else if (packet.type == "chat") {
            val fresh = mergeChat(c, packet.items)
            fresh.forEach { tell(c, "💬 " + who(c), it.optString("text"), chat = true) }
        } else if (packet.items.isNotEmpty()) {
            val merged = Todos.merge(c, box(c), packet.items)
            if (role(c) == OWNER) merged.added.forEach { tell(c, "New task", it.optString("text")) }
            else merged.finished.forEach { tell(c, "✓ " + name(c) + " finished", it.optString("text")) }
        }
        ping()
    }

    private fun ping() {
        val call = listener ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { call() }
    }

    /** Is phone par list badli: turant doosre phone ko bhejo. */
    fun localChange(context: Context, box: String, items: List<JSONObject>) {
        val c = context.applicationContext
        if (items.isEmpty() || !linked(c) || box != box(c)) return
        start(c)
        send(c, "items", items) { ok -> if (!ok) prefs(c).edit().putBoolean("dirty", true).apply() }
    }

    /** Poori list bhejo (aur "Sync now" par doosre ki bhi maango). */
    fun syncNow(context: Context) {
        val c = context.applicationContext
        if (!linked(c)) return
        start(c)
        send(c, "hello", emptyList()) {}
        sendAll(c)
    }

    private fun sendAll(c: Context) {
        lastFull = System.currentTimeMillis()
        // Chat ke pichhle sandesh bhi, taaki band raha phone peeche na rahe (doosra phone id se dohraav hata deta hai)
        ShareLogic.chunks(chat(c).takeLast(CHAT_RESEND)).forEach { part -> send(c, "chat", part) {} }
        val parts = ShareLogic.chunks(Todos.raw(c, box(c)))
        if (parts.isEmpty()) {
            prefs(c).edit().putBoolean("dirty", false).apply()
            return
        }
        var failed = false
        parts.forEachIndexed { i, part ->
            send(c, "items", part) { ok ->
                if (!ok) failed = true
                if (i == parts.size - 1) prefs(c).edit().putBoolean("dirty", failed).apply()
            }
        }
    }

    private fun send(c: Context, type: String, items: List<JSONObject>, done: (Boolean) -> Unit) {
        val code = code(c)
        if (code.isEmpty()) return
        val body = ShareLogic.lock(code, ShareLogic.packet(device(c), type, items))
        sender.execute {
            var ok = false
            try {
                val conn = URL(SERVER + ShareLogic.topic(code)).openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_MS
                conn.readTimeout = CONNECT_MS
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/plain")
                // Relay isse apna notification na banaye; ye sirf hamare app ke liye hai
                conn.setRequestProperty("X-Priority", "1")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                ok = conn.responseCode == HttpURLConnection.HTTP_OK
                if (!ok) lastError = "Relay said " + conn.responseCode
                conn.disconnect()
                Thread.sleep(SEND_GAP_MS)
            } catch (e: IOException) {
                lastError = e.message ?: "No internet"
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: RuntimeException) {
                lastError = e.message ?: "Something went wrong"
            }
            done(ok)
            ping()
        }
    }

    // ---------- Call ----------

    /** Call ka signal (ghanti, haan, kaato) doosre phone ko. */
    fun sendCall(context: Context, items: List<JSONObject>) {
        val c = context.applicationContext
        if (!linked(c)) return
        start(c)
        items.forEach { send(c, "call", listOf(it)) {} }
    }

    /** Doosre phone wale ko kya kahein: guest ke phone par diya hua naam, owner ke phone par "Other phone". */
    fun peer(c: Context): String = if (role(c) == GUEST) name(c) else "Other phone"

    // ---------- Chat ----------

    private const val CHAT_KEEP = 300
    private const val CHAT_RESEND = 40
    const val CHAT_MAX = 500

    private fun chatPrefs(c: Context) = c.getSharedPreferences(PREFS + "_chat", Context.MODE_PRIVATE)

    /** Doosre phone wale ka naam: guest ke phone par jo naam diya, owner ke phone par "Message". */
    fun who(c: Context): String = if (role(c) == GUEST) name(c) else "Message"

    /** Sab sandesh, purane se naye: {id, text, at, from}. */
    @Synchronized
    fun chat(c: Context): List<JSONObject> = try {
        val a = org.json.JSONArray(chatPrefs(c).getString("msgs", "[]"))
        List(a.length()) { a.getJSONObject(it) }
    } catch (e: org.json.JSONException) {
        emptyList()
    }

    fun mine(c: Context, msg: JSONObject) = msg.optString("from") == device(c)

    /** Kitne naye sandesh abhi padhe nahi. */
    fun unread(c: Context): Int {
        val seen = chatPrefs(c).getLong("read", 0L)
        return chat(c).count { !mine(c, it) && it.optLong("got", it.optLong("at")) > seen }
    }

    fun markRead(c: Context) = chatPrefs(c).edit().putLong("read", System.currentTimeMillis()).apply()

    @Synchronized
    private fun saveChat(c: Context, list: List<JSONObject>) {
        chatPrefs(c).edit().putString("msgs", org.json.JSONArray(list.sortedBy { it.optLong("at") }.takeLast(CHAT_KEEP)).toString()).commit()
    }

    /** Aaye sandesh jodo; sirf naye lautao. */
    @Synchronized
    private fun mergeChat(c: Context, incoming: List<JSONObject>): List<JSONObject> {
        val have = chat(c)
        val ids = have.map { it.optString("id") }.toHashSet()
        val now = System.currentTimeMillis()
        val fresh = incoming.filter { it.optString("text").isNotBlank() && ids.add(it.optString("id")) }
            .map { JSONObject(it.toString()).put("got", now) }
        if (fresh.isNotEmpty()) saveChat(c, have + fresh)
        return fresh
    }

    /** Sandesh bhejo. Pahuncha ya nahi, "sent" se pata chalta hai. */
    fun sendChat(context: Context, text: String) {
        val c = context.applicationContext
        val clean = text.trim().take(CHAT_MAX)
        if (clean.isEmpty() || !linked(c)) return
        start(c)
        val msg = JSONObject().put("id", UUID.randomUUID().toString().take(10)).put("text", clean)
            .put("at", System.currentTimeMillis()).put("from", device(c))
        saveChat(c, chat(c) + msg)
        val out = JSONObject(msg.toString())
        send(c, "chat", listOf(out)) { ok ->
            synchronized(this) {
                saveChat(c, chat(c).map { if (it.optString("id") == msg.optString("id")) it.put("sent", ok) else it })
            }
        }
        ping()
    }

    /** Upar notification: naya kaam aaya, ya doosre ne kaam kar diya. */
    private fun tell(c: Context, title: String, text: String, chat: Boolean = false) {
        try {
            val manager = c.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Shared to-do list", NotificationManager.IMPORTANCE_HIGH))
            val open = if (chat) Intent(c, SharedTodoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            else c.packageManager.getLaunchIntentForPackage(c.packageName) ?: Intent()
            val tap = PendingIntent.getActivity(c, if (chat) 1 else 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val note = Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            manager.notify(text.hashCode(), note)
        } catch (e: RuntimeException) {
            // Notification ki ijazat na ho to bhi list to aa hi gayi
        }
    }
}
