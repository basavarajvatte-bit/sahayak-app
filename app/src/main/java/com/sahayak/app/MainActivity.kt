package com.sahayak.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView

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

        val savedKey = secure.load()
        keyInput.setText(savedKey)
        if (savedKey.isEmpty()) settingsPanel.visibility = View.VISIBLE
        languageGroup.check(
            if (prefs.getString(PREF_LANGUAGE, HINDI) == MARATHI) R.id.langMarathi else R.id.langHindi
        )
        nameInput.setText(prefs.getString(PREF_NAME, ""))
        voiceGroup.check(if (prefs.getString(PREF_VOICE, VOICE_FEMALE) == VOICE_MALE) R.id.voiceMale else R.id.voiceFemale)
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
        batteryButton.text = if (batteryFree()) "✓ Background mein chalne ki ijazat hai" else "Background ki ijazat do"
    }

    private fun batteryFree(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun askBattery() {
        if (batteryFree()) return
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun language(): String =
        if (languageGroup.checkedRadioButtonId == R.id.langMarathi) MARATHI else HINDI

    private fun voice(): String =
        if (voiceGroup.checkedRadioButtonId == R.id.voiceMale) VOICE_MALE else VOICE_FEMALE

    private fun onAssist() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) {
            statusText.text = "Pehle Settings mein Gemini key daliye."
            settingsPanel.visibility = View.VISIBLE
            keyInput.requestFocus()
            return
        }
        secure.save(key)
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
        if (requestCode != REQUEST_SCREEN) return
        if (resultCode == RESULT_OK && data != null) {
            AssistService.start(
                this, resultCode, data, secure.load(), language(),
                prefs.getString(PREF_NAME, "").orEmpty(), voice(), autoStep.isChecked
            )
            moveTaskToBack(true) // app peeche chala jaye, phone ka home dikhe
        } else {
            statusText.text = "Screen share ki ijazat nahi mili."
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_SCREEN = 2
        private const val PREF_LANGUAGE = "language"
        private const val PREF_NAME = "name"
        private const val PREF_VOICE = "voice"
        private const val PREF_AUTO = "auto_step"
        private const val PREF_VOLUME = "volume"
        private const val DEFAULT_VOLUME = 50
        private const val MIN_VOLUME = 10
        private const val VOICE_FEMALE = "Aoede"
        private const val VOICE_MALE = "Puck"
        private const val HINDI = "Hindi"
        private const val MARATHI = "Marathi"
    }
}
