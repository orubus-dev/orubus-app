package app.orubus.ai

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orubus AI for Android — v3.5.5.
 *
 * The whole surface is native Kotlin — zero WebViews — and it wears the
 * exact UI the web app wears (public/chat.html ported view by view): cream
 * paper, ink borders, hard offset shadows, yolk user bubbles, plain-text
 * answers, QUICK/CHAT/DEEP pills, the actions sheet.
 *
 * Chatting is ONLINE and shares memory with every other client: the wallet,
 * the soul and the archive live on the same /api/chat the web uses; the AI
 * turn itself runs on the device with the user's own IP (the request engine),
 * so no shared lane ever packs. Errors speak like Orubus — warm, human,
 * never a raw engine dump.
 *
 * v3.5.8: real photo messages — the picked image renders INSIDE the bubble,
 * taps open a full-screen pinch-zoom viewer, and the answer relays back on
 * what's actually in the picture. A dead connection no longer prints a red
 * apology: the bubble itself carries "network error" + a ↻ resend button,
 * the words sit in the outbox, and they fire the moment the lane returns.
 * Ads go fully server-sided: unit ids arrive at load time from our backend
 * (signed-build attestation), App Open ads ride the return with a 4h cap,
 * Firebase Analytics streams the console numbers.
 *
 * v3.7.0 — the hands: the AI paints (native image lane, 100 SU, partials
 * stream into the bubble), browses ON THIS SCREEN (agent-driven WebView the
 * user watches live, with a take-the-wheel pause), speaks LIVE (WebRTC
 * realtime session — real barge-in, the lane's own voice, legacy call as
 * fallback), keeps a Keystore-encrypted toolbox (files the AI creates,
 * reads and deploys), builds + deploys websites (the URL lands on the
 * message as a "View generated website" button), and speaks MCP (paste a
 * connector link, verify, its tools join the chat contract).
 */
class MainActivity : Activity() {

    internal lateinit var screen: FrameLayout
    internal lateinit var page: LinearLayout

    // ---- header ----
    internal var meterChip: TextView? = null
    internal var bonusChip: TextView? = null
    internal var gearBtn: FrameLayout? = null
    internal var inboxDot: View? = null

    // ---- chat ----
    internal var chatList: LinearLayout? = null
    internal var chatScroll: ScrollView? = null
    internal var chatInput: EditText? = null
    internal var sendBtn: FrameLayout? = null
    internal var dryBanner: TextView? = null
    internal var attachBtn: FrameLayout? = null
    internal var imgChip: LinearLayout? = null
    internal var pendingImg: String? = null
    internal var pendingImgFile: String? = null
    internal val pills = mutableListOf<TextView>()
    internal var tier = "standard"
    internal var sending = false
    internal var outboxBusy = false

    // ---- the voice call ----
    internal var calling = false
    internal var callTicker: Runnable? = null

    // ---- drawer (chats) ----
    internal var drawerPanel: LinearLayout? = null
    internal var drawerScrim: View? = null
    internal var chatsList: LinearLayout? = null
    internal var footName: TextView? = null
    internal var footGid: TextView? = null
    internal var footBal: TextView? = null
    internal var footBonus: TextView? = null
    internal var footFill: View? = null

    // ---- settings (half window) ----
    internal var settingsPanel: LinearLayout? = null
    internal var settingsScrim: View? = null
    internal var settingsInboxDot: View? = null
    internal var creditsRedraw: (() -> Unit)? = null

    // ---- detail screens + actions sheet ----
    internal var detailFrame: LinearLayout? = null
    internal var detailScrim: View? = null
    internal var detailTitle: TextView? = null
    internal var detailCont: LinearLayout? = null
    internal var sheetPanel: FrameLayout? = null
    internal var sheetScrim: View? = null
    internal var bannerSlot: FrameLayout? = null

    // ---- state ----
    @Volatile internal var stateJson: JSONObject? = null
    @Volatile internal var prelude = ""
    internal var built = false
    internal var adsReady = false
    private val pageCache = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.app = application
        val prefs = Store.prefs()
        Api.cookieValue = prefs.getString("cookie", "") ?: ""
        W.theme = W.themeFor(prefs.getString("theme", "") ?: "")
        installCrashNet()
        bars()
        applyWindowBg()

        screen = FrameLayout(this)
        setContentView(screen)
        Solver.ensure(this)
        watchLane() // the outbox drains itself the second the lane returns

        if (!prefs.getBoolean("legal_ok", false)) showLegalGate() else next()
    }

    /**
     * The crash net: every uncaught Throwable gets its signature written to
     * filesDir/crash.log BEFORE the system does what it does, and the NEXT
     * launch tells the human what happened in one honest line. A crash we
     * can see is a crash we can kill.
     */
    private fun installCrashNet() {
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                java.io.File(filesDir, "crash.log").writeText(
                    "${System.currentTimeMillis()}|${t.name}|${e.javaClass.name}|${e.stackTraceToString().take(3000)}",
                )
            } catch (_: Throwable) {}
            prior?.uncaughtException(t, e)
        }
        try {
            val f = java.io.File(filesDir, "crash.log")
            if (f.exists()) {
                val line = f.readText().lineSequence().firstOrNull().orEmpty()
                val cls = line.split("|").getOrNull(2)?.substringAfterLast('.') ?: ""
                f.delete()
                if (cls.isNotBlank()) lastCrashNote = "the last build hit a crash ($cls) — this one hardens that path ⚡"
            }
        } catch (_: Throwable) {}
    }

    /** Surfaced into the thread right after the UI settles (null = no crash). */
    internal var lastCrashNote: String? = null

    private fun next() {
        if (Api.cookieValue.isBlank()) showTokenScreen() else enterMain()
    }

    override fun onResume() {
        super.onResume()
        // back from anywhere: the App Open ad gets its (server-capped) moment
        maybeAppOpenAd()
        // the self-updater's news from the previous install (process dies mid-install)
        val up = getSharedPreferences("orubus_updates", 0)
        if (up.getBoolean("justUpdated", false)) {
            val v = up.getString("updatedTo", "") ?: ""
            up.edit().putBoolean("justUpdated", false).putString("updateFailed", null).apply()
            Toast.makeText(this, "updated to v$v — everything stays", Toast.LENGTH_SHORT).show()
        } else if (up.getString("updateFailed", null) != null) {
            val f = up.getString("updateFailed", null)
            up.edit().putString("updateFailed", null).apply()
            Toast.makeText(this, "update didn't complete — $f", Toast.LENGTH_LONG).show()
        }
        // quiet update check: speaks only when a newer build is actually out
        Updater.autoCheck(this)
    }

    // ---------- window chrome ----------

    private fun bars() {
        try {
            window.statusBarColor = W.theme.bg
            window.navigationBarColor = W.theme.bg
            val decor = window.decorView
            val light = if (W.theme == W.MIDNIGHT) 0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            decor.systemUiVisibility = light
        } catch (_: Exception) {}
    }

    internal fun applyWindowBg() {
        try { window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(W.theme.bg)) } catch (_: Exception) {}
    }

    // ---------- first run: the legal gate ----------

    private fun showLegalGate() {
        val text = TextView(this).apply {
            textSize = 13f
            setTextColor(W.theme.ink)
            typeface = W.regular(this@MainActivity)
            text = "warming up the terms…"
        }
        fetchPageText("/legal") { t ->
            text.text = if (t.isBlank())
                "the full terms live at orubus-ai.vercel.app/legal — by agreeing you accept them: Soul Units are a virtual balance with no cash value, the service comes as-is, and you keep it kind."
            else t
        }
        val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        scroll.addView(text)
        val holder = FrameLayout(this).apply { setPadding(dp(4), dp(2), dp(4), 0) }
        holder.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))
        orubusDialog(
            "Before you come in",
            "",
            "I AGREE", "LEAVE",
            bodyView = holder,
            cancelable = false,
            onPositive = {
                Store.prefs().edit().putBoolean("legal_ok", true).apply()
                next()
            },
            onNegative = { finishAffinity() }
        )
    }

    // ---------- first run: the GENJ token ----------

    @SuppressLint("SetTextI18n")
    private fun showTokenScreen() {
        built = false // a link from here always rebuilds the shell
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(W.theme.bg)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val logoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        logoRow.addView(W.label(this, "⚡ ORUBUS", 26f, W.theme.ink, heavy = 2))
        logoRow.addView(TextView(this).apply {
            text = "AI"; textSize = 15f; typeface = W.bold(this@MainActivity)
            setTextColor(W.theme.acc)
            background = GradientDrawable().apply { setColor(W.theme.ink); cornerRadius = dp(6).toFloat() }
            setPadding(dp(7), dp(2), dp(7), dp(2))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8); topMargin = dp(4) })

        val title = W.label(this, "LINK YOUR TELEGRAM", 19f, W.theme.ink, heavy = 2)
        title.gravity = Gravity.CENTER
        title.setPadding(0, dp(16), 0, dp(4))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = W.shadowed(this@MainActivity, W.theme.paper, 14, 3)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(W.label(this, "same wallet · same memory · one soul\neverywhere — the bots, the web and this app", 13.5f, W.theme.dim))
        card.addView(View(this).apply { setBackgroundColor(W.theme.ink) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(10); bottomMargin = dp(10) })
        card.addView(W.label(this, "1.  Open @orubus_bot in Telegram", 14f, W.theme.ink, heavy = 1))
        card.addView(W.label(this, "2.  Send /genjutsu", 14f, W.theme.ink, heavy = 1))
        card.addView(W.label(this, "3.  Paste the GENJ-XXXX code below", 14f, W.theme.ink, heavy = 1))

        val input = EditText(this).apply {
            hint = "GENJ-XXXX"; maxLines = 1; textSize = 16f
            setTextColor(W.theme.ink); setHintTextColor(W.theme.dim)
            typeface = W.medium(this@MainActivity)
            background = W.stroke(this@MainActivity, W.theme.paper, 10)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val btn = W.button(this, "LINK", W.theme.acc, W.theme.ink, radiusDp = 12)
        val status = W.label(this, "", 13f, W.theme.acc2)
        status.gravity = Gravity.CENTER
        status.setPadding(0, dp(12), 0, 0)

        val later = W.label(this, "no code yet — look around first, link later", 13f, W.theme.dim)
        later.gravity = Gravity.CENTER
        later.setPadding(0, dp(16), 0, 0)

        fun goLink() {
            val code = input.text.toString().trim().uppercase(java.util.Locale.US)
            if (code.length < 6) { status.text = "the code looks short — it's GENJ-XXXX"; return }
            btn.isEnabled = false; btn.text = "LINKING…"; status.text = ""
            Thread {
                try {
                    val (ok, err) = Api.link(code)
                    runOnUiThread {
                        btn.isEnabled = true; btn.text = "LINK"
                        if (ok) {
                            Store.prefs().edit().putString("cookie", Api.cookieValue).apply()
                            Toast.makeText(this, "linked — welcome in ⚡", Toast.LENGTH_SHORT).show()
                            enterMain()
                        } else status.text = err
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        btn.isEnabled = true; btn.text = "LINK"
                        status.text = "network hiccup — check the connection and try again"
                    }
                }
            }.start()
        }
        btn.setOnClickListener { goLink() }
        input.setOnEditorActionListener { _, _, _ -> goLink(); true }
        later.setOnClickListener { enterMain() }

        box.addView(logoRow)
        box.addView(title)
        box.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        box.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        box.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(later, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        screen.removeAllViews()
        screen.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ---------- main shell ----------

    internal fun enterMain() {
        if (built) { refreshAll(); return }
        built = true
        screen.removeAllViews()
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(W.theme.bg) }
        screen.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        buildHeader()
        buildChatArea()
        buildDry()
        buildComposer()
        buildDrawer()
        buildSettingsPanel()
        buildDetail()
        buildSheet()
        lastCrashNote?.let { pushSys(it); lastCrashNote = null }

        loadCurrentChat()
        // the welcome — friendly, warm, and it says exactly what this app is
        if (Store.currentChat().msgs.none { !it.system }) {
            chatList?.addView(sysView("hey — welcome in ⚡\neverything here stays in sync with the bots and the web — one soul, one memory, everywhere.\ntype anything, or try \"open shop\"."),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        // the last-known state paints FIRST — coins, name, models, inbox —
        // so a relaunch never means watching the wallet load again
        paintCachedState()
        refreshAll()
        DuckEngine.warm() // the lane warms before the first message, not during it
        checkForUpdate()
    }

    /**
     * The disk-cached state: wallet, identity, models, prelude — rendered
     * before the network has a chance to blink. The live refresh replaces
     * it seconds later (and re-caches it for next launch).
     */
    internal fun paintCachedState() {
        if (Api.cookieValue.isBlank()) return
        val st = Store.loadState() ?: return
        stateJson = st
        st.optJSONObject("app")?.optJSONObject("models")?.let { mm ->
            val map = mutableMapOf<String, String>()
            for (k in listOf("lite", "standard", "pro")) {
                val v = mm.optString(k, "")
                if (v.isNotBlank()) map[k] = v
            }
            if (map.isNotEmpty()) DuckEngine.models = map
        }
        prelude = st.optString("prelude", "")
        st.optJSONObject("wallet")?.let { renderWallet(it) }
        footGid?.text = st.optString("gid", "").ifBlank { "linked" }
        val items = st.optJSONArray("announcements") ?: JSONArray()
        val lastSeen = Store.prefs().getLong("annSeen", 0)
        var unread = false
        for (i in 0 until items.length()) if (items.getJSONObject(i).optLong("ts", 0) > lastSeen) unread = true
        inboxDot?.visibility = if (unread) View.VISIBLE else View.GONE
        settingsInboxDot?.visibility = if (unread) View.VISIBLE else View.GONE
    }

    internal fun dp(v: Int): Int = W.dp(this, v)

    // ---------- header ----------

    @SuppressLint("SetTextI18n")
    private fun buildHeader() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(12), dp(9))
        }

        val logoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // the bolt is gone from the top left — the wordmark stands on its own
        logoRow.addView(W.label(this, "ORUBUS", 16f, W.theme.ink, heavy = 2))
        logoRow.addView(TextView(this).apply {
            text = "AI"; textSize = 10.5f; typeface = W.bold(this@MainActivity)
            setTextColor(W.theme.acc)
            background = GradientDrawable().apply { setColor(W.theme.ink); cornerRadius = dp(6).toFloat() }
            setPadding(dp(6), dp(1), dp(6), dp(1))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(7) })
        bar.addView(logoRow)

        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        meterChip = TextView(this).apply {
            textSize = 12f; typeface = W.bold(this@MainActivity); setTextColor(W.theme.ink)
            background = W.shadowed(this@MainActivity, W.theme.paper, 99, 2)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            maxWidth = (resources.displayMetrics.widthPixels * 0.22f).toInt()
            ellipsize = android.text.TextUtils.TruncateAt.END
            setSingleLine(true)
        }
        bar.addView(meterChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(7) })

        bonusChip = TextView(this).apply {
            textSize = 12f; typeface = W.bold(this@MainActivity); setTextColor(W.theme.ink)
            background = W.shadowed(this@MainActivity, W.theme.pop, 99, 2)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        bar.addView(bonusChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(9) })

        // the three top-right buttons — the waveform opens the call, then
        // the chats drawer, then settings. 36dp drawn-icon targets, room to breathe.
        val wave = W.iconBtn(this, W.Icon.WAVE)
        wave.setOnClickListener { startVoiceCall() }
        bar.addView(wave, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) })

        val burger = W.iconBtn(this, W.Icon.MENU)
        burger.setOnClickListener { toggleDrawer(true) }
        bar.addView(burger, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) })

        val gearWrap = FrameLayout(this)
        gearBtn = W.iconBtn(this, W.Icon.GEAR)
        gearBtn!!.setOnClickListener { toggleSettings(true) }
        gearWrap.addView(gearBtn, FrameLayout.LayoutParams(dp(36), dp(36)))
        inboxDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(W.theme.acc2) }
        }
        gearWrap.addView(inboxDot, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END).apply { topMargin = dp(-2); marginEnd = dp(-2) })
        inboxDot?.visibility = View.GONE
        bar.addView(gearWrap)

        page.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        page.addView(View(this).apply { setBackgroundColor(W.theme.ink) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
    }

    // ---------- chat area ----------

    private fun buildChatArea() {
        chatScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }
        chatList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(12))
        }
        chatScroll!!.addView(chatList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        page.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildDry() {
        dryBanner = TextView(this).apply {
            textSize = 13f; typeface = W.bold(this@MainActivity); gravity = Gravity.CENTER
            setTextColor(W.theme.paper)
            background = W.shadowed(this@MainActivity, W.theme.acc2, 12, 2)
            setPadding(dp(13), dp(9), dp(13), dp(9))
            text = "🔋 0 units left today — watch an ad or grab a pack in the shop. Refills tomorrow."
        }
        dryBanner?.visibility = View.GONE
        page.addView(dryBanner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14); marginEnd = dp(14); bottomMargin = dp(8) })
    }

    private fun buildComposer() {
        val composer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        composer.addView(View(this).apply { setBackgroundColor(W.theme.ink) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(10))
        }

        val tiers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((t, label) in listOf("lite" to "QUICK", "standard" to "CHAT", "pro" to "DEEP")) {
            val chip = W.pill(this, label, t == tier)
            chip.setOnClickListener { selectTier(t) }
            chip.tag = t
            pills.add(chip)
            tiers.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(7) })
        }
        inner.addView(tiers)

        // the image chip — appears when a photo is riding the next message
        imgChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = W.shadowed(this@MainActivity, W.theme.pop, 99, 2)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        val chipText = TextView(this).apply {
            textSize = 12f; typeface = W.bold(this@MainActivity); setTextColor(W.theme.ink)
        }
        chipText.tag = "text"
        imgChip!!.addView(chipText)
        imgChip!!.addView(TextView(this).apply {
            text = "  ✕"; textSize = 12f; typeface = W.bold(this@MainActivity); setTextColor(W.theme.acc2)
            setOnClickListener { clearPendingImage() }
        })
        inner.addView(imgChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })

        val sendrow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        attachBtn = W.iconBtn(this, W.Icon.IMAGE, W.theme.paper)
        attachBtn!!.setOnClickListener { pickImage() }
        sendrow.addView(attachBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(7) })

        // the hands — paint, browse, toolbox, connectors
        val toolsBtn = W.iconBtn(this, W.Icon.GLOBE, W.theme.paper)
        toolsBtn.setOnClickListener { openToolsMenu(toolsBtn) }
        sendrow.addView(toolsBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(7) })

        chatInput = EditText(this).apply {
            hint = "Message Orubus…"; textSize = 14.5f; maxLines = 4
            setTextColor(W.theme.ink); setHintTextColor(W.theme.dim)
            typeface = W.regular(this@MainActivity)
            background = W.stroke(this@MainActivity, W.theme.paper, 13)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            minHeight = dp(44)
        }
        sendrow.addView(chatInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        sendBtn = W.iconBtn(this, W.Icon.SEND)
        sendBtn!!.setOnClickListener { sendChat() }
        sendrow.addView(sendBtn, LinearLayout.LayoutParams(dp(52), dp(44)).apply { marginStart = dp(7) })
        inner.addView(sendrow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        composer.addView(inner)
        page.addView(composer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        sendBtn!!.alpha = 0.45f
        chatInput?.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { sendBtn?.alpha = if (s.isNullOrBlank() && pendingImg == null) 0.45f else 1f }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    internal fun imageCost(): Int = try {
        stateJson?.optJSONObject("app")?.optInt("imageCost", 20) ?: 20
    } catch (_: Exception) { 20 }

    internal fun setPendingImage(base64: String) {
        pendingImg = base64
        val chip = imgChip ?: return
        (chip.findViewWithTag("text") as? TextView)?.text = "🖼 image attached · ${imageCost()} SU"
        chip.visibility = View.VISIBLE
        sendBtn?.alpha = 1f
    }

    internal fun clearPendingImage() {
        pendingImg = null
        imgChip?.visibility = View.GONE
        sendBtn?.alpha = if (chatInput?.text.isNullOrBlank()) 0.45f else 1f
    }

    internal fun selectTier(t: String) {
        tier = t
        for (c in pills) {
            val sel = c.tag == t
            c.setTextColor(if (sel) W.theme.acc else W.theme.ink)
            c.background = W.shadowed(this, if (sel) W.theme.ink else W.theme.paper, 99, 2)
        }
    }

    // ---------- bubbles (the web's exact bubble CSS) ----------

    private fun bubble(text: String, kind: String): TextView {
        val tv = TextView(this).apply {
            this.text = if (text.length > 4000) text.take(4000) + "…" else text
            textSize = 14f
            typeface = W.regular(this@MainActivity)
            setPadding(dp(13), dp(9), dp(13), dp(9))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            when (kind) {
                "u" -> {
                    setTextColor(W.theme.ink)
                    background = W.bubbleShape(this@MainActivity, W.theme.acc, true)
                }
                "e" -> {
                    setTextColor(W.theme.paper)
                    typeface = W.medium(this@MainActivity)
                    background = W.bubbleShape(this@MainActivity, W.theme.acc2, false)
                }
                else -> {
                    setTextColor(W.theme.ink)
                    background = null
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    maxWidth = Int.MAX_VALUE
                }
            }
        }
        return tv
    }

    internal fun pushUser(text: String, img: String = ""): View {
        hideHello()
        val row: View = if (img.isNotBlank()) {
            // a real photo message — the image INSIDE the bubble column, tap
            // opens the full-screen viewer, the words ride under it
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            val w = (resources.displayMetrics.widthPixels * 0.62f).toInt()
            val bmp = Store.loadImage(img)
            if (bmp != null) {
                val iv = ImageView(this).apply {
                    val scale = Math.min(1f, w.toFloat() / bmp.width)
                    layoutParams = LinearLayout.LayoutParams(w, (bmp.height * scale).toInt().coerceAtLeast(dp(80)))
                    adjustViewBounds = true
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                        }
                    }
                    background = W.stroke(this@MainActivity, W.theme.acc, 14, 2)
                    setImageBitmap(bmp)
                    setOnClickListener { showImageFullScreen(img) }
                }
                col.addView(iv)
            }
            if (text.isNotBlank()) col.addView(bubble(text, "u"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            col
        } else bubble(text, "u")
        attachMsgMenu(row, mine = true, text = text)
        chatList?.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END; topMargin = dp(6)
        })
        scrollEnd()
        return row
    }

    internal fun pushBot(text: String, think: String = ""): TextView {
        hideHello()
        val tv = bubble(text, "a")
        attachMsgMenu(tv, mine = false, text = text, think = think)
        chatList?.addView(tv)
        if (think.isNotBlank()) {
            // the thinking stays one tap away — a quiet chip under the answer
            val chip = TextView(this).apply {
                this.text = "💭 my thinking"; textSize = 11.5f
                setTextColor(W.theme.dim); typeface = W.medium(this@MainActivity)
                setPadding(dp(2), dp(3), dp(2), dp(3))
                W.press(this)
            }
            chip.setOnClickListener { showThinking("what went through me", think) }
            chatList?.addView(chip)
        }
        scrollEnd()
        return tv
    }

    internal fun pushErr(text: String): TextView {
        // the AI's own voice — plain on the paper like every answer on the web,
        // garnet ink instead of a red box. No boxes on this side, ever.
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13.5f
            setTextColor(W.theme.acc2)
            typeface = W.medium(this@MainActivity)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        chatList?.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scrollEnd()
        return tv
    }

    internal fun sysView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(W.theme.dim)
        typeface = android.graphics.Typeface.create(W.regular(this@MainActivity), android.graphics.Typeface.ITALIC)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        gravity = Gravity.CENTER
    }

    internal fun pushSys(text: String): TextView {
        hideHello()
        val tv = sysView(text)
        chatList?.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(4) })
        scrollEnd()
        return tv
    }

    /**
     * The network-failed row — a bubble's honest little tail: "network error"
     * and a ↻ button. No red walls, no lost words: the message already sits
     * in the outbox, the button fires it NOW, and the lane watcher fires it
     * the moment the connection is back either way.
     */
    internal fun failRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(3), dp(2), dp(3))
        }
        row.addView(TextView(this).apply {
            text = "network error"
            textSize = 11.5f
            setTextColor(W.theme.acc2)
            typeface = W.medium(this@MainActivity)
            setPadding(dp(2), 0, dp(7), 0)
        })
        val btn = W.iconBtn(this, W.Icon.RESEND, W.theme.paper)
        btn.layoutParams = LinearLayout.LayoutParams(dp(27), dp(27))
        btn.setOnClickListener {
            it.isEnabled = false
            Toast.makeText(this, "sending the queued one…", Toast.LENGTH_SHORT).show()
            drainOutbox()
        }
        row.addView(btn)
        return row
    }

    /** Bubble + fail row together — how a queued message repaints after a relaunch. */
    internal fun pushFailed(text: String): LinearLayout {
        hideHello()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        if (text.isNotBlank()) {
            val tv = bubble(text, "u")
            attachMsgMenu(tv, mine = true, text = text)
            box.addView(tv)
        }
        box.addView(failRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        chatList?.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        scrollEnd()
        return box
    }

    /**
     * The typing indicator — three dots with a heartbeat, not a static
     * ellipsis. When deep research fires the caption beside it reads
     * "thinking…" and the whole row is tappable: the interpretation lives
     * in there, exactly where the user expects to peek at it.
     */
    internal fun typing(): FrameLayout {
        hideHello()
        val row = FrameLayout(this).apply { setPadding(dp(2), dp(6), dp(2), dp(6)) }
        val dotsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dotsRow.addView(ThinkingDots(this, W.theme.ink), FrameLayout.LayoutParams(dp(34), dp(14)))
        val cap = TextView(this).apply {
            text = ""; textSize = 11.5f; setTextColor(W.theme.dim)
            typeface = W.medium(this@MainActivity)
        }
        cap.tag = "cap"
        dotsRow.addView(cap)
        row.addView(dotsRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.tag = "think:"
        row.setOnClickListener {
            val t = (row.tag as? String)?.removePrefix("think:").orEmpty()
            showThinking(
                if (t.isBlank()) "still forming"
                else "what went through me",
                t.ifBlank { "the interpretation lands here the second it forms — right now every lane is just working on your words." }
            )
        }
        chatList?.addView(row)
        scrollEnd()
        return row
    }

    internal fun markThinking(dots: FrameLayout?, thinking: String) {
        dots?.tag = "think:$thinking"
        (dots?.findViewWithTag("cap") as? TextView)?.text = " thinking…"
    }

    internal fun showThinking(title: String, body: String) {
        orubusDialog(title, body, "GOT IT")
    }

    internal fun removeView(v: View?) { if (v != null) chatList?.removeView(v) }

    internal fun scrollEnd() {
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hello(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(26), dp(8), dp(26))
        }
        val b = W.label(this, "⚡ Say something.", 15f, W.theme.ink, heavy = 2)
        b.gravity = Gravity.CENTER
        box.addView(b)
        val sub = W.label(this, "I remember you across Telegram and this app —\nyour conversation stays in sync right here.", 13.5f, W.theme.dim)
        sub.gravity = Gravity.CENTER
        sub.setPadding(0, dp(6), 0, 0)
        box.addView(sub)
        return box
    }

    internal fun hideHello() {
        val l = chatList ?: return
        if (l.tag == "hello") { l.tag = null; l.removeAllViews() }
    }

    internal fun loadCurrentChat() {
        val list = chatList ?: return
        list.removeAllViews()
        val chat = Store.currentChat()
        val msgs = chat.msgs.takeLast(80)
        if (msgs.isEmpty()) {
            list.tag = "hello"
            list.removeAllViews()
            list.addView(hello(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            for (m in msgs) {
                when {
                    m.system -> pushSys(m.text)
                    m.mine -> pushUser(m.text, m.img)
                    m.site.isNotBlank() -> pushBotSite(m.text, m.site)
                    m.img.isNotBlank() -> pushBotImage(m.text, m.img)
                    else -> pushBot(m.text, m.think)
                }
            }
        }
        // words still waiting in the outbox for THIS thread — repaint them as
        // the honest "network error ↻" pair, not a mystery silence
        val queued = Store.outbox().firstOrNull { it.chatId == chat.id }
        if (queued != null && msgs.isNotEmpty()) pushFailed(queued.text)
    }

    // ---------- long-press menus (anchored where the finger held) ----------

    /**
     * A deliberate press — never a tap, never a scroll — opens the message
     * menu AT the finger: copy, edit prompt (own words), branch the chat and
     * my thinking (my answers). The popup is a house card, clamped to the
     * screen, and it only ever appears from a real long-press, so nothing is
     * ever mistakenly clickable.
     */
    internal fun attachMsgMenu(v: View, mine: Boolean, text: String, think: String = "") {
        val touch = floatArrayOf(0f, 0f)
        val off = W.dpf(this, 2)
        v.setOnTouchListener { vv, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touch[0] = ev.rawX; touch[1] = ev.rawY
                    vv.animate().translationX(off).translationY(off).setDuration(40).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    vv.animate().translationX(0f).translationY(0f).setDuration(40).start()
            }
            false // never consume — long-click still fires
        }
        v.setOnLongClickListener {
            try { v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Exception) {}
            val items = mutableListOf<Triple<String, Boolean, () -> Unit>>() // label, danger, action
            items.add(Triple("Copy", false) {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("orubus", text))
                Toast.makeText(this, "copied ⚡", Toast.LENGTH_SHORT).show()
            })
            if (mine) {
                items.add(Triple("Edit prompt", false) {
                    chatInput?.setText(text)
                    chatInput?.requestFocus()
                    chatInput?.setSelection(text.length)
                    pushSys("prompt loaded — shape it and send it again")
                })
            } else {
                if (think.isNotBlank()) {
                    items.add(Triple("My thinking", false) { showThinking("what went through me", think) })
                }
                items.add(Triple("Branch from here", false) {
                    val chat = Store.currentChat()
                    val idx = chat.msgs.indexOfFirst { !it.system && !it.mine && it.text == text }
                    if (idx >= 0) {
                        Store.branchChat(chat, idx)
                        loadCurrentChat()
                        pushSys("branched — this thread forks from here, the original stays untouched")
                    }
                })
            }
            showFingerMenu(v, touch[0], touch[1], items)
            true
        }
    }

    /** The menu card itself — house style, pinned to the touch point. */
    internal fun showFingerMenu(anchor: View, rawX: Float, rawY: Float, items: List<Triple<String, Boolean, () -> Unit>>) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((label, danger, action) in items) {
            val row = TextView(this).apply {
                this.text = label; textSize = 13.5f; typeface = W.medium(this@MainActivity)
                setTextColor(if (danger) W.theme.acc2 else W.theme.ink)
                background = W.stroke(this@MainActivity, W.theme.paper, 8)
                setPadding(dp(16), dp(11), dp(16), dp(11))
                W.press(this)
            }
            row.setOnClickListener {
                popup?.dismiss()
                action()
            }
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = if (col.childCount == 0) 0 else dp(6)
            })
        }
        val card = FrameLayout(this).apply {
            background = W.shadowed(this@MainActivity, W.theme.paper, 12, 3)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        card.addView(col)
        val pw = PopupWindow(card, dp(190), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup = pw
        // anchor at the finger, clamped so the card never leaves the screen
        val dm = resources.displayMetrics
        var x = (rawX - dp(95)).toInt()
        x = Math.max(dp(8), Math.min(x, dm.widthPixels - dp(198)))
        val cardH = items.size * dp(48) + dp(24)
        var y = (rawY + dp(10)).toInt()
        y = Math.min(y, Math.max(dp(8), dm.heightPixels - cardH - dp(24)))
        try { pw.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y) } catch (_: Exception) {}
    }

    internal var popup: PopupWindow? = null

    // ---------- the photo lane ----------

    internal fun pickImage() {
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
            i.type = "image/*"
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE)
            startActivityForResult(android.content.Intent.createChooser(i, "ride a photo"), 7725)
        } catch (_: Exception) {
            Toast.makeText(this, "couldn't open the gallery", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7725 || resultCode != RESULT_OK || data?.data == null) return
        Thread {
            val bmp = decodeSampled(data.data!!)
            runOnUiThread {
                if (bmp == null) {
                    Toast.makeText(this, "that image didn't load — try another", Toast.LENGTH_SHORT).show()
                } else {
                    // the real photo message: the image lands on disk (the
                    // bubble renders it), the base64 twin rides the turn so
                    // the answer can actually see it
                    pendingImgFile = Store.saveImage(bmp)
                    setPendingImage(encodeB64(bmp))
                    bmp.recycle()
                }
            }
        }.start()
    }

    /** Downscale the picked photo for both the bubble file and the vision turn. */
    private fun decodeSampled(uri: android.net.Uri): android.graphics.Bitmap? {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) { null }
    }

    private fun encodeB64(bmp: android.graphics.Bitmap): String = try {
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    } catch (_: Exception) { "" }

    // ---------- the photo viewer ----------

    /**
     * Full-screen viewer — tap a photo in a bubble and it owns the screen:
     * pinch to zoom, drag to pan, double-tap to snap back, ✕ (or back) to
     * leave. Exactly how a real attachment behaves.
     */
    internal fun showImageFullScreen(name: String) {
        val bmp = Store.loadImage(name) ?: run {
            Toast.makeText(this, "that image is gone from this device", Toast.LENGTH_SHORT).show()
            return
        }
        val overlay = FrameLayout(this).apply { setBackgroundColor(0xEE141414.toInt()) }
        val zoom = ZoomImage(this)
        zoom.setImageBitmap(bmp)
        // long-press in the viewer too — save/share/edit, same as the bubble
        zoom.setOnLongClickListener {
            val items = mutableListOf<Triple<String, Boolean, () -> Unit>>(
                Triple("Save to gallery", false) { ImageGen.saveToGallery(this, name) },
                Triple("Share", false) { ImageGen.shareImage(this, name) },
                Triple("Edit in paint", false) { openImageGenSheet(prefill = "") },
            )
            showFingerMenu(it, resources.displayMetrics.widthPixels / 2f, resources.displayMetrics.heightPixels / 3f, items)
            true
        }
        overlay.addView(zoom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val close = W.iconBtn(this, W.Icon.CLOSE, W.theme.paper)
        close.setOnClickListener { screen.removeView(overlay) }
        overlay.addView(close, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(14), dp(14), 0) })
        screen.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        imageViewer = overlay
    }

    internal var imageViewer: FrameLayout? = null

    // ---------- sending: intents first, then the deep pass, then the on-device engine ----------

    internal fun sendChat() {
        if (sending) return
        val text = chatInput?.text?.toString()?.trim().orEmpty()
        val img = pendingImg
        val imgFile = pendingImgFile
        if (text.isEmpty() && img == null) return
        chatInput?.setText("")
        clearPendingImage()
        pendingImgFile = null
        val chat = Store.currentChat()
        pushUser(text, imgFile ?: "")
        Store.append(chat, true, text, img = imgFile ?: "")

        if (img == null) {
            val intent = Intents.match(text)
            if (intent != null) { runIntent(intent); return }
        }

        runTurn(chat, text, imgBase64 = img)
    }

    /**
     * One billed turn — the full ladder, exactly as it must feel:
     *
     *   linked  → appturn (the wallet, image charges included)
     *           → appresearch (Deep Research v4.8: invisible, passive,
     *             server-sided; when it produces, its answer IS the turn
     *             and its thinking is tappable on the dots)
     *           → the device duck lane on the user's own steady IP
     *           → the server's brain as the last resort
     *   offline → the turn is CACHED in the outbox and fires itself the
     *             second the lane is back (marked as resent)
     *
     * `skipAppend` = the user's words are already in the store (outbox
     * resend); `silent` = the voice call is driving (no bubbles, the caller
     * speaks the answer); `fromOutbox` marks a drain pass.
     */
    internal fun runTurn(
        chat: Store.Chat,
        text: String,
        imgBase64: String? = null,
        skipAppend: Boolean = false,
        silent: Boolean = false,
        fromOutbox: Boolean = false,
        onAnswer: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        if (sending) return
        if (!DuckEngine.netUp()) {
            if (!fromOutbox && !silent) {
                Store.enqueueOutbox(chat.id, text, imgBase64 != null)
                // the bubble is already on the paper — it gets its honest
                // tail: "network error" + ↻, and the watcher fires it later
                chatList?.addView(failRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
                scrollEnd()
            }
            onError?.invoke("offline")
            return
        }
        sending = true
        sendBtn?.isEnabled = false
        val dots = if (silent) null else typing()
        Thread {
            val linked = Api.cookieValue.isNotBlank()
            var token = ""
            try {
                if (linked) {
                    val spend = Api.appTurn(text, tier, if (imgBase64 != null) 1 else 0)
                    if (!spend.ok) {
                        runOnUiThread {
                            removeView(dots)
                            if (silent) onError?.invoke(spend.error) else {
                                pushErr(spend.error)
                                spend.wallet?.let { renderWallet(it) }
                            }
                        }
                        sending = false
                        runOnUiThread { sendBtn?.isEnabled = true }
                        return@Thread
                    }
                    token = spend.token
                    spend.wallet?.let { runOnUiThread { renderWallet(it) } }
                }

                var answer: String? = null
                var thinking = ""
                // Deep Research v4.8 + Intent Sensing v1.5 — invisible and
                // passive: the server reads the intent first, and only a
                // message that needs what's happening right now gets the
                // deep pass (charged 7 SU only when it actually produces).
                if (linked && imgBase64 == null) {
                    try {
                        val r = Api.appResearch(text, tier)
                        r.wallet?.let { runOnUiThread { renderWallet(it) } }
                        if (r.research && r.answer.isNotBlank()) {
                            answer = r.answer
                            thinking = r.thinking
                            if (dots != null) runOnUiThread { markThinking(dots, thinking) }
                        }
                    } catch (_: Exception) {
                        // the sensor is best-effort — the lane continues
                    }
                }

                if (answer == null) {
                    // the hands — MCP connectors + the encrypted toolbox speak
                    // the same one-line tool contract; the loop is capped and
                    // every hop narrates on the typing row. The gate opens for
                    // EVERY linked text turn: the toolbox's built-in hands
                    // (create_file/deploy_site…) exist even on a fresh install
                    // — checking ToolRig.available() first (MCP-only) is what
                    // sealed them off and made "deploy" do nothing in v3.7.0.
                    if (linked && imgBase64 == null) {
                        try {
                            answer = ToolRig.toolLoop(chat, text, prelude, DuckEngine.modelFor(tier), { step ->
                                runOnUiThread {
                                    if (dots != null) (dots.findViewWithTag("cap") as? TextView)?.text = " $step"
                                }
                            }, { n, url ->
                                runOnUiThread {
                                    pushBotSite("your site is live ⚡", url)
                                    Store.append(chat, false, "your site is live ⚡", site = url)
                                }
                            })
                        } catch (de: DuckEngine.NetworkDeadException) {
                            throw de
                        } catch (_: Exception) {
                            answer = null // the plain lane takes it from here
                        }
                    }
                }

                if (answer == null) {
                    val turns = Store.turnsOf(chat)
                    val pre = prelude.ifBlank { "You are Orubus — a sharp, warm, slightly playful AI companion. Keep replies tight and human." }
                    val msgs = DuckEngine.buildMessages(pre, turns, imgBase64)
                    answer = try {
                        DuckEngine.chat(msgs, DuckEngine.modelFor(tier))
                    } catch (de: Exception) {
                        // the device lane slipped — the units go straight back first,
                        // then the server takes the turn, so chatting never dies:
                        // same memory, same soul, one more hop. Pictures included
                        // now — the server's own vision lane understands the photo
                        // when the device lane can't.
                        if (de is DuckEngine.NetworkDeadException) throw de
                        if (linked && token.isNotBlank()) {
                            val tok = token
                            Thread { Api.appFail(tok) }.start()
                            token = ""
                        }
                        if (!linked) throw de
                        if (imgBase64 != null) {
                            val fb = Api.appImg(text, imgBase64, tier)
                            runOnUiThread { fb?.optJSONObject("wallet")?.let { renderWallet(it) } }
                            if (fb == null || !fb.optBoolean("ok", false)) {
                                throw Exception(fb?.optString("error", "the picture lane is catching its breath") ?: "the picture lane is catching its breath")
                            }
                            fb.optString("answer", "")
                        } else {
                            val fb = Api.post("msg", mapOf("text" to text, "tier" to tier))
                            runOnUiThread { fb.optJSONObject("wallet")?.let { renderWallet(it) } }
                            if (!fb.optBoolean("ok", false)) throw Exception(fb.optString("error", "the lane is catching its breath"))
                            fb.optString("answer", "")
                        }
                    }
                }
                val doneToken = token
                val finalAnswer: String = answer ?: ""
                if (linked && doneToken.isNotBlank()) Thread { Api.appDone(doneToken, finalAnswer) }.start()
                runOnUiThread {
                    removeView(dots)
                    if (!silent) {
                        if (fromOutbox) pushSys("↻ resent — the queued one just went out ⚡")
                        pushBot(finalAnswer, thinking)
                    }
                    Store.append(chat, false, finalAnswer, think = thinking)
                    onAnswer?.invoke(finalAnswer)
                }
            } catch (e: Exception) {
                val failToken = token
                if (linked && failToken.isNotBlank()) Thread { Api.appFail(failToken) }.start()
                val dead = e is DuckEngine.NetworkDeadException || isNetworkFailure(e) || !DuckEngine.netUp()
                runOnUiThread {
                    removeView(dots)
                    if (dead) {
                        // the lane died mid-turn: cache it, never punish the user —
                        // the bubble carries "network error" + a ↻ resend button
                        if (!fromOutbox) {
                            if (!skipAppend || !silent) {
                                Store.enqueueOutbox(chat.id, text, imgBase64 != null)
                                if (!silent) {
                                    chatList?.addView(failRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
                                    scrollEnd()
                                }
                            }
                        } else if (!silent) {
                            pushSys("still no lane — the message stays queued and fires the second the connection is back")
                        }
                        onError?.invoke("offline")
                    } else if (silent) {
                        onError?.invoke(laneError(e, linked))
                    } else {
                        pushErr(laneError(e, linked))
                    }
                }
            } finally {
                sending = false
                runOnUiThread {
                    sendBtn?.isEnabled = true
                    // keep the outbox moving — one turn at a time
                    if (outboxBusy) { outboxBusy = false; drainOutbox() }
                }
            }
        }.start()
    }

    /**
     * The outbox — words that outlived a dead connection. The lane watcher
     * fires this the second the network returns; each turn goes out with the
     * full ladder and paints with a visible "resent" mark.
     */
    internal fun drainOutbox() {
        if (calling || sending || outboxBusy) return
        val q = Store.peekOutbox() ?: return
        val chat = Store.chats().firstOrNull { it.id == q.chatId } ?: Store.currentChat()
        val sameChat = chat.id == Store.currentChatId()
        if (chat.msgs.none { it.mine && it.text == q.text }) {
            // the words never made it into this thread's log — restore them
            if (sameChat) pushUser(q.text)
            Store.append(chat, true, q.text)
        }
        Store.dropOutboxFirst()
        if (sameChat && q.img) {
            pushSys("the image couldn't wait out the dead connection — the words went through alone")
        }
        outboxBusy = true
        runTurn(chat, q.text, imgBase64 = null, skipAppend = true, silent = !sameChat, fromOutbox = true)
    }

    /**
     * The lane watcher: the moment ANY network comes back, the outbox
     * drains itself — no user input, the queued words simply go out.
     */
    internal fun watchLane() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    runOnUiThread { drainOutbox() }
                }
            })
        } catch (_: Exception) { /* the outbox still drains on next launch */ }
    }

    /** Orubus voice for engine hiccups — warm, human, zero engine internals. */
    private fun laneError(e: Exception, linked: Boolean): String {
        val m = e.message ?: ""
        val base = when {
            m.contains("busy", true) || m.contains("catching", true) ->
                "the lane's catching its breath — give it a few seconds and go again"
            m.contains("timed out", true) || m.contains("timeout", true) || m.contains("TimeoutException", true) ->
                "that one took too long and slipped away — try again"
            m.contains("solver", true) ->
                "the lane is still waking up on this network — one more try usually lands it"
            else -> "the lane slipped for a second — try that again"
        }
        return if (linked) "$base — your units went straight back" else base
    }

    /**
     * Is this failure actually the NETWORK (vs the lane having a bad second)?
     * A WiFi icon can lie — the DNS can be dead while the radio reports up —
     * so anything that smells like an unreachable wire counts as offline:
     * those turns belong in the outbox with a ↻ on them, never a red apology.
     */
    private fun isNetworkFailure(e: Throwable): Boolean {
        var c: Throwable? = e
        var hops = 0
        while (c != null && hops < 6) {
            if (c is java.net.UnknownHostException ||
                c is java.net.ConnectException ||
                c is java.net.SocketTimeoutException ||
                c is java.net.SocketException ||
                c is javax.net.ssl.SSLException
            ) return true
            val m = (c.message ?: "").lowercase()
            if (m.contains("unable to resolve host") ||
                m.contains("failed to connect") ||
                m.contains("econnrefused") ||
                m.contains("econnreset") ||
                m.contains("enetworkunreachable") ||
                m.contains("network is unreachable") ||
                m.contains("connection reset") ||
                m.contains("no lane — the device is offline")
            ) return true
            c = c.cause
            hops++
        }
        return false
    }

    // ---------- intents ----------

    internal fun runIntent(r: Intents.Result) {
        when (r.kind) {
            Intents.Kind.SHOP -> openDetail("shop")
            Intents.Kind.REQUESTS -> openDetail("requests")
            Intents.Kind.LEGAL -> openDetail("legal")
            Intents.Kind.ABOUT -> openDetail("about")
            Intents.Kind.INBOX -> openDetail("inbox")
            Intents.Kind.SETTINGS -> toggleSettings(true)
            Intents.Kind.NEWCHAT -> {
                val c = Store.openFreshChat()
                loadCurrentChat()
                if (c.msgs.isEmpty()) pushSys("fresh chat — new thread, same soul")
                chatTitleBump(c.name)
            }
            Intents.Kind.CHATS -> toggleDrawer(true)
            Intents.Kind.UNITS -> {
                val w = stateJson?.optJSONObject("wallet")
                if (w != null) pushSys(renderWalletText(w))
                else pushSys(if (Api.cookieValue.isBlank()) "link first — the wallet lives with your telegram identity (⚙ → identity)" else "wallet loading…")
            }
            Intents.Kind.ID -> {
                val gid = stateJson?.optString("gid", "") ?: ""
                pushSys(if (gid.isNotBlank()) "you are $gid — one identity across the bots, the web and this app"
                    else if (Api.cookieValue.isBlank()) "not linked yet — paste a GENJ code (⚙ → identity)" else "identity loading…")
            }
            Intents.Kind.REMEMBER, Intents.Kind.FORGET, Intents.Kind.SUMMARY, Intents.Kind.SOUL -> serverCommand(r.serverCommand ?: return)
            Intents.Kind.DOWNLOAD -> pushSys("you're holding it — this IS the app. share it: ${Api.APK_URL}")
            Intents.Kind.HELP -> pushSys("try: \"draw an image of …\" · \"browse https://…\" · \"build me a site and deploy it\" · \"open shop\" · \"new chat\" · \"how many credits\" · \"remember my name is …\" · or just talk — I'm all ears")
            Intents.Kind.LOGOUT -> confirmLogout()
            Intents.Kind.IMAGE -> if (r.arg.isBlank()) openImageGenSheet() else startImageGen(r.arg)
            Intents.Kind.BROWSE -> {
                val arg = r.arg.trim()
                val isUrl = arg.startsWith("http") || arg.startsWith("www.")
                if (isUrl) startAgentBrowse("browse ${arg.take(80)} and tell me what's there", arg)
                else startAgentBrowse(arg, "")
            }
            // DEPLOY is no longer an intent: "deploy my site" belongs to the
            // chat lane, where the AI builds the page and the tool loop ships
            // it — stealing the phrase for the toolbox sheet is exactly the
            // "I said deploy and nothing happened" bug.
        }
    }

    private fun chatTitleBump(name: String) { /* chats are named in the drawer — nothing on the bar */ }

    internal fun confirmLogout() {
        val name = stateJson?.optJSONObject("wallet")?.optString("name", "")?.takeIf { it.isNotBlank() } ?: "friend"
        orubusDialog(
            "Log out, $name?",
            "this ends the session everywhere — re-linking needs a fresh /genjutsu code.\nyour chats stay safe on this device, and your soul will be right here waiting.\nsee you soon ⚡",
            "LOG OUT", "STAY", danger = true,
            onPositive = {
                Thread {
                    try { Api.post("logout") } catch (_: Exception) {}
                    runOnUiThread {
                        Store.prefs().edit().remove("cookie").apply()
                        Api.cookieValue = ""
                        Store.clearState() // the cached wallet belongs to the session, not the device
                        built = false
                        screen.removeAllViews()
                        showTokenScreen()
                    }
                }.start()
            }
        )
    }

    /**
     * The house dialog — the same neo-brutalist card the whole app wears:
     * paper on ink stroke, hard offset shadow, heavy title, pill buttons.
     * No plain white system boxes anywhere in this app.
     */
    internal fun orubusDialog(
        title: String,
        body: String,
        positive: String,
        negative: String? = null,
        danger: Boolean = false,
        bodyView: View? = null,
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null,
        cancelable: Boolean = true,
    ): AlertDialog {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = W.shadowed(this@MainActivity, W.theme.bg, 16, 3)
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }
        card.addView(W.label(this, title, 17f, W.theme.ink, heavy = 2))
        if (body.isNotBlank()) card.addView(W.label(this, body, 13.5f, W.theme.dim).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(8), 0, 0)
        })
        if (bodyView != null) {
            card.addView(bodyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        val dlg = AlertDialog.Builder(this).create()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        if (negative != null) {
            val n = W.button(this, negative, W.theme.paper, W.theme.ink, radiusDp = 99, small = true)
            n.setOnClickListener {
                dlg.dismiss()
                onNegative?.invoke()
            }
            row.addView(n)
        }
        val p = W.button(this, positive, if (danger) W.theme.acc2 else W.theme.acc, if (danger) W.theme.paper else W.theme.ink, radiusDp = 99, small = true)
        p.setOnClickListener {
            dlg.dismiss()
            onPositive?.invoke()
        }
        row.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        card.addView(row)
        dlg.setView(card)
        dlg.setCancelable(cancelable)
        dlg.show()
        try {
            dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.86f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        } catch (_: Exception) {}
        return dlg
    }

    /** Free soul commands ride the server's web-command path (no SU). */
    internal fun serverCommand(cmd: String) {
        if (Api.cookieValue.isBlank()) {
            pushSys("link first — your soul lives with your telegram identity (⚙ → identity)")
            return
        }
        val dots = pushSys("…")
        Thread {
            try {
                val resp = Api.post("msg", mapOf("text" to cmd, "tier" to "standard"))
                val answer = if (resp.optBoolean("free", false)) resp.optString("answer", "") else resp.optString("error", "that one didn't land")
                runOnUiThread {
                    removeView(dots)
                    pushSys(answer.ifBlank { "…nothing came back" })
                    Store.append(Store.currentChat(), false, answer, true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    removeView(dots)
                    pushSys("network hiccup — check the connection and try again")
                }
            }
        }.start()
    }

    // ---------- the hands: composer tools menu + the site card ----------

    /** The ＋ lane: paint, browse, toolbox, connectors — one menu. */
    internal fun openToolsMenu(anchor: View) {
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val items = mutableListOf<Triple<String, Boolean, () -> Unit>>(
            Triple("🎨  Generate image", false) { openImageGenSheet() },
            Triple("🌐  Browse with me", false) { openBrowseDialog() },
            Triple("🧰  Toolbox", false) { openToolboxSheet() },
            Triple("🔗  Connectors", false) { openConnectorsSheet() },
        )
        showFingerMenu(anchor, loc[0] + anchor.width / 2f, loc[1] + anchor.height / 2f, items)
    }

    /** The browse brief — the tool popup: what to do out there, where to start. */
    internal fun openBrowseDialog() {
        val sheet = ToolSheet(this, "🌐", "BROWSE WITH ME", "I drive a real browser on this screen — you watch every step")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), 0) }
        fun field(hint: String, multi: Boolean = false): EditText = EditText(this).apply {
            this.hint = hint
            textSize = 14f
            maxLines = if (multi) 3 else 1
            setTextColor(W.theme.ink); setHintTextColor(W.theme.dim)
            typeface = W.regular(this@MainActivity)
            background = W.stroke(this@MainActivity, W.theme.paper, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val goal = field("what should I do out there?", multi = true)
        val url = field("start from (optional) https://…")
        box.addView(goal)
        box.addView(url, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        box.addView(W.button(this, "GO", W.theme.acc, W.theme.ink, radiusDp = 12).apply {
            setOnClickListener {
                val g = goal.text.toString().trim()
                if (g.isBlank()) { sheet.status("tell me what to do out there first ✍"); return@setOnClickListener }
                sheet.dismiss()
                startAgentBrowse(g, url.text.toString().trim())
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        sheet.body.addView(box)
        sheet.show()
    }

    /** The deployed-site card — the "View generated website" button. */
    internal fun pushBotSite(text: String, site: String) {
        hideHello()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (text.isNotBlank()) col.addView(bubble(text, "a"))
        val url = site.trim()
        val name = try { java.net.URI(url).host?.removePrefix("www.") ?: url } catch (_: Exception) { url }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = W.shadowed(this@MainActivity, W.theme.paper, 12, 2)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(W.label(this, "🧱 $name", 14f, W.theme.ink, heavy = 2))
        card.addView(W.label(this, url, 11f, W.theme.dim).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0, dp(2), 0, dp(7))
        })
        card.addView(W.button(this, "VIEW GENERATED WEBSITE", W.theme.acc, W.theme.ink, radiusDp = 10, small = true).apply {
            setOnClickListener {
                try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "no browser opened — the link is $url", Toast.LENGTH_LONG).show()
                }
            }
        })
        col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        chatList?.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        scrollEnd()
    }

    // ---------- drawer: chats on top, name + credits at the foot ----------

    private fun buildDrawer() {
        val scrim = View(this).apply { setBackgroundColor(Color.argb(90, 20, 20, 20)); visibility = View.GONE }
        scrim.setOnClickListener { toggleDrawer(false) }
        screen.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        drawerScrim = scrim

        val w = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(W.theme.bg)
            setPadding(dp(14), dp(16), dp(14), dp(14))
        }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(W.label(this, "CHATS", 17f, W.theme.ink, heavy = 2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val plus = W.button(this, "＋ NEW", W.theme.acc, W.theme.ink, radiusDp = 10, small = true)
        plus.setOnClickListener {
            toggleDrawer(false)
            val c = Store.openFreshChat()
            loadCurrentChat()
            if (c.msgs.isEmpty()) pushSys("fresh chat — new thread, same soul")
        }
        head.addView(plus)
        panel.addView(head)
        panel.addView(View(this).apply { setBackgroundColor(W.theme.ink) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(10); bottomMargin = dp(10) })

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        chatsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(chatsList)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- foot: name + credits ----
        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = W.shadowed(this@MainActivity, W.theme.paper, 14, 3)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        footName = W.label(this, "friend", 15f, W.theme.ink, heavy = 2)
        nameRow.addView(footName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footBonus = TextView(this).apply {
            textSize = 11.5f; typeface = W.bold(this@MainActivity); setTextColor(W.theme.ink)
            background = W.shadowed(this@MainActivity, W.theme.pop, 99, 2)
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        nameRow.addView(footBonus)
        foot.addView(nameRow)

        footGid = W.label(this, "not linked", 11f, W.theme.dim)
        footGid?.setPadding(0, dp(2), 0, 0)
        foot.addView(footGid)

        val (track, fill) = W.bar(this)
        footFill = fill
        foot.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { topMargin = dp(10) })

        footBal = W.label(this, "— / — SU", 12f, W.theme.dim)
        footBal?.setPadding(0, dp(4), 0, 0)
        foot.addView(footBal)

        panel.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        screen.addView(panel, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.START })
        panel.translationX = -w.toFloat()
        drawerPanel = panel
    }

    internal fun renderDrawerChats() {
        val list = chatsList ?: return
        list.removeAllViews()
        val cur = Store.currentChatId()
        for (c in Store.chats()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = W.shadowed(this@MainActivity, if (c.id == cur) W.theme.acc else W.theme.paper, 12, 2)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                W.press(this)
            }
            row.addView(TextView(this).apply {
                text = c.name; textSize = 14f; typeface = W.bold(this@MainActivity)
                setTextColor(W.theme.ink); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val last = c.msgs.lastOrNull { !it.system }?.text ?: "empty thread"
            row.addView(TextView(this).apply {
                text = last; textSize = 11f; setTextColor(W.theme.dim); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(1), 0, 0)
            })
            row.setOnClickListener {
                Store.setCurrent(c.id)
                loadCurrentChat()
                toggleDrawer(false)
            }
            row.setOnLongClickListener {
                orubusDialog(
                    "Delete \"${c.name}\"?",
                    "this thread disappears from this device — the bots keep the memory.",
                    "DELETE", "KEEP", danger = true,
                    onPositive = {
                        Store.deleteChat(c.id); renderDrawerChats(); loadCurrentChat()
                    }
                )
                true
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
    }

    internal fun toggleDrawer(open: Boolean) {
        val p = drawerPanel ?: return
        if (open) renderDrawerChats()
        val w = p.layoutParams.width.toFloat()
        p.animate().translationX(if (open) 0f else -w).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        drawerScrim?.visibility = if (open) View.VISIBLE else View.GONE
    }

    // ---------- settings: the half window ----------

    private fun buildSettingsPanel() {
        val scrim = View(this).apply { setBackgroundColor(Color.argb(90, 20, 20, 20)); visibility = View.GONE }
        scrim.setOnClickListener { toggleSettings(false) }
        screen.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        settingsScrim = scrim

        val h = (resources.displayMetrics.heightPixels * 0.52f).toInt()
        val r18 = W.dpf(this, 18)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(W.theme.bg)
                // the bottom sheet: rounded TOP corners, square base
                cornerRadii = floatArrayOf(r18, r18, r18, r18, 0f, 0f, 0f, 0f)
                setStroke(dp(2), W.theme.ink)
            }
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val hcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hcol.addView(W.label(this, "SETTINGS", 20f, W.theme.ink, heavy = 2))
        hcol.addView(W.label(this, "the app, your way", 11f, W.theme.dim))
        head.addView(hcol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val closeBtn = W.iconBtn(this, W.Icon.CLOSE, W.theme.paper)
        closeBtn.setOnClickListener { toggleSettings(false) }
        head.addView(closeBtn, LinearLayout.LayoutParams(dp(34), dp(34)))
        panel.addView(head)

        // ---- credits card ----
        val credits = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = W.shadowed(this@MainActivity, W.theme.paper, 14, 3)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        credits.addView(W.label(this, "CREDITS", 11f, W.theme.acc2, heavy = 2).apply { letterSpacing = 0.16f })
        val credBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        credits.addView(credBody, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        panel.addView(credits, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        creditsRedraw = { renderCredits(credBody) }

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })

        fun item(emoji: String, title: String, sub: String, withDot: Boolean = false, onClick: () -> Unit): View {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = W.shadowed(this@MainActivity, W.theme.paper, 12, 2)
                setPadding(dp(13), dp(11), dp(13), dp(11))
                W.press(this)
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(W.label(this, "$emoji  $title", 14.5f, W.theme.ink, heavy = 2))
            if (withDot) {
                val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(W.theme.acc2) } }
                titleRow.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(7) })
                if (withDot) settingsInboxDot = dot
                dot.visibility = View.GONE
            }
            col.addView(titleRow)
            col.addView(W.label(this, sub, 11.5f, W.theme.dim).apply { setPadding(0, dp(1), 0, 0) })
            card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(W.label(this, "→", 15f, W.theme.dim, heavy = 2))
            card.setOnClickListener { onClick() }
            return card
        }

        rows.addView(item("🛍", "SHOP", "packs · pro · themes") { toggleSettings(false); openDetail("shop") })
        rows.addView(item("⚡", "EARN & REDEEM", "watch an ad · daily tasks · codes") { toggleSettings(false); toggleSheet(true) })
        rows.addView(item("🔗", "CONNECTORS", "paste an MCP link — verify — the AI uses it") { toggleSettings(false); openConnectorsSheet() })
        rows.addView(item("🧰", "TOOLBOX", "the AI's encrypted workspace + sites") { toggleSettings(false); openToolboxSheet() })
        rows.addView(item("🗳", "REQUESTS", "the feature wall") { toggleSettings(false); openDetail("requests") })
        rows.addView(item("📥", "INBOX", "from the admin", withDot = true) { toggleSettings(false); openDetail("inbox") })
        rows.addView(item("⚖", "LEGAL", "terms · privacy") { toggleSettings(false); openDetail("legal") })
        rows.addView(item("ℹ", "ABOUT", "who is orubus") { toggleSettings(false); openDetail("about") })
        rows.addView(item("📦", "APP VERSION · v${versionName()}", "check for updates — delta patches, no full downloads") { toggleSettings(false); checkForUpdate(true) })
        rows.addView(item("🪪", "IDENTITY", "link / log out") {
            toggleSettings(false)
            if (Api.cookieValue.isBlank()) showTokenScreen() else {
                val gid = stateJson?.optString("gid", "") ?: ""
                orubusDialog(
                    "Identity",
                    if (gid.isNotBlank()) "you are $gid — one identity across the bots, the web and this app.\n\ndisconnect everywhere?"
                    else "linked — the identity is still loading. try again in a second.",
                    "LOG OUT", "KEEP", danger = gid.isNotBlank(),
                    onPositive = { confirmLogout() }
                )
            }
        })

        screen.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { gravity = Gravity.BOTTOM })
        panel.translationY = h.toFloat()
        settingsPanel = panel
    }

    private fun renderCredits(cont: LinearLayout) {
        cont.removeAllViews()
        val w = stateJson?.optJSONObject("wallet")
        if (w == null) {
            cont.addView(W.label(this, if (Api.cookieValue.isBlank()) "link your telegram to load the wallet" else "loading the wallet…", 13f, W.theme.dim))
            return
        }
        val bal = w.optInt("dailyBal", 0)
        val max = w.optInt("meterMax", 360)
        val bonus = w.optInt("bonus", 0)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(W.label(this, "$bal / $max SU", 17f, W.theme.ink, heavy = 2))
        if (bonus > 0) {
            row.addView(TextView(this).apply {
                text = "+$bonus"; textSize = 11.5f; typeface = W.bold(this@MainActivity); setTextColor(W.theme.ink)
                background = W.shadowed(this@MainActivity, W.theme.pop, 99, 2)
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(9) })
        }
        cont.addView(row)
        val (track, fill) = W.bar(this)
        track.post {
            val pct = Math.max(0.04f, Math.min(1f, bal / Math.max(1f, max.toFloat())))
            fill.layoutParams = FrameLayout.LayoutParams(((track.width) * pct).toInt().coerceAtLeast(1), FrameLayout.LayoutParams.MATCH_PARENT)
            fill.requestLayout()
        }
        cont.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { topMargin = dp(9) })
        cont.addView(W.label(this, "units refresh daily · next in ~${w.optInt("resetsInH", 0)}h", 12f, W.theme.dim).apply { setPadding(0, dp(5), 0, 0) })
    }

    internal fun toggleSettings(open: Boolean) {
        val p = settingsPanel ?: return
        val h = p.layoutParams.height.toFloat()
        p.animate().translationY(if (open) 0f else h).setDuration(230).setInterpolator(DecelerateInterpolator()).start()
        settingsScrim?.visibility = if (open) View.VISIBLE else View.GONE
        creditsRedraw?.invoke()
    }

    // ---------- wallet + state ----------

    internal fun renderWallet(w: JSONObject) {
        val bal = w.optInt("dailyBal", 0)
        val max = w.optInt("meterMax", 360)
        val bonus = w.optInt("bonus", 0)
        meterChip?.text = "${fmt(bal)}/${fmt(max)}"
        bonusChip?.text = "+${fmt(bonus)}"
        bonusChip?.visibility = if (bonus > 0) View.VISIBLE else View.GONE
        dryBanner?.visibility = if (bal + bonus <= 0) View.VISIBLE else View.GONE
        // chips show live costs
        val tiers = w.optJSONObject("tiers")
        if (tiers != null) {
            for (c in pills) {
                val t = c.tag as? String ?: continue
                val cost = tiers.optInt(t, 0)
                val label = when (t) { "lite" -> "QUICK"; "pro" -> "DEEP"; else -> "CHAT" }
                c.text = if (cost > 0) "$label · $cost" else label
            }
        }
        // drawer foot
        footName?.text = if (w.optString("name", "").isNotBlank()) w.optString("name") else "friend"
        footBal?.text = "${fmt(bal)} / ${fmt(max)} SU · refills in ~${w.optInt("resetsInH", 0)}h"
        footBonus?.visibility = if (bonus > 0) View.VISIBLE else View.GONE
        footBonus?.text = "+${fmt(bonus)}"
        val pct = Math.max(0.04f, Math.min(1f, bal / Math.max(1f, max.toFloat())))
        footFill?.post {
            val track = footFill?.parent as? View
            val tw = track?.width ?: 0
            if (tw > 0) {
                footFill?.layoutParams = FrameLayout.LayoutParams(Math.max(1, (tw * pct).toInt()), FrameLayout.LayoutParams.MATCH_PARENT)
                footFill?.requestLayout()
            }
        }
        creditsRedraw?.invoke()
    }

    internal fun fmt(n: Int): String = String.format(java.util.Locale.US, "%,d", n)

    internal fun renderWalletText(w: JSONObject): String {
        val bal = w.optInt("dailyBal", 0)
        val max = w.optInt("meterMax", 360)
        val bonus = w.optInt("bonus", 0)
        val tiers = w.optJSONObject("tiers")
        val costs = tiers?.let { "QUICK ${it.optInt("lite", 0)} · CHAT ${it.optInt("standard", 0)} · DEEP ${it.optInt("pro", 0)}" } ?: ""
        val filled = Math.round(Math.max(0.0, Math.min(1.0, bal.toDouble() / Math.max(1, max))) * 10).toInt()
        return "⚡ ${fmt(bal)} / ${fmt(max)} SU${if (bonus > 0) " · bonus +${fmt(bonus)} (yours forever)" else ""}\n" +
            "█".repeat(filled) + "░".repeat(10 - filled) + "\n" +
            "costs: $costs · refills in ~${w.optInt("resetsInH", 0)}h"
    }

    internal fun refreshAll() {
        renderDrawerChats()
        if (Api.cookieValue.isBlank()) {
            footName?.text = "friend"; footGid?.text = "not linked — ⚙ → identity"
            footBal?.text = "— / — SU"
            return
        }
        Thread {
            try {
                val st = Api.post("state")
                stateJson = st
                Store.saveState(st) // the next launch paints from here — no coin-watching
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
                val newTheme = st.optJSONObject("wallet")?.optString("theme", "") ?: ""
                val themeChanged = W.theme != W.themeFor(newTheme)
                runOnUiThread {
                    st.optJSONObject("wallet")?.let { renderWallet(it) }
                    footGid?.text = st.optString("gid", "").ifBlank { "linked" }
                    // inbox unread dot
                    val items = st.optJSONArray("announcements") ?: JSONArray()
                    val lastSeen = Store.prefs().getLong("annSeen", 0)
                    var unread = false
                    for (i in 0 until items.length()) if (items.getJSONObject(i).optLong("ts", 0) > lastSeen) unread = true
                    inboxDot?.visibility = if (unread) View.VISIBLE else View.GONE
                    settingsInboxDot?.visibility = if (unread) View.VISIBLE else View.GONE
                    seedSharedHistory(st.optJSONArray("history"))
                    if (themeChanged) {
                        Store.prefs().edit().putString("theme", newTheme).apply()
                        recreate()
                    }
                }
                initAdsIfNeeded()
            } catch (_: Exception) {
                runOnUiThread { footGid?.text = "offline — retrying later" }
            }
        }.start()
    }

    /**
     * The memory is SHARED across clients: when this chat is still empty,
     * the conversation that already happened on the bots / the web pours in
     * here with the exact same UI taste — one continuous thread everywhere.
     */
    private fun seedSharedHistory(hist: JSONArray?) {
        if (hist == null || hist.length() == 0) return
        val chat = Store.currentChat()
        if (chat.msgs.any { !it.system }) return
        var added = 0
        for (i in 0 until hist.length()) {
            val m = hist.optJSONObject(i) ?: continue
            val mine = m.optString("r", "a") == "u"
            val text = m.optString("t", "")
            if (text.isBlank()) continue
            Store.append(chat, mine, text)
            added++
        }
        if (added > 0) {
            Store.save()
            loadCurrentChat()
            pushSys("picked up your thread — this is the same conversation your other clients see")
        }
    }

    // ---------- updates (GitHub releases) ----------

    internal fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    internal fun checkForUpdate(explicit: Boolean = false) {
        // the in-app delta updater: manifest → patch → on-device rebuild →
        // system installer. Falls back to the full APK on any trouble.
        if (explicit) Updater.checkExplicit(this)
    }

    // ---------- misc ----------

    internal fun timeAgo(ts: Long): String {
        if (ts <= 0) return "just now"
        val s = Math.max(1, (System.currentTimeMillis() - ts) / 1000)
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60}m ago"
            s < 86400 -> "${s / 3600}h ago"
            else -> "${s / 86400}d ago"
        }
    }

    /** Fetch a page and strip it to readable text (legal / about). */
    internal fun fetchPageText(path: String, done: (String) -> Unit) {
        pageCache[path]?.let { done(it); return }
        Thread {
            val text = try {
                val c = java.net.URL(Api.BASE + path).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 12_000; c.readTimeout = 12_000
                c.setRequestProperty("User-Agent", "orubus-app")
                val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                c.disconnect()
                val stripped = body
                    .replace(Regex("(?is)<(script|style).*?</\\1>"), "")
                    .replace(Regex("(?is)<br\\s*/?>"), "\n")
                    .replace(Regex("(?is)</(p|div|h[1-6]|li|tr)>"), "\n")
                android.text.Html.fromHtml(stripped, android.text.Html.FROM_HTML_MODE_COMPACT)
                    .toString()
                    .replace(Regex("\n{3,}"), "\n\n")
                    .trim()
            } catch (_: Exception) { "" }
            if (text.isNotBlank()) pageCache[path] = text
            runOnUiThread { done(text) }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            imageViewer != null -> {
                imageViewer?.let { screen.removeView(it) }
                imageViewer = null
            }
            calling -> {} // the call owns the back button — END ends it
            detailFrame?.visibility == View.VISIBLE -> closeDetail()
            sheetPanel?.visibility == View.VISIBLE && sheetPanel?.translationY == 0f -> toggleSheet(false)
            settingsPanel?.translationY == 0f -> toggleSettings(false)
            drawerPanel?.translationX == 0f -> toggleDrawer(false)
            else -> moveTaskToBack(true)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4011 && grantResults.isNotEmpty()) {
            handleMicResult(grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Solver.detach()
    }

}

/**
 * The pinch-zoom photo view — matrix-based zoom + pan, double-tap to snap
 * back to fit. Plain android.widget, no appcompat, ~90 lines, built for the
 * one job: viewing a photo full-screen like a real attachment.
 */
private class ZoomImage(c: android.content.Context) : ImageView(c) {

    private val base = Matrix()
    private val touch = Matrix()
    private val saved = Matrix()
    private val start = android.graphics.PointF()
    private val mid = android.graphics.PointF()
    private var mode = 0 // 0 = none, 1 = drag, 2 = zoom
    private val scaleDet = android.view.ScaleGestureDetector(c, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean {
            mode = 2
            saved.set(touch)
            mid.set(d.focusX, d.focusY)
            return true
        }
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            val f = d.scaleFactor.coerceIn(0.5f, 3.5f)
            val cur = FloatArray(9)
            touch.getValues(cur)
            val next = (cur[Matrix.MSCALE_X] * f).coerceIn(0.6f, 6f)
            touch.set(saved)
            touch.postScale(next / cur[Matrix.MSCALE_X], next / cur[Matrix.MSCALE_Y], mid.x, mid.y)
            imageMatrix = touch
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        val tap = android.view.GestureDetector(c, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean = true
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                val cur = FloatArray(9)
                touch.getValues(cur)
                touch.set(base)
                if (cur[Matrix.MSCALE_X] <= baseZoom + 0.01f) {
                    touch.postScale(2.2f, 2.2f, e.x, e.y)
                }
                imageMatrix = touch
                return true
            }
        })
        setOnTouchListener { v, ev ->
            scaleDet.onTouchEvent(ev)
            tap.onTouchEvent(ev)
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    mode = 1
                    saved.set(touch)
                    start.set(ev.x, ev.y)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (mode == 1) {
                        touch.set(saved)
                        touch.postTranslate(ev.x - start.x, ev.y - start.y)
                        imageMatrix = touch
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> mode = 0
            }
            true
        }
    }

    private var baseZoom = 1f

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        post {
            val vw = width.toFloat().coerceAtLeast(1f)
            val vh = height.toFloat().coerceAtLeast(1f)
            val bw = (bm?.width ?: 1).toFloat()
            val bh = (bm?.height ?: 1).toFloat()
            val fit = Math.min(vw / bw, vh / bh)
            base.reset()
            base.postScale(fit, fit, vw / 2f, vh / 2f)
            base.postTranslate((vw - bw * fit) / 2f, (vh - bh * fit) / 2f)
            baseZoom = fit
            touch.set(base)
            imageMatrix = touch
        }
    }
}
