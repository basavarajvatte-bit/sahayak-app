package com.sahayak.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** News Watch: ek job do, Sahayak tay samay par dhoondhta rahe. */
class WatchActivity : Activity() {
    private lateinit var what: EditText
    private lateinit var url: EditText
    private lateinit var every: RadioGroup
    private lateinit var google: CheckBox
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var list: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val time = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)
    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(words: String, size: Float, bold: Boolean = false, color: Int = 0xFF1D2433.toInt()) = TextView(this).apply {
        text = words
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(4))
    }

    private fun button(words: String, action: () -> Unit) = Button(this).apply {
        text = words
        isAllCaps = false
        setOnClickListener { action() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F6FA.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(page) })
        val job = Watcher.job(this)
        page.addView(text("News Watch", 24f, true))
        page.addView(text("Sahayak keeps checking for your news and shows every result below.", 14f))
        page.addView(text("What to look for", 16f, true))
        what = EditText(this).apply {
            setText(job.what)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        page.addView(what)
        page.addView(text("Website to watch (optional)", 16f, true))
        url = EditText(this).apply {
            setText(job.url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        page.addView(url)
        page.addView(text("Check every", 16f, true))
        every = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            Watcher.INTERVALS.forEachIndexed { i, m ->
                addView(RadioButton(context).apply { id = IDS + i; text = if (m < 60) "$m min" else "1 hour"; textSize = 15f })
            }
            check(IDS + Watcher.INTERVALS.indexOf(job.minutes).coerceAtLeast(0))
        }
        page.addView(HorizontalScroll(this, every))
        google = CheckBox(this).apply {
            text = "Also search Google News (free, no key needed)"
            isChecked = job.google
        }
        page.addView(google)
        toggle = button("") { flip() }
        page.addView(toggle)
        page.addView(button("Check now") {
            save()
            status.text = "Checking now"
            val app = applicationContext
            Thread {
                Watcher.run(app, false)
                main.post { render() }
            }.start()
        })
        status = text("", 15f, true)
        page.addView(status)
        page.addView(text("Results", 18f, true))
        page.addView(button("Clear results") {
            Watcher.clearResults(this)
            render()
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(list)
    }

    private class HorizontalScroll(context: android.content.Context, child: android.view.View) : android.widget.HorizontalScrollView(context) {
        init {
            addView(child)
        }
    }

    private fun save() {
        val minutes = Watcher.INTERVALS[(every.checkedRadioButtonId - IDS).coerceIn(0, Watcher.INTERVALS.lastIndex)]
        Watcher.save(this, Watcher.job(this).copy(what = what.text.toString().trim(), url = url.text.toString().trim(), minutes = minutes, google = google.isChecked))
    }

    private fun flip() {
        save()
        if (Watcher.job(this).on) Watcher.stop(this) else Watcher.start(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(refresh)
        save()
        if (Watcher.job(this).on) Watcher.rearm(this)
    }

    private fun render() {
        val job = Watcher.job(this)
        toggle.text = if (job.on) "Stop watching" else "Start watching"
        val last = Watcher.lastCheck(this)
        val next = Watcher.nextAt(this)
        status.text = (if (job.on) "Watching. " else "Not watching. ") +
            (if (last > 0) "Last check: " + time.format(Date(last)) + ". " else "") +
            (if (job.on && next > 0) "Next: " + time.format(Date(next)) else "")
        list.removeAllViews()
        val results = Watcher.results(this)
        if (results.isEmpty()) list.addView(text("No results yet.", 14f))
        results.forEach { r ->
            val alert = r.optBoolean("alert")
            val count = r.optInt("count", 1)
            val head = time.format(Date(r.optLong("at"))) + "  ·  " + r.optString("source") + "  ·  " + r.optString("status") +
                (if (count > 1) " ($count checks)" else "")
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(if (alert) 0xFFFFF1C2.toInt() else 0xFFFFFFFF.toInt())
            }
            box.addView(text(head, 13f, true, if (alert) 0xFFB54700.toInt() else 0xFF1F5C4D.toInt()))
            r.optString("text").takeIf { it.isNotBlank() }?.let { box.addView(text(it, 14f)) }
            r.optString("link").takeIf { it.isNotBlank() }?.let { link ->
                box.addView(text("Open link", 14f, true, 0xFF1565C0.toInt()).apply {
                    setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                        } catch (e: android.content.ActivityNotFoundException) {
                            status.text = "No browser found"
                        }
                    }
                })
            }
            list.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
    }

    companion object {
        private const val IDS = 5_000
        private const val REFRESH_MS = 15_000L
    }
}
