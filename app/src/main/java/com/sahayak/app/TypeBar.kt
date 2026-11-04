package com.sahayak.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Chhota Likho button aur upar khulne wala likhne ka box. */
class TypeBar(context: Context, private val windowType: Int, private val onOrder: (String) -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val keyboard = context.getSystemService(InputMethodManager::class.java)

    private fun rounded(colour: Int, radius: Float) = GradientDrawable().apply {
        setColor(colour)
        cornerRadius = radius * density
    }

    private val pill = TextView(context).apply {
        text = "Likho"
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = rounded(Color.rgb(31, 92, 77), 16f)
        setOnClickListener { open() }
    }
    private val pillParams = WindowManager.LayoutParams(
        (64 * density).toInt(), (36 * density).toInt(), windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private val input = EditText(context).apply {
        hint = "Hindi ya English mein likhiye"
        textSize = 18f
        setTextColor(Color.rgb(29, 36, 51))
        setHintTextColor(Color.rgb(102, 112, 133))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else {
                false
            }
        }
    }
    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = (10 * density).toInt()
        setPadding(pad, pad, pad, pad)
        background = rounded(Color.WHITE, 16f)
        addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(context).apply {
            text = "Bhejo"
            isAllCaps = false
            setOnClickListener { submit() }
        })
        addView(Button(context).apply {
            text = "✕"
            contentDescription = "Band karo"
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams((52 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    private val barParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, windowType,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP
        y = (40 * density).toInt()
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
    }
    private var pillShown = false
    private var barShown = false

    fun show(x: Int, y: Int) {
        move(x, y)
        if (!pillShown) {
            windows.addView(pill, pillParams)
            pillShown = true
        }
    }

    /** Likho button dot ke saath chalta hai. */
    fun move(x: Int, y: Int) {
        pillParams.x = x + (6 * density).toInt()
        pillParams.y = y + (78 * density).toInt()
        if (pillShown) windows.updateViewLayout(pill, pillParams)
    }

    private fun open() {
        if (barShown) return
        windows.addView(bar, barParams)
        barShown = true
        input.requestFocus()
        input.post { keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        close()
        onOrder(text)
    }

    private fun close() {
        if (!barShown) return
        keyboard.hideSoftInputFromWindow(input.windowToken, 0)
        windows.removeView(bar as View)
        barShown = false
    }

    fun remove() {
        close()
        if (pillShown) windows.removeView(pill)
        pillShown = false
    }
}
