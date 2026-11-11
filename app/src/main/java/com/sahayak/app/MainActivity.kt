package com.sahayak.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var assistButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var keyInput: EditText
    private lateinit var languageGroup: RadioGroup
    private lateinit var voiceGroup: RadioGroup
    private lateinit var nameInput: EditText
    private lateinit var autoStep: CheckBox
    private lateinit var controlButton: Button
    private lateinit var batteryButton: Button
    private lateinit var settingsPanel: View
    private val prefs by lazy { getSharedPreferences("sahayak", MODE_PRIVATE) }
    private val secure by lazy { SecureStore(prefs) }
    private val whisperStore by lazy { SecureStore(prefs, "whisper_key_sealed") }
    private lateinit var whisperInput: EditText
    private lateinit var taskVoxInput: EditText
    private val taskVoxStore by lazy { SecureStore(prefs, "taskvox_key_sealed") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        assistButton = findViewById(R.id.assistButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.statusText)
        keyInput = findViewById(R.id.keyInput)
        languageGroup = findViewById(R.id.languageGroup)
        voiceGroup = findViewById(R.id.voiceGroup)
        nameInput = findViewById(R.id.nameInput)
        autoStep = findViewById(R.id.autoStep)
        controlButton = findViewById(R.id.controlButton)
        batteryButton = findViewById(R.id.batteryButton)
        settingsPanel = findViewById(R.id.settingsPanel)
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        batteryButton.setOnClickListener { askBattery() }
        findViewById<Button>(R.id.teachButton).setOnClickListener { askTeachName() }
        findViewById<CheckBox>(R.id.mailBuddy).apply {
            isChecked = prefs.getBoolean("mail_buddy", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("mail_buddy", on).apply() }
        }
        findViewById<CheckBox>(R.id.todoSpeak).apply {
            isChecked = prefs.getBoolean("todo_speak", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("todo_speak", on).apply() }
        }
        findViewById<Button>(R.id.todoAdd).setOnClickListener {
            val input = findViewById<EditText>(R.id.todoInput)
            Todos.add(this, input.text.toString())
            input.setText("")
            renderTodos()
        }
        findViewById<Button>(R.id.youtubeButton).setOnClickListener {
            startActivity(YouTubeActivity.open(this, false))
        }
        findViewById<Button>(R.id.instagramButton).setOnClickListener {
            startActivity(YouTubeActivity.open(this, true))
        }
        findViewById<Button>(R.id.watchButton).setOnClickListener {
            startActivity(Intent(this, WatchActivity::class.java))
        }
        findViewById<Button>(R.id.ideaVideoButton).setOnClickListener {
            startActivity(Intent(this, VideoActivity::class.java))
        }
        val learnBox = findViewById<CheckBox>(R.id.learnBox)
        learnBox.isChecked = DailyLearning.isEnabled(this)
        learnBox.setOnCheckedChangeListener { _, on ->
            DailyLearning.setEnabled(this, on)
            renderMemory()
        }
        findViewById<Button>(R.id.clearLearning).setOnClickListener {
            DailyLearning.clear(this)
            Memory.all(this).forEach { Memory.remove(this, it) }
            Brain.clear(this)
            renderMemory()
            statusText.text = "All learned data deleted."
        }
        DailyLearning.enableOnce(this)
        learnBox.isChecked = DailyLearning.isEnabled(this)
        DailyLearning.runIfDue(this)
        findViewById<Button>(R.id.backupButton).setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "sahayak-backup.json"),
                REQUEST_BACKUP
            )
        }
        findViewById<Button>(R.id.restoreButton).setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                REQUEST_RESTORE
            )
        }
        findViewById<Button>(R.id.reportButton).setOnClickListener {
            val report = RunLog.read(this).ifEmpty { "No records yet" }
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Sahayak report", report))
            findViewById<TextView>(R.id.reportText).text = report.takeLast(1_500)
            statusText.text = "Report copied."
        }
        val volumeBar = findViewById<SeekBar>(R.id.volumeBar)
        volumeBar.progress = prefs.getInt(PREF_VOLUME, DEFAULT_VOLUME)
        AssistState.volume = volumeBar.progress.coerceAtLeast(MIN_VOLUME) / 100f
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                AssistState.volume = progress.coerceAtLeast(MIN_VOLUME) / 100f
                prefs.edit().putInt(PREF_VOLUME, progress).apply()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        controlButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        autoStep.isChecked = prefs.getBoolean(PREF_AUTO, false)

        whisperInput = findViewById(R.id.whisperInput)
        whisperInput.setText(whisperStore.load())
        taskVoxInput = findViewById(R.id.taskVoxInput)
        taskVoxInput.setText(taskVoxStore.load())
        val savedKey = secure.load()
        keyInput.setText(savedKey)
        if (savedKey.isEmpty()) settingsPanel.visibility = View.VISIBLE
        languageGroup.check(
            if (prefs.getString(PREF_LANGUAGE, HINDI) == MARATHI) R.id.langMarathi else R.id.langHindi
        )
        nameInput.setText(prefs.getString(PREF_NAME, ""))
        voiceGroup.check(
            when (prefs.getString(PREF_VOICE, VOICE_FEMALE)) {
                VOICE_MALE -> R.id.voiceMale
                VOICE_SOFT -> R.id.voiceSoft
                VOICE_DEEP -> R.id.voiceDeep
                else -> R.id.voiceFemale
            }
        )
        assistButton.setOnClickListener { onAssist() }
        stopButton.setOnClickListener { AssistService.stop(this) }
    }

    override fun onResume() {
        super.onResume()
        AssistState.listener = { render() }
        render()
    }

    override fun onPause() {
        AssistState.listener = null
        super.onPause()
    }

    private fun render() {
        val running = AssistState.running
        assistButton.visibility = if (running) View.GONE else View.VISIBLE
        stopButton.visibility = if (running) View.VISIBLE else View.GONE
        statusText.text = AssistState.status
        controlButton.text = if (PhoneControl.instance != null) "✓ Phone control is on" else "Turn on phone control"
        renderWork()
        renderMemory()
        renderSkills()
        renderRatings()
        renderTodos()
        batteryButton.text = if (batteryFree()) "✓ Background use allowed" else "Allow background use"
    }

    private fun handleBackup(request: Int, uri: Uri) {
        statusText.text = try {
            if (request == REQUEST_BACKUP) {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(Skills.exportJson(this).toByteArray()) }
                "Backup saved. Keep the file in Google Drive too."
            } else {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
                val added = Skills.importJson(this, text)
                renderSkills()
                renderMemory()
                "Backup restored: $added new tasks."
            }
        } catch (e: IOException) {
            "Backup failed. Please try again."
        } catch (e: JSONException) {
            "This is not a valid Sahayak backup file."
        } catch (e: IllegalArgumentException) {
            "This is not a valid Sahayak backup file."
        }
    }

    private fun askTeachName() {
        val control = PhoneControl.instance
        if (control == null) {
            statusText.text = "Turn on phone control first."
            return
        }
        val input = EditText(this).apply { hint = "For example: Send photo to Mom" }
        AlertDialog.Builder(this)
            .setTitle("Task name")
            .setView(input)
            .setPositiveButton("Start") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    control.startTeaching(name)
                    moveTaskToBack(true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setOnClickListener { action() }
    }

    private fun askLesson(name: String) {
        val input = EditText(this).apply { hint = "What went wrong, or how should it be done" }
        AlertDialog.Builder(this)
            .setTitle("Write a correction")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                Skills.markWrong(this, name, input.text.toString().trim())
                renderSkills()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSteps(name: String) {
        val demo = Skills.lastDemo(this, name)
        val steps = demo.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tap a wrong step to remove it")
            .setItems(steps) { _, which ->
                Skills.removeStep(this, name, steps[which])
                renderSkills()
                statusText.text = "Step removed."
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun renderSkills() {
        val box = findViewById<LinearLayout>(R.id.skillBox)
        box.removeAllViews()
        Skills.all(this).forEach { skill ->
            val name = skill.optString("name")
            box.addView(TextView(this).apply {
                text = Skills.label(skill)
                textSize = 16f
                setTextColor(0xFF1D2433.toInt())
                setPadding(0, 18, 0, 0)
            })
            val weight = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val top = LinearLayout(this)
            top.addView(smallButton("Repeat") {
                if (AssistState.running) {
                    AssistService.practice(this, name)
                    moveTaskToBack(true)
                } else {
                    statusText.text = "Press Assist first, then press Repeat."
                }
            }, weight)
            top.addView(smallButton("Teach more") {
                val control = PhoneControl.instance
                if (control == null) {
                    statusText.text = "Turn on phone control first."
                } else {
                    control.startTeaching(name)
                    moveTaskToBack(true)
                }
            }, LinearLayout.LayoutParams(weight))
            val bottom = LinearLayout(this)
            bottom.addView(smallButton("Wrong") { askLesson(name) }, LinearLayout.LayoutParams(weight))
            bottom.addView(smallButton("Steps") { showSteps(name) }, LinearLayout.LayoutParams(weight))
            bottom.addView(smallButton("Remove") {
                AlertDialog.Builder(this)
                    .setMessage("Delete this taught task?")
                    .setPositiveButton("Delete") { _, _ ->
                        Skills.remove(this, name)
                        renderSkills()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }, LinearLayout.LayoutParams(weight))
            box.addView(top)
            box.addView(bottom)
        }
    }

    /** More dabaya ho to poori list, warna sirf 4 kaam. */
    private var todoOpen = false

    private fun renderTodos() {
        val box = findViewById<LinearLayout>(R.id.todoBox)
        box.removeAllViews()
        val all = Todos.all(this).sortedBy { it.optBoolean("done") }
        val pending = all.count { !it.optBoolean("done") }
        val shown = if (todoOpen) all else all.filter { !it.optBoolean("done") }.take(TODO_ROWS)
        shown.forEach { item ->
            val done = item.optBoolean("done")
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = (if (done) "✓ " else "") + item.optString("text")
                textSize = 16f
                setTextColor(if (done) 0xFF667085.toInt() else 0xFF1D2433.toInt())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (!done) {
                row.addView(smallButton("Done") {
                    Todos.complete(this, item.optString("id"))
                    renderTodos()
                })
            }
            row.addView(smallButton("Remove") {
                Todos.remove(this, item.optString("id"))
                renderTodos()
            })
            box.addView(row)
        }
        val hidden = all.size - shown.size
        if (hidden > 0 || todoOpen) {
            box.addView(smallButton(if (todoOpen) "Less" else "More ($hidden)") {
                todoOpen = !todoOpen
                renderTodos()
            })
        }
        if (pending == 0 && !todoOpen) {
            box.addView(TextView(this).apply { text = "No tasks left."; textSize = 15f })
        }
    }

    private fun renderRatings() {
        val box = findViewById<LinearLayout>(R.id.ratingBox)
        box.removeAllViews()
        val items = Ratings.pending(this).reversed()
        findViewById<View>(R.id.ratingCard).visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        items.forEach { item ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = item.optString("order")
                textSize = 15f
                setTextColor(0xFF1D2433.toInt())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(smallButton("Rate") { pickRating(item) })
            row.addView(smallButton("Skip") {
                Ratings.dropPending(this, item.optString("id"))
                renderRatings()
            })
            box.addView(row)
        }
    }

    private fun pickRating(item: JSONObject) {
        val numbers = Array(10) { (it + 1).toString() }
        AlertDialog.Builder(this)
            .setTitle("Rate 1 to 10")
            .setItems(numbers) { _, which -> saveRating(item, which + 1) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveRating(item: JSONObject, score: Int) {
        val order = item.optString("order")
        val skill = item.optString("skill")
        val id = Ratings.add(this, order, score, item.optString("steps"))
        if (skill.isNotEmpty()) Skills.recordRating(this, skill, score)
        Ratings.dropPending(this, item.optString("id"))
        DailyLearning.note(this, "rating $score/10 for: $order")
        RunLog.add(this, "rating $score")
        renderRatings()
        renderSkills()
        renderMemory()
        if (score > LOW_RATING) return
        val input = EditText(this).apply { hint = "What went wrong?" }
        AlertDialog.Builder(this)
            .setTitle("Tell Sahayak what to fix")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val note = input.text.toString().trim()
                if (note.isNotEmpty()) {
                    Ratings.addNote(this, id, note)
                    if (skill.isNotEmpty()) Skills.markWrong(this, skill, note)
                    DailyLearning.note(this, "correction: $note")
                    renderSkills()
                }
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    private fun renderMemory() {
        val average = Ratings.average(this)
        findViewById<TextView>(R.id.ratingAverage).text = if (average == null) "" else
            "Average rating: " + String.format(Locale.ENGLISH, "%.1f", average) + " / 10"
        val summary = DailyLearning.summary(this)
        findViewById<TextView>(R.id.learnSummary).text = if (summary.isEmpty()) "" else "Learned today: $summary"
        val box = findViewById<LinearLayout>(R.id.memoryBox)
        box.removeAllViews()
        box.addView(TextView(this).apply {
            text = History.count(this@MainActivity).toString() + " past records saved on this phone"
            textSize = 15f
            setTextColor(0xFF1F5C4D.toInt())
        })
        val who = Brain.profile(this)
        if (who.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = "Profile: $who"
                textSize = 15f
                setTextColor(0xFF1D2433.toInt())
            })
        }
        Brain.books(this).forEach { book ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = "Learned: " + book.optString("name") + " (worked " + book.optInt("wins") + " times)"
                textSize = 15f
                setTextColor(0xFF1D2433.toInt())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(smallButton("Remove") {
                Brain.remove(this, book.optString("name"))
                renderMemory()
            })
            box.addView(row)
        }
        Memory.all(this).forEach { fact ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = fact
                textSize = 15f
                setTextColor(0xFF1D2433.toInt())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Remove"
                isAllCaps = false
                setOnClickListener {
                    Memory.remove(this@MainActivity, fact)
                    renderMemory()
                }
            })
            box.addView(row)
        }
    }

    private fun renderWork() {
        val box = findViewById<LinearLayout>(R.id.workBox)
        box.removeAllViews()
        val items = WorkList.all(this)
        findViewById<TextView>(R.id.workEmpty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.forEach { item ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val text = TextView(this).apply {
                setText(WorkList.label(item))
                textSize = 16f
                setTextColor(0xFF1D2433.toInt())
            }
            row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (item.optString("kind") == "youtube" && item.optString("status") == "pending") {
                row.addView(smallButton("Upload now") {
                    val id = item.optString("id")
                    val app = applicationContext
                    moveTaskToBack(true)
                    Thread { WorkList.run(app, id) }.start()
                })
            }
            row.addView(Button(this).apply {
                setText("Remove")
                isAllCaps = false
                setOnClickListener {
                    WorkList.remove(this@MainActivity, item.optString("id"))
                    renderWork()
                }
            })
            box.addView(row)
        }
    }

    private fun batteryFree(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun askBattery() {
        if (batteryFree()) return
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun language(): String =
        "English"

    private fun voice(): String = when (voiceGroup.checkedRadioButtonId) {
        R.id.voiceMale -> VOICE_MALE
        R.id.voiceSoft -> VOICE_SOFT
        R.id.voiceDeep -> VOICE_DEEP
        else -> VOICE_FEMALE
    }

    private fun onAssist() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) {
            statusText.text = "Add your Gemini key in Settings first."
            settingsPanel.visibility = View.VISIBLE
            keyInput.requestFocus()
            return
        }
        secure.save(key)
        whisperStore.save(whisperInput.text.toString().trim())
        taskVoxStore.save(taskVoxInput.text.toString().trim())
        prefs.edit().putString(PREF_LANGUAGE, language())
            .putString(PREF_NAME, nameInput.text.toString().trim()).putString(PREF_VOICE, voice())
            .putBoolean(PREF_AUTO, autoStep.isChecked).apply()

        if (PhoneControl.instance == null && !Settings.canDrawOverlays(this)) {
            statusText.text = "Allow Sahayak here, then come back and press Assist."
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        val needed = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (PhoneControl.instance != null &&
            checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.READ_CONTACTS)
        }
        if (PhoneControl.instance != null &&
            checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.CALL_PHONE)
        }
        if (needed.isEmpty()) askScreen() else requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            askScreen()
        } else {
            statusText.text = "Sahayak needs microphone permission to hear you."
        }
    }

    private fun askScreen() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Poori screen chahiye, kyunki madad ek app se doosre app tak chalti hai.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        startActivityForResult(intent, REQUEST_SCREEN)
    }

    @Deprecated("Plain Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_BACKUP || requestCode == REQUEST_RESTORE) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) handleBackup(requestCode, uri)
            return
        }
        if (requestCode != REQUEST_SCREEN) return
        if (resultCode == RESULT_OK && data != null) {
            AssistService.start(
                this, resultCode, data, secure.load(), language(),
                prefs.getString(PREF_NAME, "").orEmpty(), voice(), autoStep.isChecked, whisperStore.load(), taskVoxStore.load()
            )
            moveTaskToBack(true) // app peeche chala jaye, phone ka home dikhe
        } else {
            statusText.text = "Screen sharing was not allowed."
        }
    }

    companion object {
        private const val TODO_ROWS = 4
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_SCREEN = 2
        private const val LOW_RATING = 5
        private const val REQUEST_BACKUP = 3
        private const val REQUEST_RESTORE = 4
        private const val PREF_LANGUAGE = "language"
        private const val PREF_NAME = "name"
        private const val PREF_VOICE = "voice"
        private const val PREF_AUTO = "auto_step"
        private const val PREF_VOLUME = "volume"
        private const val DEFAULT_VOLUME = 50
        private const val MIN_VOLUME = 10
        private const val VOICE_FEMALE = "Aoede"
        private const val VOICE_MALE = "Puck"
        private const val VOICE_SOFT = "Kore"
        private const val VOICE_DEEP = "Charon"
        private const val HINDI = "English"
        private const val MARATHI = "Marathi"
    }
}
