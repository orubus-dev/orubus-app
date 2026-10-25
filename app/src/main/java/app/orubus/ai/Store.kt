package app.orubus.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local persistence: the session cookie, preferences and the CHATS —
 * the app's own multi-chat space (each chat = its own thread + context).
 * Everything lives in filesDir as JSON; nothing leaves the device except
 * the turns the user sends.
 */
object Store {

    private fun dir(): File = File(AppCtx.get().filesDir, "data").apply { mkdirs() }
    private fun chatsFile(): File = File(dir(), "chats.json")

    // ---------- prefs ----------
    fun prefs() = AppCtx.get().getSharedPreferences("orubus", android.content.Context.MODE_PRIVATE)

    // ---------- chats ----------
    data class Msg(val mine: Boolean, val text: String, val at: Long, val system: Boolean = false)
    data class Chat(val id: String, var name: String, val created: Long, val msgs: MutableList<Msg>)

    @Volatile private var cache: MutableList<Chat>? = null

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
                        msgs.add(Msg(m.optBoolean("mine"), m.optString("text", ""), m.optLong("at"), m.optBoolean("system")))
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
                    ma.put(JSONObject().put("mine", m.mine).put("text", m.text).put("at", m.at).put("system", m.system))
                }
                o.put("msgs", ma)
                arr.put(o)
            }
            chatsFile().writeText(JSONObject().put("chats", arr).toString())
        } catch (_: Exception) { /* never crash on persistence */ }
    }

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

    fun append(chat: Chat, mine: Boolean, text: String, system: Boolean = false): Msg {
        val m = Msg(mine, text, System.currentTimeMillis(), system)
        chat.msgs.add(m)
        // auto-name a fresh chat from its first user message
        if (chat.name == "New chat" && mine && !system) chat.name = text.take(24)
        save()
        return m
    }

    /** The visible turns of a chat (no system lines) for the engine context. */
    fun turnsOf(chat: Chat): List<Pair<Boolean, String>> =
        chat.msgs.filter { !it.system }.map { it.mine to it.text }

    fun UUID(): String = java.util.UUID.randomUUID().toString().take(8)
}
