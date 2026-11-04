package com.sahayak.app

import android.os.Handler
import android.os.Looper

/** Service se screen tak status pahunchane ka chhota sa tareeka. */
object AssistState {
    private val main = Handler(Looper.getMainLooper())

    @Volatile var running = false
        private set
    /** Sahayak ki awaaz, 0.1 se 1.0 tak. */
    @Volatile var volume = 0.5f
    @Volatile var status = ""
        private set
    var listener: (() -> Unit)? = null

    fun set(isRunning: Boolean, text: String) {
        running = isRunning
        status = text
        main.post { listener?.invoke() }
    }
}
