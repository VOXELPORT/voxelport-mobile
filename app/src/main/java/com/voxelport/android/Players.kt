package com.voxelport.android

/**
 * Who is on the server, read from its own console lines ("Steve joined the game" / "Steve left the game"). Plain logic, so it
 * can be unit-tested. Bedrock players (through Floodgate) show up with a leading dot, like ".Alex".
 */
object Players {
    private val line = Regex("""\]: ([A-Za-z0-9_.\-]{1,32}) (joined|left) the game\s*$""")

    /** [online] after reading [lines]: joins add a name, leaves remove it. Order of arrival is kept. */
    fun apply(online: List<String>, lines: List<String>): List<String> {
        var now = online
        for (l in lines) {
            val m = line.find(l) ?: continue
            val name = m.groupValues[1]
            now = if (m.groupValues[2] == "joined") (if (name in now) now else now + name) else now - name
        }
        return now
    }

    /** A name that is safe to put into a console command (so a odd name can never smuggle in a second command). */
    fun safe(name: String): Boolean = Regex("""[A-Za-z0-9_.\-]{1,32}""").matches(name)

    fun kick(name: String): String? = command("kick", name)
    fun ban(name: String): String? = command("ban", name)
    fun allow(name: String): String? = command("whitelist", "add", name)
    fun disallow(name: String): String? = command("whitelist", "remove", name)

    internal fun command(vararg words: String): String? = if (safe(words.last())) words.joinToString(" ") else null
}

/**
 * The whitelist, as the Players page shows it: who is on it (read from the server's whitelist.json), whether it is on (server.properties),
 * and what the server said to the last command (read from its console). Plain logic, so it can be unit-tested.
 *
 * Java names go through the server's own `whitelist` command, which asks Mojang who the name belongs to. Bedrock players (through Floodgate)
 * are not known to Mojang under that name, so they need Floodgate's `fwhitelist`; a name with a leading dot (how they show up in the server) is
 * treated as Bedrock too.
 */
object Whitelist {
    enum class Result { ADDED, ALREADY, NO_SUCH_PLAYER, REMOVED, NOT_LISTED, TURNED_ON, TURNED_OFF, ALREADY_ON, ALREADY_OFF }

    /** The names in whitelist.json (an array of {"uuid": ..., "name": ...}), sorted without regard to case. A broken file gives an empty list. */
    fun names(json: String): List<String> = runCatching {
        val arr = org.json.JSONArray(json.ifBlank { "[]" })
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
            .sortedBy { it.lowercase() }
    }.getOrDefault(emptyList())

    fun isOn(props: Map<String, String>): Boolean = props["white-list"]?.trim().equals("true", ignoreCase = true)

    fun addCommand(name: String, bedrock: Boolean): String? {
        val bare = name.removePrefix(".")
        return if (bedrock || name.startsWith(".")) Players.command("fwhitelist", "add", bare) else Players.command("whitelist", "add", bare)
    }

    fun removeCommand(name: String): String? =
        if (name.startsWith(".")) Players.command("fwhitelist", "remove", name.removePrefix(".")) else Players.command("whitelist", "remove", name)

    /** The server's answer to a whitelist command, found in [lines] (console lines, oldest first); the newest answer wins. Null if there is none. */
    fun answer(lines: List<String>): Result? {
        for (l in lines.asReversed()) {
            val s = l.substringAfter("]: ", l)
            return when {
                s.contains("already whitelisted", true) -> Result.ALREADY
                s.contains("not whitelisted", true) -> Result.NOT_LISTED
                s.contains("does not exist", true) && s.contains("player", true) -> Result.NO_SUCH_PLAYER
                s.startsWith("Added ") && s.contains("to the whitelist", true) -> Result.ADDED
                s.startsWith("Removed ") && s.contains("from the whitelist", true) -> Result.REMOVED
                s.contains("Whitelist is already turned on", true) -> Result.ALREADY_ON
                s.contains("Whitelist is already turned off", true) -> Result.ALREADY_OFF
                s.contains("Whitelist is now turned on", true) -> Result.TURNED_ON
                s.contains("Whitelist is now turned off", true) -> Result.TURNED_OFF
                else -> continue
            }
        }
        return null
    }

    /** What to tell the person, and whether it worked. */
    fun message(r: Result): Pair<String, Boolean> = when (r) {
        Result.ADDED -> t("Added to the whitelist.") to true
        Result.ALREADY -> t("That player is already on the whitelist.") to true
        Result.NO_SUCH_PLAYER -> t("The server can't find that player. Check the spelling. A Java name needs the phone to be online so the server can look it up; for a Bedrock player (phone, console, Windows 10), tick Bedrock player.") to false
        Result.REMOVED -> t("Removed from the whitelist.") to true
        Result.NOT_LISTED -> t("That player is not on the whitelist.") to true
        Result.TURNED_ON -> t("Whitelist is on: only the players below can join.") to true
        Result.TURNED_OFF -> t("Whitelist is off: anyone can join.") to true
        Result.ALREADY_ON -> t("The whitelist was already on.") to true
        Result.ALREADY_OFF -> t("The whitelist was already off.") to true
    }
}
