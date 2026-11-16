package com.sahayak.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.webrtc.SurfaceViewRenderer

/** Call ki screen: aati call uthao ya kaato, baat ke dauran mute, speaker, camera aur kaatna. */
class CallActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var remote: SurfaceViewRenderer? = null
    private var local: SurfaceViewRenderer? = null
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var buttons: LinearLayout
    private var wasBusy = false
    private var asking = false
    private var closing = false
    private var shown = ""
    private var after: (() -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF101828.toInt()) }
        val video = intent.getBooleanExtra(EXTRA_VIDEO, false) || (Calls.state != Calls.IDLE && Calls.video)
        if (video) {
            val far = SurfaceViewRenderer(this)
            val near = SurfaceViewRenderer(this)
            root.addView(far, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            root.addView(near, FrameLayout.LayoutParams(dp(110), dp(150), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(40)
                rightMargin = dp(16)
            })
            remote = far
            local = near
            Calls.bind(this, near, far)
        }

        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(70), dp(20), 0)
        }
        title = TextView(this).apply {
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 0f, 0xFF000000.toInt())
        }
        status = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFFE4E7EC.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            setShadowLayer(6f, 0f, 0f, 0xFF000000.toInt())
        }
        words.addView(title)
        words.addView(status)
        root.addView(words, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        buttons = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), dp(40))
        }
        root.addView(buttons, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        if (intent.getBooleanExtra(EXTRA_START, false) && Calls.state == Calls.IDLE) {
            // Ijazat ka dabba khula ho tab tak screen band mat karo
            asking = true
            withPermission(video, {
                asking = false
                wasBusy = true
                Calls.start(this, video)
                render()
            }) {
                asking = false
                Toast.makeText(this, "Allow the microphone" + (if (video) " and camera" else "") + " to make a call", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        Calls.listener = { render() }
        main.post(tick)
    }

    override fun onPause() {
        main.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        if (Calls.listener != null) Calls.listener = null
        main.removeCallbacksAndMessages(null)
        // Screen band ki to call bhi band: peeche se mic aur camera Android rok deta hai
        if (isFinishing && Calls.state != Calls.IDLE) Calls.hangup()
        if (remote != null) Calls.unbind()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Call ke dauran galti se band na ho: laal button se kaato
        if (Calls.state == Calls.IDLE) super.onBackPressed()
    }

    private fun granted(video: Boolean) = needed(video).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun needed(video: Boolean) = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)

    private var denied: (() -> Unit)? = null

    private fun withPermission(video: Boolean, ok: () -> Unit, no: () -> Unit) {
        if (granted(video)) {
            ok()
            return
        }
        after = ok
        denied = no
        requestPermissions(needed(video), ASK)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != ASK) return
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        val yes = after
        val no = denied
        after = null
        denied = null
        if (ok) yes?.invoke() else no?.invoke()
    }

    private fun round(words: String, color: Int, action: () -> Unit) = TextView(this).apply {
        text = words
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(14), dp(16), dp(14), dp(16))
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(28).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(5)
            rightMargin = dp(5)
        }
        setOnClickListener { action() }
    }

    private fun render() {
        if (closing) return
        if (asking) {
            title.text = TodoSync.peer(this)
            status.text = "Allow the microphone" + (if (remote != null) " and camera" else "") + " to call"
            return
        }
        val state = Calls.state
        if (state != Calls.IDLE) wasBusy = true
        title.text = TodoSync.peer(this)
        status.text = when (state) {
            Calls.CALLING -> "Calling…\nReaching the other phone"
            Calls.RINGING -> "Ringing…"
            Calls.INCOMING -> if (Calls.video) "🎥 Incoming video call" else "📞 Incoming voice call"
            Calls.CONNECTING -> "Connecting…"
            Calls.ACTIVE -> CallLogic.clock((System.currentTimeMillis() - Calls.activeSince) / 1000) +
                (if (Calls.video && !Calls.hasRemoteVideo()) "\nWaiting for their video" else "")
            else -> Calls.note.ifEmpty { "Call ended" }
        }
        local?.visibility = if (state == Calls.INCOMING || Calls.cameraOff) View.INVISIBLE else View.VISIBLE

        val key = state + Calls.muted + Calls.speaker + Calls.cameraOff
        if (key != shown) {
            shown = key
            buttons.removeAllViews()
            when (state) {
                Calls.INCOMING -> {
                    buttons.addView(round("✕ Decline", RED) { Calls.hangup("Call declined") })
                    buttons.addView(round("✓ Answer", GREEN) {
                        withPermission(Calls.video, { Calls.accept(this) }) {
                            Toast.makeText(this, "Allow the microphone" + (if (Calls.video) " and camera" else "") + " to answer", Toast.LENGTH_LONG).show()
                            Calls.hangup("Call declined")
                        }
                    })
                }
                Calls.IDLE -> Unit
                else -> {
                    buttons.addView(round(if (Calls.muted) "🔇 Unmute" else "🎤 Mute", if (Calls.muted) AMBER else GREY) { Calls.toggleMute() })
                    buttons.addView(round(if (Calls.speaker) "🔊 Speaker on" else "🔈 Speaker", if (Calls.speaker) BLUE else GREY) { Calls.toggleSpeaker() })
                    if (Calls.video) {
                        buttons.addView(round(if (Calls.cameraOff) "📷 Off" else "📷 On", if (Calls.cameraOff) AMBER else GREY) { Calls.toggleCamera() })
                        buttons.addView(round("🔄 Flip", GREY) { Calls.flipCamera() })
                    }
                    buttons.addView(round("📵 End", RED) { Calls.hangup() })
                }
            }
        }
        if (state == Calls.IDLE && (wasBusy || !intent.getBooleanExtra(EXTRA_START, false))) {
            closing = true
            main.postDelayed({ finish() }, CLOSE_MS)
        }
    }

    companion object {
        private const val EXTRA_START = "start"
        private const val EXTRA_VIDEO = "video"
        private const val ASK = 71
        private const val CLOSE_MS = 2500L
        private const val RED = 0xFFD92D20.toInt()
        private const val GREEN = 0xFF16A34A.toInt()
        private const val GREY = 0xFF475467.toInt()
        private const val AMBER = 0xFFB54700.toInt()
        private const val BLUE = 0xFF175CD3.toInt()

        /** Call lagane ke liye screen kholo. */
        fun dial(c: Context, video: Boolean): Intent = Intent(c, CallActivity::class.java).putExtra(EXTRA_START, true).putExtra(EXTRA_VIDEO, video)
    }
}
