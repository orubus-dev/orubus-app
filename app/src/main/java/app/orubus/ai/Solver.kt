package app.orubus.ai

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The on-device challenge sandbox.
 *
 * A 1px offscreen WebView hosts assets/solver.html — the faithful JS port of
 * the server-side duck-solve.ts. Kotlin hands in a challenge, the page runs
 * it against the Chrome-faithful DOM shim and hands the wrapped
 * x-vqd-hash-1 + x-fe-signals headers back over the Android bridge.
 *
 * Everything about the challenge stays identical to the proven server path —
 * only the CPU it runs on changed (the user's phone, next to their own IP).
 */
@SuppressLint("SetJavaScriptEnabled")
object Solver {

    @Volatile private var web: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val html: String by lazy {
        // the asset is shipped inside the APK; read once
        val stream = Solver::class.java.classLoader!!.getResourceAsStream("solver.html")
            ?: AppCtx.get().assets.open("solver.html")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** One-time setup on the UI thread — call from onCreate. */
    fun ensure(activity: Activity) {
        if (web != null) return
        main.post {
            try {
                val wv = WebView(activity)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = false
                // UA parity: the sandbox must believe it IS the same browser the
                // HTTP lane claims to be (Chrome 131 / Windows) — any drift
                // between the solved signals' identity and the device's real
                // Android WebView UA reads as a 400 down the lane.
                wv.settings.userAgentString = DuckEngine.UA
                wv.setBackgroundColor(0x00000000)
                wv.alpha = 0f
                wv.visibility = android.view.View.GONE
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        ready = true
                    }
                }
                wv.addJavascriptInterface(Bridge(), "Android")
                activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    .addView(wv, android.view.ViewGroup.LayoutParams(1, 1))
                // base URL duck.ai — the shim fully shadows location anyway
                wv.loadDataWithBaseURL("https://duck.ai/", html, "text/html", "utf-8", null)
                web = wv
            } catch (_: Exception) {
                // solver unavailable — DuckEngine falls back to the server lane
            }
        }
    }

    fun detach() {
        main.post {
            web?.let { w ->
                (w.parent as? android.view.ViewGroup)?.removeView(w)
                w.destroy()
            }
            web = null
            ready = false
        }
    }

    @Volatile private var ready = false

    private class Bridge {
        @JavascriptInterface
        fun solveDone(id: String, json: String) {
            pending[id]?.complete(json)
        }
    }

    /**
     * Blocking solve — call from a background thread.
     * Returns {ok:true, header, signals} or {ok:false, error}.
     */
    fun solve(challenge: String, ua: String, entryVer: String, timeoutMs: Long = 10_000): JSONObject? {
        // the WebView spins up async on the main thread — wait briefly for it
        val deadline = System.currentTimeMillis() + 3_000
        while (web == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val w = web ?: return null
        val id = java.util.UUID.randomUUID().toString()
        val fut = CompletableFuture<String>()
        pending[id] = fut
        val esc: (String) -> String = { s -> s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") }
        val call = "solveTagged('$id','${esc(challenge)}','${esc(ua)}','${esc(entryVer)}')"
        var posted = false
        try {
            main.post {
                try { w.evaluateJavascript(call, null) } catch (_: Exception) { fut.complete("{\"ok\":false,\"error\":\"eval-failed\"}") }
            }
            posted = true
            val json = fut.get(timeoutMs, TimeUnit.MILLISECONDS)
            return JSONObject(json)
        } catch (_: Exception) {
            return if (posted) JSONObject("{\"ok\":false,\"error\":\"solver-timeout\"}") else null
        } finally {
            pending.remove(id)
        }
    }
}

/** Tiny app-context holder for asset access outside activities. */
object AppCtx {
    @Volatile lateinit var app: android.content.Context
    fun get(): android.content.Context = app
}
