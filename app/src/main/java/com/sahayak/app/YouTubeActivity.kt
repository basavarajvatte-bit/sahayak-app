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
    private val queue: UploadQueue by lazy { if (intent.getBooleanExtra(EXTRA_INSTAGRAM, false)) ReelsQueue else ShortsQueue }
    private lateinit var status: TextView
    private lateinit var stats: TextView
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
        val set = queue.settings(this)
        from = set.from
        to = set.to
        page.addView(text(queue.label + " Auto Upload", 24f, true))
        status = text("", 15f, false)
        page.addView(status)

        stats = text("", 14f, false)
        page.addView(stats)
        page.addView(text(if (queue.instagram) "Reels go to the account that is open in the Instagram app." else "Videos go to the channel that is open in the YouTube app.", 14f, false))

        page.addView(heading("Caption"))
        page.addView(text("Write one caption set per line. Each video uses the next line, so captions are not all the same.", 14f, false))
        tags = input("#baby #babydance", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        tags.minLines = 2
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

        audience = radios(AUDIENCE_IDS, listOf("Made for kids", "Not made for kids"), if (set.kids) 0 else 1)
        visibility = radios(VISIBILITY_IDS, VISIBILITY.map { it.replaceFirstChar(Char::uppercase) }, VISIBILITY.indexOf(set.visibility).coerceAtLeast(0))
        if (!queue.instagram) {
            page.addView(heading("Audience"))
            page.addView(audience)
            page.addView(heading("Visibility"))
            page.addView(visibility)
        }

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
        page.addView(button("Upload next video now") { uploadNow(false) })
        page.addView(button("Upload all waiting videos now") { uploadNow(true) })
        page.addView(button("Retry failed videos") {
            queue.retryFailed(this)
            queue.rearm(this)
            render()
        })
        page.addView(button("Clear uploaded from list") {
            queue.clearUploaded(this)
            render()
        })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(listBox)
    }

    override fun onResume() {
        super.onResume()
        queue.rearm(this)
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

    private fun current(): UploadQueue.Settings = queue.settings(this).copy(
        tags = tags.text.toString().trim(),
        useName = useName.isChecked,
        kids = audience.checkedRadioButtonId == AUDIENCE_IDS,
        visibility = VISIBILITY[(visibility.checkedRadioButtonId - VISIBILITY_IDS).coerceIn(0, VISIBILITY.lastIndex)],
        perDay = perDay.text.toString().toIntOrNull()?.coerceIn(1, MAX_PER_DAY) ?: DEFAULT_PER_DAY,
        from = from,
        to = to
    )

    private fun saveAll() = queue.saveSettings(this, current())

    private fun timeLabel(minute: Int): String {
        val hour = (minute / 60).let { if (it % 12 == 0) 12 else it % 12 }
        return String.format(Locale.ENGLISH, "%d:%02d %s", hour, minute % 60, if (minute < 720) "AM" else "PM")
    }

    private fun pickTime(first: Boolean) {
        val value = if (first) from else to
        TimePickerDialog(this, { _, hour, minute ->
            if (first) from = hour * 60 + minute else to = hour * 60 + minute
            saveAll()
            if (queue.settings(this).auto) queue.start(this)
            render()
        }, value / 60, value % 60, false).show()
    }

    private fun showPreview() {
        val sample = queue.items(this).firstOrNull { it.optString("status") == queue.WAITING }?.optString("name") ?: "10_Robot_Dance"
        val turn = queue.turn(this)
        val sets = ShortsLogic.captionSets(tags.text.toString()).size.coerceAtLeast(1)
        preview.text = (0 until minOf(sets, 3)).joinToString("\n") {
            "Video " + (it + 1) + ": " + ShortsLogic.caption(sample, ShortsLogic.pickTags(tags.text.toString(), turn + it), useName.isChecked)
        }
    }

    private fun ready(): Boolean {
        if (PhoneControl.instance == null) {
            status.text = "Turn on phone control in Sahayak first."
            return false
        }
        return true
    }

    private fun toggleAuto() {
        if (queue.settings(this).auto) {
            queue.stop(this)
        } else if (ready()) {
            queue.start(this)
        }
        render()
    }

    private fun uploadNow(all: Boolean) {
        if (!ready()) return
        if (queue.count(this, queue.WAITING) == 0) {
            status.text = "No videos waiting. Press Add videos."
            return
        }
        val app = applicationContext
        moveTaskToBack(true)
        Thread { if (all) queue.runAll(app) else queue.run(app, false) }.start()
    }

    private fun pickFiles() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(pick, REQUEST_FILES)
        } catch (e: android.content.ActivityNotFoundException) {
            tell("This phone cannot open the file picker. Use Add a folder of videos.")
        }
    }

    /** Message hamesha dikhe, chahe page neeche scroll ho. */
    private fun tell(words: String) {
        status.text = words
        android.widget.Toast.makeText(this, words, android.widget.Toast.LENGTH_LONG).show()
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
                if (queue.add(this, video.uri, video.name, video.size, false)) added++
            } catch (e: SecurityException) {
                kept = false
            }
        }
        val old = uris.size - added
        tell(added.toString() + " videos added." +
            (if (old > 0 && kept) " $old were already in the list or uploaded before." else "") +
            (if (kept) "" else " Too many single videos. Use Add a folder of videos."))
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
        val added = videos.sortedBy { it.name.lowercase() }.count { queue.add(this, it.uri, it.name, it.size, true) }
        val old = videos.size - added
        tell(
            if (videos.isEmpty()) "No videos found in this folder."
            else added.toString() + " videos added from the folder." + (if (old > 0) " $old were already in the list or uploaded before." else "")
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            tell("No video chosen.")
            return
        }
        if (requestCode == REQUEST_FILES) addFiles(data)
        if (requestCode == REQUEST_FOLDER) data.data?.let { addFolder(it) }
        queue.rearm(this)
        renderList()
    }

    private fun render() {
        val set = queue.settings(this)
        val today = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
        }.timeInMillis
        stats.text = "Uploaded today: " + queue.uploadedSince(this, today) + "   Total: " + queue.totalUploaded(this) +
            "   Failed: " + queue.count(this, queue.FAILED)
        fromButton.text = "From " + timeLabel(from)
        toButton.text = "To " + timeLabel(to)
        autoButton.text = if (set.auto) "Stop automatic uploads" else "Start automatic uploads"
        val waiting = queue.count(this, queue.WAITING)
        val next = queue.nextAt(this)
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
        val items = queue.items(this).sortedBy { order(it.optString("status")) }
        val plan = queue.planned(this)
        val set = queue.settings(this)
        val turn = queue.turn(this)
        var waitingIndex = 0
        items.take(MAX_ROWS).forEach { item ->
            val id = item.optString("id")
            val state = item.optString("status")
            val reason = item.optString("reason")
            val caption = if (state == queue.WAITING) queue.captionFor(this, item, set, turn + waitingIndex++) else ""
            val whenText = plan[id]?.let { "  ·  " + shown.format(Date(it)) } ?: ""
            val doneText = item.optLong("at").takeIf { it > 0 }?.let { "  ·  " + shown.format(Date(it)) } ?: ""
            listBox.addView(text(
                item.optString("name") + "\n" + state.replaceFirstChar(Char::uppercase) + whenText + doneText +
                    (if (reason.isEmpty()) "" else ": $reason") + (if (caption.isEmpty()) "" else "\n" + caption), 14f, false
            ).apply { setPadding(0, dp(10), 0, dp(2)) })
            val actions = LinearLayout(this)
            if (state == queue.WAITING) {
                actions.addView(button("Caption") { editCaption(item) })
                actions.addView(button("Top") {
                    queue.moveFirst(this, id)
                    render()
                })
            }
            actions.addView(button("Remove") {
                queue.remove(this, id)
                render()
            })
            listBox.addView(actions)
        }
        if (items.size > MAX_ROWS) listBox.addView(text("And " + (items.size - MAX_ROWS) + " more videos", 14f, false))
    }

    /** Sirf is video ka caption. Khali chhodo to upar wala caption set lagega. */
    private fun editCaption(item: org.json.JSONObject) {
        val box = input("Leave empty to use the caption sets", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        box.setText(item.optString("caption"))
        android.app.AlertDialog.Builder(this)
            .setTitle(item.optString("name"))
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                queue.setCaption(this, item.optString("id"), box.text.toString().take(ShortsLogic.MAX_CAPTION))
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun order(state: String) = when (state) {
        queue.FAILED -> 0
        queue.WAITING -> 1
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
        private const val EXTRA_INSTAGRAM = "instagram"

        fun open(context: android.content.Context, instagram: Boolean): Intent =
            Intent(context, YouTubeActivity::class.java).putExtra(EXTRA_INSTAGRAM, instagram)
    }
}
