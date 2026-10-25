package app.orubus.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * THE REQUEST ENGINE — duck.ai called from the DEVICE.
 *
 * Every request leaves the phone with the user's own IP: the residential
 * lane DDG accepts. No Vercel hop, no datacenter ASN, no "brain lane is
 * packed" — that message was the shared server IP tripping DDG's walls;
 * from a phone, the wall isn't there.
 *
 * Flow (identical to the proven server path in lib/duck.ts):
 *   1. GET  /duckchat/v1/status   → x-vqd-hash-1 challenge (fresh per turn)
 *   2. Solver (WebView) runs the challenge JS → wrapped header + fe-signals
 *   3. POST /duckchat/v1/chat     → SSE stream → text
 * 418/challenge noise is retried with a fresh challenge (duckChat parity).
 */
object DuckEngine {

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val FE_FALLBACK = "serp_20261023_062911_ET-cdb913a87384b7be1d189a4658c28200974bd694"
    const val ENTRY_FALLBACK = "f1b96d6b68aa7e4766b9"

    @Volatile var feVersion: String = FE_FALLBACK; private set
    @Volatile var entryVer: String = ENTRY_FALLBACK; private set
    @Volatile var feFetched = false

    // tier → model slug, refreshed from the server state (panel/env-tunable
    // without an app release); constants are the known-good defaults
    @Volatile var models: Map<String, String> = mapOf(
        "lite" to "gpt-5.4-mini",
        "standard" to "gpt-6-luna",
        "pro" to "claude-haiku-4-5",
    )

    fun modelFor(tier: String): String = models[tier] ?: models["standard"]!!

    private fun baseHeaders(): Map<String, String> = mapOf(
        "user-agent" to UA,
        "accept" to "*/*",
        "referer" to "https://duck.ai/",
        "origin" to "https://duck.ai",
        "sec-ch-ua" to "\"Chromium\";v=\"131\", \"Not A(Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "accept-language" to "en-US",
    )

    /** Derive the live front-end versions from the duck.ai homepage once per run. */
    fun refreshFe() {
        if (feFetched) return
        feFetched = true
        try {
            val c = connect("https://duck.ai/", "GET")
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            val body = readAll(c) ?: return
            val tag = Regex("data-version-tag=\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            val sha = Regex("data-version-sha=\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            if (!tag.isNullOrBlank() && !sha.isNullOrBlank()) feVersion = "$tag-$sha"
            else if (!tag.isNullOrBlank()) feVersion = tag
            val e = Regex("entry\\.duckai\\.([a-z0-9]+)\\.js").find(body)?.groupValues?.get(1)
            if (!e.isNullOrBlank()) entryVer = e
        } catch (_: Exception) {
            // fallbacks stand
        }
    }

    private fun connect(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.instanceFollowRedirects = true
        for ((k, v) in baseHeaders()) c.setRequestProperty(k, v)
        return c
    }

    private fun readAll(c: HttpURLConnection): String? {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream ?: return null
        return try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    class DuckException(val status: Int, message: String) : Exception(message)
    class BusyException(message: String) : Exception(message)

    /** GET status → the x-vqd-hash-1 challenge for this journey. */
    private fun status(journey: String): String {
        val c = connect("https://duckduckgo.com/duckchat/v1/status", "GET")
        c.setRequestProperty("x-vqd-accept", "1")
        c.setRequestProperty("cache-control", "no-store")
        c.setRequestProperty("x-ddg-journey-id", journey)
        c.connectTimeout = 12_000
        c.readTimeout = 12_000
        val code = c.responseCode
        val challenge = c.headerFields?.entries
            ?.firstOrNull { it.key.equals("x-vqd-hash-1", ignoreCase = true) }?.value?.firstOrNull()
        if (code != 200 || challenge.isNullOrBlank()) {
            c.disconnect()
            throw DuckException(code, "no-challenge:$code")
        }
        c.disconnect()
        return challenge
    }

    /** POST chat → full SSE text. */
    private fun chatOnce(journey: String, solved: JSONObject, model: String, messages: JSONArray, timeoutMs: Int): String {
        val c = connect("https://duckduckgo.com/duckchat/v1/chat", "POST")
        c.setRequestProperty("accept", "text/event-stream")
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-vqd-hash-1", solved.optString("header"))
        c.setRequestProperty("x-fe-signals", solved.optString("signals"))
        c.setRequestProperty("x-fe-version", feVersion)
        c.setRequestProperty("x-duckai-response-id", UUID.randomUUID().toString())
        c.setRequestProperty("x-duckai-consent-purposes", "user-location,image-generation,web-search")
        c.setRequestProperty("x-duckai-tool-approval", "web-search")
        c.setRequestProperty("x-ddg-journey-id", journey)
        c.connectTimeout = 15_000
        c.readTimeout = timeoutMs
        c.doOutput = true

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("canUseTools", false)
            put("reasoningEffort", "none")
            put("canUseApproxLocation", JSONObject.NULL)
            put("canDelegateImageGeneration", JSONObject.NULL)
            put("canShowGreeting", false)
        }
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = c.responseCode
        if (code != 200) {
            val err = try { c.errorStream?.bufferedReader()?.use { it.readText() }?.take(120) } catch (_: Exception) { "" }
            c.disconnect()
            throw DuckException(code, "duck-$code:$err")
        }

        val out = StringBuilder()
        try {
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    val l = line.trim()
                    if (!l.startsWith("data: ")) continue
                    val payload = l.substring(6)
                    if (payload == "[DONE]") break
                    if (payload.startsWith("[")) continue
                    try {
                        val j = JSONObject(payload)
                        if (j.has("message") && !j.isNull("message")) out.append(j.getString("message"))
                        if (j.optString("action", "") == "error") throw DuckException(0, "duck-sse:${j.optString("type", "err")}")
                    } catch (e: DuckException) {
                        throw e
                    } catch (_: Exception) {
                        // partial JSON across chunk boundaries — next line completes it
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        val text = out.toString().trim()
        if (text.isEmpty()) throw DuckException(0, "duck-empty")
        return text
    }

    /**
     * One billed turn: status → solve → chat, retrying challenge-layer noise
     * with a fresh challenge. Blocking; call from a worker thread.
     */
    fun chat(messages: JSONArray, model: String, timeoutMs: Int = 45_000): String {
        refreshFe()
        var last: Exception? = null
        for (attempt in 0 until 2) {
            if (attempt > 0) Thread.sleep(2000 + (Math.random() * 2000).toLong())
            val journey = UUID.randomUUID().toString().replace("-", "")
            try {
                val challenge = status(journey)
                val solved = Solver.solve(challenge, UA, entryVer)
                    ?: throw DuckException(0, "solver-unavailable")
                if (!solved.optBoolean("ok", false)) throw DuckException(0, solved.optString("error", "solve-failed"))
                return chatOnce(journey, solved, model, messages, timeoutMs)
            } catch (e: DuckException) {
                last = e
                val retryable = e.status == 418 || e.message?.startsWith("no-challenge") == true ||
                    e.message?.startsWith("solve") == true || e.message?.startsWith("bad-sig") == true ||
                    e.message == "duck-empty" || e.status == 0
                if (!retryable) throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw BusyException(last?.message ?: "the free lane is catching its breath")
    }

    /** Build the engine message array: prelude + ack + the visible turns. */
    fun buildMessages(prelude: String, turns: List<Pair<Boolean, String>>): JSONArray {
        val arr = JSONArray()
        fun add(role: String, text: String) {
            arr.put(JSONObject().apply {
                put("role", role)
                put("content", JSONArray().put(JSONObject().apply { put("type", "text"); put("text", text) }))
            })
        }
        if (prelude.isNotBlank()) add("user", prelude)
        if (prelude.isNotBlank()) add("assistant", "Got it — I hold all of this in mind as we talk. What's on your mind?")
        for ((mine, text) in turns.takeLast(20)) add(if (mine) "user" else "assistant", text)
        return arr
    }
}
