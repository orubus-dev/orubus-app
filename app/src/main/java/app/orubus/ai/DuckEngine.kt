package app.orubus.ai

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * THE REQUEST ENGINE — the lane the phone owns.
 *
 * Every request leaves the device with the user's own IP, and one IP it
 * stays: the engine pins a single resolved address for the whole session
 * and rides one pooled connection (HTTP/2), so the journey, the challenge
 * and the chat all present the SAME steady address. Tokens flap when the
 * network flaps — so the network doesn't flap.
 *
 * Token life: a solved challenge header is kept (memory + disk) and reused
 * turn after turn until the lane rejects it — only then is the next one
 * minted. Continuous chatting never re-mints per message.
 *
 * The challenge math runs on-device first (Solver, the WebView sandbox);
 * if the sandbox can't (cold start, odd variant), the server's proven
 * solver runs the math — the duck.ai HTTP calls STILL leave from the
 * device either way.
 *
 *   1. GET  /duckchat/v1/status   → x-vqd-hash-1 challenge
 *   2. solve (device sandbox → server assist)
 *   3. POST /duckchat/v1/chat     → SSE stream → text
 * Failures retry with a fresh mint; a dead network resolution re-resolves.
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

    // ---------- the steady IP ----------
    // One resolved address per session for every engine call. Mobile stacks
    // flap between IPv4 and IPv6 across connections; a token minted on one
    // family dies on the other. Pinning one address keeps the journey steady.

    private val pinnedDns = object : Dns {
        @Volatile private var fixed: InetAddress? = null
        @Volatile private var at: Long = 0

        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname != "duckduckgo.com" && hostname != "duck.ai") return Dns.SYSTEM.lookup(hostname)
            val f = fixed
            if (f != null && System.currentTimeMillis() - at < 15 * 60_000L) return listOf(f)
            val list = try { Dns.SYSTEM.lookup(hostname) } catch (e: Exception) { throw e }
            val pick = list.firstOrNull { it is Inet4Address } ?: list.firstOrNull()
            if (pick == null) throw UnknownHostException(hostname)
            fixed = pick
            at = System.currentTimeMillis()
            return listOf(pick)
        }

        fun reset() { fixed = null; at = 0 }
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .dns(pinnedDns)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
        .build()

    /** The shared lane — the live surfaces (voice session, ice-servers) ride
     *  the exact same pinned-IP client the chat rides. */
    internal fun laneClient(): OkHttpClient = http

    /** A pre-flavored request builder: UA/journey headers baked in. */
    internal fun laneRequest(url: String): Request.Builder = Request.Builder().url(url).base()

    internal fun Request.Builder.base(): Request.Builder {
        header("user-agent", UA)
        header("accept", "*/*")
        header("referer", "https://duck.ai/")
        header("origin", "https://duck.ai")
        header("sec-ch-ua", "\"Chromium\";v=\"131\", \"Not A(Brand\";v=\"24\"")
        header("sec-ch-ua-mobile", "?0")
        header("sec-ch-ua-platform", "\"Windows\"")
        header("accept-language", "en-US")
        return this
    }

    /** Derive the live front-end versions from the homepage. Never latches a
     *  failure — a stale fe-version reads as a 400 down the lane, so a failed
     *  derivation retries on the next turn. */
    fun refreshFe(force: Boolean = false) {
        if (feFetched && !force) return
        try {
            val req = Request.Builder().url("https://duck.ai/").base().get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string() ?: return
                var ok = false
                val tag = Regex("data-version-tag=\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                val sha = Regex("data-version-sha=\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                if (!tag.isNullOrBlank() && !sha.isNullOrBlank()) { feVersion = "$tag-$sha"; ok = true }
                else if (!tag.isNullOrBlank()) { feVersion = tag; ok = true }
                val e = Regex("entry\\.duckai\\.([a-z0-9]+)\\.js").find(body)?.groupValues?.get(1)
                if (!e.isNullOrBlank()) { entryVer = e; ok = true }
                if (ok) feFetched = true
            }
        } catch (_: Exception) {
            // fallbacks stand; try again next turn
        }
    }

    class DuckException(val status: Int, message: String) : Exception(message)
    class BusyException(message: String) : Exception(message)

    /**
     * The device is genuinely offline — thrown the moment an IOException
     * lands on a dead network so callers can CACHE the turn instead of
     * burning the retry ladder against a wall that isn't there.
     */
    class NetworkDeadException(message: String) : Exception(message)

    /** Cheap connectivity probe (best effort — true when unknowable). */
    fun netUp(): Boolean {
        return try {
            val cm = AppCtx.get().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val n = cm.activeNetwork
            if (n == null) false
            else cm.getNetworkCapabilities(n)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        } catch (_: Exception) { true }
    }

    // ---------- the token: alive till it runs out ----------

    class Token(val header: String, val signals: String, val journey: String, val mintedAt: Long) {
        fun age(): Long = System.currentTimeMillis() - mintedAt
    }

    /**
     * A fresh-or-fresh-enough token for the live lanes (the WebRTC voice
     * session greets ice-servers + /session with the same x-vqd-hash-1 the
     * chat carries). Blocking; call from a worker thread.
     */
    fun tokenForLive(): Token {
        refreshFe()
        val held = currentToken()
        if (held != null && held.age() < TOKEN_TTL_MS) return held
        clearToken()
        return mint()
    }

    private const val TOKEN_TTL_MS = 5 * 60_000L // preemptive re-mint window; death retries cover the rest

    @Volatile private var token: Token? = null

    private fun currentToken(): Token? {
        token?.let { return it }
        return try {
            val raw = Store.prefs().getString("duckToken", "") ?: ""
            if (raw.isBlank()) null
            else {
                val o = JSONObject(raw)
                if (o.optString("h").isBlank()) null
                else Token(o.optString("h"), o.optString("s"), o.optString("j"), o.optLong("t")).also { token = it }
            }
        } catch (_: Exception) { null }
    }

    private fun saveToken(t: Token) {
        token = t
        try {
            Store.prefs().edit().putString(
                "duckToken",
                JSONObject().put("h", t.header).put("s", t.signals).put("j", t.journey).put("t", t.mintedAt).toString()
            ).apply()
        } catch (_: Exception) { /* persistence never blocks the lane */ }
    }

    private fun clearToken() {
        token = null
        try { Store.prefs().edit().remove("duckToken").apply() } catch (_: Exception) {}
    }

    /** GET status → the x-vqd-hash-1 challenge for this journey. */
    private fun status(journey: String): String {
        val req = Request.Builder()
            .url("https://duckduckgo.com/duckchat/v1/status")
            .base()
            .header("x-vqd-accept", "1")
            .header("cache-control", "no-store")
            .header("x-ddg-journey-id", journey)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val challenge = resp.header("x-vqd-hash-1")
            if (!resp.isSuccessful || challenge.isNullOrBlank()) throw DuckException(resp.code, "no-challenge:${resp.code}")
            return challenge
        }
    }

    /** Mint a fresh token: status → solve (device sandbox first, server
     *  assist second). The HTTP lane stays on the device throughout. */
    private fun mint(): Token {
        val journey = UUID.randomUUID().toString().replace("-", "")
        val challenge = status(journey)
        var solved = Solver.solve(challenge, UA, entryVer)
        if (solved == null || !solved.optBoolean("ok", false)) {
            solved = Api.serverSolve(challenge)
        }
        if (solved == null || !solved.optBoolean("ok", false)) {
            throw DuckException(0, solved?.optString("error", "solve-failed") ?: "solver-unavailable")
        }
        val t = Token(solved.optString("header"), solved.optString("signals"), journey, System.currentTimeMillis())
        saveToken(t)
        return t
    }

    /** POST chat → full SSE text, using the given token. */
    private fun chatOnce(t: Token, model: String, messages: JSONArray, timeoutMs: Int): String {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("canUseTools", false)
            put("reasoningEffort", "none")
            put("canUseApproxLocation", JSONObject.NULL)
            put("canDelegateImageGeneration", JSONObject.NULL)
            put("canShowGreeting", false)
        }
        val req = Request.Builder()
            .url("https://duckduckgo.com/duckchat/v1/chat")
            .base()
            .header("accept", "text/event-stream")
            .header("content-type", "application/json")
            .header("x-vqd-hash-1", t.header)
            .header("x-fe-signals", t.signals)
            .header("x-fe-version", feVersion)
            .header("x-duckai-response-id", UUID.randomUUID().toString())
            .header("x-duckai-consent-purposes", "user-location,image-generation,web-search")
            .header("x-duckai-tool-approval", "web-search")
            .header("x-ddg-journey-id", t.journey)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = try { resp.body?.string()?.take(120) ?: "" } catch (_: Exception) { "" }
                throw DuckException(resp.code, "duck-${resp.code}:$err")
            }
            val src = resp.body?.source() ?: throw DuckException(0, "duck-empty")
            val out = StringBuilder()
            while (true) {
                val line = try { src.readUtf8Line() } catch (_: Exception) { null } ?: break
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
            val text = out.toString().trim()
            if (text.isEmpty()) throw DuckException(0, "duck-empty")
            return text
        }
    }

    /** Background warm-up: fe versions + a token in the pocket before the
     *  first message, so the first send is as instant as the rest. */
    fun warm() {
        Thread {
            try { refreshFe() } catch (_: Exception) {}
            try { if (currentToken() == null) mint() } catch (_: Exception) { /* the first send mints */ }
        }.start()
    }

    /**
     * One billed turn. Token reuse: the current token rides until the lane
     * rejects it (or the TTL window passes) — only then the next one is
     * minted. Blocking; call from a worker thread.
     */
    fun chat(messages: JSONArray, model: String, timeoutMs: Int = 45_000): String {
        refreshFe()
        var last: Exception? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) Thread.sleep(1200 + (Math.random() * 1500).toLong())
            val held = currentToken()
            val t: Token = if (held != null && held.age() < TOKEN_TTL_MS) held else {
                clearToken()
                try { mint() } catch (e: Exception) { last = e; null } ?: continue
            }
            try {
                val answer = chatOnce(t, model, messages, timeoutMs)
                saveToken(t) // the token stays alive for the next turn
                return answer
            } catch (e: DuckException) {
                last = e
                clearToken() // this token ran out — the next one gets minted
                if (e.status in 400..599) feFetched = false // re-derive before the retry
                val retryable = e.status == 418 || e.status in 400..499 || e.status == 0 ||
                    e.message?.startsWith("no-challenge") == true || e.message == "duck-empty"
                if (!retryable) throw e
            } catch (e: IOException) {
                last = e
                if (!netUp()) throw NetworkDeadException("no lane — the device is offline")
                pinnedDns.reset() // the network moved — re-pin on the next attempt
            }
        }
        throw BusyException(last?.message ?: "the free lane is catching its breath")
    }

    /**
     * Build the engine message array: prelude + ack + the visible turns.
     * `imageBase64` rides the LAST user turn as an image content part —
     * that's how a picture gets understood: the words plus the pixels,
     * one message, straight from the device.
     */
    fun buildMessages(prelude: String, turns: List<Pair<Boolean, String>>, imageBase64: String? = null): JSONArray {
        val arr = JSONArray()
        fun add(role: String, text: String, img: String? = null) {
            val content = JSONArray().put(JSONObject().apply { put("type", "text"); put("text", text) })
            if (img != null) {
                content.put(JSONObject().apply { put("type", "img"); put("img_str", img) })
            }
            arr.put(JSONObject().apply {
                put("role", role)
                put("content", content)
            })
        }
        if (prelude.isNotBlank()) add("user", prelude)
        if (prelude.isNotBlank()) add("assistant", "Got it — I hold all of this in mind as we talk. What's on your mind?")
        val body = turns.takeLast(20)
        for ((i, pair) in body.withIndex()) {
            val (mine, text) = pair
            val isLast = i == body.lastIndex
            add(if (mine) "user" else "assistant", text, if (mine && isLast) imageBase64 else null)
        }
        return arr
    }

    // ---------- the image lane ----------

    class ImageGenResult(
        val b64: String,
        val w: Int,
        val h: Int,
        val format: String,
        val model: String,
        val text: String,
    )

    /**
     * The native image lane: the SAME chat call with
     * canDelegateImageGeneration turned on — the model delegates, the SSE
     * stream carries `generate-image` ui-component events, the last one
     * holds the finished picture as base64. Partial previews stream through
     * `onPartial` while the real one cooks (progressive jpeg). Retries ride
     * the same token ladder as chat.
     */
    fun chatImageGen(
        prompt: String,
        model: String,
        timeoutMs: Int = 120_000,
        onPartial: ((b64: String, w: Int, h: Int) -> Unit)? = null,
    ): ImageGenResult {
        refreshFe()
        var last: Exception? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) Thread.sleep(1200 + (Math.random() * 1500).toLong())
            val held = currentToken()
            val t: Token = if (held != null && held.age() < TOKEN_TTL_MS) held else {
                clearToken()
                try { mint() } catch (e: Exception) { last = e; null } ?: continue
            }
            try {
                val r = imageOnce(t, prompt, model, timeoutMs, onPartial)
                saveToken(t)
                return r
            } catch (e: DuckException) {
                last = e
                clearToken()
                if (e.status in 400..599) feFetched = false
                val retryable = e.status == 418 || e.status in 400..499 || e.status == 0 ||
                    e.message?.startsWith("no-challenge") == true || e.message == "duck-empty" ||
                    e.message?.startsWith("imggen:") == true
                if (!retryable) throw e
            } catch (e: IOException) {
                last = e
                if (!netUp()) throw NetworkDeadException("no lane — the device is offline")
                pinnedDns.reset()
            }
        }
        throw BusyException(last?.message ?: "the paint lane is catching its breath")
    }

    private fun imageOnce(
        t: Token,
        prompt: String,
        model: String,
        timeoutMs: Int,
        onPartial: ((b64: String, w: Int, h: Int) -> Unit)?,
    ): ImageGenResult {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().put(JSONObject().apply { put("type", "text"); put("text", prompt) }))
            }))
            put("canUseTools", false)
            put("canDelegateImageGeneration", true)
            put("reasoningEffort", "none")
            put("canUseApproxLocation", JSONObject.NULL)
            put("canShowGreeting", false)
        }
        val req = Request.Builder()
            .url("https://duckduckgo.com/duckchat/v1/chat")
            .base()
            .header("accept", "text/event-stream")
            .header("content-type", "application/json")
            .header("x-vqd-hash-1", t.header)
            .header("x-fe-signals", t.signals)
            .header("x-fe-version", feVersion)
            .header("x-duckai-response-id", UUID.randomUUID().toString())
            .header("x-duckai-consent-purposes", "user-location,image-generation,web-search")
            .header("x-duckai-tool-approval", "web-search,image-generation")
            .header("x-ddg-journey-id", t.journey)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        var finalB64 = ""
        var w = 0
        var h = 0
        var format = "jpeg"
        var modelName = ""
        var text = ""
        var genError = ""
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = try { resp.body?.string()?.take(120) ?: "" } catch (_: Exception) { "" }
                throw DuckException(resp.code, "duck-${resp.code}:$err")
            }
            val src = resp.body?.source() ?: throw DuckException(0, "duck-empty")
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val line = try { src.readUtf8Line() } catch (_: Exception) { null } ?: break
                val l = line.trim()
                if (!l.startsWith("data: ")) continue
                val payload = l.substring(6)
                if (payload == "[DONE]") break
                if (payload.startsWith("[")) {
                    if (payload.startsWith("[CHAT_TITLE:")) { /* the tab title — not the art */ }
                    continue
                }
                try {
                    val j = JSONObject(payload)
                    if (j.has("message") && !j.isNull("message")) text += j.getString("message")
                    if (j.optString("action", "") == "error") throw DuckException(0, "duck-sse:${j.optString("type", "err")}")
                    if (j.optString("type", "") == "success" && j.optString("name", "") == "generate-image") {
                        val d = j.optJSONObject("data") ?: continue
                        when {
                            d.optString("status", "") == "success" && d.optString("b64Image", "").length > 512 -> {
                                finalB64 = d.getString("b64Image")
                                w = d.optInt("width", 0); h = d.optInt("height", 0)
                                format = d.optString("format", "jpeg")
                                modelName = d.optString("imageModelDisplayName", "")
                            }
                            d.optString("type", "") == "image-partial" && d.optString("b64Image", "").length > 512 -> {
                                try { onPartial?.invoke(d.getString("b64Image"), d.optInt("width", 0), d.optInt("height", 0)) } catch (_: Exception) {}
                            }
                            d.has("error") || d.optString("status", "") == "error" -> {
                                genError = d.optString("error", "failed")
                                throw DuckException(0, "imggen:$genError")
                            }
                        }
                    }
                } catch (e: DuckException) {
                    throw e
                } catch (_: Exception) {
                    // partial JSON across chunk boundaries — next line completes it
                }
            }
        }
        if (finalB64.isBlank()) {
            throw DuckException(0, if (genError.isNotBlank()) "imggen:$genError" else "imggen:no-image")
        }
        return ImageGenResult(finalB64, w, h, format, modelName, text.trim())
    }
}
