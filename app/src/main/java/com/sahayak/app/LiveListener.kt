package com.sahayak.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Phone ka speech recognition: bolte hi shabd deta hai, isliye screen par
 * turant likhai aati hai aur dot chhodte hi order ja sakta hai.
 */
class LiveListener(
    private val context: Context,
    private val primary: String,
    private val onWords: (String) -> Unit,
    private val onBroken: () -> Unit
) : RecognitionListener {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var recognizer: SpeechRecognizer? = null
    private val finished = StringBuilder()
    private var partial = ""
    private var holding = false
    private var onDone: ((String) -> Unit)? = null
    private val giveUp = Runnable { deliver() }

    private fun words(): String = (finished.toString() + " " + partial).trim()

    /** Phone ki beep band: bolne ke dauran yeh awaazen mute. Sahayak ki awaaz chalu rehti hai. */
    private fun muteBeeps(mute: Boolean) {
        main.removeCallbacks(unmute)
        val direction = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        for (stream in listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try {
                audio.adjustStreamVolume(stream, direction, 0)
            } catch (e: SecurityException) {
                // Do Not Disturb mein yeh mana ho sakta hai
            }
        }
    }

    private val unmute = Runnable { muteBeeps(false) }

    fun start() {
        muteBeeps(true)
        finished.setLength(0)
        partial = ""
        onDone = null
        holding = true
        listen()
    }

    private fun listen() {
        val others = listOf("hi-IN", "mr-IN", "en-IN").filter { it != primary }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primary)
            .putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", others.toTypedArray())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, LONG_SILENCE_MS)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, LONG_SILENCE_MS)
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(this)
            it.startListening(intent)
        }
    }

    /** Dot chhodne par: aakhri shabd aane tak thoda ruko, phir poora order do. */
    fun finish(done: (String) -> Unit) {
        holding = false
        onDone = done
        recognizer?.stopListening()
        main.postDelayed(giveUp, FINAL_WAIT_MS)
        main.postDelayed(unmute, UNMUTE_DELAY_MS)
    }

    fun cancel() {
        holding = false
        onDone = null
        main.removeCallbacks(giveUp)
        recognizer?.cancel()
        main.postDelayed(unmute, UNMUTE_DELAY_MS)
    }

    fun destroy() {
        cancel()
        muteBeeps(false)
        recognizer?.destroy()
        recognizer = null
    }

    private fun deliver() {
        main.removeCallbacks(giveUp)
        val done = onDone ?: return
        onDone = null
        done(words())
    }

    private fun first(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    override fun onPartialResults(partialResults: Bundle?) {
        val text = first(partialResults)
        if (text.isEmpty()) return
        partial = text
        if (holding) onWords(words())
    }

    override fun onResults(results: Bundle?) {
        val text = first(results).ifEmpty { partial }
        if (text.isNotEmpty()) finished.append(' ').append(text)
        partial = ""
        if (holding) {
            onWords(words())
            listen() // lambi baat ho to sunte raho
        } else {
            deliver()
        }
    }

    override fun onError(error: Int) {
        when {
            error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && holding && words().isEmpty() ||
                error == SpeechRecognizer.ERROR_CLIENT && holding -> {
                holding = false
                onBroken()
            }
            holding && (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) -> listen()
            !holding -> deliver()
        }
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    companion object {
        private const val LONG_SILENCE_MS = 10_000L
        private const val FINAL_WAIT_MS = 500L
        private const val UNMUTE_DELAY_MS = 800L
    }
}
