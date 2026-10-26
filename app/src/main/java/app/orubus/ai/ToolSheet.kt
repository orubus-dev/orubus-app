package app.orubus.ai

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * TOOL SHEET — the tool popup, one shape for every exposed tool.
 *
 * Before v3.7.3 the tools opened as plain "pop up input boxes" (an
 * AlertDialog with a field). This is the ChatGPT-style upgrade: tap a tool
 * and a bottom sheet slides up with the tool's NAME, its honest cost, its
 * workspace (prompt, fields), an inline STATUS line that breathes while the
 * tool works, and the RESULT rendered right inside the sheet with its own
 * actions — while everything still lands in the thread like any answer.
 *
 * Paint, browse, toolbox, sites and connectors all ride this one shape.
 */
@SuppressLint("SetTextI18n")
internal class ToolSheet(
    private val act: MainActivity,
    private val icon: String,
    private val title: String,
    private val subtitle: String,
    private val tall: Boolean = false,
) {

    private val scrim = View(act).apply { setBackgroundColor(android.graphics.Color.argb(90, 20, 20, 20)) }
    private val panel = LinearLayout(act)
    internal val body = LinearLayout(act)
    private val statusLabel = TextView(act)
    private var dismissed = false

    init {
        val h = (act.resources.displayMetrics.heightPixels * (if (tall) 0.82f else 0.62f)).toInt()
        val r18 = W.dpf(act, 18)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = GradientDrawable().apply {
            setColor(W.theme.bg)
            cornerRadii = floatArrayOf(r18, r18, r18, r18, 0f, 0f, 0f, 0f)
            setStroke(act.dp(2), W.theme.ink)
        }
        panel.setPadding(act.dp(16), act.dp(14), act.dp(16), act.dp(16))

        // ---- header: icon + title + honest subtitle + close ----
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val hcol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        hcol.addView(W.label(act, "$icon $title", 20f, W.theme.ink, heavy = 2))
        hcol.addView(W.label(act, subtitle, 11f, W.theme.dim))
        head.addView(hcol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = W.iconBtn(act, W.Icon.CLOSE, W.theme.paper)
        head.addView(close, LinearLayout.LayoutParams(act.dp(34), act.dp(34)))
        panel.addView(head)
        panel.addView(View(act).apply { setBackgroundColor(W.theme.ink) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(2)).apply { topMargin = act.dp(8) })

        // ---- the tool's workspace ----
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false }
        body.orientation = LinearLayout.VERTICAL
        scroll.addView(body)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = act.dp(10) })

        // ---- the breathing status line ----
        statusLabel.textSize = 12.5f
        statusLabel.setTextColor(W.theme.acc2)
        statusLabel.typeface = W.medium(act)
        statusLabel.setPadding(act.dp(4), act.dp(8), act.dp(4), 0)
        statusLabel.visibility = View.GONE
        panel.addView(statusLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        scrim.setOnClickListener { dismiss() }
        close.setOnClickListener { dismiss() }
    }

    fun show() {
        if (dismissed) return
        val h = (act.resources.displayMetrics.heightPixels * (if (tall) 0.82f else 0.62f)).toInt()
        act.screen.addView(scrim, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        act.screen.addView(panel, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { gravity = Gravity.BOTTOM })
        panel.translationY = h.toFloat()
        panel.animate().translationY(0f).setDuration(230).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        val h = (act.resources.displayMetrics.heightPixels * (if (tall) 0.82f else 0.62f)).toInt()
        panel.animate().translationY(h.toFloat()).setDuration(200).start()
        scrim.animate().alpha(0f).setDuration(200).withEndAction {
            try { act.screen.removeView(scrim) } catch (_: Throwable) {}
            try { act.screen.removeView(panel) } catch (_: Throwable) {}
        }.start()
    }

    /** Inline progress — the sheet breathes while the tool works. */
    fun status(s: String) {
        act.runOnUiThread {
            try {
                statusLabel.text = s
                statusLabel.visibility = if (s.isBlank()) View.GONE else View.VISIBLE
            } catch (_: Throwable) {}
        }
    }

    fun active(): Boolean = !dismissed
}
