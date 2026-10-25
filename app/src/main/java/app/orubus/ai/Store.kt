package app.orubus.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local persistence: the session cookie, preferences, the CHATS (the app's
 * own multi-chat space), the last-known STATE (so relaunching never makes
 * the user watch their coins load again — the wallet paints instantly from
 * the cache and quietly re-syncs), and the OUTBOX (messages that outlive a
 * dead connection and fire the moment it returns).
 *
 * Everything lives in filesDir as JSON; nothing leaves the device except
 * the turns the user sends.
 */
object Store {

    private fun dir(): File = File(AppCtx.get().filesDir, "data").apply { mkdirs() }
    private fun chatsFile(): File = File(dir(), "chats.json")
    private fun stateFile(): File = File(dir(), "state.json")

    // ---------- prefs ----------
    fun prefs() = AppCtx.get().getSharedPreferences("orubus", android.content.Context.MODE_PRIVATE)

    // ---------- chats ----------
    data class Msg(val mine: Boolean, val text: String, val at: Long, val system: Boolean = false, val think: String = "", val img: String = "")
    data class Chat(val id: String, var name: String, val created: Long, val msgs: MutableList<Msg>)

    @Volatile private var cache: MutableList<Chat>? = null

    private fun imgDir(): File = File(AppCtx.get().filesDir, "images").apply { mkdirs() }

    /**
     * A picked photo lands on disk (already downscaled ≤1024px JPEG) and the
     * message carries only the file NAME — the bubble renders the real image
     * from here, tap it for the full-screen viewer. Nothing is uploaded
     * except the turn itself.
     */
    fun saveImage(bitmap: android.graphics.Bitmap): String {
        val name = "img-${System.currentTimeMillis()}.jpg"
        return try {
            java.io.FileOutputStream(File(imgDir(), name)).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
            name
        } catch (_: Exception) { "" }
    }

    fun loadImage(name: String): android.graphics.Bitmap? = try {
        if (name.isBlank()) null
        else android.graphics.BitmapFactory.decodeFile(File(imgDir(), name).absolutePath)
    } catch (_: Exception) { null }

    @Synchronized
    fun chats(): MutableList<Chat> {
        cache?.let { return it }
        val list = mutableListOf<Chat>()
        try {
            val f = chatsFile()
            if (f.exists()) {
                val arr = JSONObject(f.readText()).optJSONArray("chats") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val msgs = mutableListOf<Msg>()
                    val ma = o.optJSONArray("msgs") ?: JSONArray()
                    for (k in 0 until ma.length()) {
                        val m = ma.getJSONObject(k)
                        msgs.add(Msg(m.optBoolean("mine"), m.optString("text", ""), m.optLong("at"), m.optBoolean("system"), m.optString("think", ""), m.optString("img", "")))
                    }
                    list.add(Chat(o.optString("id", UUID()), o.optString("name", "New chat"), o.optLong("created"), msgs))
                }
            }
        } catch (_: Exception) { /* fresh start */ }
        if (list.isEmpty()) list.add(Chat(UUID(), "New chat", System.currentTimeMillis(), mutableListOf()))
        cache = list
        return list
    }

    @Synchronized
    fun save() {
        val list = cache ?: return
        try {
            val arr = JSONArray()
            for (c in list.take(20)) {
                val o = JSONObject()
                o.put("id", c.id)
                o.put("name", c.name)
                o.put("created", c.created)
                val ma = JSONArray()
                for (m in c.msgs.takeLast(200)) {
                    ma.put(JSONObject().put("mine", m.mine).put("text", m.text).put("at", m.at).put("system", m.system).put("think", m.think).put("img", m.img))
                }
                o.put("msgs", ma)
                arr.put(o)
            }
            chatsFile().writeText(JSONObject().put("chats", arr).toString())
        } catch (_: Exception) { /* never crash on persistence */ }
    }

    // ---------- the last-known state (coins paint before the network blinks) ----------

    /**
     * Relaunching the app must never mean watching the wallet load: the last
     * state JSON is cached on disk and painted instantly on entry; the live
     * refresh then quietly overwrites it. Stale by seconds, never wrong by
     * more than what happened while the user was away.
     */
    fun saveState(st: JSONObject) {
        try { stateFile().writeText(st.toString()) } catch (_: Exception) {}
    }

    fun loadState(): JSONObject? = try {
        val f = stateFile()
        if (f.exists()) JSONObject(f.readText()) else null
    } catch (_: Exception) { null }

    fun clearState() { try { stateFile().delete() } catch (_: Exception) {} }

    // ---------- the outbox: no internet, no problem ----------

    /**
     * A message that couldn't leave (no lane, network died mid-turn) waits
     * here and fires the SECOND the connection is back — the user sees it
     * go out on its own, marked as resent. No lost words, ever.
     */
    data class Queued(val chatId: String, val text: String, val img: Boolean, val at: Long)

    @Synchronized
    fun outbox(): MutableList<Queued> {
        val list = mutableListOf<Queued>()
        try {
            val arr = JSONArray(prefs().getString("outbox", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Queued(o.optString("chat", ""), o.optString("text", ""), o.optBoolean("img"), o.optLong("at")))
            }
        } catch (_: Exception) {}
        return list
    }

    @Synchronized
    fun enqueueOutbox(chatId: String, text: String, img: Boolean) {
        val arr = JSONArray()
        for (q in outbox()) arr.put(JSONObject().put("chat", q.chatId).put("text", q.text).put("img", q.img).put("at", q.at))
        arr.put(JSONObject().put("chat", chatId).put("text", text).put("img", img).put("at", System.currentTimeMillis()))
        prefs().edit().putString("outbox", arr.toString()).apply()
    }

    @Synchronized
    fun peekOutbox(): Queued? = outbox().firstOrNull()

    @Synchronized
    fun dropOutboxFirst() {
        val rest = outbox().drop(1)
        val arr = JSONArray()
        for (q in rest) arr.put(JSONObject().put("chat", q.chatId).put("text", q.text).put("img", q.img).put("at", q.at))
        prefs().edit().putString("outbox", arr.toString()).apply()
    }

    @Synchronized
    fun outboxCount(): Int = outbox().size

    // ---------- chat navigation ----------

    fun currentChatId(): String = prefs().getString("chatId", null) ?: chats().first().id

    fun currentChat(): Chat {
        val id = currentChatId()
        val found = chats().firstOrNull { it.id == id }
        return found ?: chats().first().also { prefs().edit().putString("chatId", it.id).apply() }
    }

    fun setCurrent(id: String) {
        prefs().edit().putString("chatId", id).apply()
    }

    fun newChat(name: String = "New chat"): Chat {
        val c = Chat(UUID(), name, System.currentTimeMillis(), mutableListOf())
        chats().add(0, c)
        setCurrent(c.id)
        save()
        return c
    }

    /**
     * "New chat" — open a fresh thread WITHOUT stacking empty ones: if the
     * current thread is already idle it stays; if any idle thread exists the
     * user lands on it; only when every thread holds content is one new
     * chat minted. One "New chat" at a time, exactly as it should feel.
     */
    fun openFreshChat(): Chat {
        val cur = currentChat()
        if (cur.msgs.none { !it.system }) { setCurrent(cur.id); return cur }
        val idle = chats().firstOrNull { it.msgs.none { m -> !m.system } }
        if (idle != null) { setCurrent(idle.id); save(); return idle }
        return newChat()
    }

    /**
     * Branch the chat — a long-press on any of my answers forks the thread
     * from that exact point: everything up to and including that message
     * becomes the opening of a new chat, the original stays untouched.
     */
    fun branchChat(from: Chat, uptoIndex: Int): Chat {
        val take = from.msgs.subList(0, (uptoIndex + 1).coerceAtMost(from.msgs.size)).toMutableList()
        val c = Chat(UUID(), "Branch · ${from.name}".take(40), System.currentTimeMillis(), take.toMutableList())
        chats().add(0, c)
        setCurrent(c.id)
        save()
        return c
    }

    fun deleteChat(id: String) {
        val list = chats()
        if (list.size <= 1) return // always keep one
        list.removeAll { it.id == id }
        if (currentChatId() == id) setCurrent(list.first().id)
        save()
    }

    fun renameChat(id: String, name: String) {
        chats().firstOrNull { it.id == id }?.name = name.take(40)
        save()
    }

    fun append(chat: Chat, mine: Boolean, text: String, system: Boolean = false, think: String = "", img: String = ""): Msg {
        val m = Msg(mine, text, System.currentTimeMillis(), system, think, img)
        chat.msgs.add(m)
        // auto-name a fresh chat from its first user message
        if (chat.name == "New chat" && mine && !system && text.isNotBlank()) chat.name = text.take(24)
        save()
        return m
    }

    /** The visible turns of a chat (no system lines) for the engine context.
     *  A photo-only message still needs words for the lane's history — the
     *  pixels themselves ride the last user turn as the image part. */
    fun turnsOf(chat: Chat): List<Pair<Boolean, String>> =
        chat.msgs.filter { !it.system }.map { it.mine to (if (it.text.isBlank() && it.img.isNotBlank()) "(sent a photo — see the attached image)" else it.text) }

    fun UUID(): String = java.util.UUID.randomUUID().toString().take(8)
}
