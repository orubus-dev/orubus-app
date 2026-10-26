package app.orubus.ai

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * The "cloud" browser — a cool name for an honest trick: the browser runs
 * RIGHT HERE on the phone, and that's exactly why the user sees everything
 * the AI does in real time. A visible WebView that the chat lane drives:
 *
 *   read the page (DOM summary) → the model answers one JSON action
 *   goto / click / type / scroll / done → the app executes it → repeat
 *
 * The human watches every step happen on their own screen and can take the
 * wheel instantly — pause the agent and the page is theirs (CAPTCHAs,
 * real-password logins, anything sensitive: human hands finish it, never a
 * bypass). Every run is capped: MAX_STEPS hops, MAX_MS wall clock, and
 * nothing happens off-screen.
 */
object AgentBrowser {

    internal const val MAX_STEPS = 10
    internal const val MAX_MS = 4 * 60_000L

    class Task(
        val chat: Store.Chat,
        val goal: String,
        @Volatile var paused: Boolean = false,
        @Volatile var stopped: Boolean = false,
    )

    /** The in-flight browse, if any. */
    @Volatile internal var running: Task? = null

    // ---------- the DOM the model sees ----------

    internal const val DOM_JS = """
        (function(){
          function t(el){ return (el.innerText||el.value||el.placeholder||el.getAttribute('aria-label')||el.getAttribute('title')||'').replace(/\s+/g,' ').trim().slice(0,60); }
          var items=[];
          var sel='a[href],button,input,textarea,select,[role="button"],[onclick]';
          var els=document.querySelectorAll(sel);
          var i=0;
          for (var k=0;k<els.length && items.length<26;k++){
            var el=els[k];
            var r=el.getBoundingClientRect();
            if(r.width<5||r.height<5) continue;
            var st=getComputedStyle(el);
            if(st.visibility==='hidden'||st.display==='none') continue;
            var o={i:i,tag:el.tagName.toLowerCase(),txt:t(el)};
            if(el.tagName==='INPUT'){ o.type=el.type||'text'; if(el.type==='password') o.txt='[password field]'; }
            if(el.tagName==='TEXTAREA') o.type='textarea';
            items.push(o); i++;
          }
          var text=(document.body?document.body.innerText:'').replace(/\s+/g,' ').trim().slice(0,1000);
          return JSON.stringify({title:document.title,url:location.href,text:text,items:items});
        })()
    """

    /** act on the element numbered [i] — SAME visibility filter + order as DOM_JS */
    internal fun clickJs(i: Int): String = """
        (function(){
          var sel='a[href],button,input,textarea,select,[role="button"],[onclick]';
          var els=document.querySelectorAll(sel);
          var n=0;
          for(var k=0;k<els.length;k++){
            var el=els[k];
            var r=el.getBoundingClientRect();
            if(r.width<5||r.height<5) continue;
            var st=getComputedStyle(el);
            if(st.visibility==='hidden'||st.display==='none') continue;
            if(n===$i){ el.scrollIntoView({block:'center'}); el.click(); return 'clicked'; }
            n++;
          }
          return 'no-such';
        })()
    """.trimIndent()

    internal fun typeJs(i: Int, text: String, submit: Boolean): String {
        val esc = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("$", "\\$")
        return """
            (function(){
              var sel='a[href],button,input,textarea,select,[role="button"],[onclick]';
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
        sb.append("TEXT: ").append(o.optString("text", "").take(850)).append('\n')
        sb.append("ELEMENTS:\n")
        val arr = o.optJSONArray("items")
        if (arr != null) for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            sb.append("[").append(e.optInt("i")).append("] <").append(e.optString("tag"))
            if (e.has("type")) sb.append(" type=").append(e.getString("type"))
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
        |After every step you receive a PAGE summary with ELEMENTS numbered [0]..[n].
        |Reply with EXACTLY one line, one of:
        |⟦ACT⟧{"act":"goto","url":"https://…"}
        |⟦ACT⟧{"act":"click","i":3}
        |⟦ACT⟧{"act":"type","i":7,"text":"hello","submit":true}
        |⟦ACT⟧{"act":"scroll","dy":600}
        |⟦ACT⟧{"act":"back"}
        |⟦ACT⟧{"act":"wait"}
        |⟦ACT⟧{"act":"done","summary":"what happened"}
        |Rules: never invent element numbers you can't see; typing into [password] fields is allowed when the user asked for sign-ins; if a CAPTCHA or a human check appears, reply wait once then done — the user takes over manually, NEVER bypass protections; when the goal is reached, reply done.
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
}

/**
 * The live overlay — the WebView IS the feature: the user watches the agent
 * work on their own screen, captions narrate, and TAKE THE WHEEL hands
 * control to the human at any second.
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

    val task = AgentBrowser.Task(chat, goal)
    AgentBrowser.running = task
    val ready = booleanArrayOf(false)
    val main = Handler(Looper.getMainLooper())

    // ---- the overlay ----
    val overlay = FrameLayout(this).apply { setBackgroundColor(W.theme.bg) }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)) }
    head.addView(W.label(this, "🌐 ORUBUS BROWSER", 14f, W.theme.ink, heavy = 2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val pauseBtn = W.button(this, "TAKE THE WHEEL", W.theme.pop, W.theme.ink, radiusDp = 99, small = true)
    head.addView(pauseBtn)
    val endBtn = W.iconBtn(this, W.Icon.CLOSE, W.theme.paper)
    head.addView(endBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(8) })
    col.addView(head)
    val caption = TextView(this).apply {
        text = "warming the page…"
        textSize = 12.5f; setTextColor(W.theme.acc2); typeface = W.medium(this@startAgentBrowse)
        setPadding(dp(14), dp(2), dp(14), dp(8)); maxLines = 2
    }
    col.addView(caption)
    col.addView(View(this).apply { setBackgroundColor(W.theme.ink) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))

    val web = WebView(this)
    web.settings.javaScriptEnabled = true
    web.settings.domStorageEnabled = true
    web.settings.userAgentString = DuckEngine.UA
    web.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) { ready[0] = true }
    }
    web.webChromeClient = WebChromeClient()
    CookieManager.getInstance().setAcceptCookie(true)
    col.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    val foot = W.label(this, "everything happens on THIS screen — pause and it's your browser", 11f, W.theme.dim).apply {
        gravity = Gravity.CENTER; setPadding(dp(10), dp(7), dp(10), dp(9))
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
            try { web.stopLoading() } catch (_: Exception) {}
            main.post { web.destroy() }
            overlay.animate().alpha(0f).setDuration(180).withEndAction { screen.removeView(overlay) }.start()
            loadCurrentChat()
            refreshAll()
        }
        Unit
    }
    val setCaption: (String) -> Unit = { s -> main.post { if (!closed) caption.text = s } }

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
                if (ready[0] || System.currentTimeMillis() > deadline) {
                    web.evaluateJavascript(AgentBrowser.DOM_JS) { raw -> done(raw ?: "") }
                } else poll()
            }, 400)
        }
        poll()
    }

    // ---- the loop ----
    Thread {
        val model = DuckEngine.modelFor(tier)
        val pre = (if (prelude.isNotBlank()) "$prelude\n\n" else "") +
            "You are driving the user's browser to reach the goal. Minimal steps, no detours.\n\n" +
            AgentBrowser.contract(goal, startUrl)
        var summary = "PAGE: (fresh)\n"
        var finalLine = ""
        val t0 = System.currentTimeMillis()
        try {
            main.post { web.loadUrl(startUrl) }
            readPage { summary = AgentBrowser.summarize(it) }
            for (step in 0 until AgentBrowser.MAX_STEPS) {
                if (task.stopped) return@Thread
                if (task.paused) { Thread.sleep(700); continue }
                setCaption("step ${step + 1} · deciding…")
                val msgs = DuckEngine.buildMessages(pre, mutableListOf(true to summary.take(3500)))
                val reply = DuckEngine.chat(msgs, model)
                val act = AgentBrowser.parseAct(reply)
                if (act == null) { setCaption("the agent spoke plain — ending here"); break }
                when (act.optString("act")) {
                    "goto" -> {
                        var u = act.optString("url", "")
                        if (u.isNotBlank() && !u.startsWith("http")) u = "https://" + u
                        setCaption("opening ${u.take(60)}")
                        main.post { web.loadUrl(u) }
                        readPage { summary = AgentBrowser.summarize(it) }
                    }
                    "click" -> {
                        val i = act.optInt("i", -1)
                        setCaption("tapping [$i]…")
                        main.post { web.evaluateJavascript(AgentBrowser.clickJs(i), null) }
                        Thread.sleep(2300)
                        readPage { summary = AgentBrowser.summarize(it) }
                    }
                    "type" -> {
                        val i = act.optInt("i", -1)
                        setCaption("typing into [$i]…")
                        main.post { web.evaluateJavascript(AgentBrowser.typeJs(i, act.optString("text", ""), act.optBoolean("submit", false)), null) }
                        Thread.sleep(2500)
                        readPage { summary = AgentBrowser.summarize(it) }
                    }
                    "scroll" -> {
                        val dy = act.optInt("dy", 600).coerceIn(-3000, 3000)
                        main.post { web.evaluateJavascript("window.scrollBy(0,$dy)", null) }
                        Thread.sleep(900)
                        readPage { summary = AgentBrowser.summarize(it) }
                    }
                    "back" -> {
                        main.post { if (web.canGoBack()) web.goBack() }
                        Thread.sleep(1500)
                        readPage { summary = AgentBrowser.summarize(it) }
                    }
                    "wait" -> {
                        setCaption("a human check is on the page — TAKE THE WHEEL, solve it, HAND BACK")
                        Thread.sleep(4000)
                        readPage { summary = AgentBrowser.summarize(it) + "NOTE: the human may have solved a check — describe what you see.\n" }
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
            if (finalLine.isBlank()) finalLine = "paused at the step cap — the page is on screen; take the wheel or browse again"
        } catch (e: Exception) {
            finalLine = if (e is DuckEngine.NetworkDeadException) "the lane dropped — the browser waits on your screen"
            else (e.message ?: "the browse slipped").take(200)
        }
        val answer = finalLine.ifBlank { "the browse ended" }
        runOnUiThread {
            closeOverlay()
            pushBot(answer)
            Store.append(chat, false, answer)
        }
    }.start()
}
