package com.sahayak.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/** YouTube Doctor: channel ki har video ki jaanch, galtiyan aur sudhaar. */
class YtDoctorActivity : Activity() {
    private lateinit var channel: EditText
    private lateinit var goal: EditText
    private lateinit var count: RadioGroup
    private lateinit var english: CheckBox
    private lateinit var shotsText: TextView
    private lateinit var checkButton: Button
    private lateinit var status: TextView
    private lateinit var report: TextView
    private val shots = ArrayList<Uri>()
    @Volatile private var working = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("sahayak_ytdoctor", Context.MODE_PRIVATE)

    private fun text(words: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = words
        textSize = size
        setTextColor(0xFF1D2433.toInt())
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
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
        page.addView(text("📈 YouTube Doctor", 24f, true))
        page.addView(text("Sahayak aapke channel ki videos padhta hai, Gemini har video ko dekh-sun kar galtiyan dhoondhta hai, aur batata hai kya sudhaarna hai.", 14f))

        page.addView(text("1. Channel link", 16f, true))
        channel = EditText(this).apply {
            hint = "youtube.com/@yourchannel"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs().getString("channel", ""))
        }
        page.addView(channel)

        page.addView(text("2. Aapka goal (optional)", 16f, true))
        goal = EditText(this).apply {
            hint = "Example: English Shorts about social media addiction for US viewers"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(prefs().getString("goal", ""))
        }
        page.addView(goal)
        english = CheckBox(this).apply {
            text = "Meri videos English mein hain (America ke log dekhte hain)"
            isChecked = prefs().getBoolean("english", true)
        }
        page.addView(english)

        page.addView(text("3. AI kitni nayi videos dekhe", 16f, true))
        count = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf("3", "5", "10").forEachIndexed { i, n -> addView(RadioButton(context).apply { id = COUNT_IDS + i; text = n; textSize = 16f }) }
            check(COUNT_IDS + 1)
        }
        page.addView(count)

        page.addView(text("4. YouTube Studio ke screenshots (optional, isse jaanch gehri hoti hai)", 16f, true))
        page.addView(text("Studio app mein video > Analytics > Engagement ('Viewed vs swiped away', 'Average view duration') ke screenshots chuniye.", 13f))
        page.addView(button("Add screenshots") { pickShots() })
        shotsText = text("Koi screenshot nahi.", 13f)
        page.addView(shotsText)

        checkButton = button("Check my channel") { start() }
        page.addView(checkButton)
        status = text("", 15f, true)
        page.addView(status)
        report = text(prefs().getString("last", "").orEmpty(), 15f).apply { setTextIsSelectable(true) }
        page.addView(report)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Copy report") { copy() })
        actions.addView(button("Share") { share() })
        page.addView(actions)
    }

    private fun pickShots() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(intent, PICK_SHOTS)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_SHOTS || resultCode != RESULT_OK || data == null) return
        shots.clear()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) shots.add(clip.getItemAt(i).uri) }
        if (shots.isEmpty()) data.data?.let { shots.add(it) }
        while (shots.size > MAX_SHOTS) shots.removeAt(shots.size - 1)
        shotsText.text = "${shots.size} screenshot chune gaye."
    }

    private fun start() {
        if (working) return
        val key = SecureStore(getSharedPreferences("sahayak", Context.MODE_PRIVATE)).load()
        if (key.isEmpty()) {
            status.text = "Pehle Sahayak Settings mein Gemini key daaliye."
            return
        }
        val link = channel.text.toString().trim()
        if (link.isEmpty()) {
            status.text = "Channel ka link daaliye."
            return
        }
        val aim = goal.text.toString().trim()
        val usAudience = english.isChecked
        val want = listOf(3, 5, 10)[(count.checkedRadioButtonId - COUNT_IDS).coerceIn(0, 2)]
        prefs().edit().putString("channel", link).putString("goal", aim).putBoolean("english", usAudience).apply()
        val pics = ArrayList(shots)
        working = true
        checkButton.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        report.text = ""
        val app = applicationContext
        fun say(line: String) = runOnUiThread { status.text = line }
        Thread {
            val doctor = YtDoctor(app, key)
            doctor.onSwitch = { say(it) }
            val now = System.currentTimeMillis()
            val out = StringBuilder()
            try {
                say("Channel dhoondh raha hun…")
                val id = doctor.resolve(link)
                val videos = if (id != null) doctor.videos(id) else emptyList()
                if (videos.isEmpty()) {
                    say("Nahi ho paaya: " + doctor.lastError)
                } else {
                    val summary = YtDoctorLogic.summaryLine(videos, now)
                    val channelIssues = YtDoctorLogic.channelIssues(videos, now)
                    out.append("📊 ").append(summary).append("\n\n")
                    val checked = ArrayList<Pair<YtVideo, YtReport>>()
                    val parts = StringBuilder()
                    videos.take(want).forEachIndexed { i, v ->
                        say("Gemini video ${i + 1}/${minOf(want, videos.size)} dekh raha hai…")
                        if (i > 0) Thread.sleep(GAP_MS)
                        val r = doctor.check(v, now, aim)
                        if (r != null) checked.add(v to r)
                        parts.append(YtDoctorLogic.reportText(v, r, YtDoctorLogic.videoIssues(v, now, usAudience)))
                        if (r != null) parts.append("\n(AI: ").append(GeminiModels.label(doctor.lastModel)).append(")")
                        parts.append("\n\n")
                    }
                    say("Poore channel ka plan bana raha hun…")
                    val images = pics.mapNotNull { doctor.shrink(it) }
                    val issues = channelIssues + videos.take(want).flatMap { YtDoctorLogic.videoIssues(it, now, usAudience) }.distinct()
                    val plan = doctor.summary(YtDoctorLogic.summaryPrompt(summary, issues, checked, images.size), images)
                    out.append("🩺 PLAN\n").append(plan ?: ("(AI plan nahi bana: " + doctor.lastError + ")")).append("\n\n")
                    if (channelIssues.isNotEmpty()) {
                        out.append("⚠ CHANNEL KI GALTIYAN\n")
                        channelIssues.forEach { out.append("• ").append(it).append("\n") }
                        out.append("\n")
                    }
                    out.append("🎬 HAR VIDEO KI JAANCH\n\n").append(parts)
                    say(if (checked.isEmpty()) "Numbers se jaanch hui. AI videos nahi dekh paaya: " + doctor.lastError else "Jaanch poori ✅")
                }
            } catch (e: Exception) {
                RunLog.add(app, "yt doctor failed " + e.message)
                say("Nahi ho paaya: " + (e.message ?: "unknown problem"))
            }
            val text = out.toString().trim()
            runOnUiThread {
                if (text.isNotEmpty()) {
                    report.text = text
                    prefs().edit().putString("last", text).apply()
                }
                working = false
                checkButton.isEnabled = true
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }.start()
    }

    private fun copy() {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("YouTube report", report.text))
        status.text = "Report copy ho gayi."
    }

    private fun share() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, report.text.toString()), "Share report"))
    }

    companion object {
        private const val PICK_SHOTS = 5_201
        private const val COUNT_IDS = 6_200
        private const val MAX_SHOTS = 8
        private const val GAP_MS = 4_000L
    }
}
