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
import java.util.concurrent.atomic.AtomicBoolean
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
    private const val NUDGE_LINES = 12
    private const val ONCE = "strong_memory_on"
    private val learning = AtomicBoolean(false)

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
        val clean = BrainLogic.scrub(text, 200) ?: return
        val stamp = DateFormat.format("EEE HH:mm", Date()).toString()
        val next = (lines(context) + "$stamp: $clean").takeLast(MAX_LINES)
        prefs(context).edit().putString(LINES, JSONArray(next).toString()).apply()
    }

    /** Aapne strong memory maangi: ek baar chalu. Baad mein aap band karein to band rahegi. */
    fun enableOnce(context: Context) {
        if (prefs(context).getBoolean(ONCE, false)) return
        prefs(context).edit().putBoolean(ONCE, true).apply()
        if (!isEnabled(context)) setEnabled(context, true)
    }

    /** Din bhar ke intezar ke bina, kuch orders ke baad hi seekh lo. */
    fun nudge(context: Context) {
        if (!isEnabled(context) || lines(context).size < NUDGE_LINES) return
        val app = context.applicationContext
        Thread { learn(app, false) }.start()
    }

    fun summary(context: Context): String = prefs(context).getString(SUMMARY, "").orEmpty()

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(LINES).remove(SUMMARY).apply()
        History.clear(context)
    }

    /** App khulne par bhi: agar din ki seekh baaki ho to chala do. */
    fun runIfDue(context: Context) {
        val last = prefs(context).getLong(LAST_RUN, 0L)
        if (isEnabled(context) && System.currentTimeMillis() - last > TimeUnit.HOURS.toMillis(20)) {
            val app = context.applicationContext
            Thread { learn(app) }.start()
        }
    }

    fun learn(context: Context, announce: Boolean = true) {
        if (!learning.compareAndSet(false, true)) return
        try {
            learnNow(context, announce)
        } finally {
            learning.set(false)
        }
    }

    private fun learnNow(context: Context, announce: Boolean) {
        val journal = lines(context)
        if (!isEnabled(context) || journal.size < MIN_LINES) return
        val key = SecureStore(context.getSharedPreferences("sahayak", Context.MODE_PRIVATE)).load()
        if (key.isEmpty()) return
        val result = Planner(GeminiLive.newClient(), key, 40L).learn(Memory.all(context), Brain.profile(context), journal) ?: return
        result.optString("profile").takeIf { it.isNotBlank() }?.let { Brain.setProfile(context, it) }
        val add = result.optJSONArray("add") ?: JSONArray()
        val remove = result.optJSONArray("remove") ?: JSONArray()
        for (i in 0 until remove.length()) Memory.remove(context, remove.optString(i))
        var learned = 0
        for (i in 0 until add.length()) if (Memory.add(context, add.optString(i))) learned++
        val text = result.optString("summary")
        synchronized(this) {
            val later = lines(context).filter { it !in journal }
            prefs(context).edit().putString(LINES, JSONArray(later).toString()).putString(SUMMARY, text)
                .putLong(LAST_RUN, System.currentTimeMillis()).apply()
        }
        RunLog.add(context, "daily_learning added $learned")
        if (announce && learned > 0) notifyDone(context, learned)
    }

    private fun notifyDone(context: Context, count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sahayak_learning", "Sahayak learning", NotificationManager.IMPORTANCE_LOW))
        manager.notify(JOB_ID, Notification.Builder(context, "sahayak_learning")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Sahayak")
            .setContentText("Learned $count new things today")
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
