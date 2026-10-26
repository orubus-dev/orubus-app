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

    /**
     * This build's own signing-certificate digest (SHA-256, hex). Every
     * request rides it as the `x-orubus-sig` header — the server answers
     * ad-config / ad-reward only to a build signed with OUR key, so a
     * re-signed mod gets nothing ad-shaped to work with.
     */
    val sigHash: String by lazy {
        runCatching {
            val app = AppCtx.get()
            val sigs = if (android.os.Build.VERSION.SDK_INT >= 28) {
                app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
            }
            val bytes = sigs?.firstOrNull()?.toByteArray() ?: return@runCatching ""
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.digest(bytes).joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    private fun connect(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 120_000 // the duck lane can think for a while
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("Accept", "application/json")
        if (cookieValue.isNotBlank()) c.setRequestProperty("Cookie", "orubus_web=$cookieValue")
        if (sigHash.isNotBlank()) c.setRequestProperty("X-Orubus-Sig", sigHash)
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

    // ---------- image generation (the native paint lane) ----------

    /**
     * appgen — spend the paint price (imageGenCost, 100 SU) and mint a
     * single-use paint token. The device lane does the painting; appdone
     * closes the token, appfail refunds it whole.
     */
    fun appGen(prompt: String): TurnSpend = try {
        val r = post("appgen", mapOf("prompt" to prompt))
        if (r.optBoolean("ok", false)) {
            TurnSpend(true, r.optString("token"), r.optInt("cost", 0), "", r.optJSONObject("wallet"))
        } else {
            TurnSpend(false, "", 0, r.optString("error", "couldn't spend units"), r.optJSONObject("wallet"))
        }
    } catch (e: Exception) {
        TurnSpend(false, "", 0, if (isNetErr(e)) "no connection right now — try again when you're back online" else "couldn't reach the paint desk", null)
    }

    /**
     * appgenimg — the server's own paint lane as the fallback: the SAME
     * token, our egress finishes the picture when the phone's lane slipped.
     * Burns the token; a slipped server lane refunds inside the response.
     */
    fun appGenImg(token: String, prompt: String): JSONObject? = try {
        post("appgenimg", mapOf("token" to token, "prompt" to prompt))
    } catch (_: Exception) { null }

    /**
     * appsite — the user's own website, deployed. op=deploy {name, html} →
     * {ok, url}; op=delete {id}; op=list. The URL lands on the message as
     * the "View generated website" button.
     */
    fun appSite(op: String, extra: Map<String, Any?> = emptyMap()): JSONObject = try {
        post("appsite", mapOf("op" to op) + extra)
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("error", if (isNetErr(e)) "no connection right now — try again when you're back online" else "the deploy desk is unreachable")
    }

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

    /**
     * adtoken → mint a rewarded-ad nonce, with the server's own words when
     * it can't. A dead button that says nothing taught nobody anything: the
     * toast now carries the real reason (ads off / not the signed build /
     * store hiccup / no connection).
     */
    data class AdStart(val nonce: String?, val error: String?)

    fun adStart(): AdStart = try {
        val r = post("adtoken")
        if (r.optBoolean("ok", false)) AdStart(r.optString("nonce"), null)
        else AdStart(null, r.optString("error", "couldn't start the ad — try again in a moment"))
    } catch (e: Exception) {
        AdStart(null, if (isNetErr(e)) "no connection right now — try again when you're back online" else "couldn't reach the ad desk — try again in a moment")
    }

    /** True when the exception is the network's fault, not the server's. */
    fun isNetErr(e: Exception): Boolean {
        var c: Throwable? = e
        repeat(4) {
            val cur = c ?: return false
            if (cur is java.net.UnknownHostException || cur is java.net.ConnectException ||
                cur is java.net.SocketTimeoutException || cur is java.net.SocketException
            ) return true
            if (cur.message?.contains("Unable to resolve host", ignoreCase = true) == true) return true
            c = cur.cause
        }
        return false
    }

    /**
     * appimg — the server's own picture lane: when the device vision lane
     * slips, the turn doesn't die anymore, the server understands the photo
     * (same soul, same memory) and answers. Billed + refunded server-side.
     */
    fun appImg(text: String, imgB64: String, tier: String): JSONObject? = try {
        post("appimg", mapOf("text" to text, "img" to imgB64, "tier" to tier))
    } catch (_: Exception) { null }

    /**
     * adconfig — the ad unit ids, fetched FRESH the moment an ad is about to
     * load. Nothing ad-shaped is baked into this binary: the ids live on the
     * server (env → admin values → test defaults), served only to the signed
     * build. Held in memory for ttlSec, never persisted.
     */
    fun adConfig(): JSONObject? = try {
        val r = post("adconfig")
        if (r.optBoolean("ok", false) && r.optBoolean("enabled", false)) r else null
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
