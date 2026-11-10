package com.voxelport.android

/**
 * Known server problems, spotted in the console, each with a button that fixes it. Plain logic, so it can be unit-tested;
 * the screen runs the fix.
 */
object Fixes {
    enum class Kind { OUT_OF_MEMORY, PORT_IN_USE, DISK_FULL, WORLD_BROKEN }

    data class Fix(val kind: Kind, val message: String, val button: String)

    private const val STARTING = "Starting minecraft server"

    /**
     * The problem in the newest start of the server, or null when it is running fine (it printed "Done") or nothing known
     * went wrong. [lines] are the console lines, oldest first.
     */
    fun detect(lines: List<String>): Fix? {
        val from = lines.indexOfLast { it.contains(STARTING) }.let { if (it < 0) 0 else it }
        val recent = lines.subList(from, lines.size)
        if (recent.any { it.contains("Done (") && it.contains("For help, type") }) return null
        for (l in recent.asReversed()) {
            when {
                l.contains("OutOfMemoryError") || l.contains("Java heap space") || l.contains("GC overhead limit") ->
                    return Fix(
                        Kind.OUT_OF_MEMORY,
                        t("The server ran out of memory. A lighter setting fixes this."),
                        t("USE LIGHTER SETTINGS"),
                    )
                l.contains("Address already in use") || l.contains("FAILED TO BIND TO PORT", ignoreCase = true) || l.contains("Failed to bind to port", ignoreCase = true) ->
                    return Fix(
                        Kind.PORT_IN_USE,
                        t("Another copy of the server is still using the port. Restarting clears it."),
                        t("FIX AND RESTART"),
                    )
                l.contains("No space left on device") ->
                    return Fix(
                        Kind.DISK_FULL,
                        t("The phone is out of storage, so the server can't save. Free some space (old backups are a good start)."),
                        t("OPEN FILES"),
                    )
                l.contains("Failed to load level") || l.contains("Failed to read level.dat") || l.contains("Failed to load chunk") ->
                    return Fix(
                        Kind.WORLD_BROKEN,
                        t("The world could not be loaded. Restoring a backup usually fixes this."),
                        t("OPEN BACKUPS"),
                    )
            }
        }
        return null
    }

    /** A smaller heap for the out-of-memory fix: one step (512 MB) lower, never under 1 GB, and never above what suits the phone. */
    fun lowerRam(currentMb: Int, recommendedMb: Int): Int =
        (currentMb - 512).coerceAtLeast(1024).coerceAtMost(maxOf(1024, recommendedMb))
}
