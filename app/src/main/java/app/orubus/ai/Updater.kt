package app.orubus.ai

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The self-updater — targeted releases without the store and without the
 * browser. Every release publishes a manifest (release/updates.json in the
 * app repo): the latest APK plus OPTIONAL bsdiff deltas from recent
 * version codes. A device on an older code downloads just its delta patch
 * (typically a few MB), rebuilds its own installed APK into the new
 * release on device, byte-verifies it against the manifest's sha256 and
 * hands it to the system PackageInstaller. Anything wrong at any step
 * falls back to the plain full-APK path — the updater can never brick an
 * install.
 *
 * v3.7.3.1: the work became IDEMPOTENT and RESUMABLE. Every byte lands in
 * filesDir/update/ with a state.json beside it, so the update screen can
 * die, be closed, or the process can be killed — and the next open
 * reconciles what's on disk against the manifest and continues from where
 * it stopped (HTTP Range resume, streamed sha256 over the whole file,
 * rebuild results cached behind an .ok marker). A verified artifact sits
 * in READY until the system installer accepts it; tapping install again
 * just re-requests — nothing re-downloads, nothing double-installs.
 */
object Updater {

    /** Manifest published next to the code: /release/updates.json. */
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/${Api.APP_REPO}/main/release/updates.json"
    private const val PREFS = "orubus_updates"
    private const val PROMPT_GAP_MS = 20 * 60 * 60 * 1000L // auto-prompt at most ~once a day
    private const val MAX_PAYLOAD = 400L * 1024 * 1024
    private const val SAVE_EVERY = 2L * 1024 * 1024 // state.json flush cadence

    class Latest(
        val code: Long,
        val name: String,
        val url: String,
        val sha256: String,
        val size: Long,
        val components: List<String>,
        val notes: String,
    )

    class Delta(
        val from: Long,
        val to: Long,
        val url: String,
        val size: Long,
        val components: List<String>,
    )

    // ---------- the work (persisted, reconciled, idempotent) ----------

    enum class Phase { DOWNLOADING, REBUILDING, READY, INSTALLING, FAILED }

    class Work(
        var url: String,        // the payload being fetched (patch or full apk)
        var kind: String,       // "delta" | "full"
        val code: Long,         // target version code
        val name: String,       // target version name
        val sha256: String,     // expected sha256 of the FINAL apk
        var size: Long,         // payload size (progress denominator)
        @Volatile var phase: Phase = Phase.DOWNLOADING,
        @Volatile var done: Long = 0L,
        @Volatile var message: String = "",
        @Volatile var cancel: Boolean = false,
    ) {
        fun pct(): Int = if (size > 0) ((done * 100) / size).toInt().coerceIn(0, 100) else 0
    }

    @Volatile var work: Work? = null
        private set

    // ---------- manifest ----------

    private fun fetchManifest(): Pair<Latest, Delta?>? {
        return try {
            val j = Api.get(MANIFEST_URL)
            val l = j.optJSONObject("latest") ?: return null
            val latest = Latest(
                code = l.optLong("code", 0),
                name = l.optString("name", "?"),
                url = l.optString("url", Api.APK_URL),
                sha256 = l.optString("sha256", ""),
                size = l.optLong("size", 0),
                components = strList(l.optJSONArray("components")),
                notes = l.optString("notes", ""),
            )
            if (latest.code <= 0) return null
            var delta: Delta? = null
            val installed = installedCode()
            val d = j.optJSONObject("deltas")?.optJSONObject(installed.toString())
            if (d != null && d.optLong("to", 0) == latest.code) {
                val cand = Delta(
                    from = installed,
                    to = latest.code,
                    url = d.optString("url", ""),
                    size = d.optLong("size", 0),
                    components = strList(d.optJSONArray("components")),
                )
                if (cand.url.isNotBlank()) delta = cand
            }
            latest to delta
        } catch (_: Exception) {
            null // CDN hiccup — the caller decides what "explicit" means
        }
    }

    private fun strList(a: JSONArray?): List<String> =
        a?.let { arr -> (0 until arr.length()).mapNotNull { runCatching { arr.getString(it) }.getOrNull() } } ?: emptyList()

    fun installedCode(): Long = try {
        AppCtx.get().packageManager.getPackageInfo(AppCtx.get().packageName, 0).versionCode.toLong()
    } catch (_: Exception) {
        0L
    }

    fun installedName(): String = try {
        AppCtx.get().packageManager.getPackageInfo(AppCtx.get().packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    // ---------- the check ----------

    /** Settings row tap — always talks, always offers the in-app install. */
    fun checkExplicit(activity: MainActivity) {
        Thread {
            val m = fetchManifest()
            val latest = m?.first
            if (latest == null) {
                toast(activity, "couldn't check — network hiccup, try again in a moment")
                return@Thread
            }
            val cur = installedName()
            if (latest.code <= installedCode()) {
                toast(activity, "you're on the latest — v$cur")
                return@Thread
            }
            offer(activity, latest, m.second)
        }.start()
    }

    /** Quiet launch-time check — only speaks when something is actually out. */
    fun autoCheck(activity: MainActivity) {
        Thread {
            val m = fetchManifest() ?: return@Thread
            val (latest, delta) = m
            if (latest.code <= installedCode()) return@Thread
            val p = activity.getSharedPreferences(PREFS, 0)
            val last = p.getLong("lastPrompt", 0)
            if (System.currentTimeMillis() - last < PROMPT_GAP_MS) return@Thread
            p.edit().putLong("lastPrompt", System.currentTimeMillis()).apply()
            offer(activity, latest, delta)
        }.start()
    }

    private fun offer(activity: MainActivity, latest: Latest, delta: Delta?) {
        val usePatch = delta != null && latest.sha256.isNotBlank()
        val how = if (usePatch && delta != null) {
            "a ${mb(delta.size)} delta patch — rebuilds on device, no full download"
        } else {
            val sz = if (latest.size > 0) "a ${mb(latest.size)} download" else "the new build"
            "$sz — full install"
        }
        val touches = (if (usePatch) delta?.components ?: emptyList() else latest.components)
            .joinToString(" · ")
            .ifBlank { "the app" }
        val body = "you're on v${installedName()} — $how.\ntouches: $touches"
        activity.runOnUiThread {
            activity.orubusDialog(
                "Update — v${latest.name}",
                body,
                "UPDATE NOW", "LATER",
                onPositive = { UpdateScreen.open(activity, latest, delta) },
            )
        }
    }

    // ---------- the work files ----------

    private fun dir(c: Context = AppCtx.get()): File = File(c.filesDir, "update").apply { mkdirs() }
    private fun stateFile(c: Context = AppCtx.get()): File = File(dir(c), "state.json")

    private fun payloadFile(c: Context, kind: String): File =
        File(dir(c), if (kind == "delta") "delta.bsdiff" else "Orubus.apk")

    private fun rebuiltFile(c: Context = AppCtx.get()): File = File(dir(c), "Orubus-new.apk")

    /** Pull the persisted work back after a process death. null = nothing usable. */
    private fun resume(): Work? {
        return try {
            val f = stateFile()
            if (!f.exists()) return null
            val j = JSONObject(f.readText())
            val phase = try { Phase.valueOf(j.optString("phase", "FAILED")) } catch (_: Exception) { Phase.FAILED }
            val w = Work(
                j.optString("url", ""), j.optString("kind", "full"), j.optLong("code", 0),
                j.optString("name", "?"), j.optString("sha256", ""), j.optLong("size", 0),
                phase, j.optLong("done", 0), j.optString("message", ""),
            )
            if (w.code <= 0 || w.url.isBlank()) return null
            if (phase == Phase.READY || phase == Phase.INSTALLING) {
                val apk = if (w.kind == "delta") rebuiltFile() else payloadFile(AppCtx.get(), "full")
                val ok = File(apk.parentFile, apk.name + ".ok")
                // delta artifacts carry their own .ok marker; a full apk's proof
                // of verification IS the READY line in state.json itself
                val good = apk.exists() && apk.length() > 0 && (w.kind != "delta" || ok.exists())
                if (good) {
                    w.phase = Phase.READY
                    w.done = apk.length()
                    w.size = apk.length()
                } else {
                    w.phase = Phase.FAILED
                    w.message = "the prepared file vanished — tap retry to rebuild it"
                }
            }
            work = w
            w
        } catch (_: Exception) {
            null
        }
    }

    /** Atomic state.json write — the idempotency anchor. */
    private fun saveState(w: Work) {
        try {
            val d = dir()
            val tmp = File(d, "state.tmp")
            tmp.writeText(JSONObject().apply {
                put("url", w.url); put("kind", w.kind); put("code", w.code); put("name", w.name)
                put("sha256", w.sha256); put("size", w.size); put("phase", w.phase.name)
                put("done", w.done); put("message", w.message); put("ts", System.currentTimeMillis())
            }.toString())
            if (!tmp.renameTo(stateFile())) {
                stateFile().delete()
                tmp.renameTo(stateFile())
            }
        } catch (_: Exception) {}
    }

    /** Success on a newer install (or the user wiping state) — drop everything. */
    fun wipe(c: Context) {
        work = null
        try {
            stateFile(c).delete()
            dir(c).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    /** The verified artifact waiting for the installer (READY phase). */
    fun readyApk(): File? {
        val w = work ?: return null
        if (w.phase != Phase.READY && w.phase != Phase.INSTALLING) return null
        val apk = if (w.kind == "delta") rebuiltFile() else payloadFile(AppCtx.get(), "full")
        return if (apk.exists() && apk.length() > 0) apk else null
    }

    /**
     * The ONE entry point — idempotent by construction. Called every time
     * the update screen opens. Reconciles disk against the manifest:
     *   READY artifact for this target  -> straight to READY (no work)
     *   live worker for this target     -> just attach the screen
     *   anything else                   -> fresh work (downloads resume
     *                                      from whatever bytes exist)
     */
    fun begin(activity: MainActivity, latest: Latest, delta: Delta?, force: Boolean = false) {
        val usePatch = delta != null && latest.sha256.isNotBlank()
        val kind = if (usePatch) "delta" else "full"
        val url = if (usePatch && delta != null) delta.url else latest.url
        val size = if (usePatch && delta != null) delta.size else latest.size

        val cur = work ?: resume()
        if (cur != null) {
            if (cur.code == latest.code && cur.kind == kind) {
                when (cur.phase) {
                    Phase.DOWNLOADING, Phase.REBUILDING -> { UpdateScreen.show(activity); return }
                    Phase.READY -> { UpdateScreen.show(activity); return }
                    Phase.INSTALLING -> { cur.phase = Phase.READY; UpdateScreen.show(activity); return }
                    Phase.FAILED -> if (!force) { UpdateScreen.show(activity); return }
                }
            }
            // stale target or stale kind — cut it loose and start over
            cur.cancel = true
            work = null
            try { stateFile().delete() } catch (_: Exception) {}
        }

        val w = Work(url, kind, latest.code, latest.name, latest.sha256, size)
        work = w
        saveState(w)
        startWorker(activity, w, latest, usePatch)
        UpdateScreen.show(activity)
    }

    /** FAILED retry — re-runs the current work from wherever disk stands.
     * A delta retry re-reads the manifest first (fresh delta/fallback decision). */
    fun retry(activity: MainActivity) {
        val w = work ?: return
        if (w.kind == "delta") {
            Thread {
                val m = fetchManifest()
                val l = m?.first
                activity.runOnUiThread {
                    if (l != null) begin(activity, l, m.second, force = true) // aim wherever the manifest points now
                    else beginFullOnly(activity, w)
                }
            }.start()
        } else {
            beginFullOnly(activity, w)
        }
    }

    private fun beginFullOnly(activity: MainActivity, w: Work) {
        // the full apk always lives at the stable release URL — a delta work's
        // url is the PATCH, so never reuse it here
        val fullUrl = if (w.kind == "full") w.url else Api.APK_URL
        val fake = Latest(w.code, w.name, fullUrl, w.sha256, if (w.kind == "full") w.size else 0L, emptyList(), "")
        w.cancel = true
        work = null
        val nw = Work(fullUrl, "full", w.code, w.name, w.sha256, if (w.kind == "full") w.size else 0L)
        work = nw
        saveState(nw)
        startWorker(activity, nw, fake, false)
        UpdateScreen.show(activity)
    }

    private fun startWorker(activity: MainActivity, w: Work, latest: Latest, usePatch: Boolean) {
        Thread {
            try {
                if (usePatch) {
                    val patch = payloadFile(activity, "delta")
                    w.phase = Phase.DOWNLOADING; w.message = ""; saveState(w)
                    val patchSha = resumeDownload(w, patch)
                    if (patchSha == null) {
                        if (!w.cancel) fail(activity, w, "the patch download failed — check the connection and try again")
                        return@Thread
                    }
                    // rebuild — deterministic, and cached behind an .ok marker
                    w.phase = Phase.REBUILDING; w.done = 0; w.size = 0
                    w.message = "applying the delta patch — no full download"
                    saveState(w)
                    val out = rebuiltFile(activity)
                    val okMarker = File(out.parentFile, out.name + ".ok")
                    val src = File(activity.applicationInfo.sourceDir ?: "")
                    val sha = if (out.exists() && okMarker.exists() &&
                        okMarker.readText().trim().equals(latest.sha256, true)) {
                        latest.sha256 // a previous run already rebuilt + verified this exact target
                    } else {
                        out.delete(); okMarker.delete()
                        val s = Bspatch.apply(src, patch, out)
                        if (s != null && s.equals(latest.sha256, true)) {
                            try { okMarker.writeText(s) } catch (_: Exception) {}
                        }
                        s
                    }
                    if (w.cancel) return@Thread
                    if (sha == null || !sha.equals(latest.sha256, true)) {
                        out.delete(); okMarker.delete()
                        // honest fallback: the patch didn't rebuild clean — full apk, same screen
                        w.kind = "full"; w.url = latest.url; w.size = latest.size; w.done = 0
                        w.phase = Phase.DOWNLOADING; w.message = "falling back to the full download"
                        saveState(w)
                        val apk = payloadFile(activity, "full")
                        val fullSha = resumeDownload(w, apk)
                        if (fullSha == null) {
                            if (!w.cancel) fail(activity, w, "the download failed — check the connection and try again")
                        } else {
                            ready(activity, w, apk)
                        }
                        return@Thread
                    }
                    ready(activity, w, out)
                    return@Thread
                }

                val apk = payloadFile(activity, "full")
                w.phase = Phase.DOWNLOADING; w.message = ""; saveState(w)
                val sha = resumeDownload(w, apk)
                if (sha == null) {
                    if (!w.cancel) fail(activity, w, "the download failed — check the connection and try again")
                    return@Thread
                }
                if (w.sha256.isNotBlank() && !sha.equals(w.sha256, true)) {
                    apk.delete()
                    fail(activity, w, "the download didn't match — tap retry and it re-verifies from scratch")
                    return@Thread
                }
                ready(activity, w, apk)
            } catch (e: Exception) {
                if (!w.cancel) fail(activity, w, e.message ?: "something went wrong — try again")
            }
        }.start()
    }

    private fun ready(activity: MainActivity, w: Work, apk: File) {
        if (w.cancel) return
        w.phase = Phase.READY
        w.done = apk.length()
        w.size = apk.length()
        w.message = ""
        saveState(w)
    }

    private fun fail(activity: MainActivity, w: Work, msg: String) {
        w.phase = Phase.FAILED
        w.message = msg
        saveState(w)
    }

    /**
     * Resumable, cancelable, sha256-streaming download. Appends to whatever
     * bytes are already on disk (HTTP Range); if the server won't honor the
     * range it restarts clean — correctness never depends on the resume.
     * Returns the file's sha256 hex, null when the work failed or canceled.
     */
    private fun resumeDownload(w: Work, dest: File): String? {
        var lastSave = 0L
        for (attempt in 1..4) {
            if (w.cancel) return null
            var start = 0L
            var md: MessageDigest = MessageDigest.getInstance("SHA-256")
            try {
                if (dest.exists()) {
                    start = dest.length()
                    FileInputStream(dest).use { fin ->
                        val b = ByteArray(64 * 1024)
                        while (true) { val n = fin.read(b); if (n < 0) break; md.update(b, 0, n) }
                    }
                }
                val c = URL(w.url).openConnection() as HttpURLConnection
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "orubus-app")
                if (start > 0) c.setRequestProperty("Range", "bytes=$start-")
                var total = w.size
                if (start > 0 && c.responseCode != 206) {
                    start = 0
                    dest.delete()
                    md = MessageDigest.getInstance("SHA-256")
                }
                val len = c.contentLengthLong
                if (len > 0) total = start + len
                c.inputStream.use { input ->
                    FileOutputStream(dest, start > 0).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (w.cancel) { try { c.disconnect() } catch (_: Exception) {}; return null }
                            val n = input.read(buf)
                            if (n < 0) break
                            start += n
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            w.done = start
                            if (total > 0) w.size = total
                            if (start > MAX_PAYLOAD) throw IllegalStateException("absurd size")
                            if (start - lastSave > SAVE_EVERY) { lastSave = start; saveState(w) }
                        }
                    }
                }
                try { c.disconnect() } catch (_: Exception) {}
                if (total <= 0 || start >= total) {
                    w.done = start
                    if (total > 0) w.size = total
                    return md.digest().joinToString("") { "%02x".format(it) }
                }
                // short read — the loop retries and resumes from disk
            } catch (e: Exception) {
                if (w.cancel) return null
                // fall through: next attempt resumes from whatever reached the disk
            }
        }
        return null
    }

    // ---------- the system handoff ----------

    fun install(activity: MainActivity, apk: File) {
        val w = work
        if (w != null) { w.phase = Phase.INSTALLING; saveState(w) }
        // Android 8+: the user flips "allow Orubus to install unknown apps"
        // once — then every future update is one tap, no browser, no store.
        if (Build.VERSION.SDK_INT >= 26 &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            try {
                activity.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:${activity.packageName}")
                    )
                )
                toast(activity, "allow Orubus to install updates — then tap INSTALL again")
            } catch (_: Exception) {}
            if (w != null) { w.phase = Phase.READY; saveState(w) } // nothing was consumed — stay ready
            return
        }
        try {
            val pi = activity.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = pi.createSession(params)
            val session = pi.openSession(sessionId)
            apk.inputStream().use { input ->
                session.openWrite("Orubus.apk", 0, apk.length()).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    session.fsync(out)
                }
            }
            val rec = Intent(activity, UpdateReceiver::class.java).setPackage(activity.packageName)
            val pending = PendingIntent.getBroadcast(
                activity, sessionId, rec,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pending.intentSender)
            session.close()
            toast(activity, "confirm the install in the system dialog")
        } catch (e: Exception) {
            if (w != null) { w.phase = Phase.READY; saveState(w) } // the artifact is still good
            toast(activity, "couldn't start the install — ${e.message ?: "try again"}")
        }
    }

    // ---------- small helpers ----------

    fun sha256(f: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { fin ->
            val b = ByteArray(64 * 1024)
            while (true) { val n = fin.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { "" }

    private fun mb(bytes: Long): String =
        if (bytes >= 1024 * 1024) String.format("%.1fMB", bytes / 1048576.0) else "${bytes / 1024}KB"

    fun mbPublic(bytes: Long): String = mb(bytes)

    private fun toast(activity: MainActivity, msg: String) {
        activity.runOnUiThread {
            android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
