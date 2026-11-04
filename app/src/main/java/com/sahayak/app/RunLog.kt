package com.sahayak.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Har order ka chhota record, kaam jaanchne ke liye.
 * Isme aapke shabd, type kiya text, screen ya contact kabhi nahi likhe jaate.
 */
object RunLog {
    private const val MAX_LINES = 300
    private val time = SimpleDateFormat("dd-MM HH:mm:ss", Locale.ENGLISH)

    @Synchronized
    fun add(context: Context, line: String) {
        val file = File(context.filesDir, "runs.log")
        val lines = if (file.exists()) file.readLines() else emptyList()
        file.writeText((lines + (time.format(Date()) + " " + line)).takeLast(MAX_LINES).joinToString("\n"))
    }

    @Synchronized
    fun read(context: Context): String {
        val file = File(context.filesDir, "runs.log")
        return if (file.exists()) file.readText() else ""
    }
}
