package app.orubus.ai

/**
 * The intent engine — commands without commands.
 *
 * Like Discord's Intent v1: the user types plain language, the engine maps
 * it onto what the app can actually do (open surfaces, run server soul
 * commands, show the wallet). Deterministic patterns only — capability
 * routing, never content hardcoding; anything unmatched flows to the AI.
 *
 *   "open shop"        → SHOP        "remember my name is X" → server /remember
 *   "how many credits" → UNITS       "forget X"              → server /forget
 *   "new chat"         → NEWCHAT     "catch me up"           → server /summary
 */
object Intents {

    enum class Kind {
        SHOP, REQUESTS, INBOX, LEGAL, ABOUT, SETTINGS, NEWCHAT, CHATS,
        UNITS, ID, REMEMBER, FORGET, SUMMARY, SOUL, DOWNLOAD, HELP, LOGOUT
    }

    data class Result(val kind: Kind, val arg: String = "", val serverCommand: String? = null)

    fun match(raw: String): Result? {
        val text = raw.trim().lowercase().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null
        fun has(vararg words: String): Boolean =
            words.any { w -> Regex("(^|[^a-z])${Regex.escape(w)}([^a-z]|$)").containsMatchIn(text) }

        // memory + soul — routed to the server's free command path (same soul as the bots)
        Regex("^\\s*(?:remember|note)[:,]?\\s+(.{2,200})$").find(text)?.let {
            return Result(Kind.REMEMBER, it.groupValues[1], "/remember ${it.groupValues[1]}")
        }
        Regex("^\\s*my name is\\s+(.{1,40})$").find(text)?.let {
            return Result(Kind.REMEMBER, it.groupValues[1], "/remember my name is ${it.groupValues[1]}")
        }
        Regex("^\\s*(?:forget|delete)\\s+(?:about\\s+)?(.{2,120})$").find(text)?.let {
            if (has("forget", "delete")) return Result(Kind.FORGET, it.groupValues[1], "/forget ${it.groupValues[1]}")
        }
        if (has("summary", "summarize", "catch me up", "recap") && !has("what")) return Result(Kind.SUMMARY, "", "/summary")
        if (has("soul") && (has("my", "read", "know about me", "what do you know"))) return Result(Kind.SOUL, "", "/soul")

        // surfaces
        if (has("shop", "store", "buy su", "buy units", "buy packs", "su packs", "top up", "pro pass", "themes")) return Result(Kind.SHOP)
        if (has("requests", "request wall", "feature wall", "vote", "the wall") && !has("request an")) return Result(Kind.REQUESTS)
        if (has("inbox", "announcements", "announcement", "news", "broadcasts")) return Result(Kind.INBOX)
        if (has("legal", "terms", "privacy", "policies")) return Result(Kind.LEGAL)
        if (has("about") && !has("tell me about")) return Result(Kind.ABOUT)
        if (has("settings", "preferences", "options")) return Result(Kind.SETTINGS)

        // chats
        if ((has("new chat", "fresh chat", "start a chat", "new conversation", "clear this chat")) ||
            (text == "new")) return Result(Kind.NEWCHAT)
        if (has("my chats", "chat list", "other chats", "show chats", "switch chat", "all chats")) return Result(Kind.CHATS)

        // wallet + identity
        if (has("credits", "units", "balance", "how many su", "wallet", "soul units", "how much su")) return Result(Kind.UNITS)
        if (has("my id", "who am i", "my code", "identity", "my gid")) return Result(Kind.ID)

        // app
        if (has("download", "apk", "get the app") && !has("can't", "cannot")) return Result(Kind.DOWNLOAD)
        if (has("help", "what can you do", "commands", "how does this work")) return Result(Kind.HELP)
        if (has("log out", "logout", "unlink", "sign out", "disconnect")) return Result(Kind.LOGOUT)

        return null
    }
}
