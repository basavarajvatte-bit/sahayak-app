package com.sahayak.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView

/** Idea likho, Sahayak animated video bana deta hai. */
class VideoActivity : Activity() {
    private lateinit var idea: EditText
    private lateinit var who: RadioGroup
    private lateinit var length: RadioGroup
    private lateinit var status: TextView
    private lateinit var script: TextView
    private lateinit var player: VideoView
    private lateinit var after: LinearLayout
    private lateinit var makeButton: Button
    private var made: Uri? = null
    private var story: Story? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F6FA.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(page) })
        page.addView(text("Idea Video", 24f, true))
        page.addView(text("Write your idea. Sahayak writes a small story and makes an animated video on this phone.", 14f))
        idea = EditText(this).apply {
            hint = "For example: Baby dances in the jungle and meets a happy frog"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
        }
        page.addView(idea)
        page.addView(text("Character", 16f, true))
        who = radios(listOf("Baby", "Chhotu"), WHO_IDS)
        page.addView(who)
        page.addView(text("Length", 16f, true))
        length = radios(listOf("15 sec", "30 sec", "60 sec"), LENGTH_IDS)
        page.addView(length)
        makeButton = button("Make video") { make() }
        page.addView(makeButton)
        status = text("", 15f, true)
        page.addView(status)
        player = VideoView(this)
        page.addView(player, LinearLayout.LayoutParams(dp(270), dp(480)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
        player.visibility = android.view.View.GONE
        after = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        after.addView(button("Add to YouTube list") { addTo(ShortsQueue) })
        after.addView(button("Add to Instagram list") { addTo(ReelsQueue) })
        after.addView(button("Share") { share() })
        after.addView(button("Make another version") { make() })
        page.addView(after)
        script = text("", 14f)
        page.addView(script)
    }

    private fun make() {
        if (working) return
        val words = idea.text.toString().trim()
        if (words.isEmpty()) {
            status.text = "Write your idea first."
            return
        }
        val key = SecureStore(getSharedPreferences("sahayak", Context.MODE_PRIVATE)).load()
        if (key.isEmpty()) {
            status.text = "Add your Gemini key in Sahayak first."
            return
        }
        val character = if (who.checkedRadioButtonId == WHO_IDS) "baby" else "chhotu"
        val seconds = listOf(15, 30, 60)[(length.checkedRadioButtonId - LENGTH_IDS).coerceIn(0, 2)]
        working = true
        makeButton.isEnabled = false
        after.visibility = android.view.View.GONE
        player.visibility = android.view.View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "Writing the story"
        val app = applicationContext
        Thread {
            val result = try {
                val answer = Planner(GeminiLive.newClient(), key, STORY_TIMEOUT_S).storyboard(words, character, seconds)
                val built = answer?.let { StoryLogic.parse(it, character) } ?: throw IllegalStateException("Could not write the story. Try again")
                runOnUiThread { showScript(built) }
                val uri = VideoMaker(app).make(built) { line -> runOnUiThread { status.text = line } }
                RunLog.add(app, "idea video made " + built.title)
                built to uri
            } catch (e: Exception) {
                RunLog.add(app, "idea video failed " + e.message)
                runOnUiThread { status.text = "Video not made: " + (e.message ?: "unknown problem") }
                null
            }
            runOnUiThread {
                working = false
                makeButton.isEnabled = true
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (result != null) show(result.first, result.second)
            }
        }.start()
    }

    private fun showScript(built: Story) {
        story = built
        script.text = "Story: " + built.title + "\n" + built.scenes.joinToString("\n") {
            "• " + it.place + ", " + it.action + (if (it.line.isBlank()) "" else ": \"" + it.line + "\"")
        }
    }

    private fun show(built: Story, uri: Uri) {
        made = uri
        status.text = "Video ready and saved in Gallery, Movies/Sahayak."
        player.visibility = android.view.View.VISIBLE
        player.setVideoURI(uri)
        player.setOnPreparedListener { it.isLooping = true; player.start() }
        after.visibility = android.view.View.VISIBLE
        if (built.caption.isNotBlank()) script.append("\n\nCaption idea: " + built.caption)
    }

    private fun addTo(queue: UploadQueue) {
        val uri = made ?: return
        var name = "video.mp4"
        var size = 0L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                name = it.getString(0).orEmpty()
                size = it.getLong(1)
            }
        }
        val added = queue.add(this, uri, name, size, true)
        story?.caption?.takeIf { added && it.isNotBlank() }?.let { caption ->
            queue.items(this).lastOrNull()?.optString("id")?.let { queue.setCaption(this, it, caption) }
        }
        status.text = if (added) "Added to the " + queue.label + " list." else "Already in the " + queue.label + " list."
    }

    private fun share() {
        val uri = made ?: return
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share video"
        ))
    }

    companion object {
        private const val WHO_IDS = 3_000
        private const val LENGTH_IDS = 4_000
        private const val STORY_TIMEOUT_S = 60L
    }
}
