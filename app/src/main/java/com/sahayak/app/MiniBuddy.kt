package com.sahayak.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import java.io.IOException
import java.util.Collections

/** Chhotu: Sahayak ka apna 3D creature. Kisi film ka character nahi. */
@SuppressLint("ViewConstructor")
class MiniBuddy(context: Context) : View(context) {
    private val frameBox = RectF()
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var time = 0f
    private var shown: List<Bitmap> = emptyList()

    /** Kaunsa bhaav: idle, happy, excited, talk, mail, sad, surprised, sleepy, grumpy, thinking. */
    var mood = "idle"
        set(value) {
            field = value
            moodStart = -1f
            Frames.prepare(context, value)
        }
    private var moodStart = 0f

    init {
        Frames.prepare(context, mood)
    }

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
        Frames.get(mood)?.let {
            shown = it
            // Clip load hone ke baad hi ghadi chalu, warna swag ka shuru ka hissa chhoot jata hai
            if (moodStart < 0f) moodStart = time
        }
        // Swag ek baar chalta hai, aakhri pose par rukta hai
        val index = if (mood == ONCE) ((time - maxOf(moodStart, 0f)) * FPS).toInt().coerceAtMost(shown.size - 1)
        else (time * FPS).toInt() % maxOf(shown.size, 1)
        val frame = shown.getOrNull(index) ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        shadowPaint.shader = RadialGradient(
            w / 2f, h * 0.92f, w * 0.3f, Color.argb(120, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.scale(1f, 0.2f, w / 2f, h * 0.92f)
        canvas.drawCircle(w / 2f, h * 0.92f, w * 0.3f, shadowPaint)
        canvas.restore()
        frameBox.set(0f, 0f, w, h)
        canvas.drawBitmap(frame, null as Rect?, frameBox, framePaint)
    }

    companion object {
        private const val FPS = 12
        private const val ONCE = "swag"
    }
}

/** Blender ke frames. Ek clip zaroorat par load, sirf aakhri teen yaad rehte hain. */
object Frames {
    private const val COUNT = 36
    private const val KEEP = 5
    private val clips = Collections.synchronizedMap(object : LinkedHashMap<String, List<Bitmap>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Bitmap>>) = size > KEEP
    })
    private val loading = Collections.synchronizedSet(HashSet<String>())

    fun get(name: String): List<Bitmap>? = clips[name]

    fun prepare(context: Context, name: String) {
        if (clips.containsKey(name) || !loading.add(name)) return
        val app = context.applicationContext
        Thread {
            val frames = (0 until COUNT).mapNotNull { i ->
                try {
                    app.assets.open("chhotu/" + name + "_" + i.toString().padStart(2, '0') + ".webp").use {
                        BitmapFactory.decodeStream(it)
                    }
                } catch (e: IOException) {
                    null
                }
            }
            clips[name] = frames
            loading.remove(name)
        }.start()
    }

    /** Swag pehle se taiyar, taaki popup aate hi poora move chale. */
    fun load(context: Context) = listOf("swag", "swagtalk", "swagidle").forEach { prepare(context, it) }
}
