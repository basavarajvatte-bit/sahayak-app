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
        val learnBox = findViewById<CheckBox>(R.id.learnBox)
        learnBox.isChecked = DailyLearning.isEnabled(this)
        learnBox.setOnCheckedChangeListener { _, on ->
            DailyLearning.setEnabled(this, on)
            renderMemory()
        }
        findViewById<Button>(R.id.clearLearning).setOnClickListener {
            DailyLearning.clear(this)
            Memory.all(this).forEach { Memory.remove(this, it) }
            renderMemory()
            statusText.text = "Seekha hua sab mita diya."
        }
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
            val report = RunLog.read(this).ifEmpty { "Abhi koi record nahi hai" }
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Sahayak report", report))
            findViewById<TextView>(R.id.reportText).text = report.takeLast(1_500)
            statusText.text = "Report copy ho gaya."
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
        controlButton.text = if (PhoneControl.instance != null) "✓ Phone control chalu hai" else "Phone control chalu karo"
        renderWork()
        renderMemory()
        renderSkills()
        batteryButton.text = if (batteryFree()) "✓ Background mein chalne ki ijazat hai" else "Background ki ijazat do"
    }

    private fun handleBackup(request: Int, uri: Uri) {
        statusText.text = try {
            if (request == REQUEST_BACKUP) {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(Skills.exportJson(this).toByteArray()) }
                "Backup ban gaya. File ko Google Drive mein bhi rakhiye."
            } else {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
                val added = Skills.importJson(this, text)
                renderSkills()
                renderMemory()
                "Backup wapas aa gaya: $added naye kaam."
            }
        } catch (e: IOException) {
            "Backup nahi hua. Dobara try kijiye."
        } catch (e: JSONException) {
            "Yeh Sahayak ki sahi backup file nahi hai."
        } catch (e: IllegalArgumentException) {
            "Yeh Sahayak ki sahi backup file nahi hai."
        }
    }

    private fun askTeachName() {
        val control = PhoneControl.instance
        if (control == null) {
            statusText.text = "Pehle Phone control chalu kijiye."
            return
        }
        val input = EditText(this).apply { hint = "Jaise: Aai ko photo bhejna" }
        AlertDialog.Builder(this)
            .setTitle("Kaam ka naam")
            .setView(input)
            .setPositiveButton("Shuru karo") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    control.startTeaching(name)
                    moveTaskToBack(true)
                }
            }
            .setNegativeButton("Rehne do", null)
            .show()
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setOnClickListener { action() }
    }

    private fun askLesson(name: String) {
        val input = EditText(this).apply { hint = "Kya galat hua, ya kaise karna tha" }
        AlertDialog.Builder(this)
            .setTitle("Sudhar likhiye")
            .setView(input)
            .setPositiveButton("Yaad rakho") { _, _ ->
                Skills.markWrong(this, name, input.text.toString().trim())
                renderSkills()
            }
            .setNegativeButton("Rehne do", null)
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
            top.addView(smallButton("Dohrao") {
                if (AssistState.running) {
                    AssistService.practice(this, name)
                    moveTaskToBack(true)
                } else {
                    statusText.text = "Pehle Assist dabaiye, phir Dohrao dabaiye."
                }
            }, weight)
            top.addView(smallButton("Aur sikhao") {
                val control = PhoneControl.instance
                if (control == null) {
                    statusText.text = "Pehle Phone control chalu kijiye."
                } else {
                    control.startTeaching(name)
                    moveTaskToBack(true)
                }
            }, LinearLayout.LayoutParams(weight))
            val bottom = LinearLayout(this)
            bottom.addView(smallButton("Galat hua") { askLesson(name) }, LinearLayout.LayoutParams(weight))
            bottom.addView(smallButton("Hatao") {
                AlertDialog.Builder(this)
                    .setMessage("Yeh sikhaya hua kaam delete karein?")
                    .setPositiveButton("Delete") { _, _ ->
                        Skills.remove(this, name)
                        renderSkills()
                    }
                    .setNegativeButton("Rehne do", null)
                    .show()
            }, LinearLayout.LayoutParams(weight))
            box.addView(top)
            box.addView(bottom)
        }
    }

    private fun renderMemory() {
        val average = Ratings.average(this)
        findViewById<TextView>(R.id.ratingAverage).text = if (average == null) "" else
            "Average rating: " + String.format(Locale.ENGLISH, "%.1f", average) + " / 10"
        val summary = DailyLearning.summary(this)
        findViewById<TextView>(R.id.learnSummary).text = if (summary.isEmpty()) "" else "Aaj seekha: $summary"
        val box = findViewById<LinearLayout>(R.id.memoryBox)
        box.removeAllViews()
        Memory.all(this).forEach { fact ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = fact
                textSize = 15f
                setTextColor(0xFF1D2433.toInt())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Hatao"
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
            row.addView(Button(this).apply {
                setText("Hatao")
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
        if (languageGroup.checkedRadioButtonId == R.id.langMarathi) MARATHI else HINDI

    private fun voice(): String = when (voiceGroup.checkedRadioButtonId) {
        R.id.voiceMale -> VOICE_MALE
        R.id.voiceSoft -> VOICE_SOFT
        R.id.voiceDeep -> VOICE_DEEP
        else -> VOICE_FEMALE
    }

    private fun onAssist() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) {
            statusText.text = "Pehle Settings mein Gemini key daliye."
            settingsPanel.visibility = View.VISIBLE
            keyInput.requestFocus()
            return
        }
        secure.save(key)
        whisperStore.save(whisperInput.text.toString().trim())
        prefs.edit().putString(PREF_LANGUAGE, language())
            .putString(PREF_NAME, nameInput.text.toString().trim()).putString(PREF_VOICE, voice())
            .putBoolean(PREF_AUTO, autoStep.isChecked).apply()

        if (PhoneControl.instance == null && !Settings.canDrawOverlays(this)) {
            statusText.text = "Sahayak ke saamne Allow chalu kijiye, phir wapas aakar Assist dabaiye."
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
        if (needed.isEmpty()) askScreen() else requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            askScreen()
        } else {
            statusText.text = "Mic ki ijazat ke bina Sahayak nahi sun sakta."
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
                prefs.getString(PREF_NAME, "").orEmpty(), voice(), autoStep.isChecked, whisperStore.load()
            )
            moveTaskToBack(true) // app peeche chala jaye, phone ka home dikhe
        } else {
            statusText.text = "Screen share ki ijazat nahi mili."
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_SCREEN = 2
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
        private const val HINDI = "Hindi"
        private const val MARATHI = "Marathi"
    }
}
