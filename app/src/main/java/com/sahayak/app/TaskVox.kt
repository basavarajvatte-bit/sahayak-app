package com.sahayak.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** TaskVox ka AI phone call: AI aapki taraf se saamne wale se baat karta hai. */
class TaskVox(http: OkHttpClient, private val apiKey: String) {
    private val client = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    private fun request(path: String) = Request.Builder().url(BASE + path).header("X-API-Key", apiKey)

    private fun send(builder: Request.Builder): String {
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("TaskVox " + response.code + " " + body.take(160))
            return body
        }
    }

    /** Pehla agent chunta hai. */
    private fun agentId(): String {
        val body = send(request("/api/agents")).trim()
        val agents = if (body.startsWith("[")) JSONArray(body) else {
            val json = JSONObject(body)
            json.optJSONArray("agents") ?: json.optJSONArray("data") ?: JSONArray()
        }
        val first = agents.optJSONObject(0) ?: throw IOException("No TaskVox agent found")
        return first.optString("id").ifEmpty { first.optString("_id") }.ifEmpty { first.optString("agentId") }
            .ifEmpty { throw IOException("TaskVox agent id missing") }
    }

    /** Call shuru karke request_id deta hai. */
    fun dial(number: String, ownerName: String, task: String): String {
        val digits = number.filter { it.isDigit() }.takeLast(10)
        if (digits.length != 10) throw IOException("Need a 10 digit Indian number")
        val body = JSONObject().put("agentId", agentId()).put("to", digits)
            .put("variables", JSONObject().put("name", ownerName).put("task", task))
            .toString().toRequestBody("application/json".toMediaType())
        val reply = JSONObject(send(request("/api/calls/dial").post(body)))
        return reply.optString("request_id").ifEmpty { reply.optString("requestId") }
            .ifEmpty { throw IOException("TaskVox did not return a request id") }
    }

    /** Call ka haal. Khatam hone par text, warna null. */
    fun result(requestId: String): String? {
        val reply = JSONObject(send(request("/api/calls/dial/$requestId")))
        val status = reply.optString("status").lowercase()
        val reason = reply.optJSONObject("failure")?.optString("reason").orEmpty()
        val summary = reply.optString("summary").ifEmpty { reply.optJSONObject("lead")?.toString().orEmpty() }
        return when {
            reason.isNotEmpty() -> "Call failed: $reason"
            status in DONE -> "Call finished. " + summary.take(300)
            status in FAILED -> "Call failed: $status"
            else -> null
        }
    }

    companion object {
        private const val BASE = "https://taskvox.in"
        private val DONE = setOf("completed", "complete", "done", "finished", "ended")
        private val FAILED = setOf("failed", "busy", "no-answer", "no_answer", "canceled", "cancelled", "rejected")
    }
}
