package com.sahayak.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

/** Chhotu: Sahayak ka apna 3D jaisa cartoon. Kisi film ka character nahi. */
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

    private fun ball(canvas: Canvas, x: Float, y: Float, radius: Float, light: Int, dark: Int) {
        paint.shader = RadialGradient(x - radius * 0.35f, y - radius * 0.4f, radius * 1.5f, light, dark, Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, radius, paint)
        paint.shader = null
    }

    override fun onDraw(canvas: Canvas) {
        val u = minOf(width, height) / 100f
        val cx = width / 2f
        val beat = sin(time * 7f)
        val squash = 1f + abs(sin(time * 5.5f)) * 0.04f
        canvas.save()
        canvas.translate(0f, beat * 2f * u)
        canvas.scale(2f - squash, squash, cx, 92f * u)
        paint.style = Paint.Style.FILL

        // Parchhai
        paint.color = Color.argb(70, 0, 0, 0)
        box.set(cx - 30f * u, 90f * u, cx + 30f * u, 98f * u)
        canvas.drawOval(box, paint)

        // Kaan
        ball(canvas, cx - 36f * u, 33f * u, 12f * u, Color.rgb(255, 196, 92), Color.rgb(204, 96, 22))
        ball(canvas, cx + 36f * u, 33f * u, 12f * u, Color.rgb(255, 196, 92), Color.rgb(204, 96, 22))

        // Sharir aur sir
        paint.shader = LinearGradient(0f, 18f * u, 0f, 94f * u, Color.rgb(112, 92, 255), Color.rgb(38, 22, 150), Shader.TileMode.CLAMP)
        box.set(cx - 38f * u, 18f * u, cx + 38f * u, 94f * u)
        canvas.drawRoundRect(box, 38f * u, 38f * u, paint)
        paint.shader = null

        // Chamak
        paint.color = Color.argb(70, 255, 255, 255)
        box.set(cx - 26f * u, 23f * u, cx + 4f * u, 37f * u)
        canvas.drawOval(box, paint)

        // Pet
        ball(canvas, cx, 78f * u, 17f * u, Color.rgb(173, 229, 255), Color.rgb(84, 142, 220))

        // Aankhein
        val blink = (time * 1.1f) % 3f < 0.09f
        val eyeY = 46f * u
        val look = sin(time * 2.2f) * 2.5f * u
        if (blink) {
            paint.color = Color.rgb(20, 12, 70)
            box.set(cx - 25f * u, eyeY - u, cx - 7f * u, eyeY + u)
            canvas.drawRoundRect(box, u, u, paint)
            box.set(cx + 7f * u, eyeY - u, cx + 25f * u, eyeY + u)
            canvas.drawRoundRect(box, u, u, paint)
        } else {
            ball(canvas, cx - 16f * u, eyeY, 12f * u, Color.WHITE, Color.rgb(205, 217, 240))
            ball(canvas, cx + 16f * u, eyeY, 12f * u, Color.WHITE, Color.rgb(205, 217, 240))
            paint.color = Color.rgb(18, 16, 48)
            canvas.drawCircle(cx - 16f * u + look, eyeY + u, 5.5f * u, paint)
            canvas.drawCircle(cx + 16f * u + look, eyeY + u, 5.5f * u, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(cx - 14f * u + look, eyeY - u, 1.8f * u, paint)
            canvas.drawCircle(cx + 18f * u + look, eyeY - u, 1.8f * u, paint)
        }

        // Bhauhein
        val lift = if (talking) abs(sin(time * 18f)) * 3f else 0f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 3.2f * u
        paint.color = Color.rgb(255, 196, 92)
        canvas.drawLine(cx - 27f * u, (33f - lift) * u, cx - 9f * u, (29f - lift) * u, paint)
        canvas.drawLine(cx + 9f * u, (29f - lift) * u, cx + 27f * u, (33f - lift) * u, paint)
        paint.style = Paint.Style.FILL

        // Muh
        val open = if (talking) 3f + 9f * abs(sin(time * 26f)) else 2f
        val mouth = Path().apply {
            moveTo(cx - 12f * u, 62f * u)
            quadTo(cx, (62f + open * 1.6f) * u, cx + 12f * u, 62f * u)
            close()
        }
        paint.color = Color.rgb(70, 8, 40)
        canvas.drawPath(mouth, paint)
        if (talking) {
            paint.color = Color.rgb(255, 104, 136)
            canvas.drawCircle(cx, (62f + open * 0.45f) * u, 3f * u, paint)
        }

        // Haath screen ki taraf
        val push = abs(sin(time * 8f)) * 3f
        ball(canvas, cx - 41f * u, (61f - push) * u, 9f * u, Color.rgb(255, 196, 92), Color.rgb(204, 96, 22))
        ball(canvas, cx + 41f * u, (61f + push) * u, 9f * u, Color.rgb(255, 196, 92), Color.rgb(204, 96, 22))
        canvas.restore()
    }
}
