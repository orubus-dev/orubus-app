package app.orubus.ai

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Image generation — the native paint lane.
 *
 * The chat call itself paints: the same request the words ride, with
 * canDelegateImageGeneration on. Partial previews stream in while the real
 * picture cooks, so "generating" looks like painting, not waiting.
 *
 * Billing: appgen mints the 100-SU paint token BEFORE the first pixel; the
 * device lane paints; appdone closes it — and if the phone's lane slips,
 * appgenimg lets the server's own lane finish on the SAME token, and a
 * double slip refunds whole. The user never pays for a lane that failed.
 */
object ImageGen {

    /** In-flight guard — one paint at a time, the lane is a shared road. */
    @Volatile internal var painting = false

    /** Decode a base64 jpeg into a bounded bitmap (memory-safe on old phones). */
    fun decode(b64: String, maxSide: Int = 1600): Bitmap? = try {
        val raw = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
    } catch (_: Exception) { null }

    /** Persist a generated image into the app's image store (the bubble renders it). */
    fun storeBitmap(bmp: Bitmap): String = try {
        val name = "gen-${System.currentTimeMillis()}.jpg"
        java.io.FileOutputStream(java.io.File(AppCtx.get().filesDir, "images").apply { mkdirs() }.resolve(name)).use {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        name
    } catch (_: Exception) { "" }

    /** Save into the phone's gallery (29+ scoped storage; older devices keep it in-app). */
    fun saveToGallery(activity: MainActivity, name: String) {
        val bmp = Store.loadImage(name) ?: run {
            Toast.makeText(activity, "that image is gone from this device", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "orubus-$name")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Orubus")
                }
                val uri: Uri? = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    activity.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    Toast.makeText(activity, "saved to Pictures/Orubus ⚡", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            Toast.makeText(activity, "kept in the app's gallery — long-press the image to share", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(activity, "the gallery said no — the image stays in the app", Toast.LENGTH_SHORT).show()
        }
    }

    /** Share a generated image through whatever the phone offers. */
    fun shareImage(activity: MainActivity, name: String) {
        val bmp = Store.loadImage(name) ?: return
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "orubus-$name")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Orubus")
            }
            val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            activity.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, "share the art"))
        } catch (_: Exception) {
            Toast.makeText(activity, "sharing slipped — try again in a second", Toast.LENGTH_SHORT).show()
        }
    }
}

/** The live paint price (server-tunable; 100 is the known-good default). */
internal fun MainActivity.imageGenCost(): Int = try {
    stateJson?.optJSONObject("app")?.optInt("imageGenCost", 100) ?: 100
} catch (_: Exception) { 100 }

/**
 * The paint request, end to end. Blocking paint runs on the caller thread;
 * all UI lands on the main thread. `row` is the in-chat paint card that the
 * partial previews breathe life into.
 */
internal fun MainActivity.startImageGen(promptRaw: String) {
    val prompt = promptRaw.trim()
    if (prompt.isBlank()) return
    if (ImageGen.painting || sending) {
        pushSys("one paint at a time — let the current one land")
        return
    }
    if (Api.cookieValue.isBlank()) {
        pushSys("painting rides the wallet — link first (⚙ → identity)")
        return
    }
    val chat = Store.currentChat()
    pushUser("🎨 $prompt")
    Store.append(chat, true, "🎨 $prompt")

    val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(4), dp(2), dp(4)) }
    val w = (resources.displayMetrics.widthPixels * 0.62f).toInt()
    val preview = ImageView(this).apply {
        adjustViewBounds = true
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
            }
        }
        background = W.stroke(this@startImageGen, W.theme.acc, 14, 2)
        setImageBitmap(paintPlaceholder())
        layoutParams = LinearLayout.LayoutParams(w, (w * 0.75f).toInt())
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    row.addView(preview)
    val cap = W.label(this, "painting · ${imageGenCost()} SU — watch it form…", 12f, W.theme.dim).apply { setPadding(dp(2), dp(5), dp(2), 0) }
    row.addView(cap)
    chatList?.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    scrollEnd()

    ImageGen.painting = true
    Thread {
        var token = ""
        var imgName = ""
        var failLine = ""
        try {
            val spend = Api.appGen(prompt)
            spend.wallet?.let { runOnUiThread { renderWallet(it) } }
            if (!spend.ok) throw Exception(spend.error.ifBlank { "the wallet couldn't cover the paint" })
            token = spend.token

            var got: DuckEngine.ImageGenResult? = null
            try {
                got = DuckEngine.chatImageGen(prompt, DuckEngine.modelFor(tier)) { b64, _, _ ->
                    val bmp = ImageGen.decode(b64, 1024) ?: return@chatImageGen
                    runOnUiThread {
                        preview.layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT)
                        preview.setImageBitmap(bmp)
                        cap.text = "painting… the details are landing"
                    }
                }
            } catch (de: Exception) {
                if (de is DuckEngine.NetworkDeadException) throw de
                // the phone's paint lane slipped — the server lane finishes on the SAME token
                val fb = Api.appGenImg(token, prompt)
                fb?.optJSONObject("wallet")?.let { runOnUiThread { renderWallet(it) } }
                val b64 = fb?.optString("b64", "") ?: ""
                if (fb == null || !fb.optBoolean("ok", false) || b64.isBlank()) {
                    throw Exception(fb?.optString("error", "the paint lane slipped — the units went straight back") ?: "the paint lane slipped — the units went straight back")
                }
                got = DuckEngine.ImageGenResult(b64, fb.optInt("w", 0), fb.optInt("h", 0), fb.optString("format", "jpeg"), fb.optString("model", ""), "")
            }

            val bmp = ImageGen.decode(got!!.b64) ?: throw Exception("the picture came back unreadable — one more try?")
            imgName = ImageGen.storeBitmap(bmp)
            if (imgName.isBlank()) throw Exception("the picture couldn't be kept on this device")
            Api.appDone(token, "[painted an image: ${prompt.take(80)}]")
            token = ""
            runOnUiThread {
                removeView(row)
                pushBotImage("🎨 $prompt", imgName, got.model)
                Store.append(chat, false, "🎨 $prompt", img = imgName)
            }
        } catch (e: Exception) {
            failLine = e.message ?: "the paint lane slipped"
            if (token.isNotBlank()) Thread { Api.appFail(token) }.start()
            runOnUiThread {
                removeView(row)
                pushErr(if (Api.isNetErr(e)) "no lane for the paint — nothing was spent, try again when you're back" else failLine)
            }
        } finally {
            ImageGen.painting = false
        }
    }.start()
}

private fun MainActivity.paintPlaceholder(): android.graphics.Bitmap {
    val w = 480
    val h = 360
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.drawColor(W.theme.paper)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = W.theme.acc; strokeWidth = dp(3).toFloat(); style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND }
    for (i in 0 until 4) {
        val y = h * (0.3f + i * 0.14f)
        c.drawLine(w * 0.2f, y, w * (0.45f + 0.1f * (i % 2)), y, p)
    }
    p.color = W.theme.acc2
    c.drawCircle(w * 0.72f, h * 0.34f, dp(16).toFloat(), p)
    return bmp
}

/** The bot's art card — image, caption, long-press for save/share/regen. */
@SuppressLint("SetTextI18n")
internal fun MainActivity.pushBotImage(caption: String, imgName: String, model: String = "") {
    hideHello()
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val bmp = Store.loadImage(imgName)
    if (bmp != null) {
        val w = (resources.displayMetrics.widthPixels * 0.62f).toInt()
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
            background = W.stroke(this@pushBotImage, W.theme.acc, 14, 2)
            setImageBitmap(bmp)
            setOnClickListener { showImageFullScreen(imgName) }
        }
        col.addView(iv)
    }
    col.addView(TextView(this).apply {
        text = caption + (if (model.isNotBlank()) "\n· $model" else "")
        textSize = 12f
        setTextColor(W.theme.dim)
        typeface = W.medium(this@pushBotImage)
        setPadding(dp(3), dp(5), dp(3), 0)
    })
    W.press(col)
    col.setOnLongClickListener {
        val items = mutableListOf<Triple<String, Boolean, () -> Unit>>()
        items.add(Triple("Save to gallery", false) { ImageGen.saveToGallery(this, imgName) })
        items.add(Triple("Share", false) { ImageGen.shareImage(this, imgName) })
        items.add(Triple("Paint it again", false) {
            startImageGen(caption.removePrefix("🎨").trim())
        })
        showFingerMenu(col, resources.displayMetrics.widthPixels / 2f, resources.displayMetrics.heightPixels / 3f, items)
        true
    }
    chatList?.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    scrollEnd()
}

/** The paint dialog — prompt in, cost chip honest, generate. */
internal fun MainActivity.openImageGenSheet() {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), 0) }
    val input = EditText(this).apply {
        hint = "a small orange cat, sticker style…"
        textSize = 14f; minLines = 2; maxLines = 4; gravity = Gravity.TOP
        setTextColor(W.theme.ink); setHintTextColor(W.theme.dim)
        typeface = W.regular(this@openImageGenSheet)
        background = W.stroke(this@openImageGenSheet, W.theme.paper, 12)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    box.addView(input)
    box.addView(W.label(this, "${imageGenCost()} SU per image · painted right on this device", 11.5f, W.theme.dim).apply { setPadding(0, dp(8), 0, 0) })
    orubusDialog(
        "Paint an image",
        "",
        "PAINT", "CANCEL",
        bodyView = box,
        onPositive = {
            val prompt = input.text.toString().trim()
            if (prompt.isNotBlank()) startImageGen(prompt)
        }
    )
}
