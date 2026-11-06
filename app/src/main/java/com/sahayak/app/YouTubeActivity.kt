package com.sahayak.app

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
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

/** YouTube Auto Upload ki alag screen. */
class YouTubeActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var channel: EditText
    private lateinit var tags: EditText
    private lateinit var useName: CheckBox
    private lateinit var preview: TextView
    private lateinit var audience: RadioGroup
    private lateinit var visibility: RadioGroup
    private lateinit var perDay: EditText
    private lateinit var fromButton: Button
    private lateinit var toButton: Button
    private lateinit var autoButton: Button
    private lateinit var listBox: LinearLayout
    private var from = 0
    private var to = 0
    private val shown = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F6FA.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(page) })
        val set = ShortsQueue.settings(this)
        from = set.from
        to = set.to
        page.addView(text("YouTube Auto Upload", 24f, true))
        status = text("", 15f, false)
        page.addView(status)

        page.addView(heading("Channel"))
        channel = input("Channel handle, like @VarshaVatte", InputType.TYPE_CLASS_TEXT)
        channel.setText(ShortsLogic.channelValue(set.channel))
        page.addView(channel)

        page.addView(heading("Caption"))
        tags = input("#baby #babydance", InputType.TYPE_CLASS_TEXT)
        tags.setText(set.tags)
        page.addView(tags)
        useName = CheckBox(this).apply {
            text = "Add the video name before the hashtags"
            isChecked = set.useName
            setOnCheckedChangeListener { _, _ -> showPreview() }
        }
        page.addView(useName)
        preview = text("", 14f, false)
        page.addView(preview)
        tags.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = showPreview()
        })

        page.addView(heading("Audience"))
        audience = radios(AUDIENCE_IDS, listOf("Made for kids", "Not made for kids"), if (set.kids) 0 else 1)
        page.addView(audience)

        page.addView(heading("Visibility"))
        visibility = radios(VISIBILITY_IDS, VISIBILITY.map { it.replaceFirstChar(Char::uppercase) }, VISIBILITY.indexOf(set.visibility).coerceAtLeast(0))
        page.addView(visibility)

        page.addView(heading("Daily schedule"))
        perDay = input("Videos per day", InputType.TYPE_CLASS_NUMBER)
        perDay.setText(set.perDay.toString())
        page.addView(perDay)
        val times = LinearLayout(this)
        fromButton = button("") { pickTime(true) }
        toButton = button("") { pickTime(false) }
        times.addView(fromButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(toButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(times)
        autoButton = button("") { toggleAuto() }
        page.addView(autoButton)

        page.addView(heading("Videos"))
        page.addView(button("Add videos") { pickFiles() })
        page.addView(button("Add a folder of videos") { pickFolder() })
        page.addView(button("Upload next video now") { uploadNow() })
        page.addView(button("Retry failed videos") {
            ShortsQueue.retryFailed(this)
            ShortsQueue.rearm(this)
            render()
        })
        page.addView(button("Clear uploaded from list") {
            ShortsQueue.clearUploaded(this)
            render()
        })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(listBox)
    }

    override fun onResume() {
        super.onResume()
        ShortsQueue.rearm(this)
        render()
    }

    override fun onPause() {
        super.onPause()
        saveAll()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun text(words: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = words
        textSize = size
        setTextColor(0xFF1D2433.toInt())
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun heading(words: String) = text(words, 16f, true).apply { setPadding(0, dp(18), 0, dp(4)) }

    private fun input(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        textSize = 16f
        importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun button(words: String, action: () -> Unit) = Button(this).apply {
        text = words
        isAllCaps = false
        setOnClickListener {
            saveAll()
            action()
        }
    }

    private fun radios(base: Int, names: List<String>, selected: Int) = RadioGroup(this).apply {
        names.forEachIndexed { index, name ->
            addView(RadioButton(context).apply {
                id = base + index
                text = name
                textSize = 16f
            })
        }
        check(base + selected)
    }

    private fun current(): ShortsQueue.Settings = ShortsQueue.settings(this).copy(
        channel = ShortsLogic.channelValue(channel.text.toString()),
        tags = tags.text.toString().trim(),
        useName = useName.isChecked,
        kids = audience.checkedRadioButtonId == AUDIENCE_IDS,
        visibility = VISIBILITY[(visibility.checkedRadioButtonId - VISIBILITY_IDS).coerceIn(0, VISIBILITY.lastIndex)],
        perDay = perDay.text.toString().toIntOrNull()?.coerceIn(1, MAX_PER_DAY) ?: DEFAULT_PER_DAY,
        from = from,
        to = to
    )

    private fun saveAll() = ShortsQueue.saveSettings(this, current())

    private fun timeLabel(minute: Int): String {
        val hour = (minute / 60).let { if (it % 12 == 0) 12 else it % 12 }
        return String.format(Locale.ENGLISH, "%d:%02d %s", hour, minute % 60, if (minute < 720) "AM" else "PM")
    }

    private fun pickTime(first: Boolean) {
        val value = if (first) from else to
        TimePickerDialog(this, { _, hour, minute ->
            if (first) from = hour * 60 + minute else to = hour * 60 + minute
            saveAll()
            if (ShortsQueue.settings(this).auto) ShortsQueue.start(this)
            render()
        }, value / 60, value % 60, false).show()
    }

    private fun showPreview() {
        val sample = ShortsQueue.items(this).firstOrNull { it.optString("status") == ShortsQueue.WAITING }?.optString("name") ?: "10_Robot_Dance"
        preview.text = "Caption: " + ShortsLogic.caption(sample, tags.text.toString(), useName.isChecked)
    }

    private fun ready(): Boolean {
        if (channel.text.isBlank()) {
            status.text = "Type your channel name first."
            return false
        }
        if (PhoneControl.instance == null) {
            status.text = "Turn on phone control in Sahayak first."
            return false
        }
        return true
    }

    private fun toggleAuto() {
        if (ShortsQueue.settings(this).auto) {
            ShortsQueue.stop(this)
        } else if (ready()) {
            ShortsQueue.start(this)
        }
        render()
    }

    private fun uploadNow() {
        if (!ready()) return
        if (ShortsQueue.count(this, ShortsQueue.WAITING) == 0) {
            status.text = "No videos waiting. Press Add videos."
            return
        }
        val app = applicationContext
        moveTaskToBack(true)
        Thread { ShortsQueue.run(app, false) }.start()
    }

    private fun pickFiles() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            REQUEST_FILES
        )
    }

    private fun pickFolder() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            REQUEST_FOLDER
        )
    }

    private data class Video(val uri: Uri, val name: String, val size: Long)

    private fun describe(uri: Uri): Video {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { row ->
            if (row.moveToFirst()) return Video(uri, row.getString(0).orEmpty(), row.getLong(1))
        }
        return Video(uri, uri.lastPathSegment.orEmpty(), 0L)
    }

    private fun addFiles(data: Intent) {
        val clip = data.clipData
        val uris = if (clip != null) List(clip.itemCount) { clip.getItemAt(it).uri } else listOfNotNull(data.data)
        var added = 0
        var kept = true
        uris.map { describe(it) }.sortedBy { it.name.lowercase() }.forEach { video ->
            try {
                contentResolver.takePersistableUriPermission(video.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (ShortsQueue.add(this, video.uri, video.name, video.size, false)) added++
            } catch (e: SecurityException) {
                kept = false
            }
        }
        status.text = added.toString() + " videos added." + if (kept) "" else " Too many single videos. Use Add a folder of videos."
    }

    private fun addFolder(tree: Uri) {
        contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE
        )
        val videos = ArrayList<Video>()
        contentResolver.query(children, columns, null, null, null)?.use { row ->
            while (row.moveToNext()) {
                if (row.getString(2).orEmpty().startsWith("video/")) {
                    videos.add(Video(DocumentsContract.buildDocumentUriUsingTree(tree, row.getString(0)), row.getString(1).orEmpty(), row.getLong(3)))
                }
            }
        }
        val added = videos.sortedBy { it.name.lowercase() }.count { ShortsQueue.add(this, it.uri, it.name, it.size, true) }
        status.text = added.toString() + " videos added from the folder."
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQUEST_FILES) addFiles(data)
        if (requestCode == REQUEST_FOLDER) data.data?.let { addFolder(it) }
        ShortsQueue.rearm(this)
        renderList()
    }

    private fun render() {
        val set = ShortsQueue.settings(this)
        fromButton.text = "From " + timeLabel(from)
        toButton.text = "To " + timeLabel(to)
        autoButton.text = if (set.auto) "Stop automatic uploads" else "Start automatic uploads"
        val waiting = ShortsQueue.count(this, ShortsQueue.WAITING)
        val next = ShortsQueue.nextAt(this)
        status.text = when {
            PhoneControl.instance == null -> "Turn on phone control in Sahayak first."
            set.auto && next > 0 -> "$waiting videos waiting. Next upload: " + shown.format(Date(next))
            else -> "$waiting videos waiting. Automatic uploads are off."
        }
        showPreview()
        renderList()
    }

    private fun renderList() {
        listBox.removeAllViews()
        val items = ShortsQueue.items(this).sortedBy { order(it.optString("status")) }
        items.take(MAX_ROWS).forEach { item ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val reason = item.optString("reason")
            row.addView(
                text(item.optString("name") + "\n" + item.optString("status").replaceFirstChar(Char::uppercase) +
                    (if (reason.isEmpty()) "" else ": $reason"), 14f, false),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(button("Remove") {
                ShortsQueue.remove(this, item.optString("id"))
                render()
            })
            listBox.addView(row)
        }
        if (items.size > MAX_ROWS) listBox.addView(text("And " + (items.size - MAX_ROWS) + " more videos", 14f, false))
    }

    private fun order(state: String) = when (state) {
        ShortsQueue.FAILED -> 0
        ShortsQueue.WAITING -> 1
        else -> 2
    }

    companion object {
        private const val REQUEST_FILES = 1
        private const val REQUEST_FOLDER = 2
        private const val MAX_PER_DAY = 50
        private const val DEFAULT_PER_DAY = 6
        private const val MAX_ROWS = 40
        private const val AUDIENCE_IDS = 1_000
        private const val VISIBILITY_IDS = 2_000
        private val VISIBILITY = listOf("public", "unlisted", "private")
    }
}
