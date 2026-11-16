package com.sahayak.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID

/**
 * Do jude hue phone ke beech awaaz aur video call.
 * Ghanti aur "haan/na" usi taale wale relay se jaate hain jisse to-do list jaati hai;
 * awaaz aur video seedhe ek phone se doosre phone jaate hain (WebRTC), relay se nahi.
 */
object Calls {
    const val IDLE = "idle"
    const val CALLING = "calling"
    const val RINGING = "ringing"
    const val INCOMING = "incoming"
    const val CONNECTING = "connecting"
    const val ACTIVE = "active"

    private const val CHANNEL = "sahayak_calls"
    private const val NOTE_RING = 7701
    private const val NOTE_MISSED = 7702
    private const val GATHER_MS = 4_000L
    private const val NO_ANSWER_MS = 45_000L
    private const val INCOMING_MS = 50_000L
    private const val CONNECT_MS = 30_000L
    private const val DROP_MS = 12_000L
    private const val TURN_HOST = "staticauth.openrelay.metered.ca"
    private const val TURN_SECRET = "openrelayprojectsecret"

    @Volatile
    var state = IDLE
        private set

    /** Ye call video wali hai? */
    @Volatile
    var video = false
        private set

    /** Baat kab shuru hui (ms). 0 = abhi judi nahi. */
    @Volatile
    var activeSince = 0L
        private set

    /** Pichhli call kyun khatm hui, screen par dikhane ke liye. */
    @Volatile
    var note = ""
        private set

    var muted = false
        private set
    var speaker = false
        private set
    var cameraOff = false
        private set

    /** Call ki screen khuli ho to haal badalte hi taaza ho. */
    @Volatile
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val joiner = CallLogic.Joiner()
    private val finished = HashSet<String>()
    private var app: Context? = null
    private var callId = ""
    private var pendingOffer = ""
    private var sentLocal = false

    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var remoteVideo: VideoTrack? = null
    private var localView: SurfaceViewRenderer? = null
    private var remoteView: SurfaceViewRenderer? = null

    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null
    private var oldMode = AudioManager.MODE_NORMAL
    private var oldSpeaker = false

    private fun ping() {
        val call = listener ?: return
        main.post { call() }
    }

    // ---------- WebRTC ka saamaan ----------

    @Synchronized
    private fun ready(c: Context): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(c.applicationContext).createInitializationOptions())
        val base = egl ?: EglBase.create().also { egl = it }
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(base.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(base.eglBaseContext))
            .createPeerConnectionFactory().also { factory = it }
    }

    /** Seedha raasta dhoondhne wale (STUN) aur, wo na mile to, beech ka muft relay (TURN). */
    private fun servers(): List<PeerConnection.IceServer> {
        val user = CallLogic.turnUser(System.currentTimeMillis() / 1000)
        val pass = CallLogic.turnPass(TURN_SECRET, user)
        return listOf(
            PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")).createIceServer(),
            PeerConnection.IceServer.builder(listOf("turn:$TURN_HOST:80", "turn:$TURN_HOST:80?transport=tcp", "turn:$TURN_HOST:443", "turns:$TURN_HOST:443?transport=tcp"))
                .setUsername(user).setPassword(pass).createIceServer()
        )
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit

        override fun onIceGatheringChange(gathering: PeerConnection.IceGatheringState?) {
            if (gathering == PeerConnection.IceGatheringState.COMPLETE) main.post { sendLocal(callId) }
        }

        override fun onIceConnectionChange(ice: PeerConnection.IceConnectionState?) {
            val id = callId
            main.post {
                if (id != callId) return@post
                when (ice) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED ->
                        if (state == CONNECTING || state == RINGING || state == CALLING) {
                            stopSounds()
                            state = ACTIVE
                            activeSince = System.currentTimeMillis()
                            ping()
                        }
                    PeerConnection.IceConnectionState.FAILED ->
                        end(if (state == ACTIVE) "Connection lost" else "Could not connect. The network did not let the two phones reach each other")
                    PeerConnection.IceConnectionState.DISCONNECTED -> main.postDelayed({
                        if (id == callId && pc?.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED) end("Connection lost")
                    }, DROP_MS)
                    else -> Unit
                }
            }
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track() as? VideoTrack ?: return
            main.post {
                remoteVideo = track
                remoteView?.let { track.addSink(it) }
                ping()
            }
        }
    }

    private open class Sdp(val what: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = main.post { end("Call could not start ($what)") }.let { }
        override fun onSetFailure(error: String?) = main.post { end("Call could not start ($what)") }.let { }
    }

    /** Mic (aur video call mein camera) jodo. */
    private fun buildPeer(c: Context): Boolean {
        val f = ready(c)
        val config = PeerConnection.RTCConfiguration(servers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val peer = f.createPeerConnection(config, observer) ?: return false
        pc = peer
        audioSource = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("audio0", audioSource).also { peer.addTrack(it, listOf("s0")) }
        if (video) {
            try {
                val cams = Camera2Enumerator(c)
                val name = cams.deviceNames.firstOrNull { cams.isFrontFacing(it) } ?: cams.deviceNames.firstOrNull()
                val cam = name?.let { cams.createCapturer(it, null) }
                if (cam != null) {
                    val base = egl ?: EglBase.create().also { egl = it }
                    helper = SurfaceTextureHelper.create("camera", base.eglBaseContext)
                    val source = f.createVideoSource(false)
                    videoSource = source
                    cam.initialize(helper, c.applicationContext, source.capturerObserver)
                    cam.startCapture(640, 480, 24)
                    capturer = cam
                    videoTrack = f.createVideoTrack("video0", source).also { track ->
                        peer.addTrack(track, listOf("s0"))
                        localView?.let { track.addSink(it) }
                    }
                }
            } catch (e: RuntimeException) {
                // Camera na khule to sirf awaaz se call chalegi
            }
        }
        return true
    }

    private fun closePeer() {
        try {
            localView?.let { videoTrack?.removeSink(it) }
            remoteView?.let { remoteVideo?.removeSink(it) }
        } catch (e: RuntimeException) {
            // Pehle hi band
        }
        remoteVideo = null
        try {
            capturer?.stopCapture()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: RuntimeException) {
            // Pehle hi band
        }
        listOf<() -> Unit>(
            { capturer?.dispose() }, { helper?.dispose() }, { pc?.close() }, { pc?.dispose() },
            { videoSource?.dispose() }, { audioSource?.dispose() }
        ).forEach {
            try {
                it()
            } catch (e: RuntimeException) {
                // Safai mein gadbad se call ka khatm hona nahi rukna chahiye
            }
        }
        capturer = null
        helper = null
        pc = null
        videoSource = null
        audioSource = null
        videoTrack = null
        audioTrack = null
    }

    /** Call ki screen apne do video dabbe deti hai. */
    fun bind(c: Context, local: SurfaceViewRenderer, remote: SurfaceViewRenderer) {
        ready(c)
        val base = egl ?: return
        try {
            remote.init(base.eglBaseContext, null)
            local.init(base.eglBaseContext, null)
            local.setMirror(true)
            local.setZOrderMediaOverlay(true)
        } catch (e: RuntimeException) {
            return
        }
        localView = local
        remoteView = remote
        videoTrack?.addSink(local)
        remoteVideo?.addSink(remote)
    }

    fun unbind() {
        try {
            localView?.let { videoTrack?.removeSink(it) }
            remoteView?.let { remoteVideo?.removeSink(it) }
            localView?.release()
            remoteView?.release()
        } catch (e: RuntimeException) {
            // Pehle hi band
        }
        localView = null
        remoteView = null
    }

    /** Doosre ka video aa raha hai? */
    fun hasRemoteVideo() = remoteVideo != null

    // ---------- Awaaz ----------

    private fun audio(c: Context) = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun startAudio(c: Context) {
        val am = audio(c)
        oldMode = am.mode
        @Suppress("DEPRECATION")
        oldSpeaker = am.isSpeakerphoneOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        speaker = video
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = speaker
        muted = false
        cameraOff = false
    }

    private fun stopAudio(c: Context) {
        try {
            val am = audio(c)
            am.mode = oldMode
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = oldSpeaker
        } catch (e: RuntimeException) {
            // Koi baat nahi
        }
    }

    private fun ringIn(c: Context) {
        try {
            ringtone = RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        } catch (e: RuntimeException) {
            // Ghanti na baje to bhi screen aur notification to hai
        }
    }

    private fun ringOut() {
        try {
            tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        } catch (e: RuntimeException) {
            // Kuch phone par tone nahi banti
        }
    }

    private fun stopSounds() {
        try {
            ringtone?.stop()
            tone?.stopTone()
            tone?.release()
        } catch (e: RuntimeException) {
            // Pehle hi band
        }
        ringtone = null
        tone = null
    }

    // ---------- Call lagana, uthana, kaatna ----------

    private fun signal(kind: String, body: String = "", call: String = callId) {
        val c = app ?: return
        TodoSync.sendCall(c, CallLogic.parts(call, kind, video, body, System.currentTimeMillis()))
    }

    /** Raaste (ICE) mil jayein ya 4 second ho jayein, tab apna hissa bhejo. */
    private fun waitThenSend() {
        sentLocal = false
        val id = callId
        main.postDelayed({ sendLocal(id) }, GATHER_MS)
    }

    private fun sendLocal(id: String) {
        if (sentLocal || id != callId || id.isEmpty()) return
        val peer = pc ?: return
        val mine = peer.localDescription ?: return
        sentLocal = true
        signal(if (mine.type == SessionDescription.Type.OFFER) "offer" else "answer", mine.description)
    }

    /** Call lagao. Mic (aur camera) ki ijazat pehle mil chuki honi chahiye. */
    fun start(context: Context, withVideo: Boolean) {
        val c = context.applicationContext
        if (state != IDLE) return
        if (!TodoSync.linked(c)) {
            note = "Connect the two phones first"
            ping()
            return
        }
        TodoSync.start(c)
        app = c
        callId = UUID.randomUUID().toString().take(10)
        video = withVideo
        note = ""
        activeSince = 0L
        state = CALLING
        startAudio(c)
        if (!buildPeer(c)) {
            end("Call could not start")
            return
        }
        ringOut()
        val peer = pc ?: return
        peer.createOffer(object : Sdp("offer") {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                peer.setLocalDescription(object : Sdp("offer") {
                    override fun onSetSuccess() = main.post { waitThenSend() }.let { }
                }, sdp)
            }
        }, MediaConstraints())
        val id = callId
        main.postDelayed({ if (id == callId && (state == CALLING || state == RINGING)) hangup("No answer") }, NO_ANSWER_MS)
        ping()
    }

    /** Relay se aaya call ka signal (TodoSync deta hai). */
    fun onSignal(context: Context, items: List<org.json.JSONObject>) {
        val c = context.applicationContext
        items.forEach { item -> joiner.add(item)?.let { s -> main.post { handle(c, s) } } }
    }

    private fun handle(c: Context, s: CallLogic.Signal) {
        if (!CallLogic.fresh(s.at, System.currentTimeMillis()) || s.call in finished) return
        when (s.kind) {
            "offer" -> {
                if (state != IDLE) {
                    if (s.call != callId) {
                        app = c
                        signal("busy", "", s.call)
                    }
                    return
                }
                app = c
                callId = s.call
                video = s.video
                pendingOffer = s.body
                note = ""
                activeSince = 0L
                state = INCOMING
                signal("ringing")
                ringIn(c)
                showIncoming(c)
                val id = callId
                main.postDelayed({ if (id == callId && state == INCOMING) end("Missed call", true) }, INCOMING_MS)
                ping()
            }
            "ringing" -> if (s.call == callId && state == CALLING) {
                state = RINGING
                ping()
            }
            "answer" -> if (s.call == callId && (state == CALLING || state == RINGING)) {
                stopSounds()
                state = CONNECTING
                pc?.setRemoteDescription(Sdp("answer"), SessionDescription(SessionDescription.Type.ANSWER, s.body))
                connectTimeout()
                ping()
            }
            "busy" -> if (s.call == callId && state != IDLE) end("Busy on another call")
            "bye" -> if (s.call == callId && state != IDLE) end(if (state == INCOMING) "Missed call" else "Call ended", state == INCOMING)
        }
    }

    private fun connectTimeout() {
        val id = callId
        main.postDelayed({
            if (id == callId && state == CONNECTING) hangup("Could not connect. The network did not let the two phones reach each other")
        }, CONNECT_MS)
    }

    /** Aayi hui call uthao. Mic (aur camera) ki ijazat pehle mil chuki honi chahiye. */
    fun accept(context: Context) {
        val c = context.applicationContext
        if (state != INCOMING) return
        stopSounds()
        cancelNote(c)
        state = CONNECTING
        startAudio(c)
        if (!buildPeer(c)) {
            hangup("Call could not start")
            return
        }
        val peer = pc ?: return
        peer.setRemoteDescription(object : Sdp("offer") {
            override fun onSetSuccess() {
                peer.createAnswer(object : Sdp("answer") {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        peer.setLocalDescription(object : Sdp("answer") {
                            override fun onSetSuccess() = main.post { waitThenSend() }.let { }
                        }, sdp)
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, pendingOffer))
        connectTimeout()
        ping()
    }

    /** Call kaato ya mana karo: doosre phone ko bhi batao. */
    fun hangup(reason: String = "Call ended") {
        if (state == IDLE) return
        signal("bye")
        end(reason)
    }

    private fun end(reason: String, missed: Boolean = false) {
        if (state == IDLE) return
        val c = app
        finished.add(callId)
        if (finished.size > 50) finished.clear()
        stopSounds()
        closePeer()
        if (c != null) {
            stopAudio(c)
            cancelNote(c)
            if (missed) showMissed(c)
        }
        state = IDLE
        note = reason
        activeSince = 0L
        callId = ""
        pendingOffer = ""
        ping()
    }

    fun toggleMute() {
        muted = !muted
        audioTrack?.setEnabled(!muted)
        ping()
    }

    fun toggleSpeaker() {
        val c = app ?: return
        speaker = !speaker
        @Suppress("DEPRECATION")
        audio(c).isSpeakerphoneOn = speaker
        ping()
    }

    fun toggleCamera() {
        cameraOff = !cameraOff
        videoTrack?.setEnabled(!cameraOff)
        ping()
    }

    fun flipCamera() {
        try {
            capturer?.switchCamera(null)
        } catch (e: RuntimeException) {
            // Ek hi camera hai
        }
    }

    // ---------- Notification ----------

    private fun manager(c: Context): NotificationManager? = c.getSystemService(NotificationManager::class.java)?.also {
        it.createNotificationChannel(NotificationChannel(CHANNEL, "Calls", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
    }

    /** Call aayi: screen kholo (band phone par bhi) aur upar notification do. */
    private fun showIncoming(c: Context) {
        val open = Intent(c, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            c.startActivity(open)
        } catch (e: RuntimeException) {
            // Android ne peeche se screen kholne nahi di: notification se khulegi
        }
        try {
            val tap = PendingIntent.getActivity(c, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val builder = Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(if (video) "🎥 Video call" else "📞 Voice call")
                .setContentText(TodoSync.peer(c) + " is calling. Tap to answer")
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(tap)
                .setFullScreenIntent(tap, true)
            manager(c)?.notify(NOTE_RING, builder.build())
        } catch (e: RuntimeException) {
            // Notification ki ijazat nahi
        }
    }

    private fun showMissed(c: Context) {
        try {
            val open = Intent(c, SharedTodoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val tap = PendingIntent.getActivity(c, 3, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val builder = Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_call_missed)
                .setContentTitle("Missed call")
                .setContentText(TodoSync.peer(c) + " called you")
                .setAutoCancel(true)
                .setContentIntent(tap)
            manager(c)?.notify(NOTE_MISSED, builder.build())
        } catch (e: RuntimeException) {
            // Notification ki ijazat nahi
        }
    }

    private fun cancelNote(c: Context) {
        try {
            c.getSystemService(NotificationManager::class.java)?.cancel(NOTE_RING)
        } catch (e: RuntimeException) {
            // Koi baat nahi
        }
    }
}
