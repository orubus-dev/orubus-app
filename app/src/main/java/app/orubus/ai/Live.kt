package app.orubus.ai

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ORUBUS LIVE — the native voice session.
 *
 * The same realtime lane the duck voice chat uses: a WebRTC peer connection
 * (mic up, assistant audio down) plus a "duckai-voice-session" data channel
 * that carries the transcript events. Auth rides the exact token the chat
 * lane mints — one x-vqd-hash-1 for everything.
 *
 *   GET  /ice-servers (relay-only TURN) → PeerConnection → addTrack(mic)
 *   data channel → createOffer → POST /session (application/sdp) → answer
 *
 * The server hears the human and the assistant on the same wire, so barge-in
 * is REAL: the assistant's audio is cleared the instant the user speaks, not
 * guessed from partial transcripts like the legacy call.
 *
 * If any rung of the ladder fails, the caller falls back to the legacy
 * on-device call (Voice.kt) — the voice never dies, only the quality tier.
 */
object Live {

    @Volatile internal var inCall = false

    private var factory: PeerConnectionFactory? = null

    @Synchronized
    internal fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(AppCtx.get())
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(AppCtx.get())
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        try {
            // the call plays out loud — the speaker, not the earpiece
            val am = AppCtx.get().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
        } catch (_: Exception) {}
        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        factory = f
        return f
    }

    internal fun iceServers(t: DuckEngine.Token): List<PeerConnection.IceServer> {
        // the voice lane rides the SAME /duckchat/v1 namespace as the chat
        // lane — bare /ice-servers was a 404 on every call (v3.7.0/3.7.1 bug)
        val req = DuckEngine.laneRequest("https://duckduckgo.com/duckchat/v1/ice-servers")
            .apply { liveHeaders(t).forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        DuckEngine.laneClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw LiveException("the lane refused the ice handshake (${resp.code})")
            val j = JSONObject(resp.body?.string() ?: "{}")
            val arr = j.optJSONArray("iceServers") ?: JSONArray()
            val out = mutableListOf<PeerConnection.IceServer>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val urls = s.optJSONArray("urls") ?: JSONArray().put(s.optString("urls", ""))
                for (k in 0 until urls.length()) {
                    var b = PeerConnection.IceServer.builder(urls.getString(k))
                    if (s.has("username")) b = b.setUsername(s.getString("username"))
                    if (s.has("credential")) b = b.setPassword(s.getString("credential"))
                    try { out.add(b.createIceServer()) } catch (_: Exception) {}
                }
            }
            if (out.isEmpty()) throw LiveException("no ice servers came back")
            return out
        }
    }

    class LiveException(message: String) : Exception(message)

    internal fun liveHeaders(t: DuckEngine.Token): Map<String, String> = mapOf(
        "x-vqd-hash-1" to t.header,
        "x-fe-signals" to t.signals,
        "x-fe-version" to DuckEngine.feVersion,
        "x-ddg-journey-id" to t.journey,
    )
}

/** The whole session, as an overlay like the legacy call. */
@SuppressLint("SetTextI18n")
internal fun MainActivity.startLiveCall(onFail: (String) -> Unit) {
    if (Live.inCall || calling) return
    if (Api.cookieValue.isBlank()) {
        onFail("the call needs your wallet — link first (⚙ → identity)")
        return
    }
    val granted = try {
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { true }
    if (!granted) {
        try { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 4011) } catch (_: Exception) { onFail("the mic needs its permission") }
        return
    }

    Live.inCall = true
    calling = true
    val main = Handler(Looper.getMainLooper())
    var secs = 0
    var lastTick = 0

    // ---- overlay ----
    val overlay = FrameLayout(this).apply { setBackgroundColor(W.theme.bg) }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(18), dp(20), dp(24)) }
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(W.label(this, "ORUBUS · LIVE", 14f, W.theme.ink, heavy = 2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val timer = W.label(this, "0:00", 13f, W.theme.dim, heavy = 1)
    head.addView(timer)
    col.addView(head)
    col.addView(View(this).apply { setBackgroundColor(W.theme.ink) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(8) })

    val wave = WaveView(this, W.theme.ink, W.theme.acc)
    col.addView(wave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { topMargin = dp(22) })

    val status = W.label(this, "connecting live…", 13f, W.theme.acc2, heavy = 1).apply { gravity = Gravity.CENTER }
    col.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

    val caption = TextView(this).apply {
        textSize = 15f; setTextColor(W.theme.ink); typeface = W.regular(this@startLiveCall)
        gravity = Gravity.CENTER; setLineSpacing(dp(2).toFloat(), 1f)
        minLines = 2; maxLines = 6
    }
    col.addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

    col.addView(W.label(this, "a real phone call — interrupt me anytime, I hear you mid-word", 11f, W.theme.dim).apply {
        gravity = Gravity.CENTER
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

    val endBtn = W.button(this, "END CALL", W.theme.acc2, W.theme.paper, radiusDp = 99)
    col.addView(endBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })
    overlay.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(18) })
    screen.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    val setStatus: (String) -> Unit = { s -> runOnUiThread { status.text = s } }
    val setCaption: (String) -> Unit = { s -> runOnUiThread { caption.text = s } }
    val setWave: (Float) -> Unit = { e -> runOnUiThread { wave.energy = e } }

    // ---- metering: the same wallet tick as the legacy call ----
    fun chargeTick(seconds: Int, done: (Boolean, String) -> Unit) {
        Thread {
            val r = Api.voiceTick(seconds)
            runOnUiThread {
                r?.optJSONObject("wallet")?.let { renderWallet(it) }
                if (r == null) done(true, "")
                else if (r.optBoolean("ok", false)) done(true, "")
                else done(false, r.optString("error", "units ran dry — the call ends here"))
            }
        }.start()
    }

    var ended = false
    val chat = Store.currentChat()
    val assistantText = StringBuffer()
    val offerHolder = arrayOf<String?>(null)

    fun finish(note: String) {
        if (ended) return
        ended = true
        val tail = secs - lastTick
        if (tail >= 15) chargeTick(tail) { _, _ -> }
        runOnUiThread {
            overlay.animate().alpha(0f).setDuration(180).withEndAction { screen.removeView(overlay) }.start()
            calling = false
            Live.inCall = false
            if (note.isNotBlank()) pushSys(note)
            loadCurrentChat()
            refreshAll()
        }
    }

    endBtn.setOnClickListener { finish("call ended — ${secs / 60}m ${secs % 60}s, everything said is in the thread ⚡") }

    val ticker = object : Runnable {
        override fun run() {
            if (ended) return
            secs += 1
            timer.text = "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
            if (secs - lastTick >= 60) {
                lastTick = secs
                chargeTick(60) { okNow, err -> if (!okNow && !ended) finish(err) }
            }
            main.postDelayed(this, 1000)
        }
    }
    main.post(ticker)

    // ---- the WebRTC ladder runs on a worker thread ----
    Thread {
        var pc: PeerConnection? = null
        var source: AudioSource? = null
        try {
            val token = DuckEngine.tokenForLive()
            setStatus("the lane said yes — wiring audio…")

            val f = Live.ensureFactory()
            val rtcConfig = PeerConnection.RTCConfiguration(Live.iceServers(token)).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                // the reference mandates relay-only: TURN candidates keep the
                // media path on the service's own infrastructure (field name in
                // stream-webrtc 1.3.8 is iceTransportsType — 'Policy' never existed)
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
            }
            val dcOpen = CountDownLatch(1)
            val connected = CountDownLatch(1)
            var channel: DataChannel? = null

            pc = f.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(c: IceCandidate) {}
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    if (state == PeerConnection.IceConnectionState.CONNECTED) connected.countDown()
                    if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED) {
                        finish("the live lane closed")
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    if (state == PeerConnection.PeerConnectionState.FAILED) finish("the live lane dropped")
                }
                override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) {}
                override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent) {}
                override fun onAddStream(s: org.webrtc.MediaStream) {}
                override fun onRemoveStream(s: org.webrtc.MediaStream) {}
                override fun onDataChannel(ch: DataChannel) { /* they never open one first */ }
                override fun onRenegotiationNeeded() {}
                override fun onTrack(trans: org.webrtc.RtpTransceiver) {}
            })!!

            // mic up
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            source = f.createAudioSource(constraints)
            val track: AudioTrack = f.createAudioTrack("audio0", source)
            pc.addTrack(track, listOf("orubus-audio"))

            // the transcript channel, negotiated in the offer
            val dcInit = DataChannel.Init().apply { ordered = true }
            channel = pc.createDataChannel("duckai-voice-session", dcInit)
            channel?.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previous: Long) {}
                override fun onStateChange() {
                    if (channel?.state() == DataChannel.State.OPEN) {
                        dcOpen.countDown()
                        setStatus("live — just talk")
                        setWave(0.62f)
                    }
                }
                override fun onMessage(buffer: DataChannel.Buffer) {
                    try {
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        val j = JSONObject(String(bytes, Charsets.UTF_8))
                        handleLiveEvent(j, setStatus, setCaption, setWave, chat, assistantText, ::finish)
                    } catch (_: Exception) {}
                }
            })

            // offer → /session → answer
            val offerDone = CountDownLatch(1)
            var offerErr: String? = null
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    offerHolder[0] = desc.description
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() { offerDone.countDown() }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) { offerErr = p0; offerDone.countDown() }
                    }, desc)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) { offerErr = p0; offerDone.countDown() }
                override fun onSetFailure(p0: String?) {}
            }, MediaConstraints())
            if (!offerDone.await(12, TimeUnit.SECONDS) || offerErr != null) throw Live.LiveException("the audio handshake failed${offerErr?.let { " — $it" } ?: ""}")

            val offerSdp = offerHolder[0] ?: throw Live.LiveException("the audio handshake never produced an offer")
            // the session POST, exactly like the reference client speaks it:
            // raw SDP (application/sdp) first — the reference's own fallback
            // shape; if the desk wants JSON now, retry {sdp, session:{voice}}
            // (application/json) before giving up. Both paths carry the same
            // chat-lane headers (x-vqd-hash-1 family).
            val sessionUrl = "https://duckduckgo.com/duckchat/v1/session"
            fun sessionPost(url: String, body: String, ctype: String): okhttp3.Response {
                val req = DuckEngine.laneRequest(url)
                    .apply { Live.liveHeaders(token).forEach { (k, v) -> header(k, v) } }
                    .post(body.toRequestBody(ctype.toMediaType()))
                    .build()
                return DuckEngine.laneClient().newCall(req).execute()
            }
            var answered = false
            var answerSdp = ""
            sessionPost(sessionUrl, offerSdp, "application/sdp").use { resp ->
                if (resp.isSuccessful) {
                    answered = true
                    answerSdp = resp.body?.string() ?: throw Live.LiveException("the voice desk came back empty")
                } else if (resp.code != 400 && resp.code != 422) {
                    throw Live.LiveException("the voice desk said ${resp.code}")
                }
            }
            if (!answered) {
                sessionPost(sessionUrl, JSONObject().put("sdp", offerSdp).put("session", JSONObject().put("voice", JSONObject.NULL)).toString(), "application/json").use { resp ->
                    if (!resp.isSuccessful) throw Live.LiveException("the voice desk said ${resp.code}")
                    val text = resp.body?.string() ?: throw Live.LiveException("the voice desk came back empty")
                    // the JSON answer may wrap the SDP — unwrap when it does
                    answerSdp = try {
                        val j = JSONObject(text)
                        j.optString("sdp", "").ifBlank { text }
                    } catch (_: Exception) { text }
                }
            }
            if (answerSdp.isBlank()) throw Live.LiveException("the voice desk came back empty")
            val setDone = CountDownLatch(1)
            var setErr: String? = null
            pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() { setDone.countDown() }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) { setErr = p0; setDone.countDown() }
            }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
            if (!setDone.await(10, TimeUnit.SECONDS) || setErr != null) throw Live.LiveException("the answer didn't take${setErr?.let { " — $it" } ?: ""}")

            if (!connected.await(20, TimeUnit.SECONDS)) throw Live.LiveException("the lane never connected")
            if (!dcOpen.await(20, TimeUnit.SECONDS)) throw Live.LiveException("the transcript channel never opened")

            setStatus("live — just talk")
            setCaption("hi, how can I help you today?")
            // the call holds until the human ends it or the lane closes
            while (!ended) Thread.sleep(400)
        } catch (e: Exception) {
            val msg = if (e is Live.LiveException) e.message ?: "the live lane failed"
            else if (Api.isNetErr(e)) "no connection for the live lane"
            else (e.message ?: "the live lane failed").take(160)
            ended = true
            runOnUiThread {
                overlay.animate().alpha(0f).setDuration(160).withEndAction { screen.removeView(overlay) }.start()
                calling = false
                Live.inCall = false
            }
            onFail(msg)
        } finally {
            try { source?.dispose() } catch (_: Exception) {}
            try { pc?.close() } catch (_: Exception) {}
            ended = true
        }
    }.start()
}

/**
 * One realtime event off the data channel — the same vocabulary the web
 * voice speaks (OpenAI-realtime style): user transcripts, assistant deltas,
 * buffer started/cleared for the speaking states.
 */
private fun handleLiveEvent(
    j: JSONObject,
    setStatus: (String) -> Unit,
    setCaption: (String) -> Unit,
    setWave: (Float) -> Unit,
    chat: Store.Chat,
    assistantText: StringBuffer,
    finish: (String) -> Unit,
) {
    val type = j.optString("type", "")
    when {
        type == "input_audio_buffer.speech_started" -> {
            setStatus("listening — talk over me anytime")
            setWave(0.62f)
        }
        type == "input_audio_buffer.speech_stopped" -> {
            setStatus("thinking…")
            setWave(0.4f)
        }
        type.startsWith("conversation.item.input_audio_transcription") -> {
            val said = j.optString("transcript", "").trim()
            if (said.isNotBlank()) {
                setCaption(said)
                Store.append(chat, true, "🎙 $said")
            }
        }
        type == "output_audio_buffer.started" -> {
            setStatus("talking — cut in anytime")
            setWave(0.85f)
            assistantText.setLength(0)
        }
        type == "output_audio_buffer.cleared" -> {
            // the human cut in — the answer yields mid-word
            setStatus("listening — just talk")
            setWave(0.62f)
        }
        type == "response.output_audio_transcript.delta" -> {
            assistantText.append(j.optString("delta", ""))
            setCaption(assistantText.toString())
        }
        type == "response.done" -> {
            val said = assistantText.toString().trim()
            if (said.isNotBlank()) Store.append(chat, false, said)
            assistantText.setLength(0)
            setStatus("listening — just talk")
            setWave(0.62f)
        }
        type.startsWith("error") || j.has("error") -> {
            val m = j.optJSONObject("error")?.optString("message", "") ?: j.optString("message", "")
            if (m.isNotBlank()) setCaption(m.take(140))
        }
    }
}
