package app.orubus.ai

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one HTTP door. Everything talks to the deployed Vercel backend —
 * same /api/chat the web app uses: same wallet, same soul, same identity.
 * The session is the orubus_web cookie minted by the GENJ link step.
 */
object Api {
    const val BASE = "https://orubus-ai.vercel.app"
    const val CHAT = "$BASE/api/chat"
    const val APP_REPO = "orubus-dev/orubus-app"
    const val APK_URL = "https://github.com/$APP_REPO/releases/latest/download/Orubus.apk"

    /** "v2.<uid>.<sid>.<exp>.<iat>.<hmac>" — set after a successful link. */
    @Volatile var cookieValue: String = ""

    private fun connect(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 120_000 // the duck lane can think for a while
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("Accept", "application/json")
        if (cookieValue.isNotBlank()) c.setRequestProperty("Cookie", "orubus_web=$cookieValue")
        return c
    }

    /** POST /api/chat {action, ...} → response JSON. Throws IOException with a human line. */
    @Throws(IOException::class)
    fun post(action: String, extra: Map<String, Any?> = emptyMap()): JSONObject {
        val body = JSONObject(extra).put("action", action).toString()
        val c = connect(CHAT, "POST")
        c.doOutput = true
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val text = readBody(c)
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    /** Capture the Set-Cookie the link step mints. Returns the bare cookie value. */
    @Throws(IOException::class)
    fun link(code: String): Pair<Boolean, String> {
        val c = connect(CHAT, "POST")
        c.doOutput = true
        c.instanceFollowRedirects = false
        try {
            c.outputStream.use {
                it.write(JSONObject(mapOf("action" to "link", "code" to code)).toString().toByteArray(Charsets.UTF_8))
            }
            val json = JSONObject(readBody(c))
            val ok = json.optBoolean("ok", false)
            if (ok) {
                // preferred: the cookie value in the JSON body (explicit, works
                // regardless of redirect/header handling); Set-Cookie as backup
                val bodyCookie = json.optString("cookie", "")
                if (bodyCookie.isNotBlank()) {
                    cookieValue = bodyCookie
                } else {
                    val raw = c.headerFields?.entries
                        ?.firstOrNull { it.key.equals("Set-Cookie", true) }
                        ?.value?.firstOrNull()
                    if (raw != null) {
                        val m = Regex("orubus_web=([^;]+)").find(raw)
                        if (m != null) cookieValue = m.groupValues[1]
                    }
                }
            }
            return ok to (json.optString("error", "that code didn't take — check it and try again"))
        } finally {
            c.disconnect()
        }
    }

    /** Plain GET → JSON (GitHub release checks). No auth, fail-soft by caller. */
    @Throws(IOException::class)
    fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "orubus-app")
        try {
            return JSONObject(readBody(c))
        } finally {
            c.disconnect()
        }
    }

    // ---------- the feature wall (real votes, real comments) ----------

    /** GET the wall — { ok, items:[…] } with the viewer's vote flags baked in. */
    fun wallGet(): JSONObject {
        val c = connect("$BASE/api/requests", "GET")
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        return try { JSONObject(readBody(c)) } finally { c.disconnect() }
    }

    /** POST a wall action: create / vote / down / comment. */
    fun wallAction(action: String, extra: Map<String, Any?> = emptyMap()): JSONObject {
        val body = JSONObject(extra).put("action", action).toString()
        val c = connect("$BASE/api/requests", "POST")
        c.doOutput = true
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return JSONObject(readBody(c))
        } finally {
            c.disconnect()
        }
    }

    // ---------- device-side duck billing (the SU wallet stays on Vercel) ----------

    /**
     * appturn: spend SU for this turn, mint a single-use turn token.
     * `img` = attached images (each debits the image-understanding price on
     * the server, inside the SAME token — appfail refunds text + image as
     * one atom). Returns (ok, token, cost, error, walletJSON).
     */
    @Throws(IOException::class)
    fun appTurn(text: String, tier: String, img: Int = 0): TurnSpend {
        val r = post("appturn", mapOf("text" to text, "tier" to tier, "img" to img))
        return if (r.optBoolean("ok", false)) {
            TurnSpend(true, r.optString("token"), r.optInt("cost", 0), "", r.optJSONObject("wallet"))
        } else {
            TurnSpend(false, "", 0, r.optString("error", "couldn't spend units"), r.optJSONObject("wallet"))
        }
    }

    /**
     * appresearch — Deep Research v4.8 + Intent Sensing v1.5, server-sided.
     * Called invisibly before every linked turn: the server reads the intent
     * and only when the answer needs what's happening RIGHT NOW does the
     * deep pass run (charged only when it actually produces). Returns the
     * research answer + the thinking interpretation the user can tap open.
     */
    fun appResearch(text: String, tier: String): ResearchReply {
        val r = try {
            post("appresearch", mapOf("text" to text, "tier" to tier))
        } catch (e: Exception) {
            return ResearchReply(false, false, "", "") // sensor is best-effort — the lane continues
        }
        return ResearchReply(
            ok = r.optBoolean("ok", false),
            research = r.optBoolean("research", false),
            thinking = r.optString("thinking", ""),
            answer = r.optString("answer", ""),
            wallet = r.optJSONObject("wallet"),
        )
    }

    /** voicecall — meter the live call: `secs` of talk time, voicePerMin per minute. */
    fun voiceTick(secs: Int): JSONObject? = try {
        post("voicecall", mapOf("secs" to secs))
    } catch (_: Exception) { null }

    /** appdone: archive the answer + close the token. Fail-soft (history only). */
    fun appDone(token: String, answer: String): JSONObject? = try {
        post("appdone", mapOf("token" to token, "answer" to answer))
    } catch (_: Exception) { null }

    /** appfail: refund the turn (token burns — no refund-after-done). */
    fun appFail(token: String): JSONObject? = try {
        post("appfail", mapOf("token" to token))
    } catch (_: Exception) { null }

    /**
     * The session-gated server assist for the challenge math: when the
     * on-device sandbox can't solve, the server's proven solver runs it —
     * and every duck.ai HTTP call STILL leaves from this device's own IP.
     * Session cookie required, so the endpoint is useless to outsiders.
     */
    fun serverSolve(challenge: String): JSONObject? = try {
        post("solve", mapOf("challenge" to challenge))
    } catch (_: Exception) { null }

    /** adtoken → mint a rewarded-ad nonce. */
    fun adToken(): String? = try {
        val r = post("adtoken")
        if (r.optBoolean("ok", false)) r.optString("nonce") else null
    } catch (_: Exception) { null }

    /** ad: claim the reward (provider=admob — Google's SDK callback on-device). */
    fun adClaim(nonce: String): JSONObject? = try {
        post("ad", mapOf("nonce" to nonce, "provider" to "admob"))
    } catch (_: Exception) { null }

    data class TurnSpend(val ok: Boolean, val token: String, val cost: Int, val error: String, val wallet: JSONObject?)
    data class ResearchReply(
        val ok: Boolean,
        val research: Boolean,
        val thinking: String,
        val answer: String,
        val wallet: JSONObject? = null,
    )

    private fun readBody(c: HttpURLConnection): String {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (c.responseCode !in 200..299 && text.isBlank()) {
            throw IOException("server said ${c.responseCode}")
        }
        return text
    }
}
