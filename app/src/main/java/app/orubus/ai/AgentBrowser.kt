package app.orubus.ai

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * The "cloud" browser 2.0 — a visible WebView the chat lane drives, now with
 * real hands and real eyes:
 *
 *   · TABS — up to 3 live WebViews: newtab / switchtab / closetab, and the
 *     human can tap the tab chips themselves at any moment
 *   · LAYOUT SENSING — every element arrives with its on-screen position
 *     (x · y · w × h), so the model reasons about placement, not just text
 *   · EYES — {"act":"see"} captures the active tab to a bitmap and runs one
 *     vision turn (the verified server vision lane) so buttons, colors and
 *     anything the DOM hides become visible to the model (capped per task)
 *   · STABLE LADDER — every step rides the device lane first and the
 *     server's raw 'appstep' lane second, so a slipping lane no longer kills
 *     the run at step 1 with "invalid request"
 *
 * The human watches everything on their own screen — TAKE THE WHEEL hands
 * control over instantly. CAPTCHAs and real-password logins stay human work,
 * never a bypass. Every run is capped: steps, wall clock, tabs, eyes.
 */
object AgentBrowser {

    internal const val MAX_STEPS = 12
    internal const val MAX_MS = 4 * 60_000L
    internal const val MAX_TABS = 3
    internal const val MAX_SEES = 2
    /** how long a finished agent lingers on the page waiting for the next line */
    internal const val IDLE_MS = 120_000L

    class Tab(val id: Int, val web: WebView, var title: String, var url: String)

    class Task(
        val chat: Store.Chat,
        val goal: String,
        val tabs: MutableList<Tab>,
        @Volatile var active: Int = 0,
        @Volatile var paused: Boolean = false,
        @Volatile var stopped: Boolean = false,
        @Volatile var sees: Int = 0,
    ) {
        /** the human's mid-run lines — plain text drives the agent ("open it up") */
        val userMsgs = java.util.concurrent.ConcurrentLinkedQueue<String>()
        fun activeTab(): Tab? = tabs.firstOrNull { it.id == active }
    }

    /** The in-flight browse, if any. */
    @Volatile internal var running: Task? = null

    // ---------- the DOM the model sees (2.0: positioned, wider, deeper) ----------

    /** Shared selector + visibility filter — DOM reads and click/type MUST match. */
    private const val SEL = "a[href],button,input,textarea,select,[role=\"button\"],[role=\"tab\"],[role=\"link\"],[role=\"menuitem\"],[role=\"combobox\"],[onclick],[contenteditable]"

    internal val DOM_JS = """
        (function(){
          var sel='$SEL';
          function t(el){ return (el.innerText||el.value||el.placeholder||el.getAttribute('aria-label')||el.getAttribute('title')||'').replace(/\s+/g,' ').trim().slice(0,60); }
          var items=[];
          var els=document.querySelectorAll(sel);
          var i=0;
          for (var k=0;k<els.length && items.length<40;k++){
            var el=els[k];
            var r=el.getBoundingClientRect();
            if(r.width<5||r.height<5) continue;
            var st=getComputedStyle(el);
            if(st.visibility==='hidden'||st.display==='none') continue;
            var o={i:i,tag:el.tagName.toLowerCase(),txt:t(el),
                   x:Math.round(r.left),y:Math.round(r.top),w:Math.round(r.width),h:Math.round(r.height)};
            if(el.tagName==='INPUT'){ o.type=el.type||'text'; if(el.type==='password') o.txt='[password field]'; }
            if(el.tagName==='TEXTAREA') o.type='textarea';
            if(el.getAttribute('contenteditable')==='true'||el.isContentEditable) o.type='editable';
            items.push(o); i++;
          }
          var text=(document.body?document.body.innerText:'').replace(/\s+/g,' ').trim().slice(0,1600);
          return JSON.stringify({title:document.title,url:location.href,text:text,items:items});
        })()
    """

    /** act on the element numbered [i] — SAME visibility filter + order as DOM_JS */
    internal fun clickJs(i: Int): String = """
        (function(){
          var sel='$SEL';
          var els=document.querySelectorAll(sel);
          var n=0;
          for(var k=0;k<els.length;k++){
            var el=els[k];
            var r=el.getBoundingClientRect();
            if(r.width<5||r.height<5) continue;
            var st=getComputedStyle(el);
            if(st.visibility==='hidden'||st.display==='none') continue;
            if(n===$i){
              el.scrollIntoView({block:'center'});
              var r2=el.getBoundingClientRect();
              el.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}));
              el.dispatchEvent(new PointerEvent('pointerup',{bubbles:true}));
              el.click();
              return 'clicked';
            }
            n++;
          }
          return 'no-such';
        })()
    """.trimIndent()

    internal fun typeJs(i: Int, text: String, submit: Boolean): String {
        val esc = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("$", "\\$")
        return """
            (function(){
              var sel='$SEL';
              var els=document.querySelectorAll(sel);
              var n=0;
              for(var k=0;k<els.length;k++){
                var el=els[k];
                var r=el.getBoundingClientRect();
                if(r.width<5||r.height<5) continue;
                var st=getComputedStyle(el);
                if(st.visibility==='hidden'||st.display==='none') continue;
                if(n===$i){
                  el.scrollIntoView({block:'center'});
                  el.focus();
                  if('value' in el){ el.value='$esc'; el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); }
                  else if(el.isContentEditable){ el.textContent='$esc'; el.dispatchEvent(new Event('input',{bubbles:true})); }
                  if($submit){ var f=el.form||el.closest('form'); if(f){ if(f.requestSubmit){f.requestSubmit()} else {f.submit()} return 'submitted'; } }
                  return 'typed';
                }
                n++;
              }
              return 'no-such';
            })()
        """.trimIndent()
    }

    internal fun summarize(raw: String): String = try {
        val o = JSONObject(raw.trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n"))
        val sb = StringBuilder()
        sb.append("PAGE: ").append(o.optString("title", "")).append('\n')
        sb.append("URL: ").append(o.optString("url", "")).append('\n')
        sb.append("TEXT: ").append(o.optString("text", "").take(1200)).append('\n')
        sb.append("ELEMENTS (tag [i] · x,y w×h — label):\n")
        val arr = o.optJSONArray("items")
        if (arr != null) for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            sb.append("[").append(e.optInt("i")).append("] <").append(e.optString("tag"))
            if (e.has("type")) sb.append(" type=").append(e.getString("type"))
            sb.append(" · ").append(e.optInt("x")).append(",").append(e.optInt("y"))
                .append(" ").append(e.optInt("w")).append("×").append(e.optInt("h"))
            sb.append("> ").append(e.optString("txt", "")).append('\n')
        }
        sb.toString()
    } catch (_: Exception) {
        "PAGE: (unreadable)\n"
    }

    // ---------- the model's one-line action contract ----------

    internal fun contract(goal: String, startUrl: String): String = """
        |BROWSER TASK PROTOCOL
        |You are driving a real web browser on the user's phone, one step at a time.
        |GOAL: $goal
        |START (or stay): $startUrl
        |After every step you receive a PAGE summary with ELEMENTS numbered [0]..[n], each with its on-screen position (x,y w×h).
        |You may have several tabs open — they are listed as TABS: [id] title — url (active).
        |The user can chat with you WHILE you drive: lines starting with USER SAYS are their live words — treat them as fresh instructions and answer them too.
        |Reply with EXACTLY one line, one of:
        |⟦ACT⟧{"act":"goto","url":"https://…"}
        |⟦ACT⟧{"act":"click","i":3}
        |⟦ACT⟧{"act":"type","i":7,"text":"hello","submit":true}
        |⟦ACT⟧{"act":"scroll","dy":600}
        |⟦ACT⟧{"act":"back"}
        |⟦ACT⟧{"act":"wait"}
        |⟦ACT⟧{"act":"see"}          — look at a screenshot of the active tab (layout, colors, buttons); use it when the text list is not enough
        |⟦ACT⟧{"act":"newtab","url":"https://…"}    — open a background research tab (max 3 tabs total)
        |⟦ACT⟧{"act":"switchtab","i":1}
        |⟦ACT⟧{"act":"closetab","i":1}
        |⟦ACT⟧{"act":"say","text":"…"}    — answer the user in plain words without touching the page (questions, status, explanations)
        |⟦ACT⟧{"act":"done","summary":"what happened"}
        |Rules: never invent element numbers you can't see; typing into [password] fields is allowed when the user asked for sign-ins; if a CAPTCHA or a human check appears, reply wait once then done — the user takes over manually, NEVER bypass protections; when the goal is reached, reply done. When the user asks something that needs no page action, reply say.
    """.trimIndent()

    fun parseAct(reply: String): JSONObject? {
        val idx = reply.indexOf("⟦ACT⟧")
        if (idx < 0) return null
        return try {
            val s = reply.substring(idx + 5).trim()
            val open = s.indexOf('{')
            if (open < 0) return null
            var depth = 0; var end = -1; var inStr = false; var esc = false
            for (i in open until s.length) {
                val ch = s[i]
                if (esc) { esc = false; continue }
                when {
                    ch == '\\' && inStr -> esc = true
                    ch == '"' -> inStr = !inStr
                    !inStr && ch == '{' -> depth++
                    !inStr && ch == '}' -> { depth--; if (depth == 0) { end = i; break } }
                }
            }
            if (end < 0) null else JSONObject(s.substring(open, end + 1))
        } catch (_: Exception) { null }
    }

    /** Raw lane words never reach a bubble — every failure gets a human line. */
    internal fun friendly(e: Exception): String = when {
        e is DuckEngine.NetworkDeadException -> "the lane dropped — the browser waits on your screen"
        else -> {
            val raw = e.message ?: ""
            when {
                raw.contains("appstep", true) || raw.contains("units", true) || raw.contains("wallet", true) -> raw.take(160)
                raw.contains("no-challenge") || raw.contains("duck-4") || raw.contains("duck-5") || raw.contains("duck-sse") || raw.contains("busy", true) ->
                    "the lane is catching its breath — the browse paused where you can see it; try again in a moment"
                else -> "the browse slipped — the page is on your screen exactly where it stopped"
            }
        }
    }
}

/**
 * The live overlay — tabs on top, the active WebView in the middle, captions
 * narrating every step, TAKE THE WHEEL for instant human control.
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun MainActivity.startAgentBrowse(goalRaw: String, startUrlRaw: String) {
    val goal = goalRaw.trim()
    if (goal.isBlank()) return
    if (AgentBrowser.running?.stopped == false) {
        pushSys("a browse is already running — END it first")
        return
    }
    if (sending || ImageGen.painting) {
        pushSys("let the current lane land first — one road at a time")
        return
    }
    var startUrl = startUrlRaw.trim()
    if (startUrl.isBlank()) startUrl = "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(goal.take(120), "UTF-8")
    if (!startUrl.startsWith("http")) startUrl = "https://" + startUrl

    val chat = Store.currentChat()
    pushUser("🌐 $goal")
    Store.append(chat, true, "🌐 $goal")

    val main = Handler(Looper.getMainLooper())
    val tabs = mutableListOf<AgentBrowser.Tab>()
    val task = AgentBrowser.Task(chat, goal, tabs)
    AgentBrowser.running = task
    val ready = booleanArrayOf(false)

    // ---- the overlay ----
    val overlay = FrameLayout(this).apply { setBackgroundColor(W.theme.bg) }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(8)) }
    head.addView(W.label(this, "🌐 ORUBUS BROWSER", 14f, W.theme.ink, heavy = 2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val pauseBtn = W.button(this, "TAKE THE WHEEL", W.theme.pop, W.theme.ink, radiusDp = 99, small = true)
    head.addView(pauseBtn)
    val endBtn = W.iconBtn(this, W.Icon.CLOSE, W.theme.paper)
    head.addView(endBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(8) })
    col.addView(head)
    val caption = TextView(this).apply {
        text = "warming the page…"
        textSize = 12.5f; setTextColor(W.theme.acc2); typeface = W.medium(this@startAgentBrowse)
        setPadding(dp(14), dp(2), dp(14), dp(6)); maxLines = 2
    }
    col.addView(caption)
    col.addView(View(this).apply { setBackgroundColor(W.theme.ink) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))

    // the tab strip — the human can drive tabs too
    val strip = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    val stripRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
    strip.addView(stripRow)
    col.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    // the page stack — one WebView per tab, only the active one visible
    val stack = FrameLayout(this)
    col.addView(stack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    // ---- the chat bar: plain text drives the agent mid-run ----
    val chatRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(5), dp(8), dp(3)) }
    val chatInput = EditText(this).apply {
        hint = "chat with it — \"what's this site?\", \"open it up\"…"
        textSize = 13.5f; maxLines = 2
        setTextColor(W.theme.ink); setHintTextColor(W.theme.dim)
        typeface = W.regular(this@startAgentBrowse)
        background = W.stroke(this@startAgentBrowse, W.theme.paper, 12)
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    chatRow.addView(chatInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val sendBtn = W.button(this, "SEND", W.theme.acc, W.theme.ink, radiusDp = 99, small = true)
    chatRow.addView(sendBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
    col.addView(chatRow)

    val foot = W.label(this, "everything happens on THIS screen — pause and it's your browser", 11f, W.theme.dim).apply {
        gravity = Gravity.CENTER; setPadding(dp(10), dp(5), dp(10), dp(8))
    }
    col.addView(foot)
    overlay.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    screen.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    var closed = false
    val closeOverlay = {
        if (!closed) {
            closed = true
            task.stopped = true
            AgentBrowser.running = null
            main.post {
                for (t in tabs.toList()) { try { t.web.stopLoading() } catch (_: Exception) {}; try { t.web.destroy() } catch (_: Exception) {} }
                tabs.clear()
            }
            overlay.animate().alpha(0f).setDuration(180).withEndAction { screen.removeView(overlay) }.start()
            loadCurrentChat()
            refreshAll()
        }
        Unit
    }
    val setCaption: (String) -> Unit = { s -> main.post { if (!closed) caption.text = s } }

    // the human's line rides in: echo on the caption, into the queue, into the thread
    fun sendUserLine() {
        val line = chatInput.text.toString().trim()
        if (line.isBlank()) return
        chatInput.setText("")
        task.userMsgs.add(line)
        setCaption("you: ${line.take(80)}")
        Thread {
            Store.append(chat, true, "🌐 $line")
            runOnUiThread { if (!closed && chat.id == Store.currentChatId()) pushUser("🌐 $line") }
        }.start()
    }
    sendBtn.setOnClickListener { sendUserLine() }
    chatInput.setOnEditorActionListener(android.widget.TextView.OnEditorActionListener { _, _, _ ->
        sendUserLine()
        true
    })

    fun renderStrip() {
        main.post {
            if (closed) return@post
            stripRow.removeAllViews()
            for (t in tabs) {
                val active = t.id == task.active
                val chip = W.button(this, "${t.id + 1} · ${t.title.take(10).ifBlank { "tab" }}",
                    if (active) W.theme.acc else W.theme.paper, if (active) W.theme.ink else W.theme.dim, radiusDp = 99, small = true)
                chip.setPadding(dp(10), dp(4), dp(10), dp(4))
                chip.setOnClickListener {
                    task.active = t.id
                    for (v in tabs) v.web.visibility = if (v.id == task.active) View.VISIBLE else View.GONE
                    renderStrip()
                }
                stripRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            }
        }
    }

    fun makeTab(url: String, done: (AgentBrowser.Tab) -> Unit) {
        main.post {
            if (closed) return@post
            val web = WebView(this)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.userAgentString = DuckEngine.UA
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    ready[0] = true
                    tabs.firstOrNull { it.web === view }?.let { t ->
                        t.title = view.title?.take(24) ?: ""
                        t.url = url
                        renderStrip()
                    }
                }
            }
            web.webChromeClient = WebChromeClient()
            CookieManager.getInstance().setAcceptCookie(true)
            web.visibility = View.GONE
            val tab = AgentBrowser.Tab(tabs.size, web, "", url)
            tabs.add(tab)
            task.active = tab.id
            stack.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            web.visibility = View.VISIBLE
            for (v in tabs) v.web.visibility = if (v.id == task.active) View.VISIBLE else View.GONE
            renderStrip()
            web.loadUrl(url)
            done(tab)
        }
    }

    pauseBtn.setOnClickListener {
        task.paused = !task.paused
        pauseBtn.text = if (task.paused) "HAND BACK" else "TAKE THE WHEEL"
        setCaption(if (task.paused) "your wheel — the agent waits; HAND BACK when done" else "agent driving again…")
    }
    endBtn.setOnClickListener { closeOverlay() }

    fun readPage(done: (String) -> Unit) {
        ready[0] = false
        val deadline = System.currentTimeMillis() + 15_000
        fun poll() {
            if (task.stopped) return
            main.postDelayed({
                if (task.stopped || closed) return@postDelayed
                val web = task.activeTab()?.web ?: return@postDelayed
                if (ready[0] || System.currentTimeMillis() > deadline) {
                    web.evaluateJavascript(AgentBrowser.DOM_JS) { raw -> done(raw ?: "") }
                } else poll()
            }, 400)
        }
        poll()
    }

    fun tabsLine(): String = if (tabs.size <= 1) "" else
        "TABS: " + tabs.joinToString(" | ") { "[${it.id}] ${it.title.take(20)} — ${it.url.take(48)}${if (it.id == task.active) " (active)" else ""}" } + "\n"

    // ---- the eyes: one screenshot → the verified server vision lane ----
    fun see(done: (String) -> Unit) {
        if (task.sees >= AgentBrowser.MAX_SEES) { done("NOTE: the eyes are rested for this task — work from the text list.\n"); return }
        task.sees += 1
        main.post {
            if (closed) return@post
            val web = task.activeTab()?.web
            if (web == null || web.width == 0) { done("NOTE: the screen couldn't be captured — work from the text list.\n"); return@post }
            try {
                val full = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
                web.draw(Canvas(full))
                val scale = Math.min(1f, 512f / full.width)
                val small = Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true)
                if (small !== full) full.recycle()
                val b64 = java.io.ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }.let {
                    android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.NO_WRAP)
                }
                small.recycle()
                Thread {
                    try {
                        val fb = Api.appImg(
                            "You are the eyes of a browser agent. This is a screenshot of the page it is driving. Describe what matters for its goal: visible buttons/links with their rough positions, form fields, colors, popups, error banners — tight, factual, no fluff.",
                            b64, tier,
                        )
                        runOnUiThread { fb?.optJSONObject("wallet")?.let { renderWallet(it) } }
                        val desc = if (fb?.optBoolean("ok", false) == true) fb.optString("answer", "").take(900)
                        else "NOTE: the eyes slipped this once — work from the text list."
                        done(desc + "\n")
                    } catch (e: Exception) {
                        done("NOTE: the eyes slipped this once — work from the text list.\n")
                    }
                }.start()
            } catch (_: Exception) {
                done("NOTE: the screen couldn't be captured — work from the text list.\n")
            }
        }
    }

    // ---- one step through the ladder: device lane → server raw lane ----
    fun stepReply(prompt: String): String {
        val msgs = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().put(JSONObject().put("type", "text").put("text", prompt)))
        })
        return try {
            DuckEngine.chat(msgs, DuckEngine.modelFor(tier))
        } catch (de: DuckEngine.NetworkDeadException) {
            throw de
        } catch (e: Exception) {
            // the phone's lane slipped — the server's raw agent lane finishes
            // the step (billed like a normal turn, refunded if it fails too)
            val fb = Api.post("appstep", mapOf("messages" to msgs, "tier" to tier))
            runOnUiThread { fb.optJSONObject("wallet")?.let { renderWallet(it) } }
            if (!fb.optBoolean("ok", false)) throw Exception(fb.optString("error", "the agent lane is catching its breath"))
            fb.optString("answer", "").ifBlank { throw Exception("the agent lane came back empty") }
        }
    }

    // ---- the conversation loop: act → answer → listen → act again ----
    Thread {
        val pre = (if (prelude.isNotBlank()) "$prelude\n\n" else "") +
            "You are driving the user's browser to reach the goal. Minimal steps, no detours.\n\n" +
            AgentBrowser.contract(goal, startUrl)
        var summary = "PAGE: (fresh)\n"
        val t0 = System.currentTimeMillis()
        try {
            val first = java.util.concurrent.CountDownLatch(1)
            makeTab(startUrl) { first.countDown() }
            first.await(20, java.util.concurrent.TimeUnit.SECONDS)
            readPage { summary = AgentBrowser.summarize(it) + tabsLine() }
            // a JS-walled page (zero elements, real text) gets ONE free look
            if (summary.contains("ELEMENTS") && !summary.contains("> ") && task.sees == 0) {
                val latch = java.util.concurrent.CountDownLatch(1)
                see { desc -> summary += desc; latch.countDown() }
                latch.await(35, java.util.concurrent.TimeUnit.SECONDS)
            }

            fun runSteps(): String {
                var finalLine = ""
                for (step in 0 until AgentBrowser.MAX_STEPS) {
                    if (task.stopped) return finalLine.ifBlank { "the browse ended" }
                    if (task.paused) { Thread.sleep(700); continue }
                    // the human's mid-run words ride in BEFORE the model decides
                    var userLines = ""
                    while (true) { val m = task.userMsgs.poll() ?: break; userLines += "USER SAYS: $m\n" }
                    setCaption("step ${step + 1}/${AgentBrowser.MAX_STEPS} · deciding…")
                    val reply = stepReply("$pre\n${userLines}${summary.take(6000)}")
                    val act = AgentBrowser.parseAct(reply)
                    if (act == null) { setCaption("the agent spoke plain — ending here"); break }
                    when (act.optString("act")) {
                        "goto" -> {
                            var u = act.optString("url", "")
                            if (u.isNotBlank() && !u.startsWith("http")) u = "https://" + u
                            setCaption("opening ${u.take(60)}")
                            main.post { task.activeTab()?.web?.loadUrl(u) }
                            readPage { summary = AgentBrowser.summarize(it) + tabsLine() }
                        }
                        "click" -> {
                            val i = act.optInt("i", -1)
                            setCaption("tapping [$i]…")
                            main.post { task.activeTab()?.web?.evaluateJavascript(AgentBrowser.clickJs(i), null) }
                            Thread.sleep(2300)
                            readPage { summary = AgentBrowser.summarize(it) + tabsLine() }
                        }
                        "type" -> {
                            val i = act.optInt("i", -1)
                            setCaption("typing into [$i]…")
                            main.post { task.activeTab()?.web?.evaluateJavascript(AgentBrowser.typeJs(i, act.optString("text", ""), act.optBoolean("submit", false)), null) }
                            Thread.sleep(2500)
                            readPage { summary = AgentBrowser.summarize(it) + tabsLine() }
                        }
                        "scroll" -> {
                            val dy = act.optInt("dy", 600).coerceIn(-3000, 3000)
                            main.post { task.activeTab()?.web?.evaluateJavascript("window.scrollBy(0,$dy)", null) }
                            Thread.sleep(900)
                            readPage { summary = AgentBrowser.summarize(it) + tabsLine() }
                        }
                        "back" -> {
                            main.post { task.activeTab()?.web?.let { w -> if (w.canGoBack()) w.goBack() } }
                            Thread.sleep(1500)
                            readPage { summary = AgentBrowser.summarize(it) + tabsLine() }
                        }
                        "wait" -> {
                            setCaption("a human check is on the page — TAKE THE WHEEL, solve it, HAND BACK")
                            Thread.sleep(4000)
                            readPage { summary = AgentBrowser.summarize(it) + tabsLine() + "NOTE: the human may have solved a check — describe what you see.\n" }
                        }
                        "see" -> {
                            setCaption("looking at the screen…")
                            val latch = java.util.concurrent.CountDownLatch(1)
                            see { desc -> summary += desc; latch.countDown() }
                            latch.await(35, java.util.concurrent.TimeUnit.SECONDS)
                        }
                        "newtab" -> {
                            var u = act.optString("url", "")
                            if (u.isNotBlank() && !u.startsWith("http")) u = "https://" + u
                            if (tabs.size >= AgentBrowser.MAX_TABS) {
                                setCaption("the tab shelf is full (${AgentBrowser.MAX_TABS}) — switching instead")
                            } else {
                                setCaption("opening tab ${tabs.size + 1}…")
                                val latch = java.util.concurrent.CountDownLatch(1)
                                makeTab(u) { latch.countDown() }
                                latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                                if (tabs.isNotEmpty() && task.active == tabs.last().id) setCaption("tab ${task.active + 1} open — now on it")
                            }
                            Thread.sleep(1800)
                            readPage { summary = AgentBrowser.summarize(it) + tabsLine() }
                        }
                        "switchtab" -> {
                            val i = act.optInt("i", -1)
                            val t = tabs.firstOrNull { it.id == i }
                            if (t != null) {
                                task.active = i
                                main.post {
                                    for (v in tabs) v.web.visibility = if (v.id == task.active) View.VISIBLE else View.GONE
                                    renderStrip()
                                }
                                setCaption("switched to tab ${i + 1}")
                                Thread.sleep(900)
                            } else setCaption("no tab $i — staying put")
                            readPage { summary = AgentBrowser.summarize(it) + tabsLine() }
                        }
                        "closetab" -> {
                            val i = act.optInt("i", -1)
                            val t = tabs.firstOrNull { it.id == i }
                            if (t != null && tabs.size > 1) {
                                main.post {
                                    try { t.web.stopLoading() } catch (_: Exception) {}
                                    try { t.web.destroy() } catch (_: Exception) {}
                                    stack.removeView(t.web)
                                }
                                tabs.remove(t)
                                if (task.active == i) task.active = tabs.first().id
                                main.post {
                                    for (v in tabs) v.web.visibility = if (v.id == task.active) View.VISIBLE else View.GONE
                                    renderStrip()
                                }
                                setCaption("tab closed — ${tabs.size} left")
                                Thread.sleep(700)
                            }
                            readPage { summary = AgentBrowser.summarize(it) + tabsLine() }
                        }
                        "say" -> {
                            // a plain answer to a plain question — no page action
                            finalLine = act.optString("text", "").ifBlank { "…done talking" }
                            setCaption(finalLine)
                        }
                        "done" -> {
                            finalLine = act.optString("summary", "done — the page is where you left it")
                            setCaption(finalLine)
                        }
                        else -> break
                    }
                    if (finalLine.isNotBlank()) break
                    if (System.currentTimeMillis() - t0 > AgentBrowser.MAX_MS) { finalLine = "time's up — the page is on your screen"; break }
                }
                if (finalLine.isBlank()) finalLine = "paused at the step cap — the page is on screen; take the wheel or chat with me"
                return finalLine
            }

            // CONVERSATION: every run answers, lands in the thread, then the
            // agent stays on the page waiting for the next plain-text line
            // ("what's GitHub website" → "open it up" → "sign me up…").
            while (!task.stopped) {
                val line = runSteps()
                runOnUiThread {
                    if (chat.id == Store.currentChatId()) pushBot(line)
                }
                Store.append(chat, false, line)
                // linger for the human's next line
                var idle = 0L
                while (task.userMsgs.isEmpty() && !task.stopped && idle < AgentBrowser.IDLE_MS) {
                    Thread.sleep(500); idle += 500
                }
                if (task.stopped) break
                val next = task.userMsgs.poll() ?: break // idle timeout — rest, page stays open
                setCaption("on it — ${next.take(70)}")
                readPage { summary = AgentBrowser.summarize(it) + tabsLine() }
                summary = "THE USER JUST SAID: $next\n" + summary
            }
        } catch (e: Exception) {
            val answer = AgentBrowser.friendly(e)
            runOnUiThread {
                if (chat.id == Store.currentChatId()) pushBot(answer)
            }
            Store.append(chat, false, answer)
        }
        runOnUiThread { closeOverlay() }
    }.start()
}
