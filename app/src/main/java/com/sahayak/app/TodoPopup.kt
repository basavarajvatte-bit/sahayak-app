package com.sahayak.app

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Screen tootne jaisi daraarein. Sirf dikhane ke liye, koi touch nahi leta. */
@SuppressLint("ViewConstructor")
private class CrackView(context: Context, private val centerY: Float) : View(context) {
    private val paths = ArrayList<Path>()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(90, 120, 255, 220)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    var progress = 0f
        set(value) {
            field = value
            invalidate()
        }

    private fun build(width: Float, height: Float) {
        val random = java.util.Random(7)
        val reach = Math.hypot(width.toDouble(), height.toDouble()).toFloat() * 0.75f
        for (i in 0 until CRACKS) {
            val angle = i * (2 * Math.PI / CRACKS) + random.nextDouble() * 0.3
            val path = Path().apply { moveTo(width / 2, centerY) }
            var distance = 0f
            var x = width / 2
            var y = centerY
            while (distance < reach) {
                val hop = 50f + random.nextFloat() * 90f
                val turn = angle + (random.nextDouble() - 0.5) * 0.9
                x += (Math.cos(turn) * hop).toFloat()
                y += (Math.sin(turn) * hop).toFloat()
                distance += hop
                path.lineTo(x, y)
                if (random.nextFloat() < 0.35f) {
                    val branch = turn + (if (random.nextBoolean()) 0.8 else -0.8)
                    path.moveTo(x, y)
                    path.lineTo(x + (Math.cos(branch) * hop).toFloat(), y + (Math.sin(branch) * hop).toFloat())
                    path.moveTo(x, y)
                }
            }
            paths.add(path)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (paths.isEmpty()) build(width.toFloat(), height.toFloat())
        val radius = Math.hypot(width.toDouble(), height.toDouble()).toFloat() * progress
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(width / 2f, centerY, radius, Path.Direction.CW) })
        paths.forEach {
            canvas.drawPath(it, glow)
            canvas.drawPath(it, line)
        }
        canvas.restore()
    }

    companion object {
        private const val CRACKS = 16
    }
}

/** Unlock karte hi baki kaam dikhata hai. Tap karne par kaam complete. */
class TodoPopup(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private var box: LinearLayout? = null
    private var crack: CrackView? = null
    private var buddy: MiniBuddy? = null
    private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(service) { status ->
        if (status == TextToSpeech.SUCCESS) {
            speaker().language = Locale("en", "IN")
            speaker().setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    main.post { buddy?.talking = true }
                }

                override fun onDone(utteranceId: String?) {
                    main.post {
                        buddy?.talking = false
                        main.removeCallbacks(hideLater)
                        main.postDelayed(hideLater, AFTER_SPEECH_MS)
                    }
                }

                @Deprecated("Android API")
                override fun onError(utteranceId: String?) {
                    onDone(utteranceId)
                }
            })
            ttsReady = true
        }
    }

    private fun speaker(): TextToSpeech = tts

    private fun speech(items: List<org.json.JSONObject>): String {
        val prefs = service.getSharedPreferences("sahayak", Context.MODE_PRIVATE)
        val name = prefs.getString("name", "").orEmpty()
        val tasks = items.take(MAX_SPOKEN).joinToString(". Also, ") { it.optString("text") }
        return if (name.isEmpty()) tasks else "$name, $tasks"
    }

    private fun canSpeak(): Boolean = ttsReady &&
        service.getSharedPreferences("sahayak", Context.MODE_PRIVATE).getBoolean("todo_speak", true)
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
        val top = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        val robot = MiniBuddy(service)
        buddy = robot
        top.addView(robot, LinearLayout.LayoutParams((84 * density).toInt(), (84 * density).toInt()))
        top.addView(TextView(service).apply {
            text = "To-do: tap when done"
            textSize = 15f
            setTextColor(Color.rgb(32, 201, 151))
            setPadding(pad, 0, 0, 0)
        })
        card.addView(top)
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
        val cracks = CrackView(service, service.resources.displayMetrics.heightPixels * 0.2f)
        windows.addView(cracks, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ))
        crack = cracks
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CRACK_MS
            addUpdateListener { cracks.progress = it.animatedValue as Float }
            start()
        }
        cracks.animate().alpha(0f).setStartDelay(CRACK_MS + 450L).setDuration(350L).start()

        card.scaleX = 0.15f
        card.scaleY = 0.15f
        card.alpha = 0f
        windows.addView(card, params)
        box = card
        card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(POP_MS)
            .setInterpolator(OvershootInterpolator(3.5f)).start()
        ObjectAnimator.ofFloat(card, View.ROTATION, 0f, -2.5f, 2.5f, -1.5f, 1.5f, 0f).apply {
            duration = POP_MS + 120L
            start()
        }
        if (canSpeak()) {
            main.postDelayed({
                tts.speak(speech(items), TextToSpeech.QUEUE_FLUSH, Bundle(), "todo")
            }, POP_MS)
            main.postDelayed(hideLater, MAX_SPEECH_MS)
        } else {
            main.postDelayed(hideLater, POP_MS + SHOW_MS)
        }
    }

    fun shutdown() {
        hide()
        tts.shutdown()
    }

    /** Box tezi se bada hokar gayab hota hai. */
    fun hide() {
        main.removeCallbacks(hideLater)
        if (ttsReady) tts.stop()
        buddy = null
        val card = box
        val cracks = crack
        box = null
        crack = null
        if (card == null) {
            cracks?.let { windows.removeView(it) }
            return
        }
        card.animate().scaleX(1.25f).scaleY(1.25f).alpha(0f).setDuration(LEAVE_MS)
            .setInterpolator(AccelerateInterpolator()).withEndAction {
                windows.removeView(card)
                cracks?.let { windows.removeView(it) }
            }.start()
    }

    companion object {
        private const val SHOW_MS = 1_000L
        private const val AFTER_SPEECH_MS = 400L
        private const val MAX_SPEECH_MS = 15_000L
        private const val MAX_SPOKEN = 3
        private const val POP_MS = 420L
        private const val CRACK_MS = 260L
        private const val LEAVE_MS = 220L
        private const val AFTER_TAP_MS = 1_000L
        private const val MAX_ROWS = 5
    }
}
