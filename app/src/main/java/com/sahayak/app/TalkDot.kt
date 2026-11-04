package com.sahayak.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/** Screen par chamakta dot: dabakar rakho to mic chalu, chhodo to jawab. */
@SuppressLint("ViewConstructor")
class TalkDot(
    context: Context,
    private val onPress: () -> Unit,
    private val onRelease: () -> Unit,
    windowType: Int
) : View(context) {
    enum class State { CONNECTING, READY, TALKING }

    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = resources.displayMetrics.density
    private val size = (TOUCH_DP * density).toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glow = 0f
    private var state = State.CONNECTING
    private var attached = false
    private var startX = 0
    private var startY = 0
    private var touchX = 0f
    private var touchY = 0f

    private val params = WindowManager.LayoutParams(
        size, size,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = resources.displayMetrics.widthPixels - size
        y = resources.displayMetrics.heightPixels / 2
    }

    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_200L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            glow = it.animatedValue as Float
            invalidate()
        }
    }

    fun show() {
        if (attached) return
        windows.addView(this, params)
        attached = true
        pulse.start()
    }

    fun remove() {
        if (!attached) return
        pulse.cancel()
        windows.removeView(this)
        attached = false
    }

    fun setState(next: State) {
        state = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val colour = when (state) {
            State.CONNECTING -> Color.rgb(140, 140, 140)
            State.READY -> Color.rgb(32, 201, 151)
            State.TALKING -> Color.rgb(239, 68, 68)
        }
        val center = width / 2f
        val core = DOT_DP * density / 2
        paint.color = colour
        paint.alpha = (40 + 90 * (1 - glow)).toInt()
        canvas.drawCircle(center, center, core + (center - core) * glow, paint)
        paint.alpha = 255
        canvas.drawCircle(center, center, core, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = params.x
                startY = params.y
                touchX = event.rawX
                touchY = event.rawY
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onPress()
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = startX + (event.rawX - touchX).toInt()
                params.y = startY + (event.rawY - touchY).toInt()
                if (attached) windows.updateViewLayout(this, params)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onRelease()
        }
        return true
    }

    companion object {
        private const val TOUCH_DP = 76
        private const val DOT_DP = 26
    }
}
