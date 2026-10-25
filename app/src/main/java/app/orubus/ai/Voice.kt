package app.orubus.ai

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The voice call — a real one.
 *
 *   · continuous, back-to-back conversation: listen → think → speak → listen
 *   · the waveform button in the header opens it, END closes it
 *   · metered: voicePerMin SU per full minute, ticked server-side on the
 *     SAME wallet — a dry wallet ends the call gracefully, never in debt
 *   · secure by construction: the recognizer runs through the OS speech
 *     service, only the RECOGNIZED WORDS travel onward — down the same
 *     pinned, TLS-encrypted device lane every message uses. No audio is
 *     uploaded anywhere by this app, ever.
 *
 * The call shares the exact same turn ladder as typing (wallet → deep
 * research → device lane → server brain), and every exchange lands in the
 * chat thread, so a call and a text conversation are one memory.
 */
internal fun MainActivity.startVoiceCall() {
    if (calling) return
    if (Api.cookieValue.isBlank()) {
        pushSys("the call needs your wallet — link first (⚙ → identity)")
        return
    }
    val granted = try {
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { true }
    if (!granted) {
        try {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 4011)
        } catch (_: Exception) {
            pushSys("the mic needs its permission — allow it and tap the waveform again")
        }
        return
    }
    if (!SpeechRecognizer.isRecognitionAvailable(this)) {
        pushSys("voice isn't available on this device — the keyboard mic still works everywhere")
        return
    }
    buildCall()
}

internal fun MainActivity.onMicPermission(granted: Boolean) {
    if (granted) startVoiceCall()
    else pushSys("no mic, no call — but the keyboard still talks to me fine")
}

/** The permission round-trip lands here from onRequestPermissionsResult. */
internal fun MainActivity.handleMicResult(granted: Boolean) = onMicPermission(granted)

@SuppressLint("MissingPermission")
private fun MainActivity.buildCall() {
    calling = true

    val main = Handler(Looper.getMainLooper())
    var tts: TextToSpeech? = null
    var recognizer: SpeechRecognizer? = null
    var secs = 0
    var lastTick = 0
    val speaking = AtomicBoolean(false)
    val ended = AtomicBoolean(false)

    // ---- the overlay ----
    val overlay = FrameLayout(this).apply { setBackgroundColor(W.theme.bg) }
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(18), dp(20), dp(24))
    }

    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(W.label(this, "ORUBUS · VOICE", 14f, W.theme.ink, heavy = 2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val timer = W.label(this, "0:00", 13f, W.theme.dim, heavy = 1)
    head.addView(timer)
    col.addView(head)
    col.addView(View(this).apply { setBackgroundColor(W.theme.ink) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(8) })

    val wave = WaveView(this, W.theme.ink, W.theme.acc)
    col.addView(wave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { topMargin = dp(22) })

    val status = W.label(this, "connecting…", 13f, W.theme.acc2, heavy = 1).apply { gravity = Gravity.CENTER }
    col.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

    val caption = TextView(this).apply {
        textSize = 15f; setTextColor(W.theme.ink); typeface = W.regular(this@buildCall)
        gravity = Gravity.CENTER; setLineSpacing(dp(2).toFloat(), 1f)
        minLines = 2; maxLines = 6
    }
    col.addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

    col.addView(W.label(this, "words travel encrypted — the voice itself never leaves this call", 11f, W.theme.dim).apply {
        gravity = Gravity.CENTER
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

    val endBtn = W.button(this, "END CALL", W.theme.acc2, W.theme.paper, radiusDp = 99)
    col.addView(endBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })

    overlay.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
        topMargin = dp(18)
    })
    screen.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    fun setStatus(s: String) { runOnUiThread { status.text = s } }
    fun setCaption(s: String) { runOnUiThread { caption.text = s } }
    fun waveEnergy(e: Float) { runOnUiThread { wave.energy = e } }

    // ---- the billing tick: voicePerMin per minute on the SAME wallet ----
    fun chargeTick(seconds: Int, done: (Boolean, String) -> Unit) {
        Thread {
            val r = Api.voiceTick(seconds)
            runOnUiThread {
                r?.optJSONObject("wallet")?.let { renderWallet(it) }
                if (r == null) done(true, "") // an unreachable server never kills a live call
                else if (r.optBoolean("ok", false)) done(true, "")
                else done(false, r.optString("error", "units ran dry — the call ends here, the chat continues in text"))
            }
        }.start()
    }

    var endCallNow: (String) -> Unit = {}

    // ---- speak an answer, then hand the mic back (back-to-back) ----
    fun listenAgain() {
        if (ended.get()) return
        val rec = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        setStatus("listening — just talk")
        waveEnergy(0.62f)
        try { rec.startListening(intent) } catch (_: Exception) {
            main.postDelayed({ try { rec.startListening(intent) } catch (_: Exception) {} }, 600)
        }
    }

    fun speak(text: String, then: () -> Unit) {
        val engine = tts
        if (engine == null) { then(); return }
        speaking.set(true)
        waveEnergy(0.85f)
        setStatus("talking")
        setCaption(text)
        val clean = text.replace(Regex("[*#`_]+"), "").replace(Regex("\\s+"), " ").trim()
        val id = "orubus-call-${System.currentTimeMillis()}"
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) {
                    speaking.set(false)
                    runOnUiThread { then() }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) {
                    speaking.set(false)
                    runOnUiThread { then() }
                }
            }
        })
        engine.speak(clean.take(900), TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    // ---- one spoken turn through the SAME ladder as typing ----
    fun handleUtterance(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) { listenAgain(); return }
        setCaption(clean)
        setStatus("thinking…")
        waveEnergy(0.4f)
        val chat = Store.currentChat()
        Store.append(chat, true, "🎙 $clean")
        runTurn(chat, clean, skipAppend = true, silent = true,
            onAnswer = { ans -> speak(ans) { listenAgain() } },
            onError = { err ->
                if (ended.get()) {
                    // call closed mid-turn — nothing to say to a closed line
                } else {
                    val warm = if (err == "offline") "the lane dropped — say that again in a second" else err
                    setStatus(warm)
                    main.postDelayed({ listenAgain() }, 1600)
                }
            })
    }

    // ---- the recognizer ----
    val rec = SpeechRecognizer.createSpeechRecognizer(this)
    recognizer = rec
    rec.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { setStatus("listening — just talk") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { setStatus("working on that…") }
        override fun onError(error: Int) {
            if (ended.get()) return
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null // silence — keep listening
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> "re-listening…"
                else -> "that slipped — one more time"
            }
            if (msg != null) setStatus(msg)
            main.postDelayed({ if (!ended.get() && !speaking.get()) listenAgain() }, 500)
        }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (ended.get()) return
            handleUtterance(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val part = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!part.isNullOrBlank() && !speaking.get()) setCaption(part)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })

    // ---- end the call: stop the mics, settle the tail, land in the thread ----
    endCallNow = { note ->
        val tk = callTicker
        if (ended.compareAndSet(false, true)) {
            if (tk != null) main.removeCallbacks(tk)
            try { rec.stopListening(); rec.destroy() } catch (_: Exception) {}
            try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
            val tail = secs - lastTick
            if (tail >= 15) chargeTick(tail) { _, _ -> }
            runOnUiThread {
                overlay.animate().alpha(0f).setDuration(180).withEndAction {
                    screen.removeView(overlay)
                }.start()
                calling = false
                if (note.isNotBlank()) pushSys(note)
                loadCurrentChat() // the call's turns land in the thread, same as typing
                refreshAll()
            }
        }
    }

    // ---- the timer + metering ----
    val ticker = object : Runnable {
        override fun run() {
            if (ended.get()) return
            secs += 1
            timer.text = "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
            if (secs - lastTick >= 60) {
                lastTick = secs
                chargeTick(60) { okNow, err ->
                    if (!okNow && !ended.get()) endCallNow(err)
                }
            }
            main.postDelayed(this, 1000)
        }
    }
    callTicker = ticker

    endBtn.setOnClickListener { endCallNow("call ended — ${secs / 60}m ${secs % 60}s, everything said is in the thread ⚡") }

    // ---- TTS up, then go live ----
    tts = TextToSpeech(this) { st ->
        if (st == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(1.0f)
        }
    }
    main.post(ticker)
    setStatus("warming the mic…")
    main.postDelayed({ if (!ended.get()) listenAgain() }, 900)
}
