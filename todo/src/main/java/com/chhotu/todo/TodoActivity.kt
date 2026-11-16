package com.chhotu.todo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sahayak.app.MiniBuddy
import com.sahayak.app.SharedTodoActivity
import com.sahayak.app.TodoSync
import com.sahayak.app.TodoPicker
import com.sahayak.app.Todos

/** To-do list aur setup ki ek hi screen. */
class TodoActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var picker: TodoPicker
    private lateinit var name: EditText
    private lateinit var overlayButton: Button
    private lateinit var batteryButton: Button
    private lateinit var shareButton: Button
    private val prefs by lazy { getSharedPreferences("sahayak", Context.MODE_PRIVATE) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun label(words: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = words
        textSize = size
        setTextColor(0xFF1D2433.toInt())
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun button(words: String, action: () -> Unit) = Button(this).apply {
        text = words
        isAllCaps = false
        setOnClickListener { action() }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F1FB.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(page) })
        page.addView(MiniBuddy(this).apply { mood = "dance" }, LinearLayout.LayoutParams(dp(180), dp(180)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        page.addView(label("Chhotu To-Do", 26f, true).apply { gravity = Gravity.CENTER })

        page.addView(label("Add a task", 16f, true))
        input = EditText(this).apply {
            hint = "For example: Pay the electricity bill"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        page.addView(input)
        val whenButton = button("") { picker.open() }
        picker = TodoPicker(this) { whenButton.text = picker.label() }
        whenButton.text = picker.label()
        val quick = LinearLayout(this)
        quick.addView(button("Today") { picker.today() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        quick.addView(button("Tomorrow") { picker.tomorrow() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(quick)
        page.addView(whenButton)
        page.addView(button("Add") {
            Todos.add(this, input.text.toString(), picker.at(), picker.timed)
            input.setText("")
            picker.reset()
            render()
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(list)
        shareButton = button("") { startActivity(Intent(this, SharedTodoActivity::class.java)) }
        page.addView(shareButton)

        page.addView(label("Setup", 16f, true))
        name = EditText(this).apply {
            hint = "Your name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(prefs.getString("name", ""))
        }
        page.addView(name)
        overlayButton = button("") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        page.addView(overlayButton)
        batteryButton = button("") {
            if (!batteryFree()) {
                @SuppressLint("BatteryLife")
                val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivity(ask)
            }
        }
        page.addView(batteryButton)
        page.addView(CheckBox(this).apply {
            text = "Chhotu speaks the list"
            isChecked = prefs.getBoolean("todo_speak", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("todo_speak", on).apply() }
        })
        page.addView(button("Test Chhotu") { test() })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun batteryFree() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun test() {
        when {
            !Settings.canDrawOverlays(this) -> toastLine("Press Allow Chhotu on screen first")
            Todos.today(this).isEmpty() -> toastLine("Add a task for today first")
            else -> {
                moveTaskToBack(true)
                Handler(Looper.getMainLooper()).postDelayed({ TodoService.instance?.showNow() }, 500L)
            }
        }
    }

    private fun toastLine(words: String) = android.widget.Toast.makeText(this, words, android.widget.Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        TodoService.start(this)
        TodoSync.start(this)
        TodoSync.listener = { render() }
        render()
    }

    override fun onPause() {
        super.onPause()
        TodoSync.listener = null
        prefs.edit().putString("name", name.text.toString().trim()).apply()
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        overlayButton.text = if (Settings.canDrawOverlays(this)) "✓ Chhotu can show on screen" else "Allow Chhotu on screen"
        batteryButton.text = if (batteryFree()) "✓ Background use allowed" else "Allow background use"
        shareButton.text = when (TodoSync.role(this)) {
            TodoSync.OWNER -> "👥 My list is shared  ·  💬 Chat" + TodoSync.unread(this).let { if (it > 0) " ($it new)" else "" }
            TodoSync.GUEST -> "👥 " + TodoSync.name(this) + "'s list  ·  💬 Chat" + TodoSync.unread(this).let { if (it > 0) " ($it new)" else "" }
            else -> "👥 Share this list with another phone"
        }
        list.removeAllViews()
        val items = Todos.pending(this)
        val today = Todos.today(this)
        val shown = if (open) items else today
        if (today.isEmpty()) list.addView(label("Nothing for today.", 15f))
        var group = ""
        shown.forEach { item ->
            val head = Todos.groupOf(item)
            if (head != group) {
                group = head
                list.addView(label(head, 15f, true).apply {
                    setTextColor(if (head == "Overdue") 0xFFC62828.toInt() else 0xFF1F5C4D.toInt())
                })
            }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val time = if (Todos.timed(item)) "⏰ " + Todos.clock(item.optLong("at")) + "  " else ""
            row.addView(label(time + item.optString("text"), 16f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("Done") {
                Todos.complete(this, item.optString("id"))
                render()
            })
            row.addView(button("Remove") {
                Todos.remove(this, item.optString("id"))
                render()
            })
            list.addView(row)
        }
        if (items.size > shown.size || open) {
            list.addView(button(if (open) "Less" else "More: all days (" + (items.size - shown.size) + ")") {
                open = !open
                render()
            })
        }
    }

    private var open = false
}
