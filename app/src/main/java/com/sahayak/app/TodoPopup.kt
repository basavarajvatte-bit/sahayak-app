package com.sahayak.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
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
    private val shards = ArrayList<FloatArray>()
    private val shardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
        repeat(SHARDS) {
            val angle = random.nextDouble() * 2 * Math.PI
            val speed = height * (0.3f + random.nextFloat() * 0.6f)
            shards.add(floatArrayOf(
                (Math.cos(angle) * speed).toFloat(), (Math.sin(angle) * speed).toFloat(),
                18f + random.nextFloat() * 42f, random.nextFloat() * 360f
            ))
        }
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
        val t = progress
        shards.forEach { s ->
            val x = width / 2f + s[0] * t
            val y = centerY + s[1] * t + height * 0.35f * t * t
            val size = s[2]
            canvas.save()
            canvas.rotate(s[3] + t * 540f, x, y)
            shardPaint.shader = LinearGradient(x, y - size, x + size, y + size,
                Color.argb(230, 230, 250, 255), Color.argb(70, 130, 210, 255), Shader.TileMode.CLAMP)
            canvas.drawPath(Path().apply {
                moveTo(x, y - size)
                lineTo(x + size * 0.8f, y + size * 0.6f)
                lineTo(x - size * 0.6f, y + size * 0.4f)
                close()
            }, shardPaint)
            canvas.restore()
        }
    }

    companion object {
        private const val CRACKS = 16
        private const val SHARDS = 34
    }
}

/** Unlock karte hi baki kaam dikhata hai. Tap karne par kaam complete. */
class TodoPopup(
    private val service: Context,
    private val overlayType: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private var box: LinearLayout? = null
    private var crack: CrackView? = null
    private var buddy: MiniBuddy? = null
    private var speakMood = "talk"
    private var endMood = "happy"
    private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(service) { status ->
        if (status == TextToSpeech.SUCCESS) {
            speaker().language = Locale("en", "IN")
            speaker().setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    main.post { buddy?.mood = speakMood }
                }

                override fun onDone(utteranceId: String?) {
                    main.post {
                        buddy?.mood = endMood
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

    private fun speech(items: List<org.json.JSONObject>, swag: Boolean): String {
        val prefs = service.getSharedPreferences("sahayak", Context.MODE_PRIVATE)
        val tasks = items.take(MAX_SPOKEN).joinToString(". Also, ") { it.optString("text") }
        val line = if (swag) SWAG_LINES.random() else "your to do list."
        return hello(prefs) + ", " + line + " $tasks"
    }

    private fun hello(prefs: android.content.SharedPreferences): String {
        val name = prefs.getString("name", "").orEmpty()
        return if (name.isEmpty()) "Hi" else "Hi $name"
    }

    /** Naya mail aane par Chhotu envelope lekar aata hai. */
    fun showMail() {
        hide()
        val prefs = service.getSharedPreferences("sahayak", Context.MODE_PRIVATE)
        val message = hello(prefs) + ", you got a mail!"
        if (ttsReady) {
            tts.setPitch(1f)
            tts.setSpeechRate(1f)
        }
        val pad = (14 * density).toInt()
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.argb(242, 20, 24, 33))
                cornerRadius = 18 * density
            }
            setOnClickListener {
                service.packageManager.getLaunchIntentForPackage("com.google.android.gm")?.let { open ->
                    service.startActivity(open.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                hide()
            }
        }
        val robot = MiniBuddy(service).apply { mood = "surprised" }
        buddy = robot
        speakMood = "mail"
        endMood = "happy"
        Frames.prepare(service, "mail")
        main.postDelayed({ if (buddy === robot) robot.mood = "mail" }, MAIL_SURPRISE_MS)
        card.addView(robot, LinearLayout.LayoutParams((190 * density).toInt(), (190 * density).toInt()))
        card.addView(TextView(service).apply {
            text = message
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        reveal(card, message)
    }

    /** Chhotu ka mood: raat ko neend, kaam zyada ho to gussa ya soch. */
    private fun todoMood(count: Int): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour >= NIGHT_HOUR || hour < MORNING_HOUR -> "sleepy"
            count >= MANY_TASKS -> "grumpy"
            count >= SOME_TASKS -> "thinking"
            else -> "swag"
        }
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
        val top = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val start = todoMood(items.size)
        val swag = start == "swag"
        val robot = MiniBuddy(service).apply { mood = start }
        buddy = robot
        speakMood = if (swag) "swagtalk" else "talk"
        endMood = if (swag) "swagidle" else "sad"
        Frames.prepare(service, speakMood)
        // Thodi dheemi aur bhaari awaaz, attitude ke saath
        if (ttsReady) {
            tts.setPitch(if (swag) SWAG_PITCH else 1f)
            tts.setSpeechRate(if (swag) SWAG_RATE else 1f)
        }
        top.addView(robot, LinearLayout.LayoutParams((190 * density).toInt(), (190 * density).toInt()))
        top.addView(TextView(service).apply {
            text = "To-do: tap when done"
            textSize = 15f
            setTextColor(Color.rgb(32, 201, 151))
            gravity = Gravity.CENTER
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
                    if (ttsReady) tts.stop()
                    endMood = "excited"
                    buddy?.mood = "excited"
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
        reveal(card, speech(items, swag), if (swag) SWAG_MS else 0L)
    }

    private fun reveal(card: LinearLayout, words: String, waitMs: Long = 0L) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
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
            overlayType,
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

        card.scaleX = 0.05f
        card.scaleY = 0.05f
        card.translationY = 260 * density
        card.alpha = 0f
        windows.addView(card, params)
        box = card
        card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        card.animate().scaleX(1f).scaleY(1f).translationY(0f).alpha(1f).setDuration(POP_MS)
            .setInterpolator(OvershootInterpolator(3.5f)).start()
        ObjectAnimator.ofFloat(card, View.ROTATION, 0f, -2.5f, 2.5f, -1.5f, 1.5f, 0f).apply {
            duration = POP_MS + 120L
            start()
        }
        if (canSpeak()) {
            main.postDelayed({
                tts.speak(words, TextToSpeech.QUEUE_FLUSH, Bundle(), "todo")
            }, POP_MS + waitMs)
            main.postDelayed(hideLater, MAX_SPEECH_MS + waitMs)
        } else {
            main.postDelayed(hideLater, POP_MS + SHOW_MS + waitMs)
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
        private const val AFTER_SPEECH_MS = 1_800L
        private const val MAIL_SURPRISE_MS = 1_000L
        private const val SWAG_MS = 3_000L
        private const val SWAG_PITCH = 0.82f
        private const val SWAG_RATE = 0.9f
        private val SWAG_LINES = listOf(
            "watch the style. Here is your work.",
            "the boss has arrived. Now listen.",
            "style first, then work. Here it comes.",
            "one look, one list. Let us finish this."
        )
        private const val NIGHT_HOUR = 22
        private const val MORNING_HOUR = 6
        private const val MANY_TASKS = 5
        private const val SOME_TASKS = 3
        private const val MAX_SPEECH_MS = 15_000L
        private const val MAX_SPOKEN = 3
        private const val POP_MS = 420L
        private const val CRACK_MS = 520L
        private const val LEAVE_MS = 220L
        private const val AFTER_TAP_MS = 1_800L
        private const val MAX_ROWS = 5
    }
}
