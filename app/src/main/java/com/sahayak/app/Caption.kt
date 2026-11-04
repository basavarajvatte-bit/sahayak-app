package com.sahayak.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Dot dabate waqt aapki baat screen par likhkar dikhata hai. */
class Caption(context: Context, windowType: Int) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val view = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        val pad = (14 * density).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(Color.argb(225, 20, 24, 33))
            cornerRadius = 18 * density
        }
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = (72 * density).toInt()
        horizontalMargin = 0.04f
    }
    private var attached = false

    fun show(text: String) {
        view.text = text.takeLast(MAX_CHARS)
        if (!attached) {
            windows.addView(view, params)
            attached = true
        }
    }

    fun hide() {
        if (!attached) return
        windows.removeView(view)
        attached = false
    }

    companion object {
        private const val MAX_CHARS = 160
    }
}
