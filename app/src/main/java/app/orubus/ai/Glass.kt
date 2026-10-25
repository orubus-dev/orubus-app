package app.orubus.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

/**
 * Liquid glass brutalism — the v2 design kit.
 *
 * Deep-ink base with drifting brand orbs (pink / yolk / cream / violet);
 * every surface sits on it as a glass panel: translucent fill, refractive
 * gradient border, top specular streak, soft shadow. Brutalist voice on top:
 * heavy uppercase type, chunky offsets, yolk-on-ink primaries.
 */
object G {
    // palette
    const val INK = 0xFF120D1A.toInt() // deep ink-violet base
    const val INK2 = 0xFF1C1428.toInt()
    const val PINK = 0xFFFF7EB6.toInt()
    const val PINK_SOFT = 0xFFFF9EC4.toInt()
    const val YOLK = 0xFFFFD75E.toInt()
    const val CREAM = 0xFFFFF3E9.toInt()
    const val VIOLET = 0xFF8E7BFF.toInt()
    const val MUTED = 0xFFC9B8D4.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BAD = 0xFFFF6B7A.toInt()
    const val GOOD = 0xFF7BE8B0.toInt()

    fun dp(c: Context, v: Int): Int = Math.round(v * c.resources.displayMetrics.density)

    fun black(c: Context): Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

    // ---- glass panel background: shadow layer + translucent fill + refractive border ----
    fun glass(c: Context, radiusDp: Int, fillAlpha: Int = 26, strokeDp: Int = 1): android.graphics.drawable.Drawable {
        val r = G.dp(c, radiusDp).toFloat()
        val fill = GradientDrawable().apply {
            colors = intArrayOf(Color.argb(fillAlpha + 10, 255, 255, 255), Color.argb(fillAlpha - 12, 255, 255, 255))
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TL_BR
            cornerRadius = r
        }
        val border = GradientDrawable().apply {
            colors = intArrayOf(Color.argb(120, 255, 255, 255), Color.argb(70, 255, 158, 196), Color.argb(50, 255, 215, 94))
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TL_BR
            cornerRadius = r
        }
        return LayerDrawable(arrayOf(border, fill)).apply {
            // fill inset by the stroke → the refractive edge reads as a rim
            setLayerInset(1, G.dp(c, strokeDp), G.dp(c, strokeDp), G.dp(c, strokeDp), G.dp(c, strokeDp))
        }
    }

    /** Specular highlight strip laid over the top edge of a panel. */
    fun specular(c: Context): View = object : View(c) {
        override fun onDraw(canvas: Canvas) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, width.toFloat(), 0f,
                    intArrayOf(Color.TRANSPARENT, Color.argb(70, 255, 255, 255), Color.argb(20, 255, 158, 196), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
            }
            canvas.drawRoundRect(RectF(dp2(14f), 0f, width - dp2(14f), dp2(1.6f)), dp2(1f), dp2(1f), p)
        }
        private fun dp2(v: Float) = v * resources.displayMetrics.density
    }

    /** Brutalist primary button: yolk fill, ink text, hard offset shadow. */
    fun brutal(c: Context, label: String, bgColor: Int = YOLK, fgColor: Int = INK, radiusDp: Int = 14): TextView {
        val tv = TextView(c).apply {
            text = label
            textSize = 14f
            typeface = black(c)
            isAllCaps = true
            gravity = android.view.Gravity.CENTER
            setTextColor(fgColor)
            setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12))
            val shadow = GradientDrawable().apply { setColor(0xFF000000.toInt()); cornerRadius = dp(c, radiusDp).toFloat() }
            val face = GradientDrawable().apply { setColor(bgColor); cornerRadius = dp(c, radiusDp).toFloat() }
            val inset = G.dp(c, 3)
            background = LayerDrawable(arrayOf(shadow, face)).apply {
                setLayerInset(1, 0, 0, inset, inset) // ink offset = the brutal drop
            }
        }
        return tv
    }

    /** Glass chip (mode pills, tags). */
    fun chip(c: Context, label: String, selected: Boolean): TextView {
        return TextView(c).apply {
            text = label
            textSize = 11.5f
            typeface = black(c)
            isAllCaps = true
            gravity = android.view.Gravity.CENTER
            setPadding(dp(c, 14), dp(c, 7), dp(c, 14), dp(c, 7))
            if (selected) {
                background = glass(c, 12, fillAlpha = 120)
                setTextColor(INK)
                // yolk wash
                val y = GradientDrawable().apply { setColor(Color.argb(230, 255, 215, 94)); cornerRadius = dp(c, 12).toFloat() }
                background = y
            } else {
                background = glass(c, 12, fillAlpha = 20)
                setTextColor(MUTED)
            }
        }
    }
}

/**
 * The living backdrop: deep ink + four drifting brand orbs. Orbs are painted
 * as radial gradients (already feathered), drifting on slow sine paths —
 * the "liquid" under all the glass.
 */
class LiquidBackground(c: Context) : View(c) {
    private val orbs = listOf(
        Orb(0xFFB35C8A.toInt(), 0.22f, 0.28f, 0.62f, 0.00021f, 0.0f),
        Orb(0xFFB89A3C.toInt(), 0.16f, 0.80f, 0.24f, 0.00017f, 2.1f),
        Orb(0xFF5C4A99.toInt(), 0.26f, 0.62f, 0.78f, 0.00013f, 4.2f),
        Orb(0xFF9E5368.toInt(), 0.14f, 0.12f, 0.72f, 0.00024f, 1.2f),
    )
    private val ink = Paint().apply { color = G.INK }

    private class Orb(val color: Int, val radiusF: Float, val ax: Float, val ay: Float, val speed: Float, val phase: Float)

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ink)
        val t = System.currentTimeMillis()
        for (o in orbs) {
            val cx = (o.ax * width + Math.sin((t * o.speed + o.phase).toDouble()) * width * 0.14).toFloat()
            val cy = (o.ay * height + Math.cos((t * o.speed * 0.8 + o.phase).toDouble()) * height * 0.10).toFloat()
            val rad = o.radiusF * Math.max(width, height)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, rad, intArgb(150, o.color), intArgb(0, o.color), Shader.TileMode.CLAMP)
            }
            canvas.drawCircle(cx, cy, rad, p)
        }
        postInvalidateDelayed(60)
    }

    private fun intArgb(a: Int, rgb: Int): Int = Color.argb(a, (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
}
