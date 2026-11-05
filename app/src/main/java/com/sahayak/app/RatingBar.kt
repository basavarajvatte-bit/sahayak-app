package com.sahayak.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Kaam ke baad 1 se 10 rating ka chhota box. Khud gayab ho jata hai. */
class RatingBar(context: Context, windowType: Int, private val onScore: (Int) -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val hideLater = Runnable { hide() }
    private var shown = false

    private fun chip(context: Context, label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.rgb(31, 92, 77))
            cornerRadius = 10 * density
        }
        setOnClickListener { action() }
    }

    private fun row(context: Context, from: Int) = LinearLayout(context).apply {
        for (score in from until from + 5) {
            val params = LinearLayout.LayoutParams(0, (44 * density).toInt(), 1f)
            params.setMargins(4, 4, 4, 4)
            addView(chip(context, score.toString()) {
                hide()
                onScore(score)
            }, params)
        }
    }

    private val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (12 * density).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(Color.argb(240, 20, 24, 33))
            cornerRadius = 18 * density
        }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(context).apply {
            text = "Kaam kaisa hua? 1 se 10"
            textSize = 17f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(context).apply {
            text = "✕"
            textSize = 20f
            contentDescription = "Band karo"
            setTextColor(Color.WHITE)
            setPadding(pad, 4, pad, 4)
            setOnClickListener { hide() }
        })
        addView(top)
        addView(row(context, 1))
        addView(row(context, 6))
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM
        y = (32 * density).toInt()
        horizontalMargin = 0.03f
    }

    fun show() {
        if (!shown) {
            windows.addView(box, params)
            shown = true
        }
        main.removeCallbacks(hideLater)
        main.postDelayed(hideLater, SHOW_MS)
    }

    fun hide() {
        main.removeCallbacks(hideLater)
        if (!shown) return
        windows.removeView(box)
        shown = false
    }

    companion object {
        private const val SHOW_MS = 20_000L
    }
}
