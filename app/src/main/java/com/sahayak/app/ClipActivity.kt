package com.sahayak.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Clipping: lambi video chuno, brief likho, Sahayak achhe hisse kaat kar Reels bana deta hai. */
class ClipActivity : Activity() {
    private lateinit var pickedText: TextView
    private lateinit var brief: EditText
    private lateinit var tags: EditText
    private lateinit var count: RadioGroup
    private lateinit var look: RadioGroup
    private lateinit var makeButton: Button
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private var source: Uri? = null
    @Volatile private var working = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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

    private fun radios(names: List<String>, base: Int) = RadioGroup(this).apply {
        orientation = RadioGroup.HORIZONTAL
        names.forEachIndexed { i, n -> addView(RadioButton(context).apply { id = base + i; text = n; textSize = 16f }) }
        check(base)
    }

    private fun prefs() = getSharedPreferences("sahayak_clips", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F6FA.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(page) })
        page.addView(text("Clipping", 24f, true))
        page.addView(text("Pick a long podcast video from the phone. Gemini listens to it, finds the best parts for your campaign, and Sahayak cuts them into 9:16 Reels with a title on top. Clips are saved in Gallery, Movies/Sahayak/Clips.", 14f))

        page.addView(text("1. Long video", 16f, true))
        page.addView(button("Pick video") { pickVideo() })
        pickedText = text("No video picked. Download the campaign footage (MP4) to the phone first.", 13f)
        page.addView(pickedText)

        page.addView(text("2. Campaign brief", 16f, true))
        page.addView(text("Paste the campaign Brief here, so Gemini picks only parts that fit.", 13f))
        brief = EditText(this).apply {
            hint = "For example: Content must clearly convey the message of social media addiction."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            setText(prefs().getString("brief", ""))
        }
        page.addView(brief)

        page.addView(text("3. Hashtags for the caption", 16f, true))
        tags = EditText(this).apply {
            hint = "#SocialMediaJustice"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs().getString("tags", ""))
        }
        page.addView(tags)

        page.addView(text("How many clips", 16f, true))
        count = radios(listOf("3", "5", "8"), COUNT_IDS)
        page.addView(count)
        page.addView(text("Look", 16f, true))
        look = radios(listOf("Full video + title", "Zoom (fills screen)"), LOOK_IDS)
        page.addView(look)
        page.addView(text("Full video shows both people in a podcast. Zoom fills the screen but cuts the sides.", 13f))

        makeButton = button("Make clips") { make() }
        page.addView(makeButton)
        status = text("", 15f, true)
        page.addView(status)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(results)
    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*")
        startActivityForResult(intent, PICK_VIDEO)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_VIDEO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        source = uri
        pickedText.text = "Picked: " + (nameOf(uri) ?: "video")
    }

    private fun nameOf(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: SecurityException) {
        null
    }

    private fun make() {
        if (working) return
        val video = source ?: run { status.text = "Pick the long video first."; return }
        val key = SecureStore(getSharedPreferences("sahayak", Context.MODE_PRIVATE)).load()
        if (key.isEmpty()) {
            status.text = "Add your Gemini key in Sahayak Settings first."
            return
        }
        val words = brief.text.toString().trim()
        val hashtags = tags.text.toString().trim()
        prefs().edit().putString("brief", words).putString("tags", hashtags).apply()
        val want = listOf(3, 5, 8)[(count.checkedRadioButtonId - COUNT_IDS).coerceIn(0, 2)]
        val zoom = look.checkedRadioButtonId == LOOK_IDS + 1
        working = true
        makeButton.isEnabled = false
        results.removeAllViews()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "Reading the video"
        val app = applicationContext
        Thread {
            var made = 0
            try {
                val clipper = Clipper(app, key)
                val src = clipper.inspect(video)
                val audio = clipper.extractAudio(video, src) { line -> runOnUiThread { status.text = line } }
                val picks = try {
                    clipper.pick(audio, words, want, src.durationS) { line -> runOnUiThread { status.text = line } }
                } finally {
                    audio.delete()
                }
                if (picks.isEmpty()) {
                    RunLog.add(app, "clips none: " + clipper.lastError)
                    runOnUiThread { status.text = "No clips made: " + clipper.lastError.ifEmpty { "Gemini found nothing" } }
                } else {
                    val stamp = SimpleDateFormat("MMdd_HHmm", Locale.ENGLISH).format(Date())
                    picks.forEachIndexed { i, pick ->
                        val label = "Clip ${i + 1} of ${picks.size}"
                        try {
                            val uri = clipper.cut(video, src, pick, zoom, ClipLogic.fileName(i, stamp)) { pct ->
                                runOnUiThread { status.text = "Making $label: $pct%" }
                            }
                            made++
                            val caption = ClipLogic.caption(pick, hashtags)
                            runOnUiThread { showClip(i, pick, uri, caption) }
                        } catch (e: Exception) {
                            RunLog.add(app, "clip cut failed " + e.message)
                            runOnUiThread { results.addView(text("$label not made: " + (e.message ?: "unknown problem"), 14f)) }
                        }
                    }
                    RunLog.add(app, "clips made $made")
                    runOnUiThread {
                        status.text = if (made > 0) "$made clips ready in Gallery, Movies/Sahayak/Clips." else "Clips could not be made."
                    }
                }
            } catch (e: Exception) {
                RunLog.add(app, "clips failed " + e.message)
                runOnUiThread { status.text = "Clips not made: " + (e.message ?: "unknown problem") }
            }
            runOnUiThread {
                working = false
                makeButton.isEnabled = true
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }.start()
    }

    private fun showClip(i: Int, pick: ClipPick, uri: Uri, caption: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        box.addView(text("Clip ${i + 1}: " + pick.hook.ifBlank { "No title" }, 16f, true))
        box.addView(text(ClipLogic.clock(pick.startS) + " to " + ClipLogic.clock(pick.endS) + " (" + pick.seconds.toInt() + " sec)", 13f))
        box.addView(text("Caption:\n$caption", 13f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("Play") { play(uri) })
        buttons.addView(button("Share") { share(uri, caption) })
        buttons.addView(button("Add to Instagram list") { addToReels(uri, caption) })
        box.addView(buttons)
        results.addView(box)
        results.visibility = View.VISIBLE
    }

    private fun play(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: android.content.ActivityNotFoundException) {
            status.text = "No video player found."
        }
    }

    private fun share(uri: Uri, caption: String) {
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, caption).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share clip"
        ))
    }

    private fun addToReels(uri: Uri, caption: String) {
        var name = "clip.mp4"
        var size = 0L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                name = it.getString(0).orEmpty()
                size = it.getLong(1)
            }
        }
        val added = ReelsQueue.add(this, uri, name, size, true)
        if (added) ReelsQueue.items(this).lastOrNull()?.optString("id")?.let { ReelsQueue.setCaption(this, it, caption) }
        status.text = if (added) "Added to the Instagram list with its caption." else "Already in the Instagram list."
    }

    companion object {
        private const val PICK_VIDEO = 5_101
        private const val COUNT_IDS = 6_000
        private const val LOOK_IDS = 6_100
    }
}
