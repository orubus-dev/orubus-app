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
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Orubus AI for Android — package app.orubus.ai.
 *
 * First run: legal agreement popup → paste your /genjutsu token → in.
 * Then four surfaces: the Soul Units app, the requests wall, the chat
 * (its own interface, duck.ai as the main brain — free) and the legal page.
 * Updates are checked against GitHub Releases — new version, one tap.
 *
 * Zero external dependencies: framework views, org.json, HttpURLConnection.
 */
class MainActivity : Activity() {

    private lateinit var prefs: android.content.SharedPreferences

    // ---- UI roots ----
    private lateinit var root: FrameLayout
    private lateinit var screens: FrameLayout
    private lateinit var bottomBar: LinearLayout
    private var tokenScreen: View? = null
    private var nativeChat: View? = null
    private val tabsButtons = mutableListOf<TextView>()
    private var chatList: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var chatInput: EditText? = null
    private var chatStatus: TextView? = null
    private var chatLoaded = false
    private var sending = false

    private val tabWebViews = mutableMapOf<Int, WebView>()

    companion object {
        val TABS = listOf("⚡ App" to "https://orubus-ai.vercel.app/app",
            "🗳 Requests" to "https://orubus-ai.vercel.app/requests",
            "💬 Chat" to "",
            "⚖ Legal" to "https://orubus-ai.vercel.app/legal")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("orubus", MODE_PRIVATE)
        Api.cookieValue = prefs.getString("cookie", "") ?: ""

        root = FrameLayout(this)
        root.setBackgroundColor(C(0xFF0F0F0F))
        buildShell()
        setContentView(root)

        if (!prefs.getBoolean("legal_ok", false)) showLegal() else next()
    }

    private fun C(v: Long) = v.toInt()

    // ---------- first-run gates ----------

    private fun showLegal() {
        val web = WebView(this)
        web.settings.javaScriptEnabled = false
        web.setBackgroundColor(C(0xFF171717))
        web.loadUrl("https://orubus-ai.vercel.app/legal")

        AlertDialog.Builder(this)
            .setTitle("Before you come in")
            .setView(web)
            .setCancelable(false)
            .setPositiveButton("I agree") { _, _ ->
                prefs.edit().putBoolean("legal_ok", true).apply()
                next()
            }
            .setNegativeButton("Leave") { _, _ -> finishAffinity() }
            .show()
    }

    private fun next() {
        if (Api.cookieValue.isBlank()) showTokenScreen() else enterMain()
    }

    // ---------- GENJ token screen ----------

    @SuppressLint("SetTextI18n")
    private fun showTokenScreen() {
        tokenScreen?.let { screens.removeView(it) }
        val pad = dp(24)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(C(0xFF0F0F0F))
            setPadding(pad, pad, pad, pad)
        }

        val logo = TextView(this).apply {
            text = "◎"
            textSize = 64f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C(0xFFFFD84A))
            gravity = Gravity.CENTER
        }
        val title = TextView(this).apply {
            text = "Link your Telegram"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        }
        val sub = TextView(this).apply {
            text = "same wallet · same memory · zero sign-up\n\n1. Open @orubus_bot in Telegram\n2. Send /genjutsu\n3. Paste the GENJ-XXXX code here"
            textSize = 14.5f
            setTextColor(C(0xFF9A9A9A))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(20))
            setLineSpacing(dp(3), 1f)
        }
        val input = EditText(this).apply {
            hint = "GENJ-XXXX"
            maxLines = 1
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(C(0xFF6A6A6A))
            typeface = Typeface.MONOSPACE
            setBackgroundColor(C(0xFF1F1F1F))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        (input.layoutParams as? LinearLayout.LayoutParams)?.width = ViewGroup.LayoutParams.MATCH_PARENT
        val btn = Button(this).apply {
            text = "LINK"
            isAllCaps = true
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C(0xFF141414))
            background = round(C(0xFFFFD84A), dp(12))
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(C(0xFFFF6B6B))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        val later = TextView(this).apply {
            text = "no code yet — explore first, link later"
            textSize = 13f
            setTextColor(C(0xFF9A9A9A))
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }

        fun goLink() {
            val code = input.text.toString().trim().uppercase(Locale.US)
            if (code.length < 6) {
                status.setTextColor(C(0xFFFF6B6B)); status.text = "the code looks short — it's GENJ-XXXX"
                return
            }
            btn.isEnabled = false; btn.text = "LINKING…"; status.text = ""
            Thread {
                try {
                    val (ok, err) = Api.link(code)
                    runOnUiThread {
                        btn.isEnabled = true; btn.text = "LINK"
                        if (ok) {
                            prefs.edit().putString("cookie", Api.cookieValue).apply()
                            pushCookieToWebViews()
                            Toast.makeText(this, "linked — welcome in", Toast.LENGTH_SHORT).show()
                            enterMain()
                        } else {
                            status.setTextColor(C(0xFFFF6B6B)); status.text = err
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        btn.isEnabled = true; btn.text = "LINK"
                        status.setTextColor(C(0xFFFF6B6B))
                        status.text = "network hiccup — ${e.message ?: "try again"}"
                    }
                }
            }.start()
        }
        btn.setOnClickListener { goLink() }
        input.setOnEditorActionListener { _, _, _ -> goLink(); true }
        later.setOnClickListener { enterMain() }

        box.addView(logo)
        box.addView(title)
        box.addView(sub)
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        box.addView(status)
        box.addView(later)

        tokenScreen = box
        screens.addView(box)
        screens.visibility = View.VISIBLE
        bottomBar.visibility = View.GONE
    }

    // ---------- main shell ----------

    private fun buildShell() {
        screens = FrameLayout(this).apply { setBackgroundColor(C(0xFF0F0F0F)) }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C(0xFF141414))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val gap = dp(4)

        TABS.forEachIndexed { i, (label, _) ->
            val b = TextView(this).apply {
                text = label
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = round(if (i == 0) C(0xFFFFD84A) else C(0xFF1F1F1F), dp(10))
                setTextColor(if (i == 0) C(0xFF141414) else C(0xFF9A9A9A))
                setOnClickListener { switchTab(i) }
            }
            tabsButtons.add(b)
            bottomBar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = gap; marginEnd = gap })
        }

        // chat screen (native) — created once, hidden by default
        val chat = buildChatScreen()
        nativeChat = chat
        screens.addView(chat, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        chat.visibility = View.GONE

        root.addView(screens, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { bottomMargin = dp(58) })
        root.addView(bottomBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun enterMain() {
        tokenScreen?.let { screens.removeView(it); tokenScreen = null }
        screens.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        switchTab(0)
        checkForUpdate()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webViewFor(i: Int): WebView {
        tabWebViews[i]?.let { return it }
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.setBackgroundColor(C(0xFF0F0F0F))
        wv.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                // keep our pages in-app; outside links go to the browser
                return if (url.startsWith("https://orubus-ai.vercel.app")) false
                else {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                    true
                }
            }
        }
        pushCookieTo(wv)
        screens.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wv.visibility = View.GONE
        tabWebViews[i] = wv
        return wv
    }

    private fun switchTab(i: Int) {
        tabsButtons.forEachIndexed { j, b ->
            val active = j == i
            b.background = round(if (active) C(0xFFFFD84A) else C(0xFF1F1F1F), dp(10))
            b.setTextColor(if (active) C(0xFF141414) else C(0xFF9A9A9A))
        }
        // hide everything, then show exactly one surface
        nativeChat?.visibility = View.GONE
        tabWebViews.values.forEach { it.visibility = View.GONE }
        if (i == 2) {
            ensureChatLoaded()
            nativeChat?.visibility = View.VISIBLE
        } else {
            webViewFor(i).visibility = View.VISIBLE
        }
    }

    // ---------- native chat (duck.ai lane) ----------

    private fun buildChatScreen(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C(0xFF0F0F0F)) }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10)); setBackgroundColor(C(0xFF141414))
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "Orubus AI"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        })
        chatStatus = TextView(this).apply {
            textSize = 11.5f; setTextColor(C(0xFF9A9A9A)); text = "free duck lane · same soul as your bots"
        }
        titleCol.addView(chatStatus)
        head.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val relink = TextView(this).apply {
            text = "🔗"; textSize = 18f; setPadding(dp(10), dp(4), dp(2), dp(4))
            setOnClickListener { showTokenScreen() }
        }
        head.addView(relink)
        box.addView(head)

        chatScroll = ScrollView(this).apply {
            setBackgroundColor(C(0xFF0F0F0F))
            isVerticalScrollBarEnabled = false
        }
        chatList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        chatScroll!!.addView(chatList)
        box.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(8), dp(10), dp(10)); setBackgroundColor(C(0xFF141414))
        }
        chatInput = EditText(this).apply {
            hint = "just talk — it's free"; maxLines = 4
            textSize = 15f; setTextColor(Color.WHITE); setHintTextColor(C(0xFF6A6A6A))
            setBackgroundColor(C(0xFF1F1F1F)); setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        row.addView(chatInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        val send = Button(this).apply {
            text = "Send"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(C(0xFF141414)); background = round(C(0xFFFFD84A), dp(12))
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { sendChat() }
        }
        row.addView(send)
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun linked(): Boolean = Api.cookieValue.isNotBlank()

    private fun ensureChatLoaded() {
        if (chatLoaded) return
        chatLoaded = true
        refreshChatStatus()
        pushBubble("🟡 Welcome. This lane runs on duck.ai — free, no Soul Units spent, and it remembers everything the rest of me knows.", false)
        Thread {
            try {
                if (!linked()) {
                    runOnUiThread { pushBubble("You're not linked yet — tap 🔗 up top and paste a GENJ code from /genjutsu. Meanwhile, chat still works.", false) }
                    return@Thread
                }
                val st = Api.post("state")
                val hist = st.optJSONArray("history") ?: JSONArray()
                val n = hist.length()
                val from = maxOf(0, n - 30)
                runOnUiThread {
                    for (k in from until n) {
                        val m = hist.getJSONObject(k)
                        pushBubble(m.optString("t", ""), m.optString("r", "u") == "u", quiet = true)
                    }
                    pushBubble("(older turns live in your soul — this archive shows the last 30)", false, quiet = true)
                }
            } catch (e: Exception) {
                runOnUiThread { pushBubble("couldn't load the archive: ${e.message ?: "?"}", false) }
            }
        }.start()
    }

    private fun refreshChatStatus() {
        chatStatus?.text = if (linked()) "free duck lane · linked · same soul as your bots"
        else "free duck lane · not linked yet (tap 🔗)"
    }

    private fun sendChat() {
        if (sending) return
        val text = chatInput?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        chatInput?.setText("")
        pushBubble(text, true)
        sending = true
        val typing = pushBubble("…", false)
        Thread {
            try {
                val r = Api.post("msg", mapOf("text" to text, "engine" to "duck", "tier" to "standard"))
                val ok = r.optBoolean("ok", false)
                val answer = r.optString("answer", r.optString("error", "hmm — empty reply"))
                runOnUiThread {
                    chatList?.removeView(typing)
                    pushBubble(if (ok) answer else "⚠ $answer", false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    chatList?.removeView(typing)
                    pushBubble("⚠ network hiccup — ${e.message ?: "try again"}", false)
                }
            } finally {
                sending = false
            }
        }.start()
    }

    private fun pushBubble(text: String, mine: Boolean, quiet: Boolean = false): TextView {
        val list = chatList ?: return TextView(this)
        val tv = TextView(this).apply {
            this.text = if (text.length > 4000) text.take(4000) + "…" else text
            textSize = 15f
            setTextColor(if (mine) C(0xFF141414) else Color.WHITE)
            movementMethod = LinkMovementMethod.getInstance()
            background = round(if (mine) C(0xFFFFD84A) else C(0xFF232323), if (mine) dp(16) else dp(4))
            setPadding(dp(12), dp(9), dp(12), dp(9))
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (mine) Gravity.END else Gravity.START
        lp.topMargin = dp(6)
        list.addView(tv, lp)
        list.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
        if (!quiet) {
            // keep the log light
            while (list.childCount > 80) list.removeViewAt(0)
        }
        return tv
    }

    // ---------- continuous updates (GitHub Releases) ----------

    private fun checkForUpdate() {
        Thread {
            try {
                val rel = Api.get("https://api.github.com/repos/${Api.APP_REPO}/releases/latest")
                val tag = rel.optString("tag_name", "").removePrefix("v")
                val cur = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
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
                }
            } catch (_: Exception) {
                /* update checks never bother anyone */
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

    // ---------- helpers ----------

    private fun pushCookieToWebViews() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setCookie("https://orubus-ai.vercel.app/", "orubus_web=${Api.cookieValue}; Path=/; Secure; SameSite=Lax")
        CookieManager.getInstance().flush()
        tabWebViews.values.forEach { pushCookieTo(it) }
    }

    private fun pushCookieTo(wv: WebView) {
        if (Api.cookieValue.isBlank()) return
        CookieManager.getInstance().setCookie("https://orubus-ai.vercel.app/", "orubus_web=${Api.cookieValue}; Path=/; Secure; SameSite=Lax")
        CookieManager.getInstance().flush()
    }

    private fun round(color: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusPx.toFloat() }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val visibleWv = tabWebViews.values.firstOrNull { it.visibility == View.VISIBLE }
        if (visibleWv != null && visibleWv.canGoBack()) visibleWv.goBack()
        else moveTaskToBack(true)
    }
}
