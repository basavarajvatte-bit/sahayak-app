package com.sahayak.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import okhttp3.OkHttpClient
import java.io.IOException

/** Background mein screen, mic aur Gemini ko jodne wala kaam. */
class AssistService : Service(), GeminiLive.Callback {
    private var projection: MediaProjection? = null
    private var screen: ScreenFeed? = null
    private var voice: VoiceIO? = null
    private var live: GeminiLive? = null
    private val http: OkHttpClient = GeminiLive.newClient()
    private val worker = HandlerThread("sahayak-worker").apply { start() }
    private val workerHandler = Handler(worker.looper)

    private var apiKey = ""
    private var systemText = ""
    private var language = ""
    private var models: List<String> = emptyList()
    private var modelIndex = 0
    @Volatile private var stopping = false
    @Volatile private var wasReady = false
    private var retries = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            finish("Band ho gaya")
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        // Android ka niyam: projection lene se pehle foreground notification zaroori hai.
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIFICATION_ID, buildNotification(), types)

        apiKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        language = intent.getStringExtra(EXTRA_LANGUAGE) ?: "Hindi"
        systemText = Prompts.system(language)

        val manager = getSystemService(MediaProjectionManager::class.java)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        if (resultData == null) {
            finish("Screen share ki ijazat nahi mili")
            return START_NOT_STICKY
        }
        val mediaProjection = manager.getMediaProjection(intent.getIntExtra(EXTRA_RESULT, 0), resultData)
        if (mediaProjection == null) {
            finish("Screen share shuru nahi hua")
            return START_NOT_STICKY
        }
        projection = mediaProjection
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                finish("Screen share band ho gaya. Dobara Assist dabaiye.")
            }
        }, workerHandler)

        AssistState.set(true, "Shuru ho raha hai...")
        workerHandler.post { begin(mediaProjection) }
        return START_NOT_STICKY
    }

    private fun begin(mediaProjection: MediaProjection) {
        try {
            models = GeminiLive.liveModels(http, apiKey)
        } catch (e: IOException) {
            val text = if (e.message == "bad key") "Gemini key sahi nahi lagi. Key dobara daliye."
            else "Internet check kijiye aur dobara Assist dabaiye."
            finish(text)
            return
        }
        try {
            val io = VoiceIO(this) { pcm, size -> live?.sendAudio(pcm, size) }
            io.start()
            voice = io
            val feed = ScreenFeed(mediaProjection) { jpeg -> live?.sendJpeg(jpeg) }
            feed.start()
            screen = feed
        } catch (e: RuntimeException) {
            finish("Mic ya screen shuru nahi hua. Phone restart karke dekhiye.")
            return
        }
        live = GeminiLive(http, this)
        connect()
    }

    private fun connect() {
        val model = models.getOrElse(modelIndex) { GeminiLive.DEFAULT_MODEL }
        live?.connect(apiKey, model, systemText)
    }

    override fun onReady(resumed: Boolean) {
        wasReady = true
        retries = 0
        AssistState.set(true, "Sahayak sun raha hai. Ab boliye.")
        if (!resumed) live?.sendText(Prompts.greeting(language))
    }

    override fun onAudio(pcm: ByteArray) {
        voice?.play(pcm)
    }

    override fun onInterrupted() {
        voice?.interrupt()
    }

    /** Google connection band karne wala ho to naya connection, wahi baat aage. */
    override fun onGoAway() {
        workerHandler.post {
            if (stopping) return@post
            live?.close()
            connect()
        }
    }

    override fun onClosed(code: Int, reason: String) {
        if (stopping) return
        workerHandler.post {
            val text = reason.lowercase()
            when {
                stopping -> Unit
                code == 429 || "quota" in text || "resource_exhausted" in text ->
                    finish("Gemini ki free limit khatam ho gayi. Thodi der baad try kijiye.")
                !wasReady && modelIndex + 1 < models.size -> {
                    modelIndex++
                    connect()
                }
                !wasReady -> finish("Gemini se connection nahi hua (code $code).")
                retries < MAX_RETRIES -> {
                    retries++
                    AssistState.set(true, "Connection dobara jod raha hai...")
                    workerHandler.postDelayed({ if (!stopping) connect() }, RETRY_DELAY_MS)
                }
                else -> finish("Connection toot gaya. Internet check karke dobara Assist dabaiye.")
            }
        }
    }

    private fun finish(message: String) {
        if (stopping) return
        stopping = true
        live?.close()
        screen?.stop()
        voice?.stop()
        val active = projection
        projection = null
        active?.stop()
        AssistState.set(false, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finish("Band ho gaya")
        worker.quitSafely()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sahayak", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AssistService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Sahayak screen dekh raha hai")
            .setContentText("Band karne ke liye Band karo dabaiye")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Band karo", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.sahayak.app.STOP"
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_LANGUAGE = "language"
        private const val CHANNEL_ID = "sahayak_live"
        private const val NOTIFICATION_ID = 7
        private const val MAX_RETRIES = 5
        private const val RETRY_DELAY_MS = 2_000L

        fun start(context: Context, resultCode: Int, data: Intent, key: String, language: String) {
            context.startForegroundService(
                Intent(context, AssistService::class.java)
                    .putExtra(EXTRA_RESULT, resultCode)
                    .putExtra(EXTRA_DATA, data)
                    .putExtra(EXTRA_KEY, key)
                    .putExtra(EXTRA_LANGUAGE, language)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AssistService::class.java).setAction(ACTION_STOP))
        }
    }
}
