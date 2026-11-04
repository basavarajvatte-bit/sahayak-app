package com.sahayak.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.text.format.DateFormat
import org.json.JSONArray
import java.util.Date
import java.util.concurrent.TimeUnit

/** Aapke orders se roz seekhna. Sirf aapke chalu karne par, aur aap kabhi bhi mita sakte hain. */
object DailyLearning {
    private const val PREFS = "sahayak_learning"
    private const val ENABLED = "enabled"
    private const val LINES = "journal"
    private const val SUMMARY = "summary"
    private const val LAST_RUN = "last_run"
    private const val JOB_ID = 417
    private const val MAX_LINES = 300
    private const val MIN_LINES = 3
    private val SECRET_WORDS = listOf("password", "otp", "cvv", "upi pin", "पासवर्ड", "ओटीपी")
    private val LONG_NUMBER = Regex("\\d{6,}")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, on).apply()
        val jobs = context.getSystemService(JobScheduler::class.java)
        if (on) {
            jobs.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, DailyLearningJob::class.java))
                .setPeriodic(TimeUnit.HOURS.toMillis(24))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build())
        } else {
            jobs.cancel(JOB_ID)
            clear(context)
        }
    }

    private fun lines(context: Context): List<String> {
        val array = JSONArray(prefs(context).getString(LINES, "[]"))
        return List(array.length()) { array.getString(it) }
    }

    @Synchronized
    fun note(context: Context, text: String) {
        if (!isEnabled(context)) return
        val lower = text.lowercase()
        if (SECRET_WORDS.any { it in lower }) return
        val clean = LONG_NUMBER.replace(text, "[number]").take(200)
        val stamp = DateFormat.format("EEE HH:mm", Date()).toString()
        val next = (lines(context) + "$stamp: $clean").takeLast(MAX_LINES)
        prefs(context).edit().putString(LINES, JSONArray(next).toString()).apply()
    }

    fun summary(context: Context): String = prefs(context).getString(SUMMARY, "").orEmpty()

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(LINES).remove(SUMMARY).apply()
    }

    /** App khulne par bhi: agar din ki seekh baaki ho to chala do. */
    fun runIfDue(context: Context) {
        val last = prefs(context).getLong(LAST_RUN, 0L)
        if (isEnabled(context) && System.currentTimeMillis() - last > TimeUnit.HOURS.toMillis(20)) {
            val app = context.applicationContext
            Thread { learn(app) }.start()
        }
    }

    fun learn(context: Context) {
        val journal = lines(context)
        if (!isEnabled(context) || journal.size < MIN_LINES) return
        val key = SecureStore(context.getSharedPreferences("sahayak", Context.MODE_PRIVATE)).load()
        if (key.isEmpty()) return
        val result = Planner(GeminiLive.newClient(), key, 40L).learn(Memory.all(context), journal) ?: return
        val add = result.optJSONArray("add") ?: JSONArray()
        val remove = result.optJSONArray("remove") ?: JSONArray()
        for (i in 0 until remove.length()) Memory.remove(context, remove.optString(i))
        var learned = 0
        for (i in 0 until add.length()) if (Memory.add(context, add.optString(i))) learned++
        val text = result.optString("summary")
        synchronized(this) {
            prefs(context).edit().remove(LINES).putString(SUMMARY, text)
                .putLong(LAST_RUN, System.currentTimeMillis()).apply()
        }
        RunLog.add(context, "daily_learning added $learned")
        if (learned > 0) notifyDone(context, learned)
    }

    private fun notifyDone(context: Context, count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sahayak_learning", "Sahayak ki seekh", NotificationManager.IMPORTANCE_LOW))
        manager.notify(JOB_ID, Notification.Builder(context, "sahayak_learning")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Sahayak")
            .setContentText("Aaj $count nayi baatein seekhi")
            .setAutoCancel(true)
            .build())
    }
}

class DailyLearningJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                DailyLearning.learn(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
