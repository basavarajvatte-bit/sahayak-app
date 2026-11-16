package com.sahayak.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Do phone ke beech ek to-do list: share karo, judo, aur doosre ki list mein kaam jodo. */
class SharedTodoActivity : Activity() {
    private lateinit var page: LinearLayout
    private var picker: TodoPicker? = null
    private var whenButton: Button? = null
    private var input: EditText? = null
    private var status: TextView? = null
    private var list: LinearLayout? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(words: String, size: Float, bold: Boolean = false, color: Int = 0xFF1D2433.toInt()) = TextView(this).apply {
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

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        }
    }

    private fun toast(words: String) = Toast.makeText(this, words, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F1FB.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(40))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFFF4F1FB.toInt())
            isFillViewport = true
            addView(page)
        })
        build()
    }

    override fun onResume() {
        super.onResume()
        TodoSync.start(this)
        TodoSync.listener = { refresh() }
        refresh()
    }

    override fun onPause() {
        TodoSync.listener = null
        super.onPause()
    }

    /** Poori screen dobara banao (share shuru / band hone par). */
    private fun build() {
        page.removeAllViews()
        picker = null
        whenButton = null
        input = null
        status = null
        list = null
        when (TodoSync.role(this)) {
            TodoSync.OWNER -> buildOwner()
            TodoSync.GUEST -> buildGuest()
            else -> buildSetup()
        }
        refresh()
    }

    private fun buildSetup() {
        page.addView(text("👥 Shared to-do list", 24f, true))
        page.addView(text("One list on two phones. A task added on one phone shows on the other in a few seconds. Both phones need internet.", 15f, color = 0xFF475467.toInt()))

        val mine = card()
        mine.addView(text("Share my list", 18f, true))
        mine.addView(text("Use this on the phone of the person whose tasks these are. You get a code. Send that code to the other person.", 14f, color = 0xFF475467.toInt()))
        mine.addView(button("Share my list") {
            TodoSync.share(this)
            build()
        })
        page.addView(mine)

        val theirs = card()
        theirs.addView(text("Join someone's list", 18f, true))
        theirs.addView(text("Use this on your phone to see their list and add tasks to it. Type their name and the code they sent you.", 14f, color = 0xFF475467.toInt()))
        val name = EditText(this).apply {
            hint = "Whose list? For example: Atul"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            maxLines = 1
        }
        val code = EditText(this).apply {
            hint = "Code: XXXX-XXXX-XXXX-XXXX"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 1
        }
        theirs.addView(name)
        theirs.addView(code)
        theirs.addView(button("Join") {
            if (TodoSync.join(this, name.text.toString(), code.text.toString())) build()
            else toast("This code is not right. It has 16 letters and numbers. Copy it again and paste")
        })
        page.addView(theirs)
    }

    @SuppressLint("SetTextI18n")
    private fun buildOwner() {
        val code = ShareLogic.showCode(TodoSync.code(this))
        page.addView(text("👥 My list is shared", 24f, true))
        status = text("", 15f).also { page.addView(it) }
        val box = card()
        box.addView(text("Your code", 15f, color = 0xFF475467.toInt()))
        box.addView(text(code, 24f, true).apply {
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
        })
        box.addView(text("Send this code to the other person. In their app they tap Join someone's list and paste it. Give it only to them: anyone with this code can see and change your list.", 14f, color = 0xFF475467.toInt()))
        box.addView(button("📤 Send code") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "My to-do list code: $code"), "Send code"))
        })
        box.addView(button("Copy code") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Code", code))
            toast("Code copied")
        })
        page.addView(box)
        page.addView(text("New tasks they add come into your normal to-do list, with a notification on top.", 14f, color = 0xFF475467.toInt()))
        page.addView(button("🔄 Sync now") {
            TodoSync.syncNow(this)
            toast("Syncing")
        })
        page.addView(button("Stop sharing") { confirmStop("Stop sharing?", "Your own list stays. The other phone will stop getting it.") })
    }

    @SuppressLint("SetTextI18n")
    private fun buildGuest() {
        val who = TodoSync.name(this)
        page.addView(text("👥 $who's to-do list", 24f, true))
        status = text("", 15f).also { page.addView(it) }

        val add = card()
        add.addView(text("Add a task for $who", 16f, true))
        val field = EditText(this).apply {
            hint = "For example: Pay the electricity bill"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
        }
        input = field
        add.addView(field)
        val whenB = button("") { picker?.open() }
        whenButton = whenB
        val pick = TodoPicker(this) { whenB.text = picker?.label() }
        picker = pick
        whenB.text = pick.label()
        val quick = LinearLayout(this)
        quick.addView(button("Today") { pick.today() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        quick.addView(button("Tomorrow") { pick.tomorrow() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        add.addView(quick)
        add.addView(whenB)
        add.addView(button("➕ Add to $who's list") {
            val words = field.text.toString().trim()
            if (words.isEmpty()) {
                toast("Write the task first")
            } else {
                Todos.add(this, words, pick.at(), pick.timed, Todos.PEER)
                field.setText("")
                pick.reset()
                refresh()
            }
        })
        page.addView(add)

        val tasks = card()
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tasks.addView(list)
        page.addView(tasks)

        page.addView(button("🔄 Sync now") {
            TodoSync.syncNow(this)
            toast("Syncing")
        })
        page.addView(button("Disconnect") { confirmStop("Disconnect from $who's list?", "$who's list goes away from this phone. Their own phone keeps it.") })
    }

    private fun confirmStop(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Yes") { _, _ ->
                TodoSync.unlink(this)
                build()
            }
            .setNegativeButton("No", null)
            .show()
    }

    /** Sirf badalne wali cheezein: status ki line aur kaamon ki list. */
    @SuppressLint("SetTextI18n")
    private fun refresh() {
        if (isFinishing) return
        status?.apply {
            val heard = TodoSync.lastHeard(this@SharedTodoActivity)
            val heardText = if (heard > 0L) "Other phone last seen: " + SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(Date(heard))
            else "Other phone has not joined yet"
            if (TodoSync.connected) {
                text = "🟢 Connected\n$heardText"
                setTextColor(0xFF1F7A4D.toInt())
            } else {
                text = "🔴 Not connected. Check internet. Trying again by itself" +
                    (if (TodoSync.lastError.isNotEmpty()) "\n(" + TodoSync.lastError + ")" else "") + "\n$heardText"
                setTextColor(0xFFC62828.toInt())
            }
        }
        val box = list ?: return
        box.removeAllViews()
        val items = Todos.pending(this, Todos.PEER)
        box.addView(text(if (items.isEmpty()) "No tasks in this list now." else "Tasks to do: " + items.size, 16f, true))
        var group = ""
        items.forEach { item ->
            val head = Todos.groupOf(item)
            if (head != group) {
                group = head
                box.addView(text(head, 15f, true, if (head == "Overdue") 0xFFC62828.toInt() else 0xFF1F5C4D.toInt()))
            }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val time = if (Todos.timed(item)) "⏰ " + Todos.clock(item.optLong("at")) + "  " else ""
            row.addView(text(time + item.optString("text"), 16f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Done"
                isAllCaps = false
                textSize = 14f
                setOnClickListener {
                    Todos.complete(this@SharedTodoActivity, item.optString("id"), Todos.PEER)
                    refresh()
                }
            })
            row.addView(Button(this).apply {
                text = "Remove"
                isAllCaps = false
                textSize = 14f
                setOnClickListener {
                    Todos.remove(this@SharedTodoActivity, item.optString("id"), Todos.PEER)
                    refresh()
                }
            })
            box.addView(row)
        }
    }
}
