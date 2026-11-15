package com.sahayak.app

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Kaam ki tarikh aur samay chunna: Today, Tomorrow, ya scroll karke tarikh aur samay. */
class TodoPicker(private val context: Context, private val onChange: () -> Unit) {
    /** Chuna hua din (din ki shuruaat, ms). */
    var day: Long = Todos.dayStart(System.currentTimeMillis())
        private set

    /** Samay: hour 0-23, minute. timed = false matlab "Any time". */
    var hour = 9
        private set
    var minute = 0
        private set
    var timed = false
        private set

    /** Kaam ka samay ms mein (Add ke waqt). */
    fun at(): Long {
        if (!timed) return day + NOON
        return Calendar.getInstance().apply {
            timeInMillis = day
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }.timeInMillis
    }

    fun label(): String = "📅  " + Todos.dayName(day) + "  ·  " + (if (timed) Todos.clock(at()) else "Any time")

    fun today() {
        day = Todos.dayStart(System.currentTimeMillis())
        onChange()
    }

    fun tomorrow() {
        day = Todos.dayStart(System.currentTimeMillis()) + DAY
        onChange()
    }

    /** Add ke baad wapas Today, Any time. */
    fun reset() {
        day = Todos.dayStart(System.currentTimeMillis())
        timed = false
        onChange()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** Scroll wale pahiye: tarikh, ghanta, minute, AM/PM. */
    fun open() {
        val start = Todos.dayStart(System.currentTimeMillis())
        val days = (0 until DAYS).map { start + it * DAY }
        val names = days.mapIndexed { i, d ->
            when (i) {
                0 -> "Today"
                1 -> "Tomorrow"
                else -> SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(Date(d))
            }
        }.toTypedArray()
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val wheels = LinearLayout(context).apply { gravity = Gravity.CENTER }
        fun wheel(values: Array<String>, pick: Int, weight: Float): NumberPicker {
            val w = NumberPicker(context).apply {
                minValue = 0
                maxValue = values.size - 1
                displayedValues = values
                value = pick.coerceIn(0, values.size - 1)
                wrapSelectorWheel = values.size > 2 && values.size != DAYS
                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            }
            wheels.addView(w, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight))
            return w
        }
        val dayIndex = days.indexOf(day).coerceAtLeast(0)
        val dayWheel = wheel(names, dayIndex, 2.2f)
        // Pehli baar samay chun rahe ho to agla poora ghanta
        val now = Calendar.getInstance()
        val startHour = if (timed) hour else (now.get(Calendar.HOUR_OF_DAY) + 1) % 24
        val startMinute = if (timed) minute else 0
        // Ghanta 1 se 12: index 0 = "1", index 11 = "12"
        val h12 = if (startHour % 12 == 0) 12 else startHour % 12
        val hourWheel = wheel(Array(12) { (it + 1).toString() }, h12 - 1, 1f)
        val minuteWheel = wheel(Array(12) { String.format(Locale.ENGLISH, "%02d", it * 5) }, startMinute / 5, 1f)
        val noonWheel = wheel(arrayOf("AM", "PM"), if (startHour >= 12) 1 else 0, 1f)
        page.addView(wheels)
        val anyTime = CheckBox(context).apply {
            text = "Any time (no fixed time)"
            textSize = 16f
            isChecked = !timed
        }
        fun dim() {
            val on = !anyTime.isChecked
            listOf(hourWheel, minuteWheel, noonWheel).forEach {
                it.isEnabled = on
                it.alpha = if (on) 1f else 0.35f
            }
        }
        anyTime.setOnCheckedChangeListener { _, _ -> dim() }
        dim()
        page.addView(anyTime)
        page.addView(TextView(context).apply {
            text = "Scroll the wheels to choose the day and time."
            textSize = 13f
            setTextColor(0xFF667085.toInt())
        })
        AlertDialog.Builder(context)
            .setTitle("When?")
            .setView(page)
            .setPositiveButton("Set") { _, _ ->
                day = days[dayWheel.value]
                timed = !anyTime.isChecked
                if (timed) {
                    val h12 = hourWheel.value + 1
                    hour = (h12 % 12) + if (noonWheel.value == 1) 12 else 0
                    minute = minuteWheel.value * 5
                }
                onChange()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val DAY = 86_400_000L
        private const val NOON = 12 * 3_600_000L
        private const val DAYS = 90
    }
}
