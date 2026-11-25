package com.sahayak.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/** Share ke bhaav ka graph: hari ya laal line, neeche halka rang, ungli se kisi bhi samay ka bhaav. */
class ChartView(context: Context) : View(context) {
    private var values = DoubleArray(0)
    private var base = Double.NaN
    private var baseLabel = ""
    private var lock = Double.NaN
    private var lockLabel = ""
    private var picked = -1

    /** Ungli jis bindu par hai uska number, hatane par -1. */
    var onPick: (Int) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f * density }
    private val labelBack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
    private val cursor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF667085.toInt()
        strokeWidth = 1f * density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    fun show(values: DoubleArray, base: Double, baseLabel: String, lock: Double, lockLabel: String) {
        this.values = values
        this.base = base
        this.baseLabel = baseLabel
        this.lock = lock
        this.lockLabel = lockLabel
        picked = -1
        invalidate()
    }

    private fun padX() = 4f * density

    private fun xOf(i: Int): Float {
        val w = width - 2 * padX()
        return padX() + if (values.size <= 1) 0f else w * i / (values.size - 1)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.size < 2 || width == 0 || height == 0) return
        var min = values.minOrNull() ?: return
        var max = values.maxOrNull() ?: return
        val span = (max - min).coerceAtLeast(max * 0.002)
        // Pichhla band bhaav aur lock ka bhaav paas ho to graph mein dikhao
        val showBase = !base.isNaN() && baseLabel.isNotEmpty() && base > min - span && base < max + span
        val showLock = !lock.isNaN() && lock > min - span && lock < max + span
        if (showBase) {
            min = minOf(min, base)
            max = maxOf(max, base)
        }
        if (showLock) {
            min = minOf(min, lock)
            max = maxOf(max, lock)
        }
        val top = 14f * density
        val bottom = height - 10f * density
        val range = (max - min).coerceAtLeast(1e-9)
        fun yOf(v: Double) = (bottom - (v - min) / range * (bottom - top)).toFloat()

        val up = values.last() >= (if (base.isNaN()) values.first() else base)
        val color = if (up) 0xFF1FAE5B.toInt() else 0xFFD93A4A.toInt()
        line.color = color
        val path = Path()
        path.moveTo(xOf(0), yOf(values[0]))
        for (i in 1 until values.size) path.lineTo(xOf(i), yOf(values[i]))
        val area = Path(path)
        area.lineTo(xOf(values.size - 1), height.toFloat())
        area.lineTo(xOf(0), height.toFloat())
        area.close()
        fill.shader = LinearGradient(0f, top, 0f, height.toFloat(), (color and 0x00FFFFFF) or 0x55000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)

        if (showBase) mark(canvas, yOf(base), baseLabel, 0xFF98A2B3.toInt(), 0xFF475467.toInt(), false)
        if (showLock) mark(canvas, yOf(lock), lockLabel, 0xFFE08600.toInt(), 0xFFB54700.toInt(), true)

        if (picked in values.indices) {
            val x = xOf(picked)
            canvas.drawLine(x, 0f, x, height.toFloat(), cursor)
            dot.color = color
            canvas.drawCircle(x, yOf(values[picked]), 4.5f * density, dot)
        }
    }

    /** Aadi tooti line aur uska naam. */
    private fun mark(canvas: Canvas, y: Float, words: String, lineColor: Int, textColor: Int, left: Boolean) {
        dash.color = lineColor
        canvas.drawLine(0f, y, width.toFloat(), y, dash)
        label.color = textColor
        val w = label.measureText(words)
        val pad = 4f * density
        val x = if (left) pad * 2 else width - w - pad * 2
        val ty = if (y < 22f * density) y + 15f * density else y - 5f * density
        canvas.drawRoundRect(x - pad, ty - 12f * density, x + w + pad, ty + 4f * density, pad, pad, labelBack)
        canvas.drawText(words, x, ty, label)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (values.size < 2) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val w = width - 2 * padX()
                val i = Math.round((event.x - padX()) / w * (values.size - 1)).coerceIn(0, values.size - 1)
                if (i != picked) {
                    picked = i
                    onPick(i)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                picked = -1
                onPick(-1)
                invalidate()
            }
        }
        return true
    }
}
