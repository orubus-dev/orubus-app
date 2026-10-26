package app.orubus.ai

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
 */
object Updater {

    /** Manifest published next to the code: /release/updates.json. */
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/${Api.APP_REPO}/main/release/updates.json"
    private const val PREFS = "orubus_updates"
    private const val PROMPT_GAP_MS = 20 * 60 * 60 * 1000L // auto-prompt at most ~once a day

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

    // ---------- manifest ----------

    private fun fetchManifest(): Pair<Latest, Delta?>? = try {
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
                onPositive = { perform(activity, latest, delta) },
            )
        }
    }

    // ---------- download → (patch) → verify → install ----------

    private fun perform(activity: MainActivity, latest: Latest, delta: Delta?) {
        Thread {
            val dir = File(activity.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // clean previous attempts

            // --- the cheap path: rebuild the installed APK with a delta patch ---
            if (delta != null && latest.sha256.isNotBlank()) {
                val patchFile = File(dir, "delta.bsdiff")
                val srcApk = File(activity.applicationInfo.sourceDir ?: "")
                if (srcApk.exists() && download(delta.url, patchFile) != null) {
                    toast(activity, "rebuilding the app on device…")
                    val out = File(dir, "Orubus-new.apk")
                    val sha = Bspatch.apply(srcApk, patchFile, out)
                    if (sha != null && sha.equals(latest.sha256, ignoreCase = true)) {
                        install(activity, out)
                        return@Thread
                    }
                    out.delete()
                    // anything off → the full path still runs below
                }
                patchFile.delete()
            }

            // --- the plain path: full APK from the release ---
            val apk = File(dir, "Orubus.apk")
            toast(activity, "downloading the update…")
            val sha = download(latest.url, apk)
            if (sha == null) {
                toast(activity, "download failed — check the connection and try again")
                apk.delete()
                return@Thread
            }
            if (latest.sha256.isNotBlank() && !sha.equals(latest.sha256, ignoreCase = true)) {
                toast(activity, "the download didn't match — try again in a bit")
                apk.delete()
                return@Thread
            }
            install(activity, apk)
        }.start()
    }

    /** Streams [url] to [dest]; returns the file's SHA-256 hex, null on trouble. */
    private fun download(url: String, dest: File): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "orubus-app")
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        c.inputStream.use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 400L * 1024 * 1024) throw IllegalStateException("absurd size")
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                }
            }
        }
        c.disconnect()
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        try { dest.delete() } catch (_: Exception) {}
        null
    }

    // ---------- the system handoff ----------

    private fun install(activity: MainActivity, apk: File) {
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
                toast(activity, "allow Orubus to install updates — then tap UPDATE again")
            } catch (_: Exception) {}
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
            toast(activity, "ready — confirm the install in the system dialog")
        } catch (e: Exception) {
            toast(activity, "couldn't start the install — ${e.message ?: "try again"}")
        }
    }

    // ---------- small helpers ----------

    private fun mb(bytes: Long): String =
        if (bytes >= 1024 * 1024) String.format("%.1fMB", bytes / 1048576.0) else "${bytes / 1024}KB"

    private fun toast(activity: MainActivity, msg: String) {
        activity.runOnUiThread {
            android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
