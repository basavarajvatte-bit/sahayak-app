package com.sahayak.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Saal dar saal revenue aur profit ke bar. Revenue neela, profit hara, loss laal. */
class FinanceView(context: Context) : View(context) {
    private var years: List<MarketLogic.Year> = emptyList()
    private var picked = -1

    /** Jis saal par tap hua uska number. */
    var onPick: (Int) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD0D5DD.toInt()
        strokeWidth = 1f * density
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
        color = 0xFF475467.toInt()
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
        color = 0xFF1D2433.toInt()
        isFakeBoldText = true
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1F3F8.toInt() }
    private val rect = RectF()

    fun show(years: List<MarketLogic.Year>) {
        this.years = years
        picked = years.lastIndex
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (years.isEmpty() || width == 0 || height == 0) return
        val top = 18f * density
        val bottom = height - 22f * density
        var max = 0.0
        var min = 0.0
        years.forEach {
            max = maxOf(max, it.revenue, it.profit)
            min = minOf(min, it.revenue, it.profit)
        }
        val range = (max - min).coerceAtLeast(1e-9)
        // Loss ho to bar zero ki line se neeche jaata hai; uske aankde ke liye thodi jagah
        val below = if (min < 0) 14f * density else 0f
        fun yOf(v: Double) = (bottom - below - (v - min) / range * (bottom - below - top)).toFloat()
        val zero = yOf(0.0)
        val group = width.toFloat() / years.size
        val wide = (group * 0.3f).coerceAtMost(30f * density)
        val gap = 3f * density
        years.forEachIndexed { i, y ->
            val mid = group * i + group / 2
            if (i == picked) {
                rect.set(group * i + 2f * density, 0f, group * (i + 1) - 2f * density, height.toFloat())
                canvas.drawRoundRect(rect, 8f * density, 8f * density, glow)
            }
            drawBar(canvas, mid - gap / 2 - wide, wide, y.revenue, zero, yOf(y.revenue), if (y.revenue >= 0) REVENUE else LOSS)
            drawBar(canvas, mid + gap / 2, wide, y.profit, zero, yOf(y.profit), if (y.profit >= 0) PROFIT else LOSS)
            canvas.drawText(y.label, mid, height - 6f * density, label)
        }
        canvas.drawLine(0f, zero, width.toFloat(), zero, axis)
    }

    /** Ek bar aur uske upar (ya loss mein neeche) chhota aankda. */
    private fun drawBar(canvas: Canvas, left: Float, wide: Float, amount: Double, zero: Float, y: Float, color: Int) {
        bar.color = color
        val small = 2f * density
        val up = amount >= 0
        val edge = if (up) minOf(y, zero - small) else maxOf(y, zero + small)
        rect.set(left, minOf(edge, zero), left + wide, maxOf(edge, zero))
        canvas.drawRoundRect(rect, 3f * density, 3f * density, bar)
        val text = MarketLogic.shortNumber(amount)
        canvas.drawText(text, left + wide / 2, if (up) edge - 4f * density else edge + 12f * density, value)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (years.isEmpty()) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
            val i = (event.x / (width.toFloat() / years.size)).toInt().coerceIn(0, years.lastIndex)
            if (i != picked) {
                picked = i
                onPick(i)
                invalidate()
            }
        }
        return true
    }

    companion object {
        const val REVENUE = 0xFF5B8DEF.toInt()
        const val PROFIT = 0xFF16A34A.toInt()
        const val LOSS = 0xFFDC2626.toInt()
    }
}
