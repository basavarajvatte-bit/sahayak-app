package com.sahayak.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Unlock karte hi baki kaam dikhata hai. Tap karne par kaam complete. */
class TodoPopup(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private var box: LinearLayout? = null
    private val hideLater = Runnable { hide() }

    fun show() {
        hide()
        val items = Todos.pending(service)
        if (items.isEmpty()) return
        val pad = (14 * density).toInt()
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.argb(242, 20, 24, 33))
                cornerRadius = 18 * density
            }
        }
        card.addView(TextView(service).apply {
            text = "To-do: tap when done"
            textSize = 15f
            setTextColor(Color.rgb(32, 201, 151))
        })
        items.take(MAX_ROWS).forEach { item ->
            card.addView(TextView(service).apply {
                text = "○  " + item.optString("text")
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(0, pad / 2, 0, pad / 2)
                setOnClickListener {
                    Todos.complete(service, item.optString("id"))
                    text = "✓  " + item.optString("text")
                    alpha = 0.5f
                    isClickable = false
                    main.removeCallbacks(hideLater)
                    main.postDelayed(hideLater, AFTER_TAP_MS)
                }
            })
        }
        if (items.size > MAX_ROWS) {
            card.addView(TextView(service).apply {
                text = "+" + (items.size - MAX_ROWS) + " more in Sahayak"
                textSize = 14f
                setTextColor(Color.LTGRAY)
            })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = (48 * density).toInt()
            horizontalMargin = 0.03f
        }
        windows.addView(card, params)
        box = card
        main.postDelayed(hideLater, SHOW_MS)
    }

    fun hide() {
        main.removeCallbacks(hideLater)
        box?.let { windows.removeView(it) }
        box = null
    }

    companion object {
        private const val SHOW_MS = 1_000L
        private const val AFTER_TAP_MS = 1_000L
        private const val MAX_ROWS = 5
    }
}
