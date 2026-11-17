package com.sahayak.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Apni awaaz ka clone: service chuno, key daalo, awaaz record karo, clone banao, kuch bhi bulwao. */
class VoiceActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var page: LinearLayout
    private lateinit var fishButton: Button
    private lateinit var elevenButton: Button
    private lateinit var providerNote: TextView
    private lateinit var keyField: EditText
    private lateinit var keyStatus: TextView
    private lateinit var nameField: EditText
    private lateinit var scriptText: TextView
    private lateinit var recordButton: Button
    private lateinit var recordStatus: TextView
    private lateinit var cloneStatus: TextView
    private lateinit var modelButton: Button
    private lateinit var sayField: EditText
    private lateinit var sayStatus: TextView
    private var busy = false
    private lateinit var cloneSpin: ProgressBar
    private lateinit var saySpin: ProgressBar

    private val tick = object : Runnable {
        override fun run() {
            if (VoiceClone.recording) {
                showRecord()
                main.postDelayed(this, 500)
            } else {
                showRecord()
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(words: String, size: Float, bold: Boolean = false, color: Int = INK) = TextView(this).apply {
        text = words
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(words: String, action: () -> Unit) = Button(this).apply {
        text = words
        isAllCaps = false
        textSize = 16f
        setOnClickListener { action() }
    }

    private fun card(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        addView(text(title, 18f, true))
    }

    /** Button ke bagal me ghumta circle, kaam chalte waqt dikhta hai. */
    private fun withSpin(b: Button): Pair<LinearLayout, ProgressBar> {
        val spin = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(b)
            addView(spin)
        }
        return row to spin
    }

    private fun toast(words: String) = Toast.makeText(this, words, Toast.LENGTH_LONG).show()

    /** Internet wala kaam peeche karo, jawab screen par. */
    private fun work(status: TextView, doing: String, spin: ProgressBar? = null, job: () -> String) {
        if (busy) {
            toast("Wait, one thing is already going on")
            return
        }
        busy = true
        status.setTextColor(GREY)
        status.text = doing
        spin?.visibility = android.view.View.VISIBLE
        Thread {
            val answer = try {
                job()
            } catch (e: RuntimeException) {
                "Something went wrong. Try again"
            }
            runOnUiThread {
                busy = false
                spin?.visibility = android.view.View.GONE
                if (isFinishing) return@runOnUiThread
                status.text = answer
                refresh()
            }
        }.start()
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACK)
            setPadding(dp(16), dp(20), dp(16), dp(40))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BACK)
            addView(page)
        })

        page.addView(text("🎙 My voice", 24f, true))
        page.addView(text("Make a copy of your own voice. Then type anything and hear it in your voice.", 15f, color = GREY))
        page.addView(text("Only your own voice. Do not record or clone anyone else's voice.", 14f, true, RED))

        // 1. Service
        val service = card("1. Choose the service")
        val row = LinearLayout(this)
        fishButton = button("Fish Audio (free)") { choose(VoiceLogic.FISH) }
        elevenButton = button("ElevenLabs (paid)") { choose(VoiceLogic.ELEVEN) }
        row.addView(fishButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(elevenButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        service.addView(row)
        providerNote = text("", 14f, color = GREY)
        service.addView(providerNote)
        page.addView(service)

        // 2. Key
        val keyCard = card("2. Your key")
        keyField = EditText(this).apply {
            hint = "Paste the API key here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            maxLines = 1
        }
        keyCard.addView(keyField)
        keyCard.addView(button("Save key") {
            val key = keyField.text.toString().trim()
            if (key.length < 10) {
                toast("This key looks too short. Copy it again and paste")
            } else {
                VoiceClone.saveKey(this, VoiceClone.provider(this), key)
                keyField.setText("")
                toast("Key saved on this phone")
                refresh()
            }
        })
        keyStatus = text("", 14f)
        keyCard.addView(keyStatus)
        keyCard.addView(text("The key stays locked on this phone only. Never send it to anyone, not in chat either.", 13f, color = GREY))
        page.addView(keyCard)

        // 3. Record
        val record = card("3. Record your voice")
        nameField = EditText(this).apply {
            hint = "Your name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            maxLines = 1
            setText(VoiceClone.name(this@VoiceActivity))
            setOnFocusChangeListener { _, has -> if (!has) saveName() }
        }
        record.addView(nameField)
        record.addView(text("Sit in a quiet room. Hold the phone a hand away. Tap Start and read this aloud in your normal voice:", 14f, color = GREY))
        scriptText = text("", 16f).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFFF2F4F7.toInt())
                cornerRadius = dp(10).toFloat()
            }
        }
        record.addView(scriptText)
        recordButton = button("● Start recording") { toggleRecord() }
        record.addView(recordButton)
        recordStatus = text("", 14f)
        record.addView(recordStatus)
        record.addView(button("▶ Play my recording") {
            if (VoiceClone.recording) toast("Stop the recording first")
            else if (!VoiceClone.play(VoiceClone.sample(this))) toast("No recording yet")
        })
        page.addView(record)

        // 4. Clone
        val clone = card("4. Make my voice")
        val (cloneRow, cs) = withSpin(button("✨ Make my voice clone") {
            saveName()
            if (VoiceClone.recording) toast("Stop the recording first")
            else work(cloneStatus, "Sending your recording and making the voice. It can take a minute…", cloneSpin) { VoiceClone.create(this) }
        })
        cloneSpin = cs
        clone.addView(cloneRow)
        cloneStatus = text("", 14f)
        clone.addView(cloneStatus)
        page.addView(clone)

        // 5. Speak
        val say = card("5. Speak in my voice")
        modelButton = button("") {
            val next = if (VoiceClone.elevenModel(this) == VoiceLogic.ELEVEN_V2) VoiceLogic.ELEVEN_V3 else VoiceLogic.ELEVEN_V2
            VoiceClone.setElevenModel(this, next)
            refresh()
        }
        say.addView(modelButton)
        sayField = EditText(this).apply {
            hint = "Write what to say, in Hindi, Marathi or English"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            filters = arrayOf(InputFilter.LengthFilter(VoiceLogic.TEXT_MAX))
        }
        say.addView(sayField)
        say.addView(text("Up to " + VoiceLogic.TEXT_MAX + " letters at a time. Every time uses some of your monthly credits.", 13f, color = GREY))
        val (sayRow, ss) = withSpin(button("🔊 Speak in my voice") {
            val words = sayField.text.toString()
            work(sayStatus, "Making the sound…", saySpin) {
                val file = VoiceClone.speak(this, words)
                if (file == null) VoiceClone.lastError else {
                    runOnUiThread { VoiceClone.play(file) }
                    "Playing. Tap Play again to hear it once more"
                }
            }
        })
        saySpin = ss
        say.addView(sayRow)
        say.addView(button("▶ Play again") { if (!VoiceClone.play(VoiceClone.spoken(this))) toast("Nothing to play yet") })
        say.addView(button("📤 Save and share this audio") { share() })
        sayStatus = text("", 14f)
        say.addView(sayStatus)
        page.addView(say)

        page.addView(text("The cloned voice can sound a little different from you. It is made by the service you chose, so your recording and text go to that service.", 13f, color = GREY))
        page.addView(button("Delete my voice clone and recording") {
            AlertDialog.Builder(this).setTitle("Delete your voice?")
                .setMessage("This removes the voice clone from the service and the recording from this phone. Your key stays.")
                .setPositiveButton("Yes, delete") { _, _ ->
                    work(cloneStatus, "Deleting…", cloneSpin) {
                        VoiceClone.deleteAll(this)
                        "Deleted"
                    }
                }
                .setNegativeButton("No", null).show()
        })
        refresh()
    }

    override fun onPause() {
        if (VoiceClone.recording) VoiceClone.stopRecording()
        VoiceClone.stopPlaying()
        main.removeCallbacks(tick)
        saveName()
        super.onPause()
    }

    private fun saveName() {
        VoiceClone.setName(this, nameField.text.toString())
        scriptText.text = VoiceLogic.script(VoiceClone.name(this))
    }

    private fun choose(provider: String) {
        if (VoiceClone.recording || busy) return
        VoiceClone.setProvider(this, provider)
        // Doosri service ka purana sandesh na dikhe
        cloneStatus.text = ""
        sayStatus.text = ""
        refresh()
    }

    private fun toggleRecord() {
        if (VoiceClone.recording) {
            VoiceClone.stopRecording()
            showRecord()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), ASK_MIC)
            return
        }
        saveName()
        if (!VoiceClone.startRecording(this)) {
            toast(VoiceClone.lastError)
            return
        }
        main.post(tick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ASK_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleRecord()
        else if (requestCode == ASK_MIC) toast("Allow the microphone to record your voice")
    }

    @SuppressLint("SetTextI18n")
    private fun showRecord() {
        if (VoiceClone.recording) {
            val s = VoiceClone.recordedMs / 1000
            recordButton.text = "■ Stop recording"
            recordStatus.setTextColor(RED)
            recordStatus.text = "● Recording: $s seconds" + (if (s < VoiceLogic.MIN_SECONDS) "  (keep reading, at least " + VoiceLogic.MIN_SECONDS + ")" else "  (enough, you can stop)")
        } else {
            val s = VoiceClone.sampleSeconds(this)
            recordButton.text = if (s > 0) "● Record again" else "● Start recording"
            recordStatus.setTextColor(if (s >= VoiceLogic.MIN_SECONDS) GREEN else GREY)
            recordStatus.text = when {
                s == 0 -> "No recording yet. Read for 30 to 60 seconds."
                s < VoiceLogic.MIN_SECONDS -> "Recording is $s seconds. Too short, record again for at least " + VoiceLogic.MIN_SECONDS + " seconds."
                else -> "✓ Recording saved: $s seconds"
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refresh() {
        val provider = VoiceClone.provider(this)
        val eleven = provider == VoiceLogic.ELEVEN
        fishButton.text = (if (!eleven) "✓ " else "") + "Fish Audio (free)"
        elevenButton.text = (if (eleven) "✓ " else "") + "ElevenLabs (paid)"
        providerNote.text = if (eleven) {
            "ElevenLabs: best quality. Cloning your voice needs the Starter plan (about \$5 a month). Get the key at elevenlabs.io → Developers → API Keys."
        } else {
            "Fish Audio: free plan gives a few minutes of voice every month. Get the key at fish.audio → API → API Keys."
        }
        val hasKey = VoiceClone.key(this, provider).isNotEmpty()
        keyStatus.setTextColor(if (hasKey) GREEN else GREY)
        keyStatus.text = if (hasKey) "✓ " + VoiceLogic.label(provider) + " key is saved" else "No " + VoiceLogic.label(provider) + " key yet"
        scriptText.text = VoiceLogic.script(VoiceClone.name(this))
        showRecord()
        val ready = VoiceClone.voiceId(this, provider).isNotEmpty()
        if (cloneStatus.text.isNullOrEmpty() || !busy) {
            if (ready && cloneStatus.text.isNullOrEmpty()) cloneStatus.text = "✓ Your voice is ready on " + VoiceLogic.label(provider)
            if (!ready && cloneStatus.text.toString().startsWith("✓")) cloneStatus.text = ""
            if (!ready && cloneStatus.text.isNullOrEmpty()) cloneStatus.text = "No voice clone on " + VoiceLogic.label(provider) + " yet"
        }
        cloneStatus.setTextColor(if (ready) GREEN else GREY)
        modelButton.visibility = if (eleven) android.view.View.VISIBLE else android.view.View.GONE
        modelButton.text = if (VoiceClone.elevenModel(this) == VoiceLogic.ELEVEN_V3) "Model: v3 (Marathi and more). Tap to change" else "Model: Multilingual v2 (Hindi, English). Tap to change"
    }

    private fun share() {
        val uri: Uri? = VoiceClone.saveToMusic(this)
        if (uri == null) {
            toast("Nothing to share yet. Tap Speak in my voice first")
            return
        }
        toast("Saved in Music/Sahayak")
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mpeg").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share audio"))
        } catch (e: RuntimeException) {
            // Koi app nahi mila: file phir bhi Music/Sahayak mein hai
        }
    }

    companion object {
        private const val ASK_MIC = 81
        private const val INK = 0xFF1D2433.toInt()
        private const val GREY = 0xFF475467.toInt()
        private const val GREEN = 0xFF1F7A4D.toInt()
        private const val RED = 0xFFC62828.toInt()
        private const val BACK = 0xFFF4F1FB.toInt()
    }
}
