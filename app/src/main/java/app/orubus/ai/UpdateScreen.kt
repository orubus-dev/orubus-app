package app.orubus.ai

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The update screen — the updater's own full stage, in the house skin.
 * Built from scratch (the reference screenshots only borrow a layout
 * skeleton: a status label, a big title, a version pill row, a progress
 * bar with live percentage, a ready badge with an install button).
 *
 * States (driven by Updater.Work, polled at 200ms):
 *   DOWNLOADING — live percentage, MB counter, resumable by design
 *   REBUILDING  — the delta patch being applied on device
 *   READY       — verified artifact, one-tap install
 *   INSTALLING  — handed off to the system installer
 *   FAILED      — honest reason + RETRY that continues from disk
 *
 * Closing the screen never stops the work — the download continues and
 * re-opens exactly where it was (that's the idempotency contract).
 */
object UpdateScreen {

    fun open(activity: MainActivity, latest: Updater.Latest, delta: Updater.Delta?) {
        Updater.begin(activity, latest, delta)
    }

    fun show(activity: MainActivity) {
        if (activity.updateOverlay != null) return // already on stage
        val ov = Overlay(activity)
        activity.updateOverlay = ov
        activity.screen.addView(
            ov,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
    }

    fun close(activity: MainActivity) {
        activity.updateOverlay?.let { activity.screen.removeView(it) }
        activity.updateOverlay = null
    }

    // ---------- the drawn badges ----------

    /** Yolk circle with an ink check — the READY mark. */
    private class CheckBadge(c: android.content.Context) : View(c) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = W.theme.acc; style = Paint.Style.FILL }
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = W.theme.ink; style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 3.2f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = W.theme.ink; style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 2.6f
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val s = width.toFloat()
            val cx = s / 2f
            val cy = s / 2f
            canvas.drawCircle(cx, cy, s * 0.42f, fill)
            canvas.drawCircle(cx, cy, s * 0.42f, ring)
            canvas.drawLine(cx - s * 0.16f, cy + s * 0.01f, cx - s * 0.04f, cy + s * 0.13f, ink)
            canvas.drawLine(cx - s * 0.04f, cy + s * 0.13f, cx + s * 0.18f, cy - s * 0.12f, ink)
        }
    }

    /** Paper circle with an ink down-into-tray arrow — the DOWNLOADING mark. */
    private class DownBadge(c: android.content.Context) : View(c) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = W.theme.ink; style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 2.6f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = W.theme.paper; style = Paint.Style.FILL }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val s = width.toFloat()
            val cx = s / 2f
            val cy = s / 2f
            canvas.drawCircle(cx, cy, s * 0.42f, fill)
            canvas.drawCircle(cx, cy, s * 0.42f, ink)
            canvas.drawLine(cx, cy - s * 0.17f, cx, cy + s * 0.08f, ink)
            canvas.drawLine(cx, cy + s * 0.08f, cx - s * 0.11f, cy - s * 0.03f, ink)
            canvas.drawLine(cx, cy + s * 0.08f, cx + s * 0.11f, cy - s * 0.03f, ink)
            canvas.drawLine(cx - s * 0.16f, cy + s * 0.17f, cx + s * 0.16f, cy + s * 0.17f, ink)
        }
    }

    // ---------- the stage ----------

    @SuppressLint("SetTextI18n")
    private class Overlay(private val host: MainActivity) : FrameLayout(host) {

        private val dp = { v: Int -> W.dp(host, v) }
        private val handler = Handler(Looper.getMainLooper())
        private var lastPhase: Updater.Phase? = null
        private var lastPct = -1
        private var lastMsg = ""

        // the pieces that repaint without a full rebuild
        private var statusLabel: android.widget.TextView? = null
        private var titleLabel: android.widget.TextView? = null
        private var subLabel: android.widget.TextView? = null
        private var barFill: View? = null
        private var pctLabel: android.widget.TextView? = null
        private var stageLabel: android.widget.TextView? = null
        private var actionButton: android.widget.TextView? = null
        private var badgeHolder: FrameLayout? = null

        private val tick = object : Runnable {
            override fun run() {
                if (host.updateOverlay !== this@Overlay) return
                paint()
                handler.postDelayed(this, 200)
            }
        }

        init {
            setBackgroundColor(W.theme.bg)
            isClickable = true
            val scroll = ScrollView(host).apply { overScrollMode = View.OVER_SCROLL_NEVER }
            val center = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(30), dp(22), dp(30))
            }
            val card = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = W.shadowed(host, W.theme.paper, 18, 3)
                setPadding(dp(20), dp(24), dp(20), dp(20))
            }

            // close — the work keeps running behind the app
            val closeBtn = W.iconBtn(host, W.Icon.CLOSE, W.theme.paper)
            closeBtn.setOnClickListener { UpdateScreen.close(host) }
            val closeRow = FrameLayout(host)
            closeRow.addView(closeBtn, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END))
            card.addView(closeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2) })

            badgeHolder = FrameLayout(host).apply {
                addView(CheckBadge(host), FrameLayout.LayoutParams(dp(74), dp(74), Gravity.CENTER))
            }
            card.addView(badgeHolder, LinearLayout.LayoutParams(dp(74), dp(74)))

            statusLabel = W.label(host, "UPDATING", 12.5f, W.theme.acc2, heavy = 2).apply { gravity = Gravity.CENTER }
            card.addView(statusLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

            titleLabel = W.label(host, "Updating Orubus", 21f, W.theme.ink, heavy = 2).apply { gravity = Gravity.CENTER }
            card.addView(titleLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

            // the version journey: vCurrent -> vTarget
            val pillRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            pillRow.addView(W.pill(host, "v" + Updater.installedName(), false))
            pillRow.addView(W.label(host, "→", 15f, W.theme.dim, heavy = 2).apply { setPadding(dp(8), 0, dp(8), 0) })
            pillRow.addView(W.pill(host, "v" + (Updater.work?.name ?: "?"), true))
            card.addView(pillRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

            subLabel = W.label(host, "", 13f, W.theme.dim).apply {
                gravity = Gravity.CENTER
                setLineSpacing(dp(2).toFloat(), 1f)
            }
            card.addView(subLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

            // progress: stage … NN% above the bar, counter under it
            val progHead = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            stageLabel = W.label(host, "In progress", 12.5f, W.theme.dim, heavy = 1)
            pctLabel = W.label(host, "0%", 12.5f, W.theme.ink, heavy = 2)
            progHead.addView(stageLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            progHead.addView(pctLabel)
            card.addView(progHead, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

            val (track, fill) = W.bar(host)
            barFill = fill
            card.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(6) })

            actionButton = W.button(host, "INSTALL NOW", W.theme.acc, W.theme.ink, radiusDp = 12)
            card.addView(actionButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })

            center.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            scroll.addView(center)
            addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            paint()
            handler.post(tick)
        }

        fun onRemoved() { handler.removeCallbacks(tick) }

        @SuppressLint("SetTextI18n")
        private fun paint() {
            val w = Updater.work ?: return
            val ph = w.phase
            // full rebuild only when the phase changed; numbers repaint every tick
            if (ph != lastPhase) {
                lastPhase = ph
                lastPct = -1
                rebuildFor(ph, w)
            }
            when (ph) {
                Updater.Phase.DOWNLOADING -> {
                    val p = w.pct()
                    if (p != lastPct) {
                        lastPct = p
                        pctLabel?.text = if (w.size > 0) "$p%" else "…"
                        (barFill?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                            val tw = (barFill?.parent as? View)?.width ?: 0
                            lp.width = if (w.size > 0) (tw * p) / 100 else 0
                            barFill?.requestLayout()
                        }
                    }
                    subLabel?.text = "In progress — ${Updater.mbPublic(w.done)} of ${Updater.mbPublic(if (w.size > 0) w.size else w.done)}" +
                        "\nyou can leave this screen — it keeps going and picks up right here"
                }
                Updater.Phase.REBUILDING -> { if (w.message != lastMsg) { lastMsg = w.message; subLabel?.text = w.message } }
                Updater.Phase.FAILED -> { if (w.message != lastMsg) { lastMsg = w.message; subLabel?.text = w.message } }
                else -> {}
            }
        }

        @SuppressLint("SetTextI18n")
        private fun rebuildFor(ph: Updater.Phase, w: Updater.Work) {
            val bh = badgeHolder ?: return
            bh.removeAllViews()
            actionButton?.visibility = View.GONE
            barFill?.visibility = View.GONE
            pctLabel?.text = ""
            stageLabel?.text = ""
            subLabel?.visibility = View.VISIBLE
            when (ph) {
                Updater.Phase.DOWNLOADING -> {
                    bh.addView(DownBadge(host), FrameLayout.LayoutParams(dp(74), dp(74), Gravity.CENTER))
                    statusLabel?.text = if (w.kind == "delta") "DOWNLOADING PATCH" else "DOWNLOADING"
                    titleLabel?.text = "Downloading the update"
                    stageLabel?.text = "In progress"
                    barFill?.visibility = View.VISIBLE
                    actionButton?.visibility = View.GONE
                }
                Updater.Phase.REBUILDING -> {
                    bh.addView(ThinkingDots(host, W.theme.ink), FrameLayout.LayoutParams(dp(74), dp(40), Gravity.CENTER))
                    statusLabel?.text = "REBUILDING"
                    titleLabel?.text = "Rebuilding the app on device"
                    stageLabel?.text = "Working"
                    actionButton?.visibility = View.GONE
                }
                Updater.Phase.READY -> {
                    bh.addView(CheckBadge(host), FrameLayout.LayoutParams(dp(74), dp(74), Gravity.CENTER))
                    statusLabel?.text = "READY"
                    titleLabel?.text = "The update is ready to install"
                    subLabel?.text = "Download complete · sha256 verified"
                    actionButton?.apply {
                        visibility = View.VISIBLE
                        text = "INSTALL NOW"
                        setOnClickListener { Updater.readyApk()?.let { apk -> Updater.install(host, apk) } }
                    }
                    (barFill?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                        lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                        barFill?.requestLayout()
                    }
                    lastPct = 100
                }
                Updater.Phase.INSTALLING -> {
                    bh.addView(DownBadge(host), FrameLayout.LayoutParams(dp(74), dp(74), Gravity.CENTER))
                    statusLabel?.text = "INSTALLING"
                    titleLabel?.text = "Handing off to the system installer"
                    subLabel?.text = "confirm in the system dialog — nothing else to do"
                }
                Updater.Phase.FAILED -> {
                    bh.addView(DownBadge(host), FrameLayout.LayoutParams(dp(74), dp(74), Gravity.CENTER))
                    statusLabel?.text = "FAILED"
                    titleLabel?.text = "The update hit a snag"
                    actionButton?.apply {
                        visibility = View.VISIBLE
                        text = "RETRY"
                        setOnClickListener { Updater.retry(host) }
                    }
                }
            }
        }
    }
}
