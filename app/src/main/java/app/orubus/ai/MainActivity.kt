package app.orubus.ai

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orubus AI for Android — v2 "Liquid Glass".
 *
 * Chat-first, no split bottom bar. You land straight into the conversation;
 * the hamburger opens your chats (name + credits live at the drawer's foot),
 * the gear opens settings: shop, requests, inbox (announcements from the
 * admin), legal, about, version.
 *
 * The request engine runs ON THE DEVICE: duck.ai is called with the user's
 * own IP — the lane DDG never packs. Vercel only does the wallet: every
 * message spends Soul Units exactly like every other Orubus surface.
 * Commands work as intents — type "open shop", "remember my name is…",
 * "how many credits" — no slash menus.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout

    // ---- chat surface ----
    private lateinit var chatColumn: LinearLayout
    private var chatList: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var chatInput: EditText? = null
    private var chatTitle: TextView? = null
    private var chatStatus: TextView? = null
    private var inboxDot: View? = null
    private val modeChips = mutableListOf<TextView>()
    private var modeRow: LinearLayout? = null
    private var tier = "standard"
    private var sending = false

    // ---- drawer / settings / overlays ----
    private var drawerPanel: LinearLayout? = null
    private var drawerScrim: View? = null
    private var chatsList: LinearLayout? = null
    private var footName: TextView? = null
    private var footGid: TextView? = null
    private var footMeter: View? = null
    private var footMeterFill: View? = null
    private var footBal: TextView? = null
    private var footBonus: TextView? = null
    private var adButton: TextView? = null
    private var settingsPanel: LinearLayout? = null
    private var settingsScrim: View? = null
    private var inboxScreen: LinearLayout? = null
    private var inboxList: LinearLayout? = null
    private var webFrame: LinearLayout? = null
    private var webView: WebView? = null
    private var webTitle: TextView? = null
    private var bannerSlot: FrameLayout? = null

    // ---- state ----
    @Volatile private var stateJson: JSONObject? = null
    @Volatile private var prelude = ""
    private var built = false
    private val webTargets = mapOf(
        "shop" to ("SHOP" to "https://orubus-ai.vercel.app/shop"),
        "requests" to ("REQUESTS" to "https://orubus-ai.vercel.app/requests"),
        "legal" to ("LEGAL" to "https://orubus-ai.vercel.app/legal"),
        "about" to ("ABOUT" to "https://orubus-ai.vercel.app/about"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.app = application
        val prefs = Store.prefs()
        Api.cookieValue = prefs.getString("cookie", "") ?: ""

        root = FrameLayout(this)
        root.setBackgroundColor(G.INK)
        root.addView(LiquidBackground(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        chatColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(chatColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        Solver.ensure(this)

        if (!prefs.getBoolean("legal_ok", false)) showLegal() else next()
    }

    private fun next() {
        if (Api.cookieValue.isBlank()) showTokenScreen() else enterMain()
    }

    // ---------- first-run gates ----------

    private fun showLegal() {
        val web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.loadUrl("https://orubus-ai.vercel.app/legal")
        AlertDialog.Builder(this)
            .setTitle("Before you come in")
            .setView(web)
            .setCancelable(false)
            .setPositiveButton("I agree") { _, _ ->
                Store.prefs().edit().putBoolean("legal_ok", true).apply()
                next()
            }
            .setNegativeButton("Leave") { _, _ -> finishAffinity() }
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun showTokenScreen() {
        val pad = G.dp(this@MainActivity, 24)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        val glass = TextView(this).apply { text = "◈"; textSize = 64f; typeface = G.black(this@MainActivity); setTextColor(G.YOLK); gravity = Gravity.CENTER }
        val title = TextView(this).apply {
            text = "LINK YOUR TELEGRAM"; textSize = 22f; typeface = G.black(this@MainActivity)
            setTextColor(G.CREAM); gravity = Gravity.CENTER; setPadding(0, G.dp(this@MainActivity, 14), 0, G.dp(this@MainActivity, 6))
        }
        val sub = TextView(this).apply {
            text = "same wallet · same memory · your own lane\n\n1. Open @orubus_bot in Telegram\n2. Send /genjutsu\n3. Paste the GENJ-XXXX code here"
            textSize = 14f; setTextColor(G.MUTED); gravity = Gravity.CENTER
            setPadding(0, G.dp(this@MainActivity, 4), 0, G.dp(this@MainActivity, 18))
            setLineSpacing(G.dp(this@MainActivity, 3).toFloat(), 1f)
        }
        val input = EditText(this).apply {
            hint = "GENJ-XXXX"; maxLines = 1; textSize = 16f
            setTextColor(G.CREAM); setHintTextColor(Color.argb(120, 201, 184, 212))
            typeface = Typeface.MONOSPACE
            background = G.glass(this@MainActivity, 14, fillAlpha = 40)
            setPadding(G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 12))
        }
        val btn = G.brutal(this, "LINK")
        val status = TextView(this).apply {
            textSize = 13f; setTextColor(G.BAD); gravity = Gravity.CENTER; setPadding(0, G.dp(this@MainActivity, 12), 0, 0)
        }
        val later = TextView(this).apply {
            text = "no code yet — explore first, link later"
            textSize = 13f; setTextColor(G.MUTED); gravity = Gravity.CENTER
            setPadding(0, G.dp(this@MainActivity, 16), 0, 0)
        }

        fun goLink() {
            val code = input.text.toString().trim().uppercase(java.util.Locale.US)
            if (code.length < 6) {
                status.text = "the code looks short — it's GENJ-XXXX"; return
            }
            btn.isEnabled = false; btn.text = "LINKING…"; status.text = ""
            Thread {
                try {
                    val (ok, err) = Api.link(code)
                    runOnUiThread {
                        btn.isEnabled = true; btn.text = "LINK"
                        if (ok) {
                            Store.prefs().edit().putString("cookie", Api.cookieValue).apply()
                            pushCookieEverywhere()
                            Toast.makeText(this, "linked — welcome in", Toast.LENGTH_SHORT).show()
                            enterMain()
                        } else status.text = err
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        btn.isEnabled = true; btn.text = "LINK"
                        status.text = "network hiccup — ${e.message ?: "try again"}"
                    }
                }
            }.start()
        }
        btn.setOnClickListener { goLink() }
        input.setOnEditorActionListener { _, _, _ -> goLink(); true }
        later.setOnClickListener { enterMain() }

        box.addView(glass); box.addView(title); box.addView(sub)
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = G.dp(this@MainActivity, 14) })
        box.addView(status); box.addView(later)
        chatColumn.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ---------- main shell ----------

    private fun enterMain() {
        if (built) { refreshAll(); return }
        built = true
        // remove onboarding leftovers
        chatColumn.removeAllViews()
        buildTopBar()
        buildChatArea()
        buildModeRow()
        buildInputRow()
        buildDrawer()
        buildSettings()
        buildInbox()
        buildWebFrame()

        loadCurrentChat()
        refreshAll()
        checkForUpdate()
        if (chatList?.childCount == 0) {
            pushBubble("◈ welcome to the liquid glass — the brain runs on YOUR connection here, so no packed lanes. type anything, or try \"open shop\".", system = true)
        }
    }

    private fun buildTopBar() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 10), G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 10))
        }
        fun glassBtn(label: String): TextView = TextView(this).apply {
            text = label; textSize = 17f; gravity = Gravity.CENTER
            typeface = G.black(this@MainActivity); setTextColor(G.CREAM)
            background = G.glass(this@MainActivity, 12)
            setPadding(G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 6), G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 6))
        }
        val menu = glassBtn("☰")
        menu.setOnClickListener { toggleDrawer(true) }
        bar.addView(menu)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(G.dp(this@MainActivity, 12), 0, 0, 0) }
        chatTitle = TextView(this).apply {
            text = "ORUBUS"; textSize = 17f; typeface = G.black(this@MainActivity); setTextColor(G.CREAM)
        }
        chatStatus = TextView(this).apply {
            text = "your connection · your lane"; textSize = 10.5f; setTextColor(G.MUTED)
        }
        col.addView(chatTitle); col.addView(chatStatus)
        bar.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val inbox = FrameLayout(this)
        val ib = glassBtn("📥")
        ib.setOnClickListener { openInbox() }
        inbox.addView(ib, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        inboxDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(G.PINK) }
        }
        inbox.addView(inboxDot, FrameLayout.LayoutParams(G.dp(this@MainActivity, 9), G.dp(this@MainActivity, 9), Gravity.TOP or Gravity.END).apply { topMargin = G.dp(this@MainActivity, 2); marginEnd = G.dp(this@MainActivity, 2) })
        inboxDot?.visibility = View.GONE
        bar.addView(inbox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = G.dp(this@MainActivity, 8) })

        val gear = glassBtn("⚙")
        gear.setOnClickListener { toggleSettings(true) }
        bar.addView(gear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = G.dp(this@MainActivity, 8) })

        chatColumn.addView(bar)
        // specular strip under the bar
        chatColumn.addView(G.specular(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, G.dp(this@MainActivity, 2)))
    }

    private fun buildChatArea() {
        chatScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        chatList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 8))
        }
        chatScroll!!.addView(chatList)
        chatColumn.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildModeRow() {
        modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 4), G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 6))
        }
        val tiers = listOf("lite" to "QUICK", "standard" to "CHAT", "pro" to "DEEP")
        for ((t, label) in tiers) {
            val chip = G.chip(this, label, t == tier)
            chip.setOnClickListener { setTier(t) }
            chip.tag = t
            modeChips.add(chip)
            modeRow!!.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = G.dp(this@MainActivity, 4); marginEnd = G.dp(this@MainActivity, 4) })
        }
        chatColumn.addView(modeRow)
    }

    private fun setTier(t: String) {
        tier = t
        for (c in modeChips) {
            val sel = c.tag == t
            c.setTextColor(if (sel) G.INK else G.MUTED)
            c.background = if (sel) {
                GradientDrawable().apply { setColor(Color.argb(230, 255, 215, 94)); cornerRadius = G.dp(this@MainActivity, 12).toFloat() }
            } else G.glass(this, 12, fillAlpha = 20)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildInputRow() {
        val wrap = FrameLayout(this).apply { setPadding(G.dp(this@MainActivity, 12), 0, G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 10)) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = G.glass(this@MainActivity, 20)
            setPadding(G.dp(this@MainActivity, 8), G.dp(this@MainActivity, 6), G.dp(this@MainActivity, 6), G.dp(this@MainActivity, 6))
        }
        chatInput = EditText(this).apply {
            hint = "message orubus…"; maxLines = 4; textSize = 15f
            setTextColor(G.CREAM); setHintTextColor(Color.argb(110, 201, 184, 212))
            background = null
            setPadding(G.dp(this@MainActivity, 10), G.dp(this@MainActivity, 8), G.dp(this@MainActivity, 10), G.dp(this@MainActivity, 8))
        }
        panel.addView(chatInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val send = TextView(this).apply {
            text = "▶"; textSize = 16f; gravity = Gravity.CENTER
            typeface = G.black(this@MainActivity); setTextColor(G.INK)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(G.PINK) }
        }
        val sendWrap = FrameLayout(this)
        sendWrap.addView(send, FrameLayout.LayoutParams(G.dp(this@MainActivity, 44), G.dp(this@MainActivity, 44)))
        send.setOnClickListener { sendChat() }
        panel.addView(sendWrap)

        wrap.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        chatColumn.addView(wrap)

        // input focus → send enabled look
        chatInput?.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                send.alpha = if (s.isNullOrBlank()) 0.55f else 1f
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    // ---------- bubbles ----------

    private fun pushBubble(text: String, mine: Boolean = false, system: Boolean = false): TextView {
        val list = chatList ?: return TextView(this)
        val c = this
        val tv = TextView(c).apply {
            this.text = if (text.length > 4000) text.take(4000) + "…" else text
            textSize = if (system) 12.5f else 14.5f
            if (system) typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            setTextColor(when {
                system -> G.MUTED
                mine -> G.INK
                else -> G.CREAM
            })
            background = when {
                system -> null
                mine -> GradientDrawable().apply {
                    colors = intArrayOf(G.PINK, G.PINK_SOFT)
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    orientation = GradientDrawable.Orientation.TL_BR
                    cornerRadius = G.dp(c, 16).toFloat()
                }
                else -> G.glass(c, 4, fillAlpha = 34)
            }
            setPadding(G.dp(c, 12), G.dp(c, 9), G.dp(c, 12), G.dp(c, 9))
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        when {
            system -> { lp.gravity = Gravity.CENTER_HORIZONTAL; lp.topMargin = G.dp(c, 4); lp.bottomMargin = G.dp(c, 4) }
            mine -> { lp.gravity = Gravity.END; lp.topMargin = G.dp(c, 6) }
            else -> { lp.gravity = Gravity.START; lp.topMargin = G.dp(c, 6) }
        }
        list.addView(tv, lp)
        list.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun loadCurrentChat() {
        val list = chatList ?: return
        list.removeAllViews()
        val chat = Store.currentChat()
        chatTitle?.text = chat.name.uppercase(java.util.Locale.US)
        for (m in chat.msgs.takeLast(80)) pushBubble(m.text, m.mine, m.system)
    }

    // ---------- sending: intents first, then the on-device engine ----------

    @SuppressLint("SetTextI18n")
    private fun sendChat() {
        if (sending) return
        val text = chatInput?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        chatInput?.setText("")
        val chat = Store.currentChat()
        pushBubble(text, mine = true)
        Store.append(chat, true, text)
        chatTitle?.text = chat.name.uppercase(java.util.Locale.US)

        // ---- intents: commands without commands ----
        val intent = Intents.match(text)
        if (intent != null) {
            runIntent(intent)
            return
        }

        // ---- the AI turn ----
        sending = true
        val typing = pushBubble("…")
        Thread {
            val linked = Api.cookieValue.isNotBlank()
            var token = ""
            try {
                if (linked) {
                    val spend = Api.appTurn(text, tier)
                    if (!spend.ok) {
                        runOnUiThread {
                            chatList?.removeView(typing)
                            pushBubble(spend.error, system = true)
                            spend.wallet?.let { renderWallet(it) }
                        }
                        sending = false
                        return@Thread
                    }
                    token = spend.token
                    spend.wallet?.let { runOnUiThread { renderWallet(it) } }
                }
                val turns = Store.turnsOf(chat)
                val pre = prelude.ifBlank { "You are Orubus — a sharp, warm, slightly playful AI companion. Keep replies tight and human." }
                val answer = DuckEngine.chat(DuckEngine.buildMessages(pre, turns), DuckEngine.modelFor(tier))
                if (linked && token.isNotBlank()) {
                    Thread { Api.appDone(token, answer) }.start()
                }
                runOnUiThread {
                    chatList?.removeView(typing)
                    pushBubble(answer)
                    Store.append(chat, false, answer)
                }
            } catch (e: Exception) {
                if (linked && token.isNotBlank()) Thread { Api.appFail(token) }.start()
                runOnUiThread {
                    chatList?.removeView(typing)
                    pushBubble("⚠ the lane slipped — ${e.message ?: "give it a moment"}${if (linked) " (your units went back)" else ""}", system = true)
                }
            } finally {
                sending = false
            }
        }.start()
    }

    private fun runIntent(r: Intents.Result) {
        when (r.kind) {
            Intents.Kind.SHOP -> openWeb("shop")
            Intents.Kind.REQUESTS -> openWeb("requests")
            Intents.Kind.LEGAL -> openWeb("legal")
            Intents.Kind.ABOUT -> openWeb("about")
            Intents.Kind.INBOX -> openInbox()
            Intents.Kind.SETTINGS -> toggleSettings(true)
            Intents.Kind.NEWCHAT -> {
                val c = Store.newChat()
                chatList?.removeAllViews()
                chatTitle?.text = c.name.uppercase(java.util.Locale.US)
                pushBubble("fresh chat — new thread, new headspace", system = true)
            }
            Intents.Kind.CHATS -> toggleDrawer(true)
            Intents.Kind.UNITS -> {
                val w = stateJson?.optJSONObject("wallet")
                if (w != null) {
                    pushBubble(renderWalletText(w), system = true)
                } else pushBubble(if (Api.cookieValue.isBlank()) "link first — the wallet lives with your telegram identity" else "wallet loading…", system = true)
            }
            Intents.Kind.ID -> {
                val gid = stateJson?.optString("gid", "") ?: ""
                pushBubble(if (gid.isNotBlank()) "you are $gid — one identity across the bots, the web and this app" else (if (Api.cookieValue.isBlank()) "not linked yet — paste a GENJ code (gear → identity)" else "identity loading…"), system = true)
            }
            Intents.Kind.REMEMBER, Intents.Kind.FORGET, Intents.Kind.SUMMARY, Intents.Kind.SOUL -> {
                val cmd = r.serverCommand ?: return
                serverCommand(cmd)
            }
            Intents.Kind.DOWNLOAD -> pushBubble("you're holding it — this IS the app. share it: ${Api.APK_URL}", system = true)
            Intents.Kind.HELP -> pushBubble(
                "try: \"open shop\" · \"new chat\" · \"how many credits\" · \"remember my name is …\" · \"catch me up\" · \"inbox\" · or just talk — I'm all ears",
                system = true
            )
            Intents.Kind.LOGOUT -> {
                AlertDialog.Builder(this)
                    .setTitle("Log out?")
                    .setMessage("The app forgets your session — chats stay on the device.")
                    .setPositiveButton("Log out") { _, _ ->
                        Store.prefs().edit().remove("cookie").apply()
                        Api.cookieValue = ""
                        pushCookieEverywhere()
                        built = false
                        chatColumn.removeAllViews()
                        showTokenScreen()
                    }
                    .setNegativeButton("Stay", null).show()
            }
        }
    }

    /** Free soul commands ride the server's web-command path (no SU). */
    private fun serverCommand(cmd: String) {
        if (Api.cookieValue.isBlank()) {
            pushBubble("link first — your soul lives with your telegram identity (gear → identity)", system = true)
            return
        }
        pushBubble("…", system = true)
        Thread {
            try {
                val resp = Api.post("msg", mapOf("text" to cmd, "tier" to "standard"))
                val answer = if (resp.optBoolean("free", false)) resp.optString("answer", "") else resp.optString("error", "that one didn't land")
                runOnUiThread {
                    // drop the typing dot (last system bubble)
                    chatList?.let { l -> if (l.childCount > 0) l.removeViewAt(l.childCount - 1) }
                    pushBubble(answer.ifBlank { "…nothing came back" }, system = true)
                    Store.append(Store.currentChat(), false, answer, true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    chatList?.let { l -> if (l.childCount > 0) l.removeViewAt(l.childCount - 1) }
                    pushBubble("network hiccup — ${e.message ?: "try again"}", system = true)
                }
            }
        }.start()
    }

    // ---------- drawer: chats on top, name + credits at the foot ----------

    private fun buildDrawer() {
        val scrim = View(this).apply { setBackgroundColor(Color.argb(140, 10, 6, 16)); visibility = View.GONE }
        scrim.setOnClickListener { toggleDrawer(false) }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        drawerScrim = scrim

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = G.glass(this@MainActivity, 0, fillAlpha = 46, strokeDp = 1)
            setPadding(G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 16), G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 14))
        }
        val w = (resources.displayMetrics.widthPixels * 0.82f).toInt()

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "CHATS"; textSize = 17f; typeface = G.black(this@MainActivity); setTextColor(G.CREAM)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val plus = G.brutal(this, "＋ NEW", bgColor = G.PINK, fgColor = G.INK, radiusDp = 10)
        plus.setPadding(G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 7), G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 7))
        plus.setOnClickListener {
            toggleDrawer(false)
            val c = Store.newChat()
            chatList?.removeAllViews()
            chatTitle?.text = c.name.uppercase(java.util.Locale.US)
            pushBubble("fresh chat — new thread, new headspace", system = true)
        }
        head.addView(plus)
        panel.addView(head)
        panel.addView(G.specular(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, G.dp(this@MainActivity, 2)).apply { topMargin = G.dp(this@MainActivity, 8); bottomMargin = G.dp(this@MainActivity, 8) })

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        chatsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(chatsList)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- foot: name + credits ----
        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = G.glass(this@MainActivity, 16, fillAlpha = 52)
            setPadding(G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 12))
        }
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        footName = TextView(this).apply {
            text = "friend"; textSize = 15f; typeface = G.black(this@MainActivity); setTextColor(G.CREAM)
        }
        nameRow.addView(footName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footBonus = TextView(this).apply {
            text = "+0"; textSize = 11.5f; typeface = G.black(this@MainActivity); setTextColor(G.INK)
            background = GradientDrawable().apply { setColor(G.YOLK); cornerRadius = G.dp(this@MainActivity, 8).toFloat() }
            setPadding(G.dp(this@MainActivity, 8), G.dp(this@MainActivity, 3), G.dp(this@MainActivity, 8), G.dp(this@MainActivity, 3))
        }
        nameRow.addView(footBonus)
        foot.addView(nameRow)

        footGid = TextView(this).apply {
            text = "not linked"; textSize = 11f; setTextColor(G.MUTED); setPadding(0, G.dp(this@MainActivity, 2), 0, 0)
        }
        foot.addView(footGid)

        val meterHolder = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(Color.argb(60, 0, 0, 0)); cornerRadius = G.dp(this@MainActivity, 6).toFloat() }
        }
        footMeterFill = View(this).apply {
            background = GradientDrawable().apply {
                colors = intArrayOf(G.PINK, G.YOLK); gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadius = G.dp(this@MainActivity, 6).toFloat()
            }
        }
        meterHolder.addView(footMeterFill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        footMeter = meterHolder
        foot.addView(meterHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, G.dp(this@MainActivity, 10)).apply { topMargin = G.dp(this@MainActivity, 8) })

        footBal = TextView(this).apply {
            text = "— / — SU"; textSize = 12f; setTextColor(G.MUTED); setPadding(0, G.dp(this@MainActivity, 3), 0, 0)
        }
        foot.addView(footBal)

        adButton = G.brutal(this, "📺 WATCH AD · +SU", bgColor = G.VIOLET, fgColor = G.CREAM, radiusDp = 12)
        adButton?.setPadding(G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 9), G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 9))
        adButton?.setOnClickListener { watchAd() }
        foot.addView(adButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = G.dp(this@MainActivity, 10) })

        panel.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = G.dp(this@MainActivity, 10) })

        root.addView(panel, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.START })
        panel.translationX = -w.toFloat()
        drawerPanel = panel
    }

    private fun renderDrawerChats() {
        val list = chatsList ?: return
        list.removeAllViews()
        val cur = Store.currentChatId()
        for (c in Store.chats()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = G.glass(this@MainActivity, 12, fillAlpha = if (c.id == cur) 60 else 18)
                setPadding(G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 9), G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 9))
            }
            row.addView(TextView(this).apply {
                text = c.name; textSize = 14f; typeface = G.black(this@MainActivity)
                setTextColor(if (c.id == cur) G.YOLK else G.CREAM); maxLines = 1
            })
            val last = c.msgs.lastOrNull()?.text ?: "empty thread"
            row.addView(TextView(this).apply {
                text = last; textSize = 11f; setTextColor(G.MUTED); maxLines = 1
                setPadding(0, G.dp(this@MainActivity, 1), 0, 0)
            })
            row.setOnClickListener {
                Store.setCurrent(c.id)
                loadCurrentChat()
                toggleDrawer(false)
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Delete chat?")
                    .setMessage("\"${c.name}\" disappears from this device.")
                    .setPositiveButton("Delete") { _, _ -> Store.deleteChat(c.id); renderDrawerChats(); loadCurrentChat() }
                    .setNegativeButton("Keep", null).show()
                true
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = G.dp(this@MainActivity, 6) })
        }
    }

    private fun toggleDrawer(open: Boolean) {
        val p = drawerPanel ?: return
        if (open) renderDrawerChats()
        val w = p.layoutParams.width.toFloat()
        p.animate().translationX(if (open) 0f else -w).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
        drawerScrim?.visibility = if (open) View.VISIBLE else View.GONE
        drawerScrim?.animate()?.alpha(if (open) 1f else 0f)?.setDuration(240)?.start()
    }

    // ---------- settings (top right): shop · requests · inbox · legal · about · version ----------

    private fun buildSettings() {
        val scrim = View(this).apply { setBackgroundColor(Color.argb(140, 10, 6, 16)); visibility = View.GONE }
        scrim.setOnClickListener { toggleSettings(false) }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        settingsScrim = scrim

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = G.glass(this@MainActivity, 0, fillAlpha = 46)
            setPadding(G.dp(this@MainActivity, 16), G.dp(this@MainActivity, 20), G.dp(this@MainActivity, 16), G.dp(this@MainActivity, 16))
        }
        val w = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        panel.addView(TextView(this).apply {
            text = "SETTINGS"; textSize = 17f; typeface = G.black(this@MainActivity); setTextColor(G.CREAM)
            setPadding(0, 0, 0, G.dp(this@MainActivity, 6))
        })

        fun row(label: String, id: String): TextView {
            val t = TextView(this).apply {
                text = label; textSize = 15f; typeface = G.black(this@MainActivity); setTextColor(G.CREAM)
                background = G.glass(this@MainActivity, 12, fillAlpha = 24)
                setPadding(G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 13), G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 13))
            }
            t.setOnClickListener {
                when (id) {
                    "identity" -> {
                        toggleSettings(false)
                        if (Api.cookieValue.isBlank()) showRelink() else {
                            val gid = stateJson?.optString("gid", "") ?: ""
                            AlertDialog.Builder(this)
                                .setTitle("Identity")
                                .setMessage(if (gid.isNotBlank()) "You are $gid — one identity across the bots, the web and this app.\n\nLog out of this device?" else "Linked, but the identity is still loading. Try again in a second.")
                                .setPositiveButton("Log out") { _, _ -> runIntent(Intents.Result(Intents.Kind.LOGOUT)) }
                                .setNegativeButton("Keep", null).show()
                        }
                    }
                    "version" -> {
                        toggleSettings(false)
                        checkForUpdate(true)
                    }
                    else -> {
                        toggleSettings(false)
                        when (id) {
                            "shop" -> openWeb("shop")
                            "requests" -> openWeb("requests")
                            "inbox" -> openInbox()
                            "legal" -> openWeb("legal")
                            "about" -> openWeb("about")
                        }
                    }
                }
            }
            return t
        }
        panel.addView(row("🛍 SHOP — packs · pro · themes", "shop"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = G.dp(this@MainActivity, 6) })
        panel.addView(row("🗳 REQUESTS — the feature wall", "requests"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = G.dp(this@MainActivity, 6) })
        panel.addView(row("📥 INBOX — from the admin", "inbox"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = G.dp(this@MainActivity, 6) })
        panel.addView(row("⚖ LEGAL", "legal"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = G.dp(this@MainActivity, 6) })
        panel.addView(row("ℹ ABOUT", "about"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = G.dp(this@MainActivity, 6) })
        val ver = "APP VERSION · v${versionName()}"
        panel.addView(row(ver, "version"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = G.dp(this@MainActivity, 6) })
        panel.addView(row("🪪 IDENTITY — link / log out", "identity"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = G.dp(this@MainActivity, 6) })

        root.addView(panel, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.END })
        panel.translationX = w.toFloat()
        settingsPanel = panel
    }

    private fun toggleSettings(open: Boolean) {
        val p = settingsPanel ?: return
        val w = p.layoutParams.width.toFloat()
        p.animate().translationX(if (open) 0f else w).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
        settingsScrim?.visibility = if (open) View.VISIBLE else View.GONE
        settingsScrim?.animate()?.alpha(if (open) 1f else 0f)?.setDuration(240)?.start()
    }

    private fun showRelink() {
        for (i in 0 until chatColumn.childCount) {
            val v = chatColumn.getChildAt(i)
            if (v.tag == "relink") { chatColumn.removeView(v); break }
        }
        // simple inline re-link strip
        val strip = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "relink" }
        val input = EditText(this).apply {
            hint = "GENJ-XXXX"; maxLines = 1; textSize = 14f
            setTextColor(G.CREAM); typeface = Typeface.MONOSPACE
            background = G.glass(this@MainActivity, 12, fillAlpha = 40)
            setPadding(G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 10), G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 10))
        }
        val go = G.brutal(this, "LINK", radiusDp = 10)
        go.setOnClickListener {
            val code = input.text.toString().trim().uppercase(java.util.Locale.US)
            if (code.length < 6) return@setOnClickListener
            go.text = "…"
            Thread {
                val (ok, err) = Api.link(code)
                runOnUiThread {
                    go.text = "LINK"
                    if (ok) {
                        Store.prefs().edit().putString("cookie", Api.cookieValue).apply()
                        pushCookieEverywhere()
                        chatColumn.removeView(strip)
                        refreshAll()
                        Toast.makeText(this, "linked", Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
        strip.addView(input)
        strip.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = G.dp(this@MainActivity, 8) })
        chatColumn.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ---------- inbox: announcements from the admin ----------

    private fun buildInbox() {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = G.glass(this@MainActivity, 0, fillAlpha = 58)
            setPadding(G.dp(this@MainActivity, 16), G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 16), G.dp(this@MainActivity, 14))
            visibility = View.GONE
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "INBOX"; textSize = 17f; typeface = G.black(this@MainActivity); setTextColor(G.CREAM)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "✕"; textSize = 16f; typeface = G.black(this@MainActivity); setTextColor(G.MUTED)
            setPadding(G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 4), G.dp(this@MainActivity, 4), G.dp(this@MainActivity, 4))
            setOnClickListener { inboxScreen?.visibility = View.GONE }
        })
        screen.addView(head)
        screen.addView(G.specular(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, G.dp(this@MainActivity, 2)).apply { topMargin = G.dp(this@MainActivity, 8); bottomMargin = G.dp(this@MainActivity, 10) })
        val scroll = ScrollView(this)
        inboxList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(inboxList)
        screen.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        inboxScreen = screen
    }

    private fun openInbox() {
        toggleDrawer(false); toggleSettings(false)
        val list = inboxList ?: return
        list.removeAllViews()
        val items = stateJson?.optJSONArray("announcements") ?: JSONArray()
        val lastSeen = Store.prefs().getLong("annSeen", 0)
        if (items.length() == 0) {
            list.addView(TextView(this).apply {
                text = "nothing yet — announcements from the admin land right here"; textSize = 13f
                setTextColor(G.MUTED); setPadding(0, G.dp(this@MainActivity, 24), 0, 0); gravity = Gravity.CENTER
            })
        }
        for (i in items.length() - 1 downTo 0) {
            val o = items.getJSONObject(i)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = G.glass(this@MainActivity, 14, fillAlpha = 40)
                setPadding(G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 11), G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 11))
            }
            val unread = o.optLong("ts", 0) > lastSeen
            if (unread) {
                card.addView(View(this).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(G.PINK) }
                }, LinearLayout.LayoutParams(G.dp(this@MainActivity, 8), G.dp(this@MainActivity, 8)).apply { bottomMargin = G.dp(this@MainActivity, 5) })
            }
            card.addView(TextView(this).apply {
                text = o.optString("text", ""); textSize = 13.5f; setTextColor(G.CREAM)
                setLineSpacing(G.dp(this@MainActivity, 2).toFloat(), 1f)
            })
            card.addView(TextView(this).apply {
                text = "from the admin · ${timeAgo(o.optLong("ts", 0))}"; textSize = 10.5f; setTextColor(G.MUTED)
                setPadding(0, G.dp(this@MainActivity, 5), 0, 0)
            })
            list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = G.dp(this@MainActivity, 8) })
        }
        Store.prefs().edit().putLong("annSeen", System.currentTimeMillis()).apply()
        inboxDot?.visibility = View.GONE
        inboxScreen?.visibility = View.VISIBLE
    }

    // ---------- web overlay (shop / requests / legal / about) + AdMob banner ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebFrame() {
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(G.INK)
            visibility = View.GONE
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 10), G.dp(this@MainActivity, 14), G.dp(this@MainActivity, 10))
        }
        webTitle = TextView(this).apply {
            text = "SHOP"; textSize = 15f; typeface = G.black(this@MainActivity); setTextColor(G.CREAM)
        }
        head.addView(webTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "✕"; textSize = 16f; typeface = G.black(this@MainActivity); setTextColor(G.MUTED)
            setPadding(G.dp(this@MainActivity, 12), G.dp(this@MainActivity, 4), G.dp(this@MainActivity, 4), G.dp(this@MainActivity, 4))
            setOnClickListener { webFrame?.visibility = View.GONE }
        })
        frame.addView(head)
        frame.addView(G.specular(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, G.dp(this@MainActivity, 2)))

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
            webViewClient = object : WebViewClient() {
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    return if (url.startsWith("https://orubus-ai.vercel.app")) false
                    else {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                        true
                    }
                }
            }
        }
        frame.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        bannerSlot = FrameLayout(this).apply { visibility = View.GONE }
        frame.addView(bannerSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webFrame = frame
    }

    private fun openWeb(target: String) {
        val (title, url) = webTargets[target] ?: return
        webTitle?.text = title
        pushCookieEverywhere()
        webView?.loadUrl(url)
        webFrame?.visibility = View.VISIBLE
        renderBanner(target == "shop")
    }

    private fun pushCookieEverywhere() {
        if (Api.cookieValue.isBlank()) return
        try {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setCookie("https://orubus-ai.vercel.app/", "orubus_web=${Api.cookieValue}; Path=/; Secure; SameSite=Lax")
            CookieManager.getInstance().flush()
        } catch (_: Exception) {}
    }

    // ---------- AdMob ----------

    private var rewardedLoaded: Boolean = false

    private fun admobCfg(): JSONObject? = stateJson?.optJSONObject("app")?.optJSONObject("admob")?.takeIf { it.optBoolean("enabled", false) }

    private fun initAdsIfNeeded() {
        val cfg = admobCfg() ?: return
        if (adsReady) return
        adsReady = true
        try {
            com.google.android.gms.ads.MobileAds.initialize(this) {}
        } catch (_: Exception) { adsReady = false }
    }

    @Volatile private var adsReady = false

    private fun renderBanner(forShop: Boolean) {
        val slot = bannerSlot ?: return
        slot.removeAllViews()
        val cfg = admobCfg()
        if (!forShop || cfg == null || !adsReady) { slot.visibility = View.GONE; return }
        try {
            val ad = com.google.android.gms.ads.AdView(this)
            // some SDK bindings expose adUnitId getter-only — reflect the setter
            // (present at bytecode level) and hide the banner if it ever vanishes
            val m = com.google.android.gms.ads.AdView::class.java.getMethod("setAdUnitId", String::class.java)
            m.invoke(ad, cfg.optString("bannerId", ""))
            ad.adSize = com.google.android.gms.ads.AdSize.BANNER
            ad.loadAd(com.google.android.gms.ads.AdRequest.Builder().build())
            slot.addView(ad)
            slot.visibility = View.VISIBLE
        } catch (_: Exception) {
            slot.visibility = View.GONE
        }
    }

    private fun watchAd() {
        val cfg = admobCfg()
        if (cfg == null) {
            Toast.makeText(this, "ads aren't on right now", Toast.LENGTH_SHORT).show()
            return
        }
        if (!adsReady) initAdsIfNeeded()
        val nonce = Api.adToken()
        if (nonce.isNullOrBlank()) {
            Toast.makeText(this, "couldn't start an ad — try again in a moment", Toast.LENGTH_SHORT).show()
            return
        }
        val reward = cfg.optInt("reward", 50)
        Toast.makeText(this, "loading the ad…", Toast.LENGTH_SHORT).show()
        try {
            com.google.android.gms.ads.rewarded.RewardedAd.load(this, cfg.optString("rewardedId", ""),
                com.google.android.gms.ads.AdRequest.Builder().build(),
                object : com.google.android.gms.ads.rewarded.RewardedAdLoadCallback() {
                    override fun onAdFailedToLoad(error: com.google.android.gms.ads.LoadAdError) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "no ad filled — try again later", Toast.LENGTH_SHORT).show() }
                    }
                    override fun onAdLoaded(ad: com.google.android.gms.ads.rewarded.RewardedAd) {
                        ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                            override fun onAdDismissedFullScreenContent() { rewardedLoaded = false }
                        }
                        runOnUiThread {
                            ad.show(this@MainActivity) { item ->
                                Thread {
                                    val r = Api.adClaim(nonce)
                                    runOnUiThread {
                                        if (r?.optBoolean("ok", false) == true) {
                                            Toast.makeText(this@MainActivity, "+${r.optInt("gained", reward)} SU — straight to your wallet", Toast.LENGTH_LONG).show()
                                            r.optJSONObject("wallet")?.let { renderWallet(it) }
                                        } else {
                                            Toast.makeText(this@MainActivity, r?.optString("error", "reward didn't land") ?: "reward didn't land", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }.start()
                            }
                        }
                    }
                })
        } catch (e: Exception) {
            Toast.makeText(this, "ads aren't ready — ${e.message ?: "try later"}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- wallet + state ----------

    private fun renderWallet(w: JSONObject) {
        val bal = w.optInt("dailyBal", 0)
        val max = w.optInt("meterMax", 360)
        val bonus = w.optInt("bonus", 0)
        footName?.text = if (w.optString("name", "").isNotBlank()) w.optString("name") else "friend"
        footBal?.text = "$bal / $max SU · refills in ~${w.optInt("resetsInH", 0)}h"
        footBonus?.visibility = if (bonus > 0) View.VISIBLE else View.GONE
        footBonus?.text = "+${bonus}"
        val pct = Math.max(0.04f, Math.min(1f, bal / Math.max(1f, max.toFloat())))
        footMeter?.post {
            footMeterFill?.layoutParams = FrameLayout.LayoutParams(
                ((footMeter?.width ?: 0) * pct).toInt().coerceAtLeast(1), ViewGroup.LayoutParams.MATCH_PARENT)
            footMeterFill?.requestLayout()
        }
        // chips show live costs
        val tiers = w.optJSONObject("tiers")
        if (tiers != null) {
            for (c in modeChips) {
                val t = c.tag as? String ?: continue
                val cost = tiers.optInt(t, 0)
                val label = when (t) { "lite" -> "QUICK"; "pro" -> "DEEP"; else -> "CHAT" }
                c.text = if (cost > 0) "$label · $cost" else label
            }
        }
    }

    private fun renderWalletText(w: JSONObject): String {
        val bal = w.optInt("dailyBal", 0)
        val max = w.optInt("meterMax", 360)
        val bonus = w.optInt("bonus", 0)
        val tiers = w.optJSONObject("tiers")
        val costs = tiers?.let { "QUICK ${it.optInt("lite", 0)} · CHAT ${it.optInt("standard", 0)} · DEEP ${it.optInt("pro", 0)}" } ?: ""
        val filled = Math.round(Math.max(0.0, Math.min(1.0, bal.toDouble() / Math.max(1, max))) * 10).toInt()
        return "⚡ $bal / $max SU${if (bonus > 0) " · bonus +$bonus (yours forever)" else ""}\n" +
            "█".repeat(filled) + "░".repeat(10 - filled) + "\n" +
            "costs: $costs · refills in ~${w.optInt("resetsInH", 0)}h"
    }

    private fun refreshAll() {
        renderDrawerChats()
        if (Api.cookieValue.isBlank()) {
            footName?.text = "friend"; footGid?.text = "not linked — gear → identity"
            footBal?.text = "— / — SU"
            adButton?.visibility = View.GONE
            chatStatus?.text = "your connection · your lane · not linked"
            return
        }
        adButton?.visibility = View.VISIBLE
        Thread {
            try {
                val st = Api.post("state")
                stateJson = st
                val app = st.optJSONObject("app")
                app?.optJSONObject("models")?.let { mm ->
                    val map = mutableMapOf<String, String>()
                    for (k in listOf("lite", "standard", "pro")) {
                        val v = mm.optString(k, "")
                        if (v.isNotBlank()) map[k] = v
                    }
                    if (map.isNotEmpty()) DuckEngine.models = map
                }
                prelude = st.optString("prelude", "")
                runOnUiThread {
                    st.optJSONObject("wallet")?.let { renderWallet(it) }
                    footGid?.text = st.optString("gid", "").ifBlank { "linked" }
                    initAdsIfNeeded()
                    // inbox badge
                    val items = st.optJSONArray("announcements") ?: JSONArray()
                    val lastSeen = Store.prefs().getLong("annSeen", 0)
                    var unread = 0
                    for (i in 0 until items.length()) if (items.getJSONObject(i).optLong("ts", 0) > lastSeen) unread++
                    inboxDot?.visibility = if (unread > 0) View.VISIBLE else View.GONE
                }
            } catch (_: Exception) {
                runOnUiThread { footGid?.text = "offline — retrying later" }
            }
        }.start()
    }

    // ---------- continuous updates (GitHub Releases) ----------

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun checkForUpdate(explicit: Boolean = false) {
        Thread {
            try {
                val rel = Api.get("https://api.github.com/repos/${Api.APP_REPO}/releases/latest")
                val tag = rel.optString("tag_name", "").removePrefix("v")
                val cur = versionName()
                if (isNewer(tag, cur)) {
                    val asset = (rel.optJSONArray("assets") ?: JSONArray())
                        .let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
                        .firstOrNull { it.optString("name", "").endsWith(".apk") }
                    val url = asset?.optString("browser_download_url") ?: Api.APK_URL
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("Update available — v$tag")
                            .setMessage("You're on v$cur. A newer build is out — grab it, install over the top, everything stays.")
                            .setPositiveButton("Download") { _, _ ->
                                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                            }
                            .setNegativeButton("Later", null)
                            .show()
                    }
                } else if (explicit) {
                    runOnUiThread { Toast.makeText(this, "you're on the latest — v$cur", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                if (explicit) runOnUiThread { Toast.makeText(this, "couldn't check — ${e.message ?: "later"}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun isNewer(tag: String, cur: String): Boolean {
        val p = fun(s: String) = s.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = p(tag); val b = p(cur)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // ---------- misc ----------

    private fun timeAgo(ts: Long): String {
        if (ts <= 0) return "just now"
        val s = Math.max(1, (System.currentTimeMillis() - ts) / 1000)
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60}m ago"
            s < 86400 -> "${s / 3600}h ago"
            else -> "${s / 86400}d ago"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            webFrame?.visibility == View.VISIBLE -> {
                val wv = webView
                if (wv != null && wv.canGoBack()) wv.goBack() else webFrame?.visibility = View.GONE
            }
            inboxScreen?.visibility == View.VISIBLE -> inboxScreen?.visibility = View.GONE
            settingsPanel?.translationX == 0f -> toggleSettings(false)
            drawerPanel?.translationX == 0f -> toggleDrawer(false)
            else -> moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Solver.detach()
    }
}
