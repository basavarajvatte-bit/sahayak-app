package com.sahayak.app

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Gemini Live API se WebSocket connection. */
class GeminiLive(private val client: OkHttpClient, private val callback: Callback) {

    interface Callback {
        fun onReady(resumed: Boolean)
        fun onAudio(pcm: ByteArray)
        fun onInterrupted()
        fun onGoAway()
        fun onTurnComplete()
        fun onTranscript(text: String)
        fun onToolCall(id: String, name: String, args: JSONObject)
        fun onClosed(code: Int, reason: String)
    }

    private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile var resumeHandle: String? = null
        private set

    fun connect(apiKey: String, model: String, systemText: String, voiceName: String, tools: JSONArray?) {
        ready = false
        val resuming = resumeHandle != null
        val request = Request.Builder().url(WS_URL + "?key=" + apiKey).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== socket) return
                webSocket.send(setupMessage(model, systemText, voiceName, tools, resumeHandle).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket === socket) handle(text, resuming)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (webSocket === socket) handle(bytes.utf8(), resuming)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (webSocket !== socket) return // purana connection, ise chhod do
                ready = false
                socket = null
                callback.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== socket) return
                ready = false
                socket = null
                callback.onClosed(response?.code ?: -1, t.message ?: "connection failed")
            }
        })
    }

    private fun handle(text: String, resuming: Boolean) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        if (msg.has("setupComplete")) {
            ready = true
            callback.onReady(resuming)
        }
        msg.optJSONObject("sessionResumptionUpdate")?.let { update ->
            val handle = update.optString("newHandle")
            if (update.optBoolean("resumable", false) && handle.isNotEmpty()) resumeHandle = handle
        }
        if (msg.has("goAway")) callback.onGoAway()
        msg.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                callback.onToolCall(call.optString("id"), call.optString("name"), call.optJSONObject("args") ?: JSONObject())
            }
        }
        val content = msg.optJSONObject("serverContent") ?: return
        if (content.optBoolean("interrupted", false)) callback.onInterrupted()
        content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
            ?.let { callback.onTranscript(it) }
        content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data").orEmpty()
                if (data.isNotEmpty()) callback.onAudio(Base64.decode(data, Base64.DEFAULT))
            }
        }
        if (content.optBoolean("turnComplete", false)) callback.onTurnComplete()
    }

    fun sendAudio(pcm: ByteArray, size: Int) =
        sendMedia("audio", "audio/pcm;rate=16000", pcm, size, MAX_QUEUE_BYTES)

    /** Internet dheema ho to photo chhod do, taaki awaaz atke nahi. */
    fun sendJpeg(jpeg: ByteArray) =
        sendMedia("video", "image/jpeg", jpeg, jpeg.size, MAX_FRAME_QUEUE_BYTES)

    fun activityStart() = sendActivity("activityStart")

    fun activityEnd() = sendActivity("activityEnd")

    private fun sendActivity(field: String) {
        if (ready) socket?.send(JSONObject().put("realtimeInput", JSONObject().put(field, JSONObject())).toString())
    }

    fun sendToolResult(id: String, name: String, result: JSONObject) {
        val response = JSONObject().put("name", name).put("response", result)
        if (id.isNotEmpty()) response.put("id", id)
        val message = JSONObject().put("toolResponse",
            JSONObject().put("functionResponses", JSONArray().put(response)))
        if (ready) socket?.send(message.toString())
    }

    fun sendText(text: String) {
        if (ready) socket?.send(JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString())
    }

    private fun sendMedia(field: String, mime: String, bytes: ByteArray, size: Int, limit: Long) {
        val ws = socket ?: return
        if (!ready || ws.queueSize() > limit) return
        val blob = JSONObject().put("mimeType", mime)
            .put("data", Base64.encodeToString(bytes, 0, size, Base64.NO_WRAP))
        ws.send(JSONObject().put("realtimeInput", JSONObject().put(field, blob)).toString())
    }

    fun close() {
        ready = false
        val old = socket
        socket = null
        old?.close(1000, null)
    }

    val isReady: Boolean get() = ready

    companion object {
        private const val MAX_QUEUE_BYTES = 2_000_000L
        private const val MAX_FRAME_QUEUE_BYTES = 120_000L
        private const val WS_URL = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000"
        const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"

        private fun tool(name: String, description: String, params: JSONObject? = null, required: List<String> = emptyList()): JSONObject {
            val tool = JSONObject().put("name", name).put("description", description)
            if (params != null) {
                tool.put("parameters", JSONObject().put("type", "OBJECT").put("properties", params)
                    .put("required", JSONArray(required)))
            }
            return tool
        }

        private fun prop(type: String, description: String) =
            JSONObject().put("type", type).put("description", description)

        fun phoneTools(): JSONArray {
            val element = prop("INTEGER", "Element number from the screen list")
            val declarations = JSONArray()
                .put(tool("get_screen", "Read the list of elements on the current screen."))
                .put(tool("open_app", "Open an installed app by name.",
                    JSONObject().put("name", prop("STRING", "App name")), listOf("name")))
                .put(tool("open_url", "Open a website address in the browser.",
                    JSONObject().put("url", prop("STRING", "Full https address")), listOf("url")))
                .put(tool("tap_text", "Tap the item showing these words. Best way to tap.",
                    JSONObject().put("text", prop("STRING", "Visible words")), listOf("text")))
                .put(tool("wait", "Wait for a page or result to load.",
                    JSONObject().put("seconds", prop("INTEGER", "1 to 10")), listOf("seconds")))
                .put(tool("tap", "Tap an element from the screen list.",
                    JSONObject().put("element", element), listOf("element")))
                .put(tool("tap_position", "Tap a point. x and y run from 0 to 1000 across the screen image.",
                    JSONObject().put("x", prop("INTEGER", "0 to 1000")).put("y", prop("INTEGER", "0 to 1000")), listOf("x", "y")))
                .put(tool("type_text", "Type text into an input element.",
                    JSONObject().put("element", element).put("text", prop("STRING", "Text to type")), listOf("element", "text")))
                .put(tool("press_enter", "Press the keyboard enter or send key on the focused input."))
                .put(tool("scroll", "Scroll the screen.",
                    JSONObject().put("direction", prop("STRING", "up or down")), listOf("direction")))
                .put(tool("go_back", "Press the back button."))
            return JSONArray().put(JSONObject().put("functionDeclarations", declarations))
        }

        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        fun setupMessage(model: String, systemText: String, voiceName: String, tools: JSONArray?, handle: String?): JSONObject {
            val resumption = JSONObject()
            if (handle != null) resumption.put("handle", handle)
            val setup = JSONObject()
                .put("model", "models/$model")
                .put("generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                        .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName)))))
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText))))
                .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection",
                    JSONObject().put("disabled", true)))
                .put("inputAudioTranscription", JSONObject())
                .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
                .put("sessionResumption", resumption)
            if (tools != null) setup.put("tools", tools)
            return JSONObject().put("setup", setup)
        }

        /** Key par chalne wale Live models. Key galat ho to IOException deta hai. */
        fun liveModels(client: OkHttpClient, apiKey: String): List<String> {
            val request = Request.Builder().url(MODELS_URL).header("x-goog-api-key", apiKey).build()
            client.newCall(request).execute().use { response ->
                if (response.code == 400 || response.code == 401 || response.code == 403) {
                    throw IOException("bad key")
                }
                if (!response.isSuccessful) return listOf(DEFAULT_MODEL)
                return rankModels(response.body?.string().orEmpty()).ifEmpty { listOf(DEFAULT_MODEL) }
            }
        }

        fun rankModels(json: String): List<String> {
            val models = JSONObject(json).optJSONArray("models") ?: return emptyList()
            val names = ArrayList<String>()
            for (i in 0 until models.length()) {
                val model = models.optJSONObject(i) ?: continue
                val methods = model.optJSONArray("supportedGenerationMethods") ?: continue
                val live = (0 until methods.length()).any { methods.optString(it) == "bidiGenerateContent" }
                val name = model.optString("name").removePrefix("models/")
                if (live && name.isNotEmpty() && !name.contains("translate")) names.add(name)
            }
            return names.sortedBy { name ->
                when {
                    name == DEFAULT_MODEL -> 0
                    name.contains("flash-live") -> 1
                    name.contains("native-audio") -> 2
                    else -> 3
                }
            }
        }
    }
}
