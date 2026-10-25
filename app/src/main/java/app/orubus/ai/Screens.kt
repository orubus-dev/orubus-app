package app.orubus.ai

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * The full screens that live on top of the chat page — every one of them a
 * native Kotlin view tree wearing the web design kit, zero WebViews:
 *
 *   detail  — shop / requests wall / inbox / legal / about
 *   sheet   — the chat page's ⚡ actions (earn · top up · session · more)
 *   AdMob   — rewarded SU + the shop banner
 */

// ---------- detail container ----------

internal fun MainActivity.buildDetail() {
    val scrim = View(this).apply { setBackgroundColor(Color.argb(120, 20, 20, 20)); visibility = View.GONE }
    scrim.setOnClickListener { closeDetail() }
    screen.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    detailScrim = scrim

    val frame = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(W.theme.bg)
        visibility = View.GONE
    }
    val head = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(9), dp(12), dp(9))
    }
    val back = W.iconBtn(this, W.Icon.BACK)
    back.setOnClickListener { closeDetail() }
    head.addView(back, LinearLayout.LayoutParams(dp(36), dp(36)))
    val title = W.label(this, "", 16f, W.theme.ink, heavy = 2)
    title.setPadding(dp(10), 0, 0, 0)
    head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    frame.addView(head)
    frame.addView(View(this).apply { setBackgroundColor(W.theme.ink) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))

    val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
    val cont = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(18)) }
    scroll.addView(cont)
    frame.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    bannerSlot = FrameLayout(this).apply { visibility = View.GONE }
    frame.addView(bannerSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    screen.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    detailFrame = frame
    detailTitle = title
    detailCont = cont
}

internal fun MainActivity.openDetail(kind: String) {
    val cont = detailCont ?: return
    detailTitle?.text = when (kind) {
        "shop" -> "🛍 SHOP"; "requests" -> "🗳 REQUESTS"; "inbox" -> "📥 INBOX"
        "legal" -> "⚖ LEGAL"; else -> "ℹ ABOUT"
    }
    cont.removeAllViews()
    when (kind) {
        "shop" -> renderShop(cont)
        "requests" -> renderRequests(cont)
        "inbox" -> renderInbox(cont)
        "legal" -> renderLegal(cont)
        "about" -> renderAbout(cont)
    }
    detailFrame?.visibility = View.VISIBLE
    renderBanner(kind == "shop")
}

internal fun MainActivity.closeDetail() {
    detailFrame?.visibility = View.GONE
    renderBanner(false)
}

internal fun MainActivity.sectionLabel(cont: LinearLayout, text: String) {
    cont.addView(W.label(this, text, 11f, W.theme.acc2, heavy = 2).apply {
        letterSpacing = 0.16f
        setPadding(0, dp(14), 0, dp(2))
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

// ---------- shop ----------

internal fun MainActivity.renderShop(cont: LinearLayout) {
    val st = stateJson
    val wallet = st?.optJSONObject("wallet")

    // balance strip
    if (wallet != null) {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = W.shadowed(this@renderShop, W.theme.paper, 12, 2)
            setPadding(dp(13), dp(10), dp(13), dp(10))
        }
        strip.addView(W.label(this, "wallet", 12f, W.theme.dim))
        strip.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        strip.addView(W.label(this, "${fmt(wallet.optInt("dailyBal", 0))} / ${fmt(wallet.optInt("meterMax", 360))} SU", 14f, W.theme.ink, heavy = 2))
        if (wallet.optInt("bonus", 0) > 0) {
            strip.addView(W.label(this, "  +${fmt(wallet.optInt("bonus", 0))}", 13f, W.theme.ink, heavy = 2))
        }
        cont.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // SU packs — checkout happens in Telegram (Stars), exactly like the web
    val packs = st?.optJSONArray("packs") ?: JSONArray()
    if (packs.length() > 0) {
        sectionLabel(cont, "SU PACKS")
        cont.addView(W.label(this, "paid in Telegram Stars · lands instantly · gifted units never expire", 11.5f, W.theme.dim).apply { setPadding(0, 0, 0, dp(6)) })
        for (i in 0 until packs.length()) {
            val p = packs.getJSONObject(i)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = W.shadowed(this@renderShop, W.theme.paper, 12, 2)
                setPadding(dp(13), dp(11), dp(13), dp(11))
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(W.label(this, p.optInt("su", 0).toString() + " SU", 15f, W.theme.ink, heavy = 2))
            col.addView(W.label(this, "never expires", 11f, W.theme.dim))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(W.button(this, "⭐ ${p.optInt("stars", 0)}", W.theme.acc, W.theme.ink, small = true).apply {
                setOnClickListener { openBot() }
            })
            cont.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    // items — pro pass / themes / vanity / title
    val items = st?.optJSONObject("shop")?.optJSONArray("items") ?: JSONArray()
    if (items.length() > 0) {
        sectionLabel(cont, "THE SHOP")
        val owned = wallet?.optJSONArray("themes")
        val ownedIds = mutableSetOf("classic")
        if (owned != null) for (i in 0 until owned.length()) ownedIds.add(owned.optString(i))
        val activeTheme = wallet?.optString("theme", "") ?: ""
        val active = if (activeTheme.isBlank()) "classic" else activeTheme

        // the default stays reachable — one tap back to the classic cream, always free
        val defCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = W.shadowed(this@renderShop, W.theme.paper, 12, 2)
            setPadding(dp(13), dp(11), dp(13), dp(11))
        }
        defCard.addView(W.label(this, "Default theme", 14.5f, W.theme.ink, heavy = 2))
        defCard.addView(W.label(this, "the classic cream look — the one you met first. always yours.", 11.5f, W.theme.dim).apply { setPadding(0, dp(2), 0, dp(8)) })
        val defActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (active == "classic") defActions.addView(W.pill(this, "ACTIVE", true))
        else defActions.addView(W.button(this, "USE", W.theme.acc, W.theme.ink, small = true).apply {
            setOnClickListener { applyTheme("classic") }
        })
        defCard.addView(defActions)
        cont.addView(defCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val kind = it.optString("kind", "")
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = W.shadowed(this@renderShop, W.theme.paper, 12, 2)
                setPadding(dp(13), dp(11), dp(13), dp(11))
            }
            card.addView(W.label(this, it.optString("name", ""), 14.5f, W.theme.ink, heavy = 2))
            card.addView(W.label(this, it.optString("desc", ""), 11.5f, W.theme.dim).apply { setPadding(0, dp(2), 0, dp(8)) })

            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (kind == "theme") {
                val themeId = it.optString("id", "").removePrefix("theme_")
                if (themeId == active) {
                    actions.addView(W.pill(this, "ACTIVE", true))
                } else if (ownedIds.contains(themeId)) {
                    actions.addView(W.button(this, "USE", W.theme.acc, W.theme.ink, small = true).apply {
                        setOnClickListener { applyTheme(themeId) }
                    })
                } else {
                    actions.addView(W.pill(this, "⭐ ${it.optInt("stars", 0)}", false))
                    actions.addView(W.button(this, "GET IT", W.theme.acc, W.theme.ink, small = true).apply {
                        setOnClickListener { openBot() }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
                }
            } else {
                actions.addView(W.pill(this, "⭐ ${it.optInt("stars", 0)}", false))
                actions.addView(W.button(this, "GET IT", W.theme.acc, W.theme.ink, small = true).apply {
                    setOnClickListener { openBot() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            }
            card.addView(actions)
            cont.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    cont.addView(W.label(this, "checkout lives in @orubus_bot — tap anything with a ⭐ and the bot opens", 11.5f, W.theme.dim).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(16), 0, 0)
    })
}

internal fun MainActivity.openBot() {
    val link = stateJson?.optString("botLink", "").takeUnless { it.isNullOrBlank() } ?: "https://t.me/orubus_bot"
    try {
        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link)))
    } catch (_: Exception) {}
}

/** Wear a theme: server prefs first, then the whole app re-skins itself.
 *  "classic" is always free — the default is never lost. */
internal fun MainActivity.applyTheme(id: String) {
    Thread {
        try {
            val r = Api.post("setprefs", mapOf("theme" to id))
            runOnUiThread {
                if (r.optBoolean("ok", false)) {
                    r.optJSONObject("wallet")?.let { renderWallet(it) }
                    stateJson?.optJSONObject("wallet")?.put("theme", if (id == "classic") "" else id)
                    Store.prefs().edit().putString("theme", id).apply()
                    W.theme = W.themeFor(id)
                    Toast.makeText(this, "theme on — re-wrapping the app", Toast.LENGTH_SHORT).show()
                    recreate()
                } else Toast.makeText(this, r.optString("error", "couldn't apply"), Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            runOnUiThread { Toast.makeText(this, "network hiccup — try again", Toast.LENGTH_SHORT).show() }
        }
    }.start()
}

// ---------- requests wall ----------

internal fun MainActivity.renderRequests(cont: LinearLayout) {
    // propose box on top — exactly like the web wall
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = W.shadowed(this@renderRequests, W.theme.paper, 12, 3)
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }
    val input = EditText(this).apply {
        hint = "Suggest a feature…"; maxLines = 3; textSize = 13.5f
        setTextColor(W.theme.ink); setHintTextColor(W.theme.dim)
        typeface = W.regular(this@renderRequests)
        background = W.stroke(this@renderRequests, W.theme.bg, 10)
        setPadding(dp(11), dp(9), dp(11), dp(9))
    }
    box.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    box.addView(W.button(this, "PROPOSE", W.theme.acc, W.theme.ink, small = true).apply {
        setOnClickListener {
            val text = input.text.toString().trim()
            if (text.length < 4) { Toast.makeText(this@renderRequests, "give it a few more words", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            isEnabled = false; text_propose(this, "…")
            Thread {
                try {
                    val r = Api.wallAction("create", mapOf("title" to text))
                    runOnUiThread {
                        isEnabled = true; text_propose(this, "PROPOSE")
                        if (r.optBoolean("ok", false)) {
                            input.setText("")
                            Toast.makeText(this@renderRequests, "on the wall — the admin got the ping ⚡", Toast.LENGTH_SHORT).show()
                            refillWall(cont)
                        } else Toast.makeText(this@renderRequests, r.optString("error", "didn't land"), Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    runOnUiThread { isEnabled = true; text_propose(this, "PROPOSE"); Toast.makeText(this@renderRequests, "network hiccup — try again", Toast.LENGTH_SHORT).show() }
                }
            }.start()
        }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
    cont.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    cont.addView(W.label(this, "real votes, real comments — you wear the gj......ksjsj mask", 11f, W.theme.dim).apply { setPadding(0, dp(6), 0, 0) })

    val feed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    cont.addView(feed, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    refillWall(feed)
}

private fun text_propose(tv: TextView, s: String) { tv.text = s }

internal fun MainActivity.refillWall(feed: LinearLayout) {
    feed.removeAllViews()
    feed.addView(W.label(this, "pulling the wall…", 12f, W.theme.dim))
    Thread {
        val items: JSONArray
        try {
            items = Api.wallGet().optJSONArray("items") ?: JSONArray()
        } catch (_: Exception) {
            runOnUiThread { feed.removeAllViews(); feed.addView(W.label(this, "the wall didn't load — pull down and reopen", 12.5f, W.theme.acc2)) }
            return@Thread
        }
        runOnUiThread {
            feed.removeAllViews()
            if (items.length() == 0) feed.addView(W.label(this, "empty wall — be the first to propose", 12.5f, W.theme.dim))
            for (i in 0 until items.length()) {
                feed.addView(wallCard(items.getJSONObject(i)) { refillWall(feed) })
            }
        }
    }.start()
}

private fun MainActivity.wallCard(o: JSONObject, refresh: () -> Unit): View {
    val id = o.optInt("id", 0)
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = W.shadowed(this@wallCard, W.theme.paper, 12, 2)
        setPadding(dp(13), dp(11), dp(13), dp(11))
    }
    val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    top.addView(W.label(this, o.optString("title", ""), 14.5f, W.theme.ink, heavy = 2).apply {
        maxLines = 3; ellipsize = null
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val status = o.optString("status", "")
    if (status.isNotBlank() && status != "open") {
        top.addView(W.pill(this, status.uppercase(java.util.Locale.US), false).apply { textSize = 10f }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
    }
    card.addView(top)
    val detail = o.optString("detail", "")
    if (detail.isNotBlank()) card.addView(W.label(this, detail, 12f, W.theme.dim).apply { setPadding(0, dp(3), 0, 0) })
    card.addView(W.label(this, "by ${o.optString("by", "?")} · ${timeAgo(o.optLong("at", 0))}", 10.5f, W.theme.dim).apply { setPadding(0, dp(4), 0, 0) })

    // comments
    val comments = o.optJSONArray("comments") ?: JSONArray()
    val commentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    if (comments.length() > 0) {
        commentBox.addView(View(this).apply { setBackgroundColor(W.theme.ink) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(6) })
        for (i in 0 until comments.length()) {
            val cm = comments.getJSONObject(i)
            commentBox.addView(W.label(this, "💬 ${cm.optString("text", "")}", 12.5f, W.theme.ink).apply { setPadding(0, dp(7), 0, 0) })
            commentBox.addView(W.label(this, "— ${cm.optString("by", "?")} · ${timeAgo(cm.optLong("at", 0))}", 10.5f, W.theme.dim).apply { setPadding(0, dp(1), 0, 0) })
        }
    }
    val cInput = EditText(this).apply {
        hint = "say something — anonymously"; maxLines = 2; textSize = 12.5f
        setTextColor(W.theme.ink); setHintTextColor(W.theme.dim)
        typeface = W.regular(this@wallCard)
        background = W.stroke(this@wallCard, W.theme.bg, 9)
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }
    commentBox.addView(cInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    commentBox.addView(W.button(this, "COMMENT", W.theme.acc, W.theme.ink, small = true).apply {
        setOnClickListener {
            val t = cInput.text.toString().trim()
            if (t.length < 2) { Toast.makeText(this@wallCard, "needs 2+ characters", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            isEnabled = false
            Thread {
                try {
                    val r = Api.wallAction("comment", mapOf("id" to id, "text" to t))
                    runOnUiThread {
                        isEnabled = true
                        if (r.optBoolean("ok", false)) { refresh() } else Toast.makeText(this@wallCard, r.optString("error", "didn't land"), Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    runOnUiThread { isEnabled = true; Toast.makeText(this@wallCard, "network hiccup — try again", Toast.LENGTH_SHORT).show() }
                }
            }.start()
        }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    card.addView(commentBox)

    // actions row — vote / comment / dislike, in that order
    val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun act(label: String, voted: Boolean, bg: Int, click: () -> Unit): TextView =
        W.button(this, label, if (voted) bg else W.theme.paper, W.theme.ink, small = true)
    val up = act("👍 ${o.optInt("votes", 0)}", o.optBoolean("voted", false), W.theme.acc) {}
    val down = act("👎 ${o.optInt("downs", 0)}", o.optBoolean("downed", false), W.theme.pop) {}
    val cmt = act("💬 ${comments.length()}", false, W.theme.acc) {}
    up.setOnClickListener {
        up.isEnabled = false
        Thread { try { Api.wallAction("vote", mapOf("id" to id)) } catch (_: Exception) {} ; runOnUiThread { refresh() } }.start()
    }
    down.setOnClickListener {
        down.isEnabled = false
        Thread { try { Api.wallAction("down", mapOf("id" to id)) } catch (_: Exception) {} ; runOnUiThread { refresh() } }.start()
    }
    cmt.setOnClickListener { commentBox.visibility = if (commentBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
    acts.addView(up)
    acts.addView(down, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
    acts.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    acts.addView(cmt)
    card.addView(acts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(9) })
    return card
}

// ---------- inbox ----------

internal fun MainActivity.renderInbox(cont: LinearLayout) {
    val items = stateJson?.optJSONArray("announcements") ?: JSONArray()
    val lastSeen = Store.prefs().getLong("annSeen", 0)
    if (items.length() == 0) {
        cont.addView(W.label(this, "nothing yet — announcements from the admin land right here, like a tiny broadcast tower that only whispers to you", 13f, W.theme.dim).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(30), dp(12), 0)
        })
        return
    }
    for (i in items.length() - 1 downTo 0) {
        val o = items.getJSONObject(i)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = W.shadowed(this@renderInbox, W.theme.paper, 12, 2)
            setPadding(dp(13), dp(11), dp(13), dp(11))
        }
        val unread = o.optLong("ts", 0) > lastSeen
        if (unread) card.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(W.theme.acc2) }
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { bottomMargin = dp(5) })
        card.addView(W.label(this, o.optString("text", ""), 13.5f, W.theme.ink).apply { setLineSpacing(dp(2).toFloat(), 1f) })
        card.addView(W.label(this, "from the admin · ${timeAgo(o.optLong("ts", 0))}", 10.5f, W.theme.dim).apply { setPadding(0, dp(5), 0, 0) })
        cont.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }
    Store.prefs().edit().putLong("annSeen", System.currentTimeMillis()).apply()
    inboxDot?.visibility = View.GONE
    settingsInboxDot?.visibility = View.GONE
}

// ---------- legal / about ----------

internal fun MainActivity.renderLegal(cont: LinearLayout) {
    renderPageText(cont, "/legal")
}

internal fun MainActivity.renderAbout(cont: LinearLayout) {
    renderPageText(cont, "/about/")
    cont.addView(W.label(this, "app v${versionName()} · the android client — one soul, everywhere you are", 11.5f, W.theme.dim).apply {
        setPadding(0, dp(12), 0, 0); gravity = Gravity.CENTER
    })
}

private fun MainActivity.renderPageText(cont: LinearLayout, path: String) {
    val tv = W.label(this, "loading…", 13.5f, W.theme.ink)
    tv.setLineSpacing(dp(3).toFloat(), 1f)
    fetchPageText(path) { t ->
        tv.text = if (t.isBlank()) "couldn't reach the page — check the connection and reopen"
        else t
    }
    cont.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

// ---------- the ⚡ actions sheet ----------

internal fun MainActivity.buildSheet() {
    val scrim = View(this).apply { setBackgroundColor(Color.argb(90, 20, 20, 20)); visibility = View.GONE }
    scrim.setOnClickListener { toggleSheet(false) }
    screen.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    sheetScrim = scrim

    val r18 = W.dpf(this, 18)
    val panel = ScrollView(this).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        background = GradientDrawable().apply {
            setColor(W.theme.bg)
            cornerRadii = floatArrayOf(r18, r18, r18, r18, 0f, 0f, 0f, 0f)
            setStroke(dp(2), W.theme.ink)
        }
    }
    val cont = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(20)) }
    panel.addView(cont, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    // HALF the screen, like the web's menu: the content fits in the sheet
    // and scrolling reveals the rest — never a wall of cards running past
    // the top edge with no way back down
    val sheetH = (resources.displayMetrics.heightPixels * 0.56f).toInt()
    screen.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sheetH).apply { gravity = Gravity.BOTTOM })
    sheetPanel = panel

    fun h2(text: String) {
        cont.addView(W.label(this, text, 11f, W.theme.acc2, heavy = 2).apply {
            letterSpacing = 0.16f
            setPadding(0, dp(14), 0, dp(8))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    fun card(big: String, sub: String, buildBody: (LinearLayout) -> Unit): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = W.shadowed(this@buildSheet, W.theme.paper, 12, 2)
            setPadding(dp(12), dp(11), dp(12), dp(11))
        }
        c.addView(W.label(this, big, 14f, W.theme.ink, heavy = 2))
        if (sub.isNotBlank()) c.addView(W.label(this, sub, 11.5f, W.theme.dim).apply { setPadding(0, dp(2), 0, 0) })
        buildBody(c)
        return c
    }
    fun pair(a: View, b: View) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        cont.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ---- earn units ----
    h2("EARN UNITS")
    val adCard = card("📺 Watch an ad", "+${stateJson?.optJSONObject("app")?.optJSONObject("admob")?.optInt("reward", 50) ?: 50} SU · straight to the wallet") { body ->
        body.addView(W.button(this, "WATCH · +SU", W.theme.acc, W.theme.ink, small = true).apply {
            setOnClickListener { watchAd() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }
    val taskCard = card("🎯 Daily tasks", "") { body ->
        body.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "tasks" },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }
    pair(adCard, taskCard)
    renderTasks(taskCard)

    // ---- top up ----
    h2("TOP UP")
    val redeemCard = card("🎟️ Redeem a code", "ORU codes from drops & admins") { body ->
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            hint = "ORU-XXXXX-XXXXX"; maxLines = 1; textSize = 12.5f
            setTextColor(W.theme.ink); setHintTextColor(W.theme.dim)
            typeface = W.medium(this@buildSheet)
            background = W.stroke(this@buildSheet, W.theme.bg, 9)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(W.button(this, "GO", W.theme.acc, W.theme.ink, small = true).apply {
            setOnClickListener {
                val code = input.text.toString().trim()
                if (code.isEmpty()) { Toast.makeText(this@buildSheet, "paste a code first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                isEnabled = false
                Thread {
                    val r = try { Api.post("redeem", mapOf("code" to code)) } catch (_: Exception) { null }
                    runOnUiThread {
                        isEnabled = true
                        Toast.makeText(this@buildSheet, r?.optString("msg", "")?.ifBlank { r?.optString("error", "failed") } ?: "network hiccup — try again", Toast.LENGTH_LONG).show()
                        if (r?.optBoolean("ok", false) == true) { input.setText(""); refreshAll() }
                    }
                }.start()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }
    val packsCard = card("🛍️ SU packs & refill", "units refresh daily · the shop has the rest") { body ->
        body.addView(W.button(this, "OPEN SHOP", W.theme.paper, W.theme.ink, small = true).apply {
            setOnClickListener { toggleSheet(false); openDetail("shop") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }
    pair(redeemCard, packsCard)

    // ---- session ----
    h2("SESSION")
    val clearCard = card("🧹 Clear this archive", "wipes this page's log — the bots keep their memory") { body ->
        body.addView(W.button(this, "CLEAR", W.theme.paper, W.theme.ink, small = true).apply {
            setOnClickListener {
                Thread {
                    try { Api.post("clearhist") } catch (_: Exception) {}
                    runOnUiThread {
                        val chat = Store.currentChat()
                        chat.msgs.clear()
                        Store.save()
                        loadCurrentChat()
                        toggleSheet(false)
                        pushSys("archive cleared — the memory stays with the bots")
                    }
                }.start()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }
    val outCard = card("🚪 Disconnect", "ends this session everywhere — re-link needs a fresh code") { body ->
        body.addView(W.button(this, "LOG OUT", W.theme.acc2, W.theme.paper, small = true).apply {
            setOnClickListener { toggleSheet(false); confirmLogout() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }
    pair(clearCard, outCard)

    // ---- more ----
    h2("MORE")
    val chatsCard = card("💬 Chats", "your threads — name and credits at the foot") { body ->
        body.addView(W.button(this, "OPEN CHATS", W.theme.acc, W.theme.ink, small = true).apply {
            setOnClickListener { toggleSheet(false); toggleDrawer(true) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }
    val setCard = card("⚙ Settings", "shop · requests · inbox · identity · version") { body ->
        body.addView(W.button(this, "OPEN SETTINGS", W.theme.paper, W.theme.ink, small = true).apply {
            setOnClickListener { toggleSheet(false); toggleSettings(true) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }
    pair(chatsCard, setCard)

    panel.visibility = View.GONE // closed means GONE — no bar may ever peek at the bottom
    panel.translationY = (resources.displayMetrics.heightPixels * 0.9f)
}

internal fun MainActivity.renderTasks(card: LinearLayout) {
    val body = card.findViewWithTag("tasks") as? LinearLayout ?: return
    body.removeAllViews()
    val tasks = stateJson?.optJSONObject("tasks")?.optJSONArray("tasks") ?: JSONArray()
    if (tasks.length() == 0) {
        body.addView(W.label(this, "tasks land once you link — same ones the web wears", 11.5f, W.theme.dim).apply { setPadding(0, dp(6), 0, 0) })
        return
    }
    for (i in 0 until tasks.length()) {
        val t = tasks.getJSONObject(i)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(W.label(this, "${if (t.optBoolean("done", false)) "✅" else "•"} ${t.optString("label", "")}", 11f, W.theme.ink), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        val (track, fill) = W.bar(this)
        val have = t.optInt("have", 0); val goal = Math.max(1, t.optInt("goal", 1))
        track.post {
            val tw = track.width
            if (tw > 0) {
                fill.layoutParams = FrameLayout.LayoutParams(Math.max(1, (tw * Math.min(1f, have.toFloat() / goal)).toInt()), FrameLayout.LayoutParams.MATCH_PARENT)
                fill.requestLayout()
            }
        }
        row.addView(track, LinearLayout.LayoutParams(0, dp(7), 1f))
        row.addView(W.label(this, "${have}/${goal}", 11f, W.theme.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
    }
    val streak = stateJson?.optJSONObject("tasks")?.optInt("streak", 0) ?: 0
    if (streak > 0) body.addView(W.label(this, "🔥 $streak-day streak", 11.5f, W.theme.dim).apply { setPadding(0, dp(5), 0, 0) })
}

internal fun MainActivity.toggleSheet(open: Boolean) {
    val p = sheetPanel ?: return
    val hidden = p.layoutParams.height.toFloat().coerceAtLeast(resources.displayMetrics.heightPixels * 0.5f)
    if (open) {
        val taskBody = p.findViewWithTag("tasks") as? LinearLayout
        if (taskBody != null) renderTasks(taskBody)
        // rise from fully BELOW the screen — never peek while closed
        p.visibility = View.VISIBLE
        p.translationY = hidden
        p.animate().translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        sheetScrim?.visibility = View.VISIBLE
    } else {
        p.animate().translationY(hidden)
            .setDuration(220).setInterpolator(DecelerateInterpolator())
            .withEndAction { p.visibility = View.GONE }
            .start()
        sheetScrim?.visibility = View.GONE
    }
}

// ---------- AdMob ----------
//
// Money rule: this binary contains NO ad unit ids. The manifest carries the
// AdMob APPLICATION id; the unit ids arrive from our own backend (api/chat
// action 'adconfig') at the moment an ad is about to load, served only to a
// build signed with our key (x-orubus-sig attestation). Swap/rotate/pause a
// unit = a server change, zero app releases, and a modded build has nothing
// ad-shaped to strip.

internal fun MainActivity.admobCfg(): JSONObject? =
    stateJson?.optJSONObject("app")?.optJSONObject("admob")?.takeIf { it.optBoolean("enabled", false) }

internal fun MainActivity.initAdsIfNeeded() {
    if (adsReady) return
    adsReady = true
    try {
        com.google.android.gms.ads.MobileAds.initialize(this) {}
    } catch (_: Exception) { adsReady = false }
}

internal fun MainActivity.renderBanner(forShop: Boolean) {
    val slot = bannerSlot ?: return
    slot.removeAllViews()
    val cfg = admobCfg()
    if (!forShop || cfg == null || !adsReady) { slot.visibility = View.GONE; return }
    try {
        val ad = com.google.android.gms.ads.AdView(this)
        val cls = com.google.android.gms.ads.AdView::class.java
        cls.getMethod("setAdSize", com.google.android.gms.ads.AdSize::class.java)
            .invoke(ad, com.google.android.gms.ads.AdSize.BANNER)
        cls.getMethod("setAdUnitId", String::class.java)
            .invoke(ad, cfg.optString("bannerId", ""))
        ad.loadAd(com.google.android.gms.ads.AdRequest.Builder().build())
        slot.addView(ad)
        slot.visibility = View.VISIBLE
    } catch (_: Exception) {
        slot.visibility = View.GONE
    }
}

/** The rewarded ad — config fetched FRESH at load time from our server. */
internal fun MainActivity.watchAd() {
    if (!adsReady) initAdsIfNeeded()
    Toast.makeText(this, "loading the ad…", Toast.LENGTH_SHORT).show()
    Thread {
        val cfg = try { Api.adConfig() } catch (_: Exception) { null }
        if (cfg == null) {
            runOnUiThread { Toast.makeText(this, "ads aren't on right now", Toast.LENGTH_SHORT).show() }
            return@Thread
        }
        // the ad desk gets TWO tries — a store hiccup on the server is a
        // pacing problem, not a dead end; the toast carries the real reason
        var start = Api.adStart()
        if (start.nonce == null) {
            try { Thread.sleep(900) } catch (_: InterruptedException) {}
            start = Api.adStart()
        }
        if (start.nonce == null) {
            val why = start.error ?: "couldn't start an ad — try again in a moment"
            runOnUiThread { Toast.makeText(this, why, Toast.LENGTH_LONG).show() }
            return@Thread
        }
        val nonce = start.nonce
        val reward = cfg.optInt("reward", 20)
        val unitId = cfg.optString("rewarded", cfg.optString("rewardedId", ""))
        runOnUiThread {
            try {
                com.google.android.gms.ads.rewarded.RewardedAd.load(this, unitId,
                    com.google.android.gms.ads.AdRequest.Builder().build(),
                    object : com.google.android.gms.ads.rewarded.RewardedAdLoadCallback() {
                        override fun onAdFailedToLoad(error: com.google.android.gms.ads.LoadAdError) {
                            runOnUiThread { Toast.makeText(this@watchAd, "no ad filled — try again later", Toast.LENGTH_SHORT).show() }
                        }
                        override fun onAdLoaded(ad: com.google.android.gms.ads.rewarded.RewardedAd) {
                            ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                                override fun onAdDismissedFullScreenContent() { /* next ad loads fresh */ }
                            }
                            runOnUiThread {
                                ad.show(this@watchAd) { item ->
                                    Thread {
                                        val r = Api.adClaim(nonce)
                                        runOnUiThread {
                                            if (r?.optBoolean("ok", false) == true) {
                                                Toast.makeText(this@watchAd, "+${r.optInt("gained", reward)} SU — straight to your wallet", Toast.LENGTH_LONG).show()
                                                r.optJSONObject("wallet")?.let { renderWallet(it) }
                                            } else {
                                                Toast.makeText(this@watchAd, r?.optString("error", "reward didn't land") ?: "reward didn't land", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }.start()
                                }
                            }
                        }
                    })
            } catch (e: Exception) {
                Toast.makeText(this, "ads aren't ready — try again in a bit", Toast.LENGTH_SHORT).show()
            }
        }
    }.start()
}

/**
 * The App Open ad — a real one: shows when the app comes back from the
 * background, capped hard by the server (appOpenGapMin, default 4h) and by
 * a local floor so a flapping ad never bullies the chat. Never fires while
 * a call is live or a send is in flight.
 */
internal fun MainActivity.maybeAppOpenAd() {
    if (!built || calling || sending) return
    if (Api.cookieValue.isBlank()) return
    Thread {
        val cfg = try { Api.adConfig() } catch (_: Exception) { null } ?: return@Thread
        val gapMin = cfg.optInt("appOpenGapMin", 4).coerceAtLeast(1)
        val last = Store.prefs().getLong("appopen_at", 0L)
        if (System.currentTimeMillis() - last < gapMin * 60_000L) return@Thread
        if (calling || sending) return@Thread
        if (!adsReady) runOnUiThread { initAdsIfNeeded() }
        val unitId = cfg.optString("appOpen", "")
        if (unitId.isBlank()) return@Thread
        runOnUiThread {
            try {
                com.google.android.gms.ads.appopen.AppOpenAd.load(this, unitId,
                    com.google.android.gms.ads.AdRequest.Builder().build(),
                    object : com.google.android.gms.ads.appopen.AppOpenAd.AppOpenAdLoadCallback() {
                        override fun onAdFailedToLoad(error: com.google.android.gms.ads.LoadAdError) { /* silent — it's an opener, not a job */ }
                        override fun onAdLoaded(ad: com.google.android.gms.ads.appopen.AppOpenAd) {
                            if (calling || sending) return
                            ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                                override fun onAdDismissedFullScreenContent() { /* fresh load next time */ }
                            }
                            Store.prefs().edit().putLong("appopen_at", System.currentTimeMillis()).apply()
                            try { ad.show(this@maybeAppOpenAd) } catch (_: Exception) {}
                        }
                    })
            } catch (_: Exception) { /* ads never get in the chat's way */ }
        }
    }.start()
}
