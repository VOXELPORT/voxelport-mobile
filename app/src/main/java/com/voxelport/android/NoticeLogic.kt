package com.voxelport.android

/**
 * Announcements from the relay's operator ("The VoxelPort relay will restart."), with the time left. The app
 * shows them as a banner, and also says them in the game's chat so players, who never see the app, hear it too.
 * Plain logic with no Android calls (same behaviour as the desktop app), so it is unit-tested.
 */
object NoticeLogic {
    const val MAX_CHAT = 200

    /** A relay that reconnects hands the same notice over again: say it once, not on every reconnect. */
    const val REPEAT_QUIET_MS = 10 * 60 * 1000L

    /** When to say it again as the time runs out (seconds before), if there was that much time. */
    private val REMINDERS = listOf(300, 60)

    /** Text that is safe to hand to the server's `say` command: no controls or formatting codes, one line. */
    fun chatSafe(text: String?): String =
        (text ?: "").replace(Regex("[\\u0000-\\u001f\\u007f\\u00a7]"), " ").replace(Regex("\\s+"), " ").trim().take(MAX_CHAT)

    /** "in 10 minutes" for a number of seconds left. */
    fun timeLeftPhrase(seconds: Long): String {
        if (seconds <= 0) return "now"
        if (seconds < 60) return "in less than a minute"
        val minutes = (seconds + 59) / 60
        return when {
            minutes == 1L -> "in 1 minute"
            minutes < 120 -> "in $minutes minutes"
            else -> {
                val hours = (minutes + 30) / 60
                "in about $hours hour${if (hours == 1L) "" else "s"}"
            }
        }
    }

    /**
     * What to say in the game's chat, as (seconds from now, line): at once (unless this is the same notice again),
     * again as the time runs out, and once when it is due. A notice with no time is said once, with no time in it.
     */
    fun plan(message: String, seconds: Int, repeat: Boolean): List<Pair<Int, String>> {
        val text = chatSafe(message)
        if (text.isEmpty()) return emptyList()
        val s = seconds.coerceAtLeast(0)
        fun line(left: Long, timed: Boolean) = "[VoxelPort] $text" + if (timed) " (${timeLeftPhrase(left)})" else ""
        val out = mutableListOf<Pair<Int, String>>()
        if (!repeat) out += 0 to line(s.toLong(), s > 0)
        if (s > 0) {
            for (before in REMINDERS) if (s > before + 30) out += (s - before) to line(before.toLong(), true)
            out += s to line(0, true)
        }
        return out
    }
}
