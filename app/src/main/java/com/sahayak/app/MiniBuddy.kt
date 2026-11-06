package com.sahayak.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/** Sahayak ka apna chhota robot: jhoolta hai, palak jhapkata hai, bolte waqt muh hilata hai. */
@SuppressLint("ViewConstructor")
class MiniBuddy(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private var time = 0f
    var talking = false

    private val ticker = ValueAnimator.ofFloat(0f, 1000f).apply {
        duration = 1_000_000L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            time = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val unit = size / 100f
        val cx = width / 2f
        val wave = sin(time * 9f)
        val bob = sin(time * 6f) * 3f * unit
        canvas.save()
        canvas.translate(0f, bob)
        canvas.rotate(wave * 4f, cx, 60f * unit)

        // Antenna
        paint.color = Color.rgb(255, 214, 102)
        paint.strokeWidth = 4f * unit
        paint.style = Paint.Style.STROKE
        canvas.drawLine(cx, 28f * unit, cx, 13f * unit, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (sin(time * 12f) > 0) Color.rgb(255, 107, 107) else Color.rgb(255, 214, 102)
        canvas.drawCircle(cx, 10f * unit, 7f * unit, paint)

        // Haath
        paint.color = Color.rgb(32, 201, 151)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f * unit
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx - 34f * unit, 60f * unit, cx - 45f * unit, (46f + wave * 12f) * unit, paint)
        canvas.drawLine(cx + 34f * unit, 60f * unit, cx + 45f * unit, (46f - wave * 12f) * unit, paint)
        paint.style = Paint.Style.FILL

        // Sharir
        box.set(cx - 36f * unit, 26f * unit, cx + 36f * unit, 92f * unit)
        paint.color = Color.rgb(32, 201, 151)
        canvas.drawRoundRect(box, 22f * unit, 22f * unit, paint)
        box.set(cx - 28f * unit, 33f * unit, cx + 28f * unit, 76f * unit)
        paint.color = Color.rgb(16, 42, 67)
        canvas.drawRoundRect(box, 15f * unit, 15f * unit, paint)

        // Aankhein
        val blink = (time * 1.1f) % 3f < 0.08f
        paint.color = Color.WHITE
        val eyeHeight = if (blink) 1.2f else 8f
        box.set(cx - 18f * unit, (48f - eyeHeight) * unit, cx - 6f * unit, (48f + eyeHeight) * unit)
        canvas.drawOval(box, paint)
        box.set(cx + 6f * unit, (48f - eyeHeight) * unit, cx + 18f * unit, (48f + eyeHeight) * unit)
        canvas.drawOval(box, paint)
        if (!blink) {
            paint.color = Color.rgb(16, 42, 67)
            canvas.drawCircle(cx - 12f * unit, 49f * unit, 3.5f * unit, paint)
            canvas.drawCircle(cx + 12f * unit, 49f * unit, 3.5f * unit, paint)
        }

        // Muh
        val open = if (talking) (3f + 7f * kotlin.math.abs(sin(time * 28f))) else 1.5f
        paint.color = Color.rgb(255, 107, 107)
        box.set(cx - 9f * unit, (66f - open / 2) * unit, cx + 9f * unit, (66f + open / 2) * unit)
        canvas.drawRoundRect(box, 4f * unit, 4f * unit, paint)
        canvas.restore()
    }
}
