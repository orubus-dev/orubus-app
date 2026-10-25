package app.orubus.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The design kit — a 1:1 port of the web app's CSS (public/chat.html).
 *
 * Neo-brutalism, exactly as the site wears it: cream paper, 2dp ink borders,
 * hard 2dp offset shadows, yolk primaries, garnet errors, pink accents,
 * Space Grotesk everywhere. The shop themes (classic / midnight / ocean /
 * bubblegum) are the same variable swaps the site does on <body>.
 *
 *   :root{ --bg:#F4EDE3; --card:#F6F6F6; --paper:#FFFDF7; --ink:#141414;
 *          --acc:#FFC94B; --acc2:#90353D; --pop:#F2A9C4; --dim:#6f6659;
 *          --bd:2px solid var(--ink); --shs:2px 2px 0 var(--ink) }
 */
object W {

    data class T(
        val bg: Int, val card: Int, val paper: Int, val ink: Int,
        val acc: Int, val acc2: Int, val pop: Int, val dim: Int,
    )

    val CLASSIC = T(0xFFF4EDE3.toInt(), 0xFFF6F6F6.toInt(), 0xFFFFFDF7.toInt(), 0xFF141414.toInt(), 0xFFFFC94B.toInt(), 0xFF90353D.toInt(), 0xFFF2A9C4.toInt(), 0xFF6F6659.toInt())
    val MIDNIGHT = T(0xFF14161D.toInt(), 0xFF1B1E27.toInt(), 0xFF20242F.toInt(), 0xFFECEAF6.toInt(), 0xFFFFD75E.toInt(), 0xFFC25B64.toInt(), 0xFFF2A9C4.toInt(), 0xFF9A96AB.toInt())
    val OCEAN = T(0xFFE4F0F6.toInt(), 0xFFEFF7FB.toInt(), 0xFFFBFEFF.toInt(), 0xFF0F2430.toInt(), 0xFF5FB4DB.toInt(), 0xFFB34A5A.toInt(), 0xFFF2C4A9.toInt(), 0xFF54707E.toInt())
    val BUBBLEGUM = T(0xFFFBE9F0.toInt(), 0xFFFDF1F6.toInt(), 0xFFFFFAFC.toInt(), 0xFF33131F.toInt(), 0xFFF49AC1.toInt(), 0xFF8E3B5B.toInt(), 0xFFFFD75E.toInt(), 0xFF8A6472.toInt())

    /** The active skin — the same one the wallet wears on the web. */
    @Volatile var theme: T = CLASSIC

    fun themeFor(id: String): T = when (id) {
        "midnight" -> MIDNIGHT
        "ocean" -> OCEAN
        "bubblegum" -> BUBBLEGUM
        else -> CLASSIC
    }

    // ---------- type ----------

    private var reg: Typeface? = null
    private var med: Typeface? = null
    private var bold: Typeface? = null

    @Synchronized
    fun regular(c: Context): Typeface {
        if (reg == null) reg = try {
            Typeface.createFromAsset(c.assets, "space_regular.ttf")
        } catch (_: Exception) { Typeface.DEFAULT }
        return reg!!
    }

    @Synchronized
    fun medium(c: Context): Typeface {
        if (med == null) med = try {
            Typeface.createFromAsset(c.assets, "space_medium.ttf")
        } catch (_: Exception) { Typeface.DEFAULT }
        return med!!
    }

    @Synchronized
    fun bold(c: Context): Typeface {
        if (bold == null) bold = try {
            Typeface.createFromAsset(c.assets, "space_bold.ttf")
        } catch (_: Exception) { Typeface.create("sans-serif-black", Typeface.NORMAL) }
        return bold!!
    }

    fun dp(c: Context, v: Int): Int = Math.round(v * c.resources.displayMetrics.density)
    fun dpf(c: Context, v: Int): Float = v * c.resources.displayMetrics.density

    // ---------- primitives ----------

    /** `border: 2px solid var(--ink)` — the card stroke. */
    fun stroke(c: Context, fill: Int, radiusDp: Int, strokeDp: Int = 2, color: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(c, radiusDp).toFloat()
            setStroke(Math.max(1, dp(c, strokeDp)), if (color == 0) theme.ink else color)
        }

    /** `box-shadow: 2px 2px 0 var(--ink)` — the hard offset shadow behind a card. */
    fun shadowed(c: Context, fill: Int, radiusDp: Int, offDp: Int = 2, strokeDp: Int = 2): LayerDrawable {
        val off = dp(c, offDp)
        val sh = GradientDrawable().apply {
            setColor(theme.ink)
            cornerRadius = dp(c, radiusDp).toFloat()
        }
        val face = stroke(c, fill, radiusDp, strokeDp)
        return LayerDrawable(arrayOf(sh, face)).apply { setLayerInset(1, 0, 0, off, off) }
    }

    /** The web's :active — press onto the shadow, release back. */
    fun press(v: View) {
        val c = v.context
        val off = dpf(c, 2)
        v.setOnTouchListener { vv, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> vv.animate().translationX(off).translationY(off).setDuration(40).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> vv.animate().translationX(0f).translationY(0f).setDuration(40).start()
            }
            false // never consume — the click still fires
        }
    }

    /** A styled label — the workhorse text view. */
    fun label(c: Context, text: CharSequence, sizeSp: Float, color: Int, heavy: Int = 0, lines: Int = 0): TextView =
        TextView(c).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            typeface = when (heavy) {
                2 -> bold(c)
                1 -> medium(c)
                else -> regular(c)
            }
            if (lines > 0) maxLines = lines
        }

    /** Tier pill / tag — `.pill` on the web. */
    fun pill(c: Context, labelText: String, on: Boolean): TextView = TextView(c).apply {
        text = labelText
        textSize = 12f
        typeface = bold(c)
        setTextColor(if (on) theme.acc else theme.ink)
        background = shadowed(c, if (on) theme.ink else theme.paper, 99, 2)
        gravity = Gravity.CENTER
        setPadding(dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 4))
    }

    /** Solid action button — `.sheet button` with the accent variants. */
    fun button(c: Context, labelText: String, bg: Int, fg: Int, radiusDp: Int = 10, small: Boolean = false): TextView =
        TextView(c).apply {
            text = labelText
            textSize = 13f
            typeface = bold(c)
            gravity = Gravity.CENTER
            setTextColor(fg)
            background = shadowed(c, bg, radiusDp, 2)
            setPadding(dp(c, 12), dp(c, if (small) 7 else 9), dp(c, 12), dp(c, if (small) 7 else 9))
            press(this)
        }

    /** 36dp square icon button — `.iconbtn` (yolk fill by default). The glyph
     *  is DRAWN (IconView), never a font glyph: fonts squeeze or drop ☰ ⚙ ✕ ←,
     *  and a drawn icon is pixel-comfortable on every device. */
    fun iconBtn(c: Context, icon: Icon, bg: Int = -1): FrameLayout = FrameLayout(c).apply {
        background = shadowed(c, if (bg == -1) theme.acc else bg, 10, 2)
        press(this)
        isClickable = true
        addView(IconView(c, icon, theme.ink), FrameLayout.LayoutParams(dp(c, 19), dp(c, 19), Gravity.CENTER))
    }

    enum class Icon { MENU, GEAR, CLOSE, BACK, SEND, WAVE, IMAGE, DOTS }

    /** Hand-drawn icon — 2.1dp rounded ink strokes, centered, never squeezed. */
    class IconView(c: Context, private val icon: Icon, inkColor: Int) : View(c) {

        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 2.1f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val s = Math.min(w, h)
            val cx = w / 2f
            val cy = h / 2f
            when (icon) {
                Icon.MENU -> {
                    val half = s * 0.30f
                    val gap = s * 0.21f
                    for (i in -1..1) canvas.drawLine(cx - half, cy + i * gap, cx + half, cy + i * gap, stroke)
                }
                Icon.GEAR -> {
                    val r = s * 0.27f
                    canvas.drawCircle(cx, cy, r, stroke)
                    canvas.drawCircle(cx, cy, s * 0.115f, stroke)
                    for (i in 0 until 8) {
                        val a = Math.PI * i / 4.0
                        val cos = Math.cos(a).toFloat()
                        val sin = Math.sin(a).toFloat()
                        canvas.drawLine(cx + r * cos, cy + r * sin, cx + r * 1.42f * cos, cy + r * 1.42f * sin, stroke)
                    }
                }
                Icon.CLOSE -> {
                    val half = s * 0.27f
                    canvas.drawLine(cx - half, cy - half, cx + half, cy + half, stroke)
                    canvas.drawLine(cx - half, cy + half, cx + half, cy - half, stroke)
                }
                Icon.BACK -> {
                    val half = s * 0.28f
                    canvas.drawLine(cx + half, cy, cx - half, cy, stroke)
                    canvas.drawLine(cx - half, cy, cx - half + half * 0.66f, cy - half * 0.66f, stroke)
                    canvas.drawLine(cx - half, cy, cx - half + half * 0.66f, cy + half * 0.66f, stroke)
                }
                Icon.SEND -> {
                    val path = Path()
                    path.moveTo(cx - s * 0.30f, cy - s * 0.27f)
                    path.lineTo(cx + s * 0.36f, cy)
                    path.lineTo(cx - s * 0.30f, cy + s * 0.27f)
                    path.lineTo(cx - s * 0.13f, cy)
                    path.close()
                    canvas.drawPath(path, fill)
                }
                Icon.WAVE -> {
                    // the voice-call mark — a little waveform, rounded bars
                    val heights = floatArrayOf(0.22f, 0.52f, 0.34f, 0.66f, 0.30f)
                    val gap = s * 0.185f
                    val barW = s * 0.085f
                    val left = cx - (heights.size - 1) * gap / 2f
                    for ((i, hh) in heights.withIndex()) {
                        val x = left + i * gap
                        val half = s * hh / 2f
                        canvas.drawLine(x, cy - half, x, cy + half, stroke)
                    }
                }
                Icon.IMAGE -> {
                    // a tiny photograph — frame, sun, mountains
                    val inset = s * 0.14f
                    val r = android.graphics.RectF(cx - s / 2f + inset, cy - s / 2f + inset, cx + s / 2f - inset, cy + s / 2f - inset)
                    canvas.drawRoundRect(r, dp2(c), dp2(c), stroke)
                    canvas.drawCircle(cx - s * 0.14f, cy - s * 0.12f, s * 0.055f, fill)
                    val m = Path()
                    m.moveTo(r.left + s * 0.04f, r.bottom - s * 0.05f)
                    m.lineTo(cx - s * 0.02f, cy + s * 0.06f)
                    m.lineTo(cx + s * 0.12f, r.bottom - s * 0.05f)
                    m.moveTo(cx + s * 0.06f, cy + s * 0.14f)
                    m.lineTo(cx + s * 0.17f, cy + s * 0.02f)
                    m.lineTo(r.right - s * 0.04f, r.bottom - s * 0.05f)
                    canvas.drawPath(m, stroke)
                }
                Icon.DOTS -> {
                    // the "more" mark — three dots, vertical
                    for (i in -1..1) canvas.drawCircle(cx, cy + i * s * 0.22f, s * 0.06f, fill)
                }
            }
        }

        private fun dp2(c: Context): Float = c.resources.displayMetrics.density * 2f
    }

    /** The progress bar — `.bar` with the `.bar i` fill. Returns (track, fill). */
    fun bar(c: Context): Pair<FrameLayout, View> {
        val track = FrameLayout(c).apply {
            background = stroke(c, theme.paper, 99, 2)
        }
        val fill = View(c).apply {
            background = GradientDrawable().apply {
                setColor(theme.acc)
                cornerRadius = dp(c, 99).toFloat()
            }
        }
        track.addView(fill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        return track to fill
    }

    /** Chat bubble radius: 14dp everywhere, 4dp on one corner (the tail). */
    fun bubbleShape(c: Context, fill: Int, tailBottomRight: Boolean): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            val r = dp(c, 14).toFloat()
            val t = dp(c, 4).toFloat()
            cornerRadii = if (tailBottomRight)
                floatArrayOf(r, r, r, r, t, t, r, r) // tl, tr, br, bl — br is the tail
            else
                floatArrayOf(r, r, r, r, r, r, r, r)
            setStroke(dp(c, 2), theme.ink)
        }
}

/**
 * The thinking indicator — three ink dots with a HEARTBEAT: each dot swells
 * and fades on a staggered rhythm, like the app is literally thinking in
 * pulses. When deep research fires, the caption beside it reads "thinking…"
 * and the whole thing is tappable — the interpretation lives in there.
 */
class ThinkingDots(c: Context, inkColor: Int) : View(c) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inkColor
        style = Paint.Style.FILL
    }
    private var phase = 0.0
    private val frame = 33L // ~30fps, cheap
    private val tick = object : Runnable {
        override fun run() {
            phase += 0.13
            invalidate()
            postDelayed(this, frame)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(tick) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = Math.min(width, height).toFloat()
        val cy = height / 2f
        val gap = s * 0.30f
        val r0 = s * 0.115f
        val left = width / 2f - gap
        for (i in 0 until 3) {
            // the beat: a heartbeat-ish double swell per cycle, staggered per dot
            val t = (phase - i * 0.55) % (2 * Math.PI)
            val beat = Math.max(0.0, Math.sin(t)) * 0.65 + Math.max(0.0, Math.sin(t * 2.0 + 0.6)) * 0.35
            val r = r0 * (0.55 + 0.65 * beat).toFloat()
            paint.alpha = (70 + 185 * beat).toInt().coerceIn(0, 255)
            canvas.drawCircle(left + i * gap, cy, r, paint)
        }
        paint.alpha = 255
    }
}

/**
 * The call waveform — a wide band of rounded bars breathing like a real
 * call. `energy` 0..1 sets how alive it looks: listening is lively,
 * speaking is strong, idle settles to a calm pulse.
 */
class WaveView(c: Context, inkColor: Int, accent: Int) : View(c) {

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }
    private val bars = 28
    private var phase = 0.0

    @Volatile var energy: Float = 0.55f

    private val tick = object : Runnable {
        override fun run() {
            phase += 0.16
            invalidate()
            postDelayed(this, 33)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(tick) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = h / 2f
        val gap = w / bars
        val bw = gap * 0.38f
        ink.strokeWidth = bw
        for (i in 0 until bars) {
            val x = gap * i + gap / 2f
            val a = Math.sin(phase + i * 0.55)
            val b = Math.sin(phase * 0.63 + i * 1.31)
            val c = Math.sin(phase * 1.7 + i * 0.21)
            val amp = (0.30 + 0.70 * Math.abs(a * 0.55 + b * 0.30 + c * 0.15)) * energy
            val half = Math.max(0.04f, (amp * h * 0.42f).toFloat())
            ink.alpha = (110 + 120 * amp).toInt().coerceIn(0, 255)
            canvas.drawLine(x, mid - half, x, mid + half, ink)
        }
    }
}
