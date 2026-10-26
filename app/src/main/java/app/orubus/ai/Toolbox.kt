package app.orubus.ai

import android.annotation.SuppressLint
import android.graphics.Color
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The toolbox — the AI's own hands on the device, inside a safe.
 *
 * A Keystore-backed AES-256-GCM key (non-exportable, hardware-held where the
 * phone supports it) encrypts every file before it touches disk. The folder
 * lives in the app's private storage and NEVER leaves it — no other app can
 * see it, and the user's real storage is only touched when they export.
 *
 * The chat lane gets these as built-in tools (same ⟦TOOL⟧ contract as MCP):
 *   create_file · read_file · list_files · delete_file · deploy_site
 * deploy_site is the website pipeline: the AI writes an .html file into the
 * toolbox, the app deploys it to the server, and the URL lands on the
 * message as a "View generated website" button.
 */
object Toolbox {

    private const val KEY_ALIAS = "orubus_toolbox"
    private const val GCM_TAG_LEN = 128
    private const val MAX_FILE_BYTES = 200_000
    private const val MAX_FILES = 40
    private const val QUOTA_BYTES = 2_000_000

    private fun dir(): java.io.File = java.io.File(AppCtx.get().filesDir, "toolbox").apply { mkdirs() }

    // ---------- the key ----------

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val ct = c.doFinal(plain)
        return ByteArray(1 + iv.size + ct.size).also {
            it[0] = iv.size.toByte()
            System.arraycopy(iv, 0, it, 1, iv.size)
            System.arraycopy(ct, 0, it, 1 + iv.size, ct.size)
        }
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ct = blob.copyOfRange(1 + ivLen, blob.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_LEN, iv))
        return c.doFinal(ct)
    }

    // ---------- names ----------

    private fun safeName(raw: String): String {
        val n = raw.trim().replace(Regex("[^a-zA-Z0-9 ._\\-]"), "_").replace(Regex("\\.{2,}"), ".").take(60)
        return if (n.isBlank() || n == ".") "file.txt" else n
    }

    private fun fileOf(name: String): java.io.File = java.io.File(dir(), safeName(name) + ".bin")
    private fun metaOf(): JSONObject = try {
        JSONObject(java.io.File(dir(), "index.json").readText())
    } catch (_: Exception) { JSONObject() }

    @Synchronized
    private fun saveMeta(m: JSONObject) {
        try { java.io.File(dir(), "index.json").writeText(m.toString()) } catch (_: Exception) {}
    }

    // ---------- the operations (UI + tools both ride these) ----------

    fun list(): List<Triple<String, Int, Long>> {
        val m = metaOf()
        val names = m.optJSONArray("files") ?: JSONArray()
        val out = mutableListOf<Triple<String, Int, Long>>()
        for (i in 0 until names.length()) {
            val o = names.optJSONObject(i) ?: continue
            val n = o.optString("name")
            val f = fileOf(n)
            if (f.exists()) out.add(Triple(n, o.optInt("size", f.length().toInt()), o.optLong("ts", f.lastModified())))
        }
        return out.sortedByDescending { it.third }
    }

    fun createFile(name: String, content: String, overwrite: Boolean = true): String {
        val n = safeName(name)
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) throw Exception("that file is over ${MAX_FILE_BYTES / 1000} KB — split it into parts")
        val files = list()
        val existing = files.firstOrNull { it.first == n }
        if (!overwrite && existing != null) throw Exception("\"$n\" already exists — read it first, then rewrite it if needed")
        if (existing == null) {
            if (files.size >= MAX_FILES) throw Exception("the toolbox holds $MAX_FILES files — clear one out first")
            val used = files.sumOf { it.second }
            if (used + bytes.size > QUOTA_BYTES) throw Exception("the toolbox is at its 2 MB quota — delete something first")
        }
        fileOf(n).writeBytes(encrypt(bytes))
        val m = metaOf()
        val arr = m.optJSONArray("files") ?: JSONArray()
        val found = (0 until arr.length()).firstOrNull { arr.getJSONObject(it).optString("name") == n }
        val entry = JSONObject().put("name", n).put("size", bytes.size).put("ts", System.currentTimeMillis())
        if (found != null) arr.put(found, entry) else arr.put(entry)
        m.put("files", arr)
        saveMeta(m)
        return n
    }

    fun readFile(name: String): String {
        val f = fileOf(name)
        if (!f.exists()) throw Exception("no file named \"${safeName(name)}\" in the toolbox")
        return try { String(decrypt(f.readBytes()), Charsets.UTF_8) } catch (_: Exception) { throw Exception("that file couldn't be unlocked") }
    }

    fun deleteFile(name: String): Boolean {
        val f = fileOf(name)
        val ok = f.delete()
        val m = metaOf()
        val arr = m.optJSONArray("files") ?: JSONArray()
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("name") != safeName(name)) keep.put(o)
        }
        m.put("files", keep)
        saveMeta(m)
        return ok
    }

    // ---------- the AI's builtin tools (ToolRig contract) ----------

    fun toolMenu(): List<Pair<String, String>> = listOf(
        "create_file" to " — write a file into the encrypted toolbox. args: {\"name\":\"page.html\",\"content\":\"…\"}",
        "read_file" to " — read a toolbox file back. args: {\"name\":\"page.html\"}",
        "list_files" to " — list the toolbox contents. args: {}",
        "delete_file" to " — remove a toolbox file. args: {\"name\":\"old.txt\"}",
        "deploy_site" to " — deploy a toolbox .html file as a live website and return its URL. args: {\"name\":\"page.html\"}",
    )

    /** Execute one builtin tool — returns the text result, never throws empty. */
    fun callTool(tool: String, args: JSONObject): String {
        return try {
            when (tool) {
                "create_file" -> {
                    val n = createFile(args.optString("name", "file.txt"), args.optString("content", ""))
                    "OK — \"$n\" saved in the toolbox (${args.optString("content", "").length} chars)"
                }
                "read_file" -> readFile(args.optString("name", "")).take(6000).ifBlank { "(empty file)" }
                "list_files" -> {
                    val l = list()
                    if (l.isEmpty()) "(the toolbox is empty)"
                    l.joinToString("\n") { "${it.first} · ${it.second}B" }
                }
                "delete_file" -> if (deleteFile(args.optString("name", ""))) "OK — deleted" else "nothing by that name"
                "deploy_site" -> "DEPLOY_STARTED:${args.optString("name", "")}"
                else -> "unknown toolbox tool: $tool"
            }
        } catch (e: Exception) {
            "TOOL ERROR: ${(e.message ?: "it failed").take(300)}"
        }
    }

    // ---------- deploy ----------

    /**
     * Blocking deploy of a toolbox .html file. Returns "name|url" — the UI
     * turns that into the site card. The model's deploy_site tool call
     * returns DEPLOY_STARTED:<file> and the TURN LAYER finishes the hop by
     * calling this, so the URL lands where messages can carry it.
     *
     * `project` (optional) claims a pretty /users/<project> URL instead of
     * a random slug — the instance-sites lane.
     */
    fun deploySite(name: String, project: String? = null): Pair<String, String> {
        val html = readFile(name)
        if (!name.endsWith(".html", true) && !html.contains("<html", true)) {
            throw Exception("that's not a web page — deploy needs an .html file")
        }
        val r = if (project != null) {
            Api.appSite("deploy", mapOf("project" to project, "html" to html))
        } else {
            Api.appSite("deploy", mapOf("name" to name.removeSuffix(".html"), "html" to html))
        }
        if (!r.optBoolean("ok", false)) throw Exception(r.optString("error", "the deploy desk refused"))
        return name to r.optString("url", "")
    }

    // ---------- the sheet lives at file scope (top-level extension) ----------
}

/**
 * One raw completion through the stable ladder: the device lane first, the
 * server's raw 'appstep' lane second (billed like a normal turn, refunded
 * when it fails too). The browser agent's ladder, shared with site builds.
 */
internal fun MainActivity.rawAgentStep(prompt: String, tier: String): String {
    val msgs = JSONArray().put(JSONObject().apply {
        put("role", "user")
        put("content", JSONArray().put(JSONObject().put("type", "text").put("text", prompt)))
    })
    return try {
        DuckEngine.chat(msgs, DuckEngine.modelFor(tier))
    } catch (de: DuckEngine.NetworkDeadException) {
        throw de
    } catch (e: Exception) {
        val fb = Api.post("appstep", mapOf("messages" to msgs, "tier" to tier))
        runOnUiThread { try { fb.optJSONObject("wallet")?.let { renderWallet(it) } } catch (_: Throwable) {} }
        if (!fb.optBoolean("ok", false)) throw Exception(fb.optString("error", "the lane is catching its breath"))
        fb.optString("answer", "").ifBlank { throw Exception("the lane came back empty") }
    }
}

/**
 * INSTANCE SITES — the on-device website builder.
 *
 * The user picks a name and says what they want ("create a website for me:")
 * — the AI writes the files directly on this device into the encrypted
 * toolbox, the deploy desk publishes them under a pretty URL
 * (<sites-host>/users/<project>), and a live link lands in the thread with
 * an open button. A same-name rebuild by the same identity just updates it.
 */
internal fun MainActivity.buildInstanceSite(
    briefRaw: String,
    projectRaw: String,
    onStatus: (String) -> Unit,
    onDone: (name: String, url: String) -> Unit,
    onFail: (String) -> Unit,
) {
    val brief = briefRaw.trim()
    val project = projectRaw.lowercase().replace(Regex("[^a-z0-9-]"), "").trim('-')
    if (brief.isBlank()) { onFail("say what the site should be first ✍"); return }
    if (project.length < 3) { onFail("site names are 3+ letters, numbers or dashes"); return }
    Thread {
        try {
            onStatus("designing your site…")
            val contract = "You are a website generator. Write ONE complete, self-contained index.html file for this brief: $brief\n" +
                "Rules: modern, clean, responsive design with real content (never lorem ipsum); inline CSS and JS only; " +
                "no external requests (system fonts, inline SVG icons); include a working mobile layout; give it personality.\n" +
                "Reply with the RAW HTML ONLY — no markdown fences, no commentary. Start with <!doctype html>."
            var html = rawAgentStep(contract, tier)
            html = html.trim().removePrefix("```html").removePrefix("```HTML").removePrefix("```").removeSuffix("```").trim()
            if (!html.contains("<!doctype", true) && !html.contains("<html", true)) {
                throw Exception("the site came back mangled — try again in a second")
            }
            if (html.length > 145_000) html = html.take(145_000)
            onStatus("saving to the toolbox…")
            Toolbox.createFile("$project.html", html)
            onStatus("deploying…")
            val r = Api.appSite("deploy", mapOf("project" to project, "html" to html))
            if (!r.optBoolean("ok", false)) throw Exception(r.optString("error", "the deploy desk said no"))
            val url = r.optString("url", "")
            if (url.isBlank()) throw Exception("the deploy desk came back without a link")
            onDone("$project.html", url)
        } catch (de: DuckEngine.NetworkDeadException) {
            onFail("no connection right now — the build waits, try again when you're back")
        } catch (e: Exception) {
            onFail(e.message ?: "the build slipped — try again in a moment")
        }
    }.start()
}

@SuppressLint("SetTextI18n")
internal fun MainActivity.openToolboxSheet() {
        val scrim = View(this).apply { setBackgroundColor(Color.argb(90, 20, 20, 20)) }
        screen.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val h = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        val r18 = W.dpf(this, 18)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(W.theme.bg)
                cornerRadii = floatArrayOf(r18, r18, r18, r18, 0f, 0f, 0f, 0f)
                setStroke(dp(2), W.theme.ink)
            }
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val hcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hcol.addView(W.label(this, "TOOLBOX", 20f, W.theme.ink, heavy = 2))
        hcol.addView(W.label(this, "encrypted on this device — the AI's own hands", 11f, W.theme.dim))
        head.addView(hcol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = W.iconBtn(this, W.Icon.CLOSE, W.theme.paper)
        head.addView(close, LinearLayout.LayoutParams(dp(34), dp(34)))
        panel.addView(head)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })

        val used = Toolbox.list().sumOf { it.second }
        panel.addView(
            W.label(this, "${Toolbox.list().size} files · ${(used / 1000.0).toInt()} KB of 2 MB encrypted", 11f, W.theme.dim).apply { setPadding(dp(4), dp(6), 0, 0) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val dismiss = {
            panel.animate().translationY(h.toFloat()).setDuration(200).start()
            scrim.animate().alpha(0f).setDuration(200).withEndAction { screen.removeView(scrim); screen.removeView(panel) }.start()
        }
        scrim.setOnClickListener { dismiss() }
        close.setOnClickListener { dismiss() }

        /** The instance-sites builder block — name + brief + BUILD & DEPLOY. */
        fun siteBuilder(reload: () -> Unit): LinearLayout {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = W.shadowed(this@openToolboxSheet, W.theme.paper, 14, 2)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            box.addView(W.label(this, "⚡ INSTANCE SITES", 15f, W.theme.ink, heavy = 2))
            box.addView(W.label(this, "your own live site at a pretty link — files written right on this device", 11f, W.theme.dim).apply { setPadding(0, dp(2), 0, dp(8)) })

            fun bfield(hint: String): EditText = EditText(this).apply {
                this.hint = hint
                textSize = 13.5f
                maxLines = 1
                setTextColor(W.theme.ink); setHintTextColor(W.theme.dim)
                typeface = W.regular(this@openToolboxSheet)
                background = W.stroke(this@openToolboxSheet, W.theme.bg, 10)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val nameF = bfield("site name — my-shop, portfolio, links…")
            box.addView(nameF, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            val briefF = bfield("what's the site? — \"a bakery landing page…\"").apply { maxLines = 3; gravity = Gravity.TOP }
            box.addView(briefF, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

            val st = W.label(this, "", 11.5f, W.theme.acc2).apply { visibility = View.GONE }
            val go = W.button(this, "CREATE & DEPLOY", W.theme.acc, W.theme.ink, radiusDp = 10, small = true)
            go.setOnClickListener {
                buildInstanceSite(
                    briefF.text.toString(),
                    nameF.text.toString(),
                    onStatus = { s -> runOnUiThread { st.text = s; st.visibility = View.VISIBLE } },
                    onDone = { n, url ->
                        runOnUiThread {
                            st.text = "live at $url ⚡"
                            pushSys("🚀 deployed — $url")
                            Store.append(Store.currentChat(), false, "🚀 deployed \"$n\"", site = url)
                            loadCurrentChat()
                            reload()
                        }
                    },
                    onFail = { m -> runOnUiThread { st.text = m; st.visibility = View.VISIBLE } },
                )
            }
            box.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            box.addView(st, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            return box
        }

        fun render() {
            list.removeAllViews()
            // ---- INSTANCE SITES: the builder input (writes on device, deploys, live url) ----
            list.addView(siteBuilder { render() })
            val files = Toolbox.list()
            if (files.isEmpty()) {
                list.addView(W.label(this, "empty for now.\nask the AI in chat — \"build me a pricing page and deploy it\" — and watch the toolbox fill up.", 12.5f, W.theme.dim).apply {
                    setPadding(dp(4), dp(10), dp(4), dp(4)); setLineSpacing(dp(2).toFloat(), 1f)
                })
            }
            for ((name, size, ts) in files) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = W.shadowed(this@openToolboxSheet, W.theme.paper, 12, 2)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    W.press(this)
                }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(W.label(this, if (name.endsWith(".html")) "🧱 $name" else "📄 $name", 14f, W.theme.ink, heavy = 2))
                col.addView(W.label(this, "$size B · ${timeAgo(ts)}", 11f, W.theme.dim))
                card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                card.setOnClickListener {
                    try {
                        val content = Toolbox.readFile(name)
                        orubusDialog(name, content.take(1800) + if (content.length > 1800) "\n…" else "", "CLOSE")
                    } catch (e: Exception) {
                        pushSys(e.message ?: "couldn't open it")
                    }
                }
                card.setOnLongClickListener {
                    val items = mutableListOf<Triple<String, Boolean, () -> Unit>>(
                        Triple("Copy contents", false) {
                            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("orubus-toolbox", try { Toolbox.readFile(name) } catch (_: Exception) { "" }))
                            Toast.makeText(this, "copied ⚡", Toast.LENGTH_SHORT).show()
                        },
                        Triple("Deploy as site", false) {
                            Thread {
                                try {
                                    val (n, url) = Toolbox.deploySite(name)
                                    runOnUiThread {
                                        pushSys("🚀 deployed — $url")
                                        Store.append(Store.currentChat(), false, "🚀 deployed \"$n\"", site = url)
                                        loadCurrentChat()
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread { pushSys(e.message ?: "the deploy slipped") }
                                }
                            }.start()
                        },
                        Triple("Delete", true) {
                            Toolbox.deleteFile(name); render()
                        },
                    )
                    showFingerMenu(card, resources.displayMetrics.widthPixels / 2f, resources.displayMetrics.heightPixels / 3f, items)
                    true
                }
                list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
        }
        render()

        screen.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { gravity = Gravity.BOTTOM })
        panel.translationY = h.toFloat()
        panel.animate().translationY(0f).setDuration(230).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
}
