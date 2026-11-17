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
    private lateinit var ownButton: Button
    private lateinit var keyTitle: TextView
    private lateinit var saveKeyButton: Button
    private lateinit var keyNote: TextView
    private lateinit var serverRow: LinearLayout
    private lateinit var testSpin: ProgressBar
    private lateinit var providerNote: TextView
    private lateinit var keyField: EditText
    private lateinit var keyStatus: TextView
    private lateinit var nameField: EditText
    private lateinit var scriptText: TextView
    private lateinit var recordButton: Button
    private lateinit var recordStatus: TextView
    private lateinit var labelField: EditText
    private lateinit var voicesBox: LinearLayout
    private lateinit var usingText: TextView
    private lateinit var naturalButton: Button
    private lateinit var cloneStatus: TextView
    private lateinit var modelButton: Button
    private lateinit var sayField: EditText
    private lateinit var sayStatus: TextView
    private var busy = false
    private lateinit var clipSpin: ProgressBar
    private lateinit var clipStatus: TextView
    private lateinit var clipField: EditText
    private lateinit var cloneSpin: ProgressBar
    private lateinit var saySpin: ProgressBar

    private val tick = object : Runnable {
        override fun run() {
            if (VoiceClone.recording) {
                showRecord()
                main.postDelayed(this, 500)
            } else {
                refresh()
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
        ownButton = button("My own server (free)") { choose(VoiceLogic.OWN) }
        service.addView(ownButton)
        providerNote = text("", 14f, color = GREY)
        service.addView(providerNote)
        page.addView(service)

        // 2. Key
        val keyCard = card("2. Your key")
        keyTitle = keyCard.getChildAt(0) as TextView
        serverRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        serverRow.addView(button("🚀 Open the server notebook") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VoiceLogic.NOTEBOOK)))
            } catch (e: RuntimeException) {
                toast("No browser found")
            }
        })
        serverRow.addView(text("Run the notebook on Kaggle (free GPU, 30 hours a week). In 10 to 20 minutes it shows one line starting with SAHAYAK. Copy that whole line and paste it below.", 13f, color = GREY))
        keyCard.addView(serverRow)
        keyField = EditText(this).apply {
            hint = "Paste the API key here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            maxLines = 1
        }
        keyCard.addView(keyField)
        saveKeyButton = button("Save key") {
            val key = keyField.text.toString().trim()
            if (VoiceClone.provider(this) == VoiceLogic.OWN) {
                if (!VoiceClone.saveServerLine(this, key)) {
                    toast("This is not the SAHAYAK line. Copy the whole line from the notebook and paste")
                } else {
                    keyField.setText("")
                    refresh()
                    work(keyStatus, "Checking your server…", testSpin) {
                        val problem = VoiceClone.checkServer(this)
                        if (problem.isEmpty()) "✓ Your server is on and ready" else problem
                    }
                }
            } else if (key.length < 10) {
                toast("This key looks too short. Copy it again and paste")
            } else {
                VoiceClone.saveKey(this, VoiceClone.provider(this), key)
                keyField.setText("")
                toast("Key saved on this phone")
                refresh()
            }
        }
        val (saveRow, ts) = withSpin(saveKeyButton)
        testSpin = ts
        keyCard.addView(saveRow)
        keyStatus = text("", 14f)
        keyCard.addView(keyStatus)
        keyNote = text("", 13f, color = GREY)
        keyCard.addView(keyNote)
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
        record.addView(text("You can save many voices: record the same lines again in a different pitch or mood (normal, happy, soft, angry, child-like…). Give each one a name first.", 14f, color = GREY))
        labelField = EditText(this).apply {
            hint = "Name for this voice (e.g. Normal, Khush, Dheere)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            maxLines = 1
        }
        record.addView(labelField)
        recordButton = button("● Record a new voice") { toggleRecord() }
        record.addView(recordButton)
        recordStatus = text("", 14f)
        record.addView(recordStatus)
        record.addView(button("▶ Play the chosen voice's recording") {
            if (VoiceClone.recording) toast("Stop the recording first")
            else if (!VoiceClone.play(VoiceClone.sample(this))) toast("No recording yet")
        })

        // Ya phone ki koi audio clip
        record.addView(text("Or use an audio clip from your phone (a voice note or recording where only you speak, 10 seconds to 1.5 minutes):", 14f, color = GREY))
        val (clipRow, cps) = withSpin(button("📂 Use an audio clip") {
            if (VoiceClone.recording) toast("Stop the recording first")
            else if (busy) toast("Wait, one thing is already going on")
            else try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), PICK_CLIP)
            } catch (e: RuntimeException) {
                toast("No file picker found on this phone")
            }
        })
        clipSpin = cps
        record.addView(clipRow)
        clipStatus = text("", 14f)
        record.addView(clipStatus)
        clipField = EditText(this).apply {
            hint = "Exactly what you say in the clip"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            setText(VoiceClone.clipText(this@VoiceActivity))
            setOnFocusChangeListener { _, has -> if (!has) saveClipText() }
        }
        record.addView(clipField)
        page.addView(record)

        // 4. Clone
        val clone = card("4. Make my voice")
        val (cloneRow, cs) = withSpin(button("✨ Make my voice clone") {
            saveName()
            saveClipText()
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
        say.addView(text("My saved voices. Tap a name to choose it, or 🔊 to speak the text below in that voice:", 14f, color = GREY))
        voicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        say.addView(voicesBox)
        usingText = text("", 14f, true)
        say.addView(usingText)
        sayField = EditText(this).apply {
            hint = "Write what to say, in Hindi, Marathi or English"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            filters = arrayOf(InputFilter.LengthFilter(VoiceLogic.TEXT_MAX))
        }
        say.addView(sayField)
        naturalButton = button("") {
            VoiceClone.setNatural(this, !VoiceClone.natural(this))
            refresh()
        }
        say.addView(naturalButton)
        say.addView(text("Up to " + VoiceLogic.TEXT_MAX + " letters at a time. Fish Audio and ElevenLabs use some monthly credits each time. Your own server has no limit but is slower: wait up to 2 minutes.", 13f, color = GREY))
        val (sayRow, ss) = withSpin(button("🔊 Speak in the chosen voice") { speakNow() })
        saySpin = ss
        say.addView(sayRow)
        say.addView(button("▶ Play again") { if (!VoiceClone.play(VoiceClone.spoken(this))) toast("Nothing to play yet") })
        say.addView(button("📤 Save and share this audio") { share() })
        sayStatus = text("", 14f)
        say.addView(sayStatus)
        page.addView(say)

        page.addView(text("The cloned voice can sound a little different from you. It is made by the service you chose, so your recording and text go to that service.", 13f, color = GREY))
        page.addView(button("Delete all my voices") {
            AlertDialog.Builder(this).setTitle("Delete your voice?")
                .setMessage("This removes ALL your saved voices from this phone and their clones from the service. Your key stays.")
                .setPositiveButton("Yes, delete") { _, _ ->
                    work(cloneStatus, "Deleting…", cloneSpin) {
                        VoiceClone.deleteAll(this)
                        runOnUiThread {
                            clipField.setText("")
                            clipStatus.text = ""
                            showRecord()
                        }
                        "Deleted"
                    }
                }
                .setNegativeButton("No", null).show()
        })
        refresh()
    }

    private fun speakNow() {
        val words = sayField.text.toString()
        saveClipText()
        val label = VoiceClone.selectedVoice(this)?.label ?: ""
        work(sayStatus, "Making the sound in " + label.ifEmpty { "your" } + " voice…", saySpin) {
            val file = VoiceClone.speak(this, words)
            if (file == null) VoiceClone.lastError else {
                runOnUiThread { VoiceClone.play(file) }
                val toned = VoiceClone.lastToned
                "Playing ($label). Tap Play again to hear it once more" +
                    (if (toned.contains("[") || toned != VoiceLogic.cleanText(words)) "\n\nSpoken as: $toned" else "")
            }
        }
    }

    private fun chooseVoice(id: String) {
        if (VoiceClone.recording || busy) return
        saveClipText()
        VoiceClone.select(this, id)
        clipField.setText(VoiceClone.clipText(this))
        clipStatus.text = ""
        cloneStatus.text = ""
        refresh()
    }

    /** Bachi hui awaazon ki list: naam (chuno), ▶ suno, 🔊 is awaaz mein bolo, 🗑 hatao. */
    @SuppressLint("SetTextI18n")
    private fun showVoices() {
        voicesBox.removeAllViews()
        val list = VoiceClone.voices(this)
        val sel = VoiceClone.selected(this)
        if (list.isEmpty()) {
            voicesBox.addView(text("No voice yet. Record one in step 3.", 14f, color = GREY))
        }
        for (v in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val chosen = v.id == sel
            val name = button((if (chosen) "✓ " else "") + v.label + " · " + VoiceClone.secondsOf(this, v.id) + "s") { chooseVoice(v.id) }.apply {
                if (chosen) setTypeface(typeface, Typeface.BOLD)
                setOnLongClickListener { renameVoice(v.id, v.label); true }
            }
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("▶") {
                if (!VoiceClone.recording && !VoiceClone.play(VoiceClone.fileFor(this, v.id))) toast("No recording")
            }, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(button("🔊") {
                if (VoiceClone.recording || busy) return@button
                chooseVoice(v.id)
                speakNow()
            }, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(button("🗑") {
                if (VoiceClone.recording || busy) return@button
                AlertDialog.Builder(this).setTitle("Delete " + v.label + "?")
                    .setMessage("This voice's recording is removed from this phone.")
                    .setPositiveButton("Yes, delete") { _, _ ->
                        work(sayStatus, "Deleting…", saySpin) {
                            VoiceClone.removeVoice(this, v.id)
                            runOnUiThread { clipField.setText(VoiceClone.clipText(this)) }
                            "Deleted " + v.label
                        }
                    }
                    .setNegativeButton("No", null).show()
            }, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
            voicesBox.addView(row)
        }
        val chosen = list.firstOrNull { it.id == sel }
        usingText.text = if (chosen == null) "" else "Speaking with: " + chosen.label + "  (long-press a name to rename)"
    }

    private fun renameVoice(id: String, old: String) {
        val field = EditText(this).apply {
            setText(old)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(this).setTitle("Rename voice").setView(field)
            .setPositiveButton("Save") { _, _ ->
                VoiceClone.rename(this, id, field.text.toString())
                refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onPause() {
        if (VoiceClone.recording) VoiceClone.stopRecording()
        VoiceClone.stopPlaying()
        main.removeCallbacks(tick)
        saveName()
        saveClipText()
        super.onPause()
    }

    /** Clip ke shabd sudhaare hon to rakho (sirf jab clip daali ho). */
    private fun saveClipText() {
        if (VoiceClone.clipText(this).isNotEmpty() || clipField.text.isNotBlank()) {
            if (VoiceClone.sampleSeconds(this) > 0) VoiceClone.setClipText(this, clipField.text.toString())
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK_CLIP || resultCode != RESULT_OK || uri == null) return
        clipField.setText("")
        saveClipText()
        val newId = VoiceClone.newVoice(this, labelField.text.toString())
        labelField.setText("")
        work(clipStatus, "Reading the clip and writing down its words…", clipSpin) {
            val answer = VoiceClone.importClip(this, uri)
            if (!answer.startsWith("✓")) VoiceClone.removeVoice(this, newId)
            runOnUiThread {
                clipField.setText(VoiceClone.clipText(this))
                showRecord()
                clipStatus.setTextColor(if (answer.startsWith("✓")) GREEN else RED)
            }
            answer
        }
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
        keyStatus.text = ""
        refresh()
    }

    private fun toggleRecord() {
        if (VoiceClone.recording) {
            VoiceClone.stopRecording()
            refresh()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), ASK_MIC)
            return
        }
        saveName()
        saveClipText()
        val newId = VoiceClone.newVoice(this, labelField.text.toString())
        if (!VoiceClone.startRecording(this)) {
            Thread { VoiceClone.removeVoice(this, newId) }.start()
            toast(VoiceClone.lastError)
            return
        }
        labelField.setText("")
        clipField.setText("")
        clipStatus.text = ""
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
            recordButton.text = "● Record a new voice"
            recordStatus.setTextColor(if (s >= VoiceLogic.MIN_SECONDS || (s > 0 && VoiceClone.clipText(this).isNotEmpty())) GREEN else GREY)
            recordStatus.text = when {
                s == 0 -> "No recording yet. Read for 30 to 60 seconds."
                s < VoiceLogic.MIN_SECONDS && VoiceClone.clipText(this).isEmpty() -> "Recording is $s seconds. Too short, record again for at least " + VoiceLogic.MIN_SECONDS + " seconds."
                else -> "✓ " + (VoiceClone.selectedVoice(this)?.label ?: "Recording") + " saved: $s seconds"
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refresh() {
        val provider = VoiceClone.provider(this)
        val eleven = provider == VoiceLogic.ELEVEN
        val own = provider == VoiceLogic.OWN
        fishButton.text = (if (provider == VoiceLogic.FISH) "✓ " else "") + "Fish Audio (free)"
        elevenButton.text = (if (eleven) "✓ " else "") + "ElevenLabs (paid)"
        ownButton.text = (if (own) "✓ " else "") + "My own server (free)"
        providerNote.text = when {
            eleven -> "ElevenLabs: best quality. Cloning your voice needs the Starter plan (about \$5 a month). Get the key at elevenlabs.io → Developers → API Keys."
            own -> "Your own server: Fish Audio's open-source S2-Pro model runs on Kaggle's free GPU. No credits, no card. It stays on up to 12 hours; when it stops, run the notebook again and paste the new line."
            else -> "Fish Audio: free plan gives a few minutes of voice every month. Get the key at fish.audio → API → API Keys."
        }
        keyTitle.text = if (own) "2. Start your server" else "2. Your key"
        serverRow.visibility = if (own) android.view.View.VISIBLE else android.view.View.GONE
        keyField.hint = if (own) "Paste the SAHAYAK line here" else "Paste the API key here"
        saveKeyButton.text = if (own) "Save and check server" else "Save key"
        keyNote.text = if (own) "The line has your server's password. It stays locked on this phone. Do not send it to anyone, not in chat either."
        else "The key stays locked on this phone only. Never send it to anyone, not in chat either."
        if (!busy) {
            val hasKey = if (own) VoiceClone.serverUrl(this).isNotEmpty() else VoiceClone.key(this, provider).isNotEmpty()
            val now = keyStatus.text.toString()
            // Server jaanch ka jawab (theek ya galti) tab tak dikhe jab tak service na badle
            val auto = now.isEmpty() || now.startsWith("No ") || now.startsWith("✓ Server link") || now.endsWith("key is saved")
            if (auto) {
                keyStatus.setTextColor(if (hasKey) GREEN else GREY)
                keyStatus.text = when {
                    own && hasKey -> "✓ Server link saved: " + VoiceClone.serverUrl(this).substringAfter("://")
                    own -> "No server yet"
                    hasKey -> "✓ " + VoiceLogic.label(provider) + " key is saved"
                    else -> "No " + VoiceLogic.label(provider) + " key yet"
                }
            } else {
                keyStatus.setTextColor(if (now.startsWith("✓")) GREEN else RED)
            }
        }
        scriptText.text = VoiceLogic.script(VoiceClone.name(this))
        showRecord()
        showVoices()
        // Apna server aur Fish free mein recording hi kaafi hai; ElevenLabs mein clone banana padta hai
        val ready = VoiceClone.voiceId(this, provider).isNotEmpty() || (!eleven && VoiceClone.sampleSeconds(this) > 0)
        if (cloneStatus.text.isNullOrEmpty() || !busy) {
            if (ready && cloneStatus.text.isNullOrEmpty()) cloneStatus.text = "✓ Your voice is ready on " + VoiceLogic.label(provider)
            if (!ready && cloneStatus.text.toString().startsWith("✓")) cloneStatus.text = ""
            if (!ready && cloneStatus.text.isNullOrEmpty()) cloneStatus.text = "No voice clone on " + VoiceLogic.label(provider) + " yet"
        }
        cloneStatus.setTextColor(if (ready) GREEN else GREY)
        naturalButton.text = if (VoiceClone.natural(this)) "🎭 Natural talking style: ON (AI adds feeling and pauses). Tap to turn off"
        else "📖 Plain reading: ON. Tap for natural talking style"
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
        private const val PICK_CLIP = 82
        private const val INK = 0xFF1D2433.toInt()
        private const val GREY = 0xFF475467.toInt()
        private const val GREEN = 0xFF1F7A4D.toInt()
        private const val RED = 0xFFC62828.toInt()
        private const val BACK = 0xFFF4F1FB.toInt()
    }
}
