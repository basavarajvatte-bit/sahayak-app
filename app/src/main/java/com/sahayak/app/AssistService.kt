package com.sahayak.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
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
import android.os.Looper
import android.speech.SpeechRecognizer
import android.view.WindowManager
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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
    private var userName = ""
    private var voiceName = "Aoede"
    private var models: List<String> = emptyList()
    private var modelIndex = 0
    @Volatile private var stopping = false
    @Volatile private var wasReady = false
    private var retries = 0
    private var lastClose = ""

    // Screen badalne par Sahayak khud agla step bole, iska hisaab
    @Volatile private var screenChangedAt = 0L
    @Volatile private var speakingUntil = 0L
    @Volatile private var releasedAt = 0L
    @Volatile private var talking = false
    @Volatile private var stopRequested = false
    private var pressedAt = 0L
    private var autoStep = false
    private var planner: Planner? = null
    private val plannerThread = HandlerThread("sahayak-planner").apply { start() }
    private val plannerHandler = Handler(plannerThread.looper)
    private val recent = ArrayDeque<String>()
    private val appVisits = ArrayDeque<String>()
    private var stepCount = 0
    @Volatile private var currentOrder: String? = null
    @Volatile private var quietOrder = false
    private val stepLog = ArrayList<String>()
    private var reviews = 0
    private var controlLostShown = false
    private var tools: JSONArray? = null
    private val actions = HandlerThread("sahayak-actions").apply { start() }
    private val actionHandler = Handler(actions.looper)
    private var dot: TalkDot? = null
    private var caption: Caption? = null
    private val heard = StringBuilder()
    private var listener: LiveListener? = null
    private var typeBar: TypeBar? = null
    @Volatile private var silentTurn = false
    @Volatile private var framesPaused = false
    private val replyText = StringBuilder()
    private val hideReply = Runnable { if (!talking) caption?.hide() }
    private var whisper: Whisper? = null
    private val spoken = ByteArrayOutputStream()
    private val whisperThread = HandlerThread("sahayak-whisper").apply { start() }
    private val whisperHandler = Handler(whisperThread.looper)
    private var partialBusy = false
    private var partialOff = false
    private val partialTick = object : Runnable {
        override fun run() {
            if (!talking || whisper == null) return
            whisperHandler.postDelayed(this, PARTIAL_MS)
            val audio = snapshot()
            if (partialOff || partialBusy || audio.size < MIN_AUDIO_BYTES || !hasVoice(audio)) return
            partialBusy = true
            try {
                val text = whisper?.transcribe(audio).orEmpty()
                if (talking && text.isNotEmpty()) mainHandler.post { caption?.show(text) }
            } catch (e: IOException) {
                partialOff = true // limit par ho to sirf aakhri likhai lo
            }
            partialBusy = false
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val endTalk = Runnable { finishTalk() }
    private var endPending = false
    private var lastNudgeAt = 0L
    private val nudgeCheck = object : Runnable {
        override fun run() {
            if (stopping) return
            maybeNudge()
            if (tools != null && PhoneControl.instance == null && !controlLostShown) {
                controlLostShown = true
                RunLog.add(this@AssistService, "control_lost")
                AssistState.set(true, "Phone control band ho gaya. Sahayak app kholkar dobara chalu kijiye.")
            }
            workerHandler.postDelayed(this, NUDGE_TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            finish("Band ho gaya")
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_TYPE) {
            val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_KEY)?.toString().orEmpty().trim()
            if (typed.isNotEmpty()) typedOrder(typed)
            updateNotification()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_TALK) {
            if (talking) releaseDot() else pressDot()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        // Android ka niyam: projection lene se pehle foreground notification zaroori hai.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, buildNotification(), types)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        apiKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        language = intent.getStringExtra(EXTRA_LANGUAGE) ?: "Hindi"
        userName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        voiceName = intent.getStringExtra(EXTRA_VOICE) ?: "Aoede"
        autoStep = intent.getBooleanExtra(EXTRA_AUTO, false)
        planner = Planner(http, apiKey)
        whisper = intent.getStringExtra(EXTRA_WHISPER).orEmpty().takeIf { it.isNotBlank() }?.let { Whisper(http, it) }
        val canControl = PhoneControl.instance != null
        tools = if (canControl) GeminiLive.phoneTools() else null
        systemText = Prompts.system(language, userName, canControl)

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
            val io = VoiceIO(this) { pcm, size ->
                if (whisper != null) synchronized(spoken) { spoken.write(pcm, 0, size) }
                else live?.sendAudio(pcm, size)
            }
            io.start()
            voice = io
            val feed = ScreenFeed(
                mediaProjection,
                onChanged = { screenChangedAt = System.currentTimeMillis() },
                onFrame = { jpeg -> if (!framesPaused) live?.sendJpeg(jpeg) }
            )
            feed.start()
            screen = feed
        } catch (e: RuntimeException) {
            finish("Mic ya screen shuru nahi hua. Phone restart karke dekhiye.")
            return
        }
        live = GeminiLive(http, this)
        mainHandler.post {
            if (!stopping) {
                // Phone control chalu ho to dot IRCTC jaise apps par bhi dikhta hai.
                val control = PhoneControl.instance
                val host: android.content.Context = control ?: this
                val type = if (control != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                val bar = TypeBar(host, type) { text -> typedOrder(text) }
                typeBar = bar
                val talkDot = TalkDot(
                    host, onPress = { pressDot() }, onRelease = { releaseDot() }, windowType = type,
                    onMoved = { x, y -> bar.move(x, y) }
                )
                talkDot.show()
                dot = talkDot
                bar.show(talkDot.windowX, talkDot.windowY)
                caption = Caption(host, type)
                if (whisper == null && SpeechRecognizer.isRecognitionAvailable(this)) {
                    val primary = if (language == "Marathi") "mr-IN" else "hi-IN"
                    listener = LiveListener(this, primary,
                        onWords = { words -> if (talking) caption?.show(words) },
                        onBroken = { useAudioFallback() })
                }
            }
        }
        connect()
        workerHandler.postDelayed(nudgeCheck, NUDGE_TICK_MS)
    }

    private val busyTimeout = Runnable { setBusy(false) }

    /** Order chalne tak ghoomta circle. Kisi jawab ke bina 45 second baad khud band. */
    private fun setBusy(on: Boolean) {
        mainHandler.removeCallbacks(busyTimeout)
        if (on) mainHandler.postDelayed(busyTimeout, BUSY_TIMEOUT_MS)
        mainHandler.post { dot?.setBusy(on) }
    }

    /** Likhkar diya order: Sahayak chup rehkar kaam karta hai, jawab screen par dikhta hai. */
    private fun typedOrder(text: String) {
        workerHandler.post {
            if (live?.isReady != true) {
                AssistState.set(true, "Abhi connection nahi hai. Thodi der mein dobara likhiye.")
                return@post
            }
            stopRequested = false
            silentTurn = true
            voice?.interrupt()
            mainHandler.post {
                replyText.setLength(0)
                mainHandler.removeCallbacks(hideReply)
            }
            releasedAt = System.currentTimeMillis()
            RunLog.add(this, "order typed")
            setBusy(true)
            dispatchOrder(text)
        }
    }

    private fun dotState(state: TalkDot.State) {
        mainHandler.post { dot?.setState(state) }
    }

    private fun pressDot() {
        if (live?.isReady != true) return
        workerHandler.post {
            if (endPending) {
                workerHandler.removeCallbacks(endTalk)
                finishTalk()
            }
            talking = true
            silentTurn = false
            mainHandler.removeCallbacks(hideReply)
            pressedAt = System.currentTimeMillis()
            mainHandler.post {
                heard.setLength(0)
                caption?.show("Boliye...")
            }
            setBusy(false)
            speakingUntil = 0L
            voice?.interrupt()
            val active = listener
            when {
                whisper != null -> {
                    synchronized(spoken) { spoken.reset() }
                    partialOff = false
                    voice?.setMic(true)
                    whisperHandler.postDelayed(partialTick, PARTIAL_MS)
                }
                active != null -> mainHandler.post { active.start() }
                else -> voice?.setMic(true)
            }
            dotState(TalkDot.State.TALKING)
            updateNotification()
            AssistState.set(true, "Sun raha hai...")
        }
    }

    private fun releaseDot() {
        workerHandler.post {
            if (!talking) return@post
            talking = false
            mainHandler.post { caption?.hide() }
            dotState(TalkDot.State.READY)
            updateNotification()
            endPending = true
            if (System.currentTimeMillis() - pressedAt < STOP_TAP_MS) {
                // Ek chhota tap: chal raha kaam turant roko
                stopRequested = true
                currentOrder = null
                mainHandler.post { listener?.cancel() }
                whisperHandler.removeCallbacks(partialTick)
                voice?.setMic(false)
                endPending = false
                setBusy(false)
                live?.sendText(Prompts.STOP)
                AssistState.set(true, "Kaam rok diya.")
                RunLog.add(this, "stopped_by_person")
                return@post
            }
            stopRequested = false
            workerHandler.postDelayed(endTalk, MIC_TAIL_MS) // aakhri shabd na kate
        }
    }

    private fun finishTalk() {
        endPending = false
        releasedAt = System.currentTimeMillis()
        if (whisper != null) {
            whisperHandler.removeCallbacks(partialTick)
            whisper?.cancelPartial()
            voice?.setMic(false)
            setBusy(true)
            whisperHandler.post { sendWhisperOrder() }
            return
        }
        val active = listener
        if (active == null) {
            voice?.setMic(false)
            live?.audioEnd()
            setBusy(true)
            return
        }
        setBusy(true)
        mainHandler.post {
            active.finish { words ->
                if (words.isBlank()) {
                    setBusy(false)
                    AssistState.set(true, "Awaaz samajh nahi aayi. Dot dabakar dobara boliye.")
                } else {
                    releasedAt = System.currentTimeMillis()
                    RunLog.add(this, "order listener")
                    dispatchOrder(words)
                }
            }
        }
    }

    /** Chhota order phone par turant, baaki AI ko. */
    private fun dispatchOrder(text: String) {
        val command = LocalCommands.parse(text)
        val control = PhoneControl.instance
        if (command == null || control == null) {
            sendPlanned(text)
            return
        }
        actionHandler.post {
            val began = System.currentTimeMillis()
            val done = try {
                control.fast(command)
            } catch (e: RuntimeException) {
                false
            }
            RunLog.add(this, "fast " + (if (done) "ok " else "failed ") + (System.currentTimeMillis() - began) + "ms")
            if (done) {
                setBusy(false)
                silentTurn = false
                mainHandler.post {
                    caption?.show("Ho gaya")
                    mainHandler.postDelayed(hideReply, FAST_DONE_MS)
                }
            } else {
                sendPlanned(text)
            }
        }
    }

    /** Pehle soch, phir kaam: plan banakar order bhejta hai. */
    private fun sendPlanned(text: String) {
        val think = planner
        if (think == null || PhoneControl.instance == null) {
            live?.sendText(text)
            return
        }
        plannerHandler.post {
            val began = System.currentTimeMillis()
            val app = PhoneControl.instance?.rootInActiveWindow?.packageName?.toString().orEmpty()
            val plan = think.plan(text, app)
            RunLog.add(this, "plan " + (if (plan != null) "ok " else "skipped ") + (System.currentTimeMillis() - began) + "ms")
            if (stopRequested) return@post
            workerHandler.post {
                recent.clear()
                appVisits.clear()
                stepCount = 0
                stepLog.clear()
                reviews = 0
                currentOrder = text
                quietOrder = silentTurn
            }
            live?.sendText(if (plan == null) text else "ORDER: $text\n\nPLAN:\n$plan")
        }
    }

    /** Doosra model kaam jaanchta hai aur zaroorat par sudhar bhejta hai. */
    private fun reviewWork(stuck: Boolean) {
        val order = currentOrder ?: return
        val think = planner ?: return
        if (reviews >= MAX_REVIEWS || stepLog.isEmpty() || stopRequested) return
        reviews++
        val steps = stepLog.takeLast(15).joinToString("\n")
        setBusy(true)
        plannerHandler.postDelayed({
            val began = System.currentTimeMillis()
            val answer = think.review(order, steps, screen?.latest(), stuck).orEmpty().trim()
            val fix = answer.takeIf { it.startsWith("FIX") }?.removePrefix("FIX")?.trimStart(':', ' ')
            RunLog.add(this, "review " + (if (fix != null) "fix " else "done ") + (System.currentTimeMillis() - began) + "ms")
            when {
                stopRequested || talking -> setBusy(false)
                fix.isNullOrEmpty() -> {
                    setBusy(false)
                    if (!stuck) currentOrder = null
                }
                else -> {
                    silentTurn = quietOrder
                    live?.sendText("[REVIEW] $fix")
                }
            }
        }, REVIEW_DELAY_MS)
    }

    /** Sahayak chakkar mein phanse to use rokta hai. */
    private fun checkProgress(name: String, args: JSONObject, result: JSONObject) {
        stepCount++
        stepLog.add(name + " " + result.optString("status") + " " + result.optString("reason"))
        val mark = name + "|" + args.toString().take(120) + "|" + result.optString("app")
        recent.addLast(mark)
        if (recent.size > HISTORY_SIZE) recent.removeFirst()
        if (name == "open_app") {
            appVisits.addLast(args.optString("name").lowercase())
            if (appVisits.size > 4) appVisits.removeFirst()
        }
        val visits = appVisits.toList()
        val bouncing = visits.size == 4 && visits[0] == visits[2] && visits[1] == visits[3] && visits[0] != visits[1]
        val repeating = recent.count { it == mark } >= REPEAT_LIMIT
        when {
            stepCount >= STEP_LIMIT -> result.put("status", "stopped").put("reason", "too_many_steps")
                .put("warning", "Stop now and tell the person in one sentence what is blocking you.")
            bouncing || repeating -> {
                RunLog.add(this, "loop_warning")
                reviewWork(stuck = true)
                result.put("warning", "You are going in circles. Do not repeat this. Use what you already have and take a different approach.")
            }
        }
    }

    private fun snapshot(): ByteArray = synchronized(spoken) { spoken.toByteArray() }

    private fun hasVoice(pcm: ByteArray): Boolean {
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = (pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)
            if (kotlin.math.abs(sample) > VOICE_PEAK) return true
            i += 2
        }
        return false
    }

    /** Dot chhodne par poori awaaz Whisper ko. Whisper na chale to wahi awaaz Gemini ko. */
    private fun sendWhisperOrder() {
        val audio = snapshot()
        if (audio.size < MIN_AUDIO_BYTES || !hasVoice(audio)) {
            setBusy(false)
            AssistState.set(true, "Awaaz nahi aayi. Dot dabakar dobara boliye.")
            return
        }
        try {
            val text = whisper?.transcribe(audio).orEmpty()
            if (text.isBlank()) throw IOException("empty")
            releasedAt = System.currentTimeMillis()
            RunLog.add(this, "order whisper")
            dispatchOrder(text)
        } catch (e: IOException) {
            RunLog.add(this, "order whisper_failed_audio_used")
            var start = 0
            while (start < audio.size) {
                val size = minOf(AUDIO_CHUNK, audio.size - start)
                live?.sendAudio(audio.copyOfRange(start, start + size), size)
                start += size
            }
            live?.audioEnd()
        }
    }

    /** Is phone par speech recognition na chale to seedha awaaz Gemini ko bhejo. */
    private fun useAudioFallback() {
        listener?.destroy()
        listener = null
        caption?.show("Dobara boliye")
        workerHandler.post { if (talking) voice?.setMic(true) }
    }

    /** Screen rukne ke baad, jab koi bol nahi raha, Sahayak ko dekhne ko kehta hai. */
    private fun maybeNudge() {
        if (!autoStep || talking) return
        val changedAt = screenChangedAt
        val now = System.currentTimeMillis()
        if (changedAt == 0L || now - changedAt < SCREEN_SETTLE_MS) return
        if (now < speakingUntil + AFTER_SPEECH_MS || now - releasedAt < AFTER_SPEECH_MS) return
        if (now - lastNudgeAt < NUDGE_GAP_MS || live?.isReady != true) return
        screenChangedAt = 0L
        lastNudgeAt = now
        live?.sendText(Prompts.SCREEN_UPDATE)
    }

    private fun connect() {
        val model = models.getOrElse(modelIndex) { GeminiLive.DEFAULT_MODEL }
        live?.connect(apiKey, model, systemText, voiceName, tools)
    }

    override fun onReady(resumed: Boolean) {
        wasReady = true
        retries = 0
        dotState(TalkDot.State.READY)
        AssistState.set(
            true,
            if (tools != null) "Taiyar. Dot dabakar order dijiye."
            else "Phone control band hai, Sahayak sirf bolkar batayega. Khud kaam karwane ke liye control chalu kijiye."
        )
    }

    override fun onReplyText(text: String) {
        if (!silentTurn) return
        mainHandler.post {
            replyText.append(text)
            caption?.show(replyText.toString())
        }
    }

    override fun onAudio(pcm: ByteArray) {
        if (talking || silentTurn) return // chup rehne wali halat
        val now = System.currentTimeMillis()
        if (now >= speakingUntil && now - releasedAt < LATENCY_WINDOW_MS) {
            val seconds = (now - releasedAt) / 100 / 10.0
            RunLog.add(this, "reply ${seconds}s")
            AssistState.set(true, "Pichhla jawab: $seconds second")
            releasedAt = 0L
        }
        speakingUntil = maxOf(now, speakingUntil) + pcm.size / BYTES_PER_MS
        voice?.play(pcm)
    }

    /** Dot dabate hi automatic kaam ruk jata hai, taaki control aapke haath mein rahe. */
    /** Mic chalu rehne tak hi likhta hai. Dot chhodte hi text hat jata hai. */
    override fun onTranscript(text: String) {
        mainHandler.post {
            if (talking && listener == null) {
                heard.append(text)
                caption?.show(heard.toString())
            }
        }
    }

    override fun onTurnComplete() {
        framesPaused = false
        setBusy(false)
        reviewWork(stuck = false)
        if (silentTurn) {
            silentTurn = false
            mainHandler.postDelayed(hideReply, REPLY_SHOW_MS)
        }
        RunLog.add(this, "turn_done")
    }

    override fun onToolCall(id: String, name: String, args: JSONObject) {
        setBusy(true)
        framesPaused = true // kaam ke dauran AI ko screen list milti hai, photo ki zaroorat nahi
        actionHandler.post {
            val control = PhoneControl.instance
            val began = System.currentTimeMillis()
            val result = when {
                stopRequested || stopping -> JSONObject().put("status", "cancelled_by_person")
                control == null -> JSONObject().put("status", "control_off")
                else -> try {
                    AssistState.set(true, "Kaam chal raha hai. Rokne ke liye dot par ek chhota tap kijiye.")
                    control.run(name, args)
                } catch (e: RuntimeException) {
                    JSONObject().put("status", "failed")
                }
            }
            checkProgress(name, args, result)
            // List khali ho to AI ko wahi screen photo se dikhao
            if ((result.optJSONArray("screen")?.length() ?: 99) < MIN_LIST_SIZE) {
                screen?.latest()?.let { live?.sendJpeg(it) }
            }
            RunLog.add(this, name + " " + result.optString("status") + " " +
                (System.currentTimeMillis() - began) + "ms " + result.optString("app"))
            live?.sendToolResult(id, name, result)
        }
    }

    override fun onInterrupted() {
        speakingUntil = 0L
        setBusy(false)
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
            lastClose = "code $code" + if (reason.isBlank()) "" else ": " + reason.take(120)
            when {
                stopping -> Unit
                code == 429 || "quota" in text || "resource_exhausted" in text ->
                    finish("Gemini ki free limit khatam ho gayi. Thodi der baad try kijiye.")
                !wasReady && modelIndex + 1 < models.size -> {
                    modelIndex++
                    connect()
                }
                !wasReady -> finish("Gemini se connection nahi hua ($lastClose).")
                retries < MAX_RETRIES -> {
                    retries++
                    AssistState.set(true, "Connection dobara jod raha hai...")
                    dotState(TalkDot.State.CONNECTING)
                    setBusy(false)
                    workerHandler.postDelayed({ if (!stopping) connect() }, RETRY_DELAY_MS)
                }
                else -> finish("Connection toot gaya ($lastClose). Dobara Assist dabaiye.")
            }
        }
    }

    private fun finish(message: String) {
        if (stopping) return
        stopping = true
        mainHandler.removeCallbacks(busyTimeout)
        mainHandler.post {
            dot?.remove()
            dot = null
            typeBar?.remove()
            typeBar = null
            mainHandler.removeCallbacks(hideReply)
            caption?.hide()
            caption = null
            listener?.destroy()
            listener = null
        }
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
        actions.quitSafely()
        whisperThread.quitSafely()
        plannerThread.quitSafely()
        super.onDestroy()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
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
        val talk = PendingIntent.getService(
            this, 2, Intent(this, AssistService::class.java).setAction(ACTION_TALK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val typeIntent = PendingIntent.getService(
            this, 3, Intent(this, AssistService::class.java).setAction(ACTION_TYPE),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val typeAction = Notification.Action.Builder(null, "Likho", typeIntent)
            .addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel("Hindi ya English mein likhiye").build())
            .build()
        val talkLabel = if (talking) "Bas, jawab do" else "Bolo"
        val text = if (talking) "Sun raha hai. Boliye, phir Bas, jawab do dabaiye."
        else "Dot na dikhe to Bolo dabaiye"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Sahayak chalu hai")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, talkLabel, talk).build())
            .addAction(typeAction)
            .addAction(Notification.Action.Builder(null, "Band karo", stop).build())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.sahayak.app.STOP"
        private const val ACTION_TALK = "com.sahayak.app.TALK"
        private const val ACTION_TYPE = "com.sahayak.app.TYPE"
        private const val REPLY_KEY = "typed_order"
        private const val REPLY_SHOW_MS = 5_000L
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_LANGUAGE = "language"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_VOICE = "voice"
        private const val EXTRA_AUTO = "auto"
        private const val EXTRA_WHISPER = "whisper"
        private const val CHANNEL_ID = "sahayak_live"
        private const val NOTIFICATION_ID = 7
        private const val MAX_RETRIES = 5
        private const val RETRY_DELAY_MS = 2_000L
        private const val NUDGE_TICK_MS = 300L
        private const val SCREEN_SETTLE_MS = 1_200L
        private const val AFTER_SPEECH_MS = 800L
        private const val NUDGE_GAP_MS = 5_000L
        private const val LATENCY_WINDOW_MS = 15_000L
        private const val MIC_TAIL_MS = 80L
        private const val HISTORY_SIZE = 8
        private const val MAX_REVIEWS = 2
        private const val REVIEW_DELAY_MS = 1_200L
        private const val REPEAT_LIMIT = 3
        private const val STEP_LIMIT = 40
        private const val FAST_DONE_MS = 1_500L
        private const val MIN_LIST_SIZE = 8
        private const val PARTIAL_MS = 2_000L
        private const val MIN_AUDIO_BYTES = 8_000
        private const val AUDIO_CHUNK = 3_200
        private const val VOICE_PEAK = 1_200
        private const val STOP_TAP_MS = 300L
        private const val BUSY_TIMEOUT_MS = 45_000L
        private const val BYTES_PER_MS = 48 // 24 kHz, 16 bit

        fun start(context: Context, resultCode: Int, data: Intent, key: String, language: String, name: String, voice: String, autoStep: Boolean, whisperKey: String) {
            context.startForegroundService(
                Intent(context, AssistService::class.java)
                    .putExtra(EXTRA_RESULT, resultCode)
                    .putExtra(EXTRA_DATA, data)
                    .putExtra(EXTRA_KEY, key)
                    .putExtra(EXTRA_LANGUAGE, language)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_VOICE, voice)
                    .putExtra(EXTRA_AUTO, autoStep)
                    .putExtra(EXTRA_WHISPER, whisperKey)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AssistService::class.java).setAction(ACTION_STOP))
        }
    }
}
