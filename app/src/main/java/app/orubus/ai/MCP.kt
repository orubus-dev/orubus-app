package app.orubus.ai

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * MCP connectors — paste a link, verify, done.
 *
 * A connector is any MCP server speaking Streamable HTTP (JSON-RPC over
 * POST, JSON or SSE answers). The verify step is the whole onboarding:
 *   initialize → notifications/initialized → tools/list → tool cards
 * stdio-only servers (the npx/python kind) can't run on a phone — the verify
 * step says so in one honest line.
 *
 * USING the tools is client-side orchestration (ToolRig): the chat lane is
 * told the tool menu with a strict one-line call contract, the app parses
 * the call, executes it over MCP, feeds the result back, and the answer
 * continues — capped, human-visible, and never a black box.
 */
object Mcp {

    data class Tool(val name: String, val description: String, val schema: JSONObject)
    data class Server(val id: String, val name: String, val url: String, val tools: List<Tool>, var enabled: Boolean = true)

    private const val PROTOCOL = "2025-06-18"
    private const val MAX_TOOLS = 12
    private const val MAX_SERVERS = 6

    private fun http(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // ---------- persistence (prefs JSON — like everything local) ----------

    fun servers(): MutableList<Server> {
        val raw = Store.prefs().getString("mcpServers", "[]") ?: "[]"
        val out = mutableListOf<Server>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val tools = mutableListOf<Tool>()
                val ta = o.optJSONArray("tools") ?: JSONArray()
                for (k in 0 until ta.length()) {
                    val t = ta.getJSONObject(k)
                    tools.add(Tool(t.optString("name"), t.optString("description", ""), t.optJSONObject("schema") ?: JSONObject()))
                }
                out.add(Server(o.optString("id"), o.optString("name"), o.optString("url"), tools, o.optBoolean("enabled", true)))
            }
        } catch (_: Exception) {}
        return out
    }

    fun save(list: List<Server>) {
        try {
            val arr = JSONArray()
            for (s in list.take(MAX_SERVERS)) {
                val ta = JSONArray()
                for (t in s.tools) ta.put(JSONObject().put("name", t.name).put("description", t.description).put("schema", t.schema))
                arr.put(JSONObject().put("id", s.id).put("name", s.name).put("url", s.url).put("enabled", s.enabled).put("tools", ta))
            }
            Store.prefs().edit().putString("mcpServers", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    // ---------- the wire ----------

    private class RpcException(val code: Int, message: String) : Exception(message)

    /**
     * One JSON-RPC round trip over Streamable HTTP. Handles both response
     * shapes the spec allows: a plain JSON body, or an SSE stream whose
     * `data:` lines carry the JSON. Returns the `result` object.
     */
    private fun rpc(url: String, sessionId: String?, body: JSONObject, timeoutS: Long): Pair<JSONObject?, String> {
        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("User-Agent", "OrubusAI/3.7.0")
            .apply { if (!sessionId.isNullOrBlank()) header("mcp-session-id", sessionId) }
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http().newCall(req).execute().use { resp ->
            val newSession = resp.header("mcp-session-id") ?: sessionId ?: ""
            if (!resp.isSuccessful) {
                val t = try { resp.body?.string()?.take(160) ?: "" } catch (_: Exception) { "" }
                throw RpcException(resp.code, "the server said ${resp.code}${if (t.isNotBlank()) " — ${t.take(80)}" else ""}")
            }
            val ctype = resp.header("Content-Type") ?: ""
            val raw = resp.body?.string() ?: ""
            if (ctype.contains("text/event-stream")) {
                // SSE: pick the data: line whose payload carries the result object
                for (line in raw.lineSequence()) {
                    val l = line.trim()
                    if (!l.startsWith("data:")) continue
                    val payload = l.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    try {
                        val j = JSONObject(payload)
                        if (j.optJSONObject("result") != null || j.optJSONObject("error") != null) {
                            return settle(j, newSession)
                        }
                    } catch (_: Exception) { }
                }
                return null to newSession // a notification-only stream — that's a valid no-result reply
            }
            return try { settle(JSONObject(raw), newSession) } catch (_: Exception) { null to newSession }
        }
    }

    private fun settle(j: JSONObject, session: String): Pair<JSONObject?, String> {
        val err = j.optJSONObject("error")
        if (err != null) throw RpcException(err.optInt("code", 0), err.optString("message", "the connector refused"))
        return j.optJSONObject("result") to session
    }

    // ---------- verify: the whole onboarding ----------

    /**
     * Blocking verify. Returns (server, human error). Rejects stdio-only
     * hints up front, https-only, and caps the tool list to keep the chat
     * prompt honest.
     */
    fun verify(rawUrl: String): Pair<Server?, String> {
        val url = rawUrl.trim()
        if (url.isBlank()) return null to "paste the connector's link first"
        if (!url.startsWith("https://")) {
            return null to if (url.startsWith("http://")) "only https links — an unencrypted connector would see too much"
            else "that doesn't look like a link — it should start with https://"
        }
        if (url.contains("stdio://") || url.startsWith("npx ") || url.startsWith("uvx ")) {
            return null to "that's a stdio connector — those only work with a terminal on a computer; this app connects over https links only"
        }
        return try {
            val initBody = JSONObject()
                .put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
                .put("params", JSONObject()
                    .put("protocolVersion", PROTOCOL)
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "Orubus AI").put("version", "3.7.0")))
            val (_, session) = rpc(url, null, initBody, 20)
            try {
                rpc(url, session, JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), 10)
            } catch (_: Exception) { /* some servers 202-empty it — not fatal */ }
            val (tools, _) = rpc(url, session, JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list"), 20)
            val arr = tools?.optJSONArray("tools") ?: JSONArray()
            if (arr.length() == 0) return null to "it connected, but it offers no tools — nothing to add"
            val list = mutableListOf<Tool>()
            for (i in 0 until Math.min(arr.length(), MAX_TOOLS)) {
                val t = arr.getJSONObject(i)
                list.add(Tool(t.optString("name", "tool"), t.optString("description", "").take(220), t.optJSONObject("inputSchema") ?: JSONObject()))
            }
            val name = try { java.net.URI(url).host?.removePrefix("www.")?.take(28) ?: "connector" } catch (_: Exception) { "connector" }
            Server(java.util.UUID.randomUUID().toString().take(8), name, url, list) to ""
        } catch (e: RpcException) {
            null to (e.message ?: "the connector didn't answer")
        } catch (e: Exception) {
            null to if (Api.isNetErr(e)) "no connection right now — try again when you're back online"
            else "it didn't speak MCP over https — this app connects Streamable-HTTP servers only (no stdio commands)"
        }
    }

    /** Blocking tool call → the content as text (the chat lane reads text). */
    fun call(server: Server, tool: String, args: JSONObject): String {
        val body = JSONObject()
            .put("jsonrpc", "2.0").put("id", 7).put("method", "tools/call")
            .put("params", JSONObject().put("name", tool).put("arguments", args))
        val (result, session) = rpc(server.url, sessionFor(server), body, 60)
        if (session != sessionFor(server)) rememberSession(server, session)
        if (result == null) throw Exception("the tool came back empty")
        val err = result.optBoolean("isError", false)
        val content = result.optJSONArray("content") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val c = content.getJSONObject(i)
            if (c.optString("type") == "text") sb.append(c.optString("text", ""))
        }
        val text = sb.toString().trim().take(4000)
        if (text.isEmpty() && err) throw Exception("the tool ran but came back with nothing")
        return text.ifBlank { "(the tool ran — it returned no text)" }
    }

    // sessions are per-server, memory only — a fresh app run re-verifies on call
    private val sessions = mutableMapOf<String, String>()
    private fun sessionFor(s: Server): String = sessions[s.id] ?: ""
    private fun rememberSession(s: Server, session: String) { if (session.isNotBlank()) sessions[s.id] = session }
}

/**
 * The rig that lets the chat lane USE tools — MCP connectors and the
 * encrypted toolbox speak the same one-line contract:
 *
 *   the model replies  ⟦TOOL⟧{"server":"…","tool":"…","args":{…}}
 *   the app executes   and feeds back  ⟦RESULT⟧…
 *   capped at MAX_HOPS, every hop visible to the user as a quiet system line.
 */
object ToolRig {

    private const val MAX_HOPS = 6

    fun available(): Boolean = Mcp.servers().any { it.enabled && it.tools.isNotEmpty() }

    private fun menu(): String {
        val sb = StringBuilder()
        for (s in Mcp.servers()) {
            if (!s.enabled || s.tools.isEmpty()) continue
            for (t in s.tools) {
                sb.append("- server=\"${s.name}\" tool=\"${t.name}\"")
                if (t.description.isNotBlank()) sb.append(": ${t.description.take(140)}")
                sb.append('\n')
            }
        }
        for (t in Toolbox.toolMenu()) sb.append("- server=\"toolbox\" tool=\"${t.first}\"${t.second}\n")
        return sb.toString().trim()
    }

    fun contract(): String {
        return """
            |TOOL USE PROTOCOL
            |You can use tools. To call one, reply with EXACTLY one line and nothing else:
            |⟦TOOL⟧{"server":"<server>","tool":"<tool>","args":{...}}
            |Then you will receive the result as ⟦RESULT⟧… and you continue from there.
            |Available tools:
            |${menu()}
            |When no tool is needed, answer normally and never mention this protocol.
        """.trimMargin()
    }

    private data class Parsed(val server: String, val tool: String, val args: JSONObject)

    private fun parseCall(reply: String): Parsed? {
        val idx = reply.indexOf("⟦TOOL⟧")
        if (idx < 0) return null
        val jsonPart = reply.substring(idx + 6).trim().let {
            val open = it.indexOf('{')
            if (open < 0) return null
            var depth = 0
            var end = -1
            var inStr = false
            var esc = false
            for (i in open until it.length) {
                val ch = it[i]
                if (esc) { esc = false; continue }
                when {
                    ch == '\\' && inStr -> esc = true
                    ch == '"' -> inStr = !inStr
                    !inStr && ch == '{' -> depth++
                    !inStr && ch == '}' -> { depth--; if (depth == 0) { end = i; break } }
                }
            }
            if (end < 0) return null
            it.substring(open, end + 1)
        }
        return try {
            val o = JSONObject(jsonPart)
            Parsed(o.optString("server", ""), o.optString("tool", ""), o.optJSONObject("args") ?: JSONObject())
        } catch (_: Exception) { null }
    }

    private fun execute(p: Parsed, onSite: (String, String) -> Unit): String = try {
        if (p.tool == "deploy_site" && p.server.equals("toolbox", true)) {
            // the deploy is real: the site goes live NOW and the message with
            // the "View generated website" button lands while the loop runs
            val (n, url) = Toolbox.deploySite(p.args.optString("name", ""))
            onSite(n, url)
            "DEPLOYED: $url — the user's site is live; tell them the URL."
        } else if (p.server.equals("toolbox", true)) {
            Toolbox.callTool(p.tool, p.args)
        } else {
            val s = Mcp.servers().firstOrNull { it.name.equals(p.server, true) && it.enabled }
                ?: throw Exception("no connector named \"${p.server}\"")
            Mcp.call(s, p.tool, p.args)
        }
    } catch (e: Exception) {
        "TOOL ERROR: ${(e.message ?: "it failed").take(300)}"
    }

    /**
     * The tool loop — a full device-lane conversation with tools. Blocking;
     * call from the turn thread. Returns the final answer, or null when the
     * loop never produced one (the caller falls back to the plain lane).
     */
    fun toolLoop(
        chat: Store.Chat,
        userText: String,
        prelude: String,
        model: String,
        onStep: (String) -> Unit,
        onSite: (String, String) -> Unit = { _, _ -> },
    ): String? {
        if (!available() && Toolbox.toolMenu().isEmpty()) return null
        val pre = (if (prelude.isBlank()) "" else "$prelude\n\n") + contract()
        val turns = mutableListOf<Pair<Boolean, String>>()
        turns.addAll(Store.turnsOf(chat).dropLast(1)) // the user text rides again as the live turn
        turns.add(true to userText)
        try {
            for (hop in 0 until MAX_HOPS) {
                val msgs = DuckEngine.buildMessages(pre, turns)
                val reply = DuckEngine.chat(msgs, model)
                val call = parseCall(reply)
                if (call == null) return reply.trim().ifBlank { null }
                onStep("🔧 ${call.tool}${if (call.server.isNotBlank()) " · ${call.server}" else ""}…")
                val result = execute(call, onSite)
                turns.add(false to reply)
                turns.add(true to "⟦RESULT⟧$result")
            }
        } catch (e: Exception) {
            if (e is DuckEngine.NetworkDeadException) throw e
            return null // the plain lane takes it from here
        }
        return null // hop cap — plain lane answers without tools
    }
}

// ---------- the connectors sheet (house style, extension fns like Voice.kt) ----------

@SuppressLint("SetTextI18n")
internal fun MainActivity.openConnectorsSheet() {
    val scrim = View(this).apply { setBackgroundColor(Color.argb(90, 20, 20, 20)) }
    scrim.setOnClickListener { (it.tag as? (() -> Unit))?.invoke() }
    screen.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    val h = (resources.displayMetrics.heightPixels * 0.66f).toInt()
    val r18 = W.dpf(this, 18)
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(W.theme.bg)
            cornerRadii = floatArrayOf(r18, r18, r18, r18, 0f, 0f, 0f, 0f)
            setStroke(dp(2), W.theme.ink)
        }
        setPadding(dp(16), dp(14), dp(16), dp(16))
    }

    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val hcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    hcol.addView(W.label(this, "CONNECTORS", 20f, W.theme.ink, heavy = 2))
    hcol.addView(W.label(this, "paste an MCP link — verify — done", 11f, W.theme.dim))
    head.addView(hcol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val close = W.iconBtn(this, W.Icon.CLOSE, W.theme.paper)
    head.addView(close, LinearLayout.LayoutParams(dp(34), dp(34)))
    panel.addView(head)

    val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    scroll.addView(list)
    panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })

    // add row
    val addCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = W.shadowed(this@openConnectorsSheet, W.theme.paper, 14, 3)
        setPadding(dp(12), dp(10), dp(12), dp(12))
    }
    val input = EditText(this).apply {
        hint = "https://your-mcp-server/mcp"; textSize = 13f; maxLines = 1
        setTextColor(W.theme.ink); setHintTextColor(W.theme.dim)
        typeface = W.medium(this@openConnectorsSheet)
        background = W.stroke(this@openConnectorsSheet, W.theme.paper, 10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    addCard.addView(input)
    val status = W.label(this, "", 12f, W.theme.acc2).apply { setPadding(0, dp(6), 0, 0) }
    val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val verifyBtn = W.button(this, "VERIFY & ADD", W.theme.acc, W.theme.ink, radiusDp = 10, small = true)
    btnRow.addView(verifyBtn)
    btnRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
    addCard.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(9) })
    panel.addView(addCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

    fun closeSheet() {
        (scrim.tag as? (() -> Unit))?.invoke()
    }
    val dismiss = {
        panel.animate().translationY(h.toFloat()).setDuration(200).start()
        scrim.animate().alpha(0f).setDuration(200).withEndAction { screen.removeView(scrim); screen.removeView(panel) }.start()
    }
    scrim.tag = dismiss
    close.setOnClickListener { dismiss() }
    scrim.setOnClickListener { dismiss() }

    fun render() {
        list.removeAllViews()
        val servers = Mcp.servers()
        if (servers.isEmpty()) {
            list.addView(W.label(this, "no connectors yet — paste a link above.\nevery verified tool shows up to the AI automatically.", 12.5f, W.theme.dim).apply {
                setPadding(dp(4), dp(10), dp(4), dp(4)); setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
        for (s in servers) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = W.shadowed(this@openConnectorsSheet, W.theme.paper, 12, 2)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(W.label(this, "🔗 ${s.name}", 14.5f, W.theme.ink, heavy = 2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val toggle = W.pill(this, if (s.enabled) "ON" else "OFF", s.enabled)
            toggle.setOnClickListener { s.enabled = !s.enabled; Mcp.save(servers); render() }
            row.addView(toggle)
            card.addView(row)
            card.addView(W.label(this, s.url, 11f, W.theme.dim).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            card.addView(W.label(this, if (s.tools.isEmpty()) "no tools" else s.tools.joinToString(" · ") { it.name }.take(120), 11.5f, W.theme.acc2).apply { setPadding(0, dp(3), 0, 0) })
            card.setOnLongClickListener {
                orubusDialog("Remove \"${s.name}\"?", "the connector and its tools leave this app. the server itself keeps running — paste it again anytime.", "REMOVE", "KEEP", danger = true,
                    onPositive = { Mcp.save(Mcp.servers().filter { it.id != s.id }); render() })
                true
            }
            list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }
    render()

    verifyBtn.setOnClickListener {
        val url = input.text.toString().trim()
        verifyBtn.isEnabled = false
        verifyBtn.text = "VERIFYING…"
        status.text = ""
        Thread {
            val (server, err) = Mcp.verify(url)
            runOnUiThread {
                verifyBtn.isEnabled = true
                verifyBtn.text = "VERIFY & ADD"
                if (server == null) {
                    status.text = err
                } else {
                    val servers = Mcp.servers().toMutableList()
                    if (servers.none { it.url == server.url }) {
                        servers.add(0, server)
                        Mcp.save(servers)
                    }
                    input.setText("")
                    status.text = ""
                    render()
                    pushSys("🔗 ${server.name} verified — ${server.tools.size} tool${if (server.tools.size == 1) "" else "s"} on, the AI can use them now")
                }
            }
        }.start()
    }

    screen.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { gravity = Gravity.BOTTOM })
    panel.translationY = h.toFloat()
    panel.animate().translationY(0f).setDuration(230).setInterpolator(DecelerateInterpolator()).start()
}
