package com.sahayak.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Kisi khabar par nazar: website aur Google, tay samay par, nateeje list mein. */
object Watcher {
    private const val PREFS = "sahayak_watch"
    const val ACTION_RUN = "com.sahayak.app.RUN_WATCH"
    private const val NOTE_ID = 7_801
    private const val MAX_RESULTS = 150
    private const val MAX_PAGE = 2_000_000
    private const val AI_GAP_MS = 5 * 60_000L
    private const val SHOW_LINES = 5
    val INTERVALS = listOf(1, 5, 15, 30, 60)
    private val busy = AtomicBoolean(false)

    data class Job(val what: String, val url: String, val minutes: Int, val google: Boolean, val on: Boolean)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun job(c: Context) = prefs(c).let {
        Job(
            it.getString("what", "UP NEET PG counselling registration open").orEmpty(),
            it.getString("url", "https://upneet.gov.in").orEmpty(),
            it.getInt("minutes", 15), it.getBoolean("google", true), it.getBoolean("on", false)
        )
    }

    fun save(c: Context, j: Job) {
        val old = job(c)
        prefs(c).edit().putString("what", j.what).putString("url", j.url).putInt("minutes", j.minutes)
            .putBoolean("google", j.google).putBoolean("on", j.on).apply()
        // Website badli to purani yaad bekaar
        if (old.url != j.url) prefs(c).edit().remove("seen").apply()
    }

    fun results(c: Context): List<JSONObject> {
        val a = JSONArray(prefs(c).getString("results", "[]"))
        return List(a.length()) { a.getJSONObject(it) }
    }

    fun clearResults(c: Context) = prefs(c).edit().putString("results", "[]").apply()

    fun lastCheck(c: Context) = prefs(c).getLong("last", 0L)

    fun nextAt(c: Context) = prefs(c).getLong("next", 0L)

    @Synchronized
    private fun add(c: Context, source: String, status: String, text: String, link: String, alert: Boolean) {
        val list = results(c).toMutableList()
        val now = System.currentTimeMillis()
        val top = list.firstOrNull()
        // Lagataar "koi badlav nahi" ek hi line mein ginti ke saath
        if (!alert && top != null && top.optString("source") == source && top.optString("status") == status && status == "No change") {
            top.put("at", now).put("count", top.optInt("count", 1) + 1)
        } else {
            list.add(0, JSONObject().put("at", now).put("source", source).put("status", status).put("text", text)
                .put("link", link).put("alert", alert).put("count", 1))
        }
        prefs(c).edit().putString("results", JSONArray(list.take(MAX_RESULTS)).toString()).apply()
    }

    private fun intent(c: Context) = PendingIntent.getBroadcast(
        c, NOTE_ID, Intent(c, WorkReceiver::class.java).setAction(ACTION_RUN),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun arm(c: Context, at: Long) {
        prefs(c).edit().putLong("next", at).apply()
        val manager = c.getSystemService(AlarmManager::class.java)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            val show = PendingIntent.getActivity(c, NOTE_ID, Intent(c, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), intent(c))
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c))
        }
    }

    fun start(c: Context) {
        save(c, job(c).copy(on = true))
        arm(c, System.currentTimeMillis() + 2_000L)
    }

    fun stop(c: Context) {
        save(c, job(c).copy(on = false))
        prefs(c).edit().putLong("next", 0L).apply()
        c.getSystemService(AlarmManager::class.java).cancel(intent(c))
    }

    fun rearm(c: Context) {
        val j = job(c)
        if (!j.on) return
        val at = nextAt(c)
        arm(c, if (at > System.currentTimeMillis()) at else System.currentTimeMillis() + 5_000L)
    }

    /** Ek baar check. Alarm se aaye to agla samay bhi lagao. */
    fun run(c: Context, scheduled: Boolean) {
        val j = job(c)
        if (scheduled) {
            if (!j.on) return
            arm(c, System.currentTimeMillis() + j.minutes * 60_000L)
        }
        if (!busy.compareAndSet(false, true)) return
        try {
            prefs(c).edit().putLong("last", System.currentTimeMillis()).apply()
            if (j.url.isNotBlank()) checkSite(c, j)
            val lastAi = prefs(c).getLong("last_ai", 0L)
            if (j.google && (!scheduled || System.currentTimeMillis() - lastAi >= AI_GAP_MS - 30_000L)) {
                prefs(c).edit().putLong("last_ai", System.currentTimeMillis()).apply()
                checkGoogle(c, j)
            }
        } finally {
            busy.set(false)
        }
    }

    private fun checkSite(c: Context, j: Job) {
        val url = if (j.url.startsWith("http")) j.url else "https://" + j.url
        val request = try {
            Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 12) Sahayak").build()
        } catch (e: IllegalArgumentException) {
            add(c, "Website", "Error", "Website address is not correct", url, false)
            return
        }
        val strict = GeminiLive.newClient().newBuilder().callTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
        val call = try {
            strict.newCall(request).execute()
        } catch (e: javax.net.ssl.SSLException) {
            null
        } catch (e: IOException) {
            add(c, "Website", "Error", "Could not open the website. Check internet.", url, false)
            return
        }
        val html = try {
            (call ?: looseClient().newCall(request).execute()).use { r ->
                if (!r.isSuccessful) {
                    add(c, "Website", "Error", "Website answered " + r.code, url, false)
                    return
                }
                r.body?.string()?.take(MAX_PAGE).orEmpty()
            }
        } catch (e: IOException) {
            add(c, "Website", "Error", "Could not open the website. Check internet.", url, false)
            return
        } catch (e: IllegalArgumentException) {
            add(c, "Website", "Error", "Website address is not correct", url, false)
            return
        }
        val now = WatchLogic.lines(html)
        val seen = prefs(c).getStringSet("seen", null)
        prefs(c).edit().putStringSet("seen", now.toSet()).apply()
        val keys = WatchLogic.keywords(j.what)
        if (seen == null) {
            val top = WatchLogic.important(now, keys).take(SHOW_LINES)
            add(c, "Website", "Started", if (top.isEmpty()) "Watching " + now.size + " lines on the page." else "Current notices:\n• " + top.joinToString("\n• "), url, false)
            return
        }
        val fresh = WatchLogic.newLines(now, seen)
        if (fresh.isEmpty()) {
            add(c, "Website", "No change", "", url, false)
            return
        }
        val hot = WatchLogic.important(fresh, keys)
        val show = (hot + fresh).distinct().take(SHOW_LINES)
        add(c, "Website", if (hot.isNotEmpty()) "New notice" else "Page changed", "• " + show.joinToString("\n• "), url, hot.isNotEmpty())
        if (hot.isNotEmpty()) WorkList.notifyText(c, NOTE_ID, "New on website: " + hot.first().take(120))
    }

    /** Google News ka muft RSS. Koi key nahi, koi limit nahi. */
    private fun checkGoogle(c: Context, j: Job) {
        val q = java.net.URLEncoder.encode(j.what + " when:7d", "UTF-8")
        val url = "https://news.google.com/rss/search?q=$q&hl=en-IN&gl=IN&ceid=IN:en"
        val xml = try {
            GeminiLive.newClient().newBuilder().callTimeout(40, java.util.concurrent.TimeUnit.SECONDS).build().newCall(
                Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 12) Sahayak").build()
            ).execute().use { r ->
                if (!r.isSuccessful) {
                    add(c, "Google", "Error", "Google News answered " + r.code, "", false)
                    return
                }
                r.body?.string()?.take(MAX_PAGE).orEmpty()
            }
        } catch (e: IOException) {
            add(c, "Google", "Error", "No internet or Google News did not open", "", false)
            return
        }
        val keys = WatchLogic.keywords(j.what)
        val items = WatchLogic.news(xml)
        val hits = items.filter { WatchLogic.newsHit(it.title, keys) }
        val seen = prefs(c).getStringSet("news_seen", null)
        prefs(c).edit().putStringSet("news_seen", (items.map { it.link } + seen.orEmpty()).take(300).toSet()).apply()
        if (seen == null) {
            val top = (hits + items).distinct().take(SHOW_LINES)
            add(c, "Google", "Started", if (top.isEmpty()) "No news in the last 7 days yet." else "Latest news:\n• " + top.joinToString("\n• ") { it.title },
                top.firstOrNull()?.link.orEmpty(), false)
            return
        }
        val fresh = hits.filter { it.link !in seen }
        if (fresh.isEmpty()) {
            add(c, "Google", "No change", "", "", false)
            return
        }
        add(c, "Google", "Found", "• " + fresh.take(SHOW_LINES).joinToString("\n• ") { it.title }, fresh.first().link, true)
        WorkList.notifyText(c, NOTE_ID, "News found: " + fresh.first().title.take(140))
    }

    /** Kuch sarkari sites ka certificate adhoora hota hai. Sirf padhne ke liye, phir bhi khol lo. */
    private fun looseClient(): okhttp3.OkHttpClient {
        val trust = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
        val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), java.security.SecureRandom()) }
        return GeminiLive.newClient().newBuilder().sslSocketFactory(ssl.socketFactory, trust)
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    }
}
