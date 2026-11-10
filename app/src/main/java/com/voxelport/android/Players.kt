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

    private fun command(vararg words: String): String? = if (safe(words.last())) words.joinToString(" ") else null
}
