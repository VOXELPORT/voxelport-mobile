package com.voxelport.android

/**
 * The words on the Backups screen, as plain functions (no Android calls), so they are unit-tested.
 */
object BackupsLogic {
    /** "12 min ago" for how long ago [thenMs] was. */
    fun ago(nowMs: Long, thenMs: Long): String {
        val min = ((nowMs - thenMs).coerceAtLeast(0) / 60_000L).toInt()
        val hours = min / 60
        val days = hours / 24
        return when {
            min < 1 -> fill("just now")
            min < 60 -> fill("{n} min ago", mapOf("n" to min))
            hours == 1 -> fill("1 hour ago")
            hours < 24 -> fill("{n} hours ago", mapOf("n" to hours))
            days == 1 -> fill("1 day ago")
            else -> fill("{n} days ago", mapOf("n" to days))
        }
    }

    /** What an automatic-backup interval is called: 10 → "10 min", 60 → "1 hour", 240 → "4 hours". */
    fun interval(minutes: Int): String = when {
        minutes < 60 -> fill("{n} min", mapOf("n" to minutes))
        minutes == 60 -> fill("1 hour")
        minutes % 60 == 0 -> fill("{n} hours", mapOf("n" to minutes / 60))
        else -> fill("{n} min", mapOf("n" to minutes))
    }

    /** Why a backup was made, in a few words. */
    fun reason(reason: String): String = when (reason) {
        "auto" -> fill("Automatic")
        "manual" -> fill("You made this")
        "stop" -> fill("When the server stopped")
        "before-restore" -> fill("Saved before a restore")
        else -> reason
    }

    /** "340 KB", "12 MB", "1.4 GB". */
    fun size(bytes: Long): String = when {
        bytes < 1_000_000L -> "${(bytes / 1000L).coerceAtLeast(1)} KB"
        bytes < 1_000_000_000L -> "${bytes / 1_000_000L} MB"
        else -> "%.1f GB".format(java.util.Locale.ROOT, bytes / 1e9)
    }

    /** What a job is doing, for the progress line. */
    fun phase(phase: String): String = when (phase) {
        "starting" -> fill("Getting ready…")
        "zipping" -> fill("Copying your world…")
        "checking" -> fill("Checking the backup…")
        "saving-current" -> fill("Saving your current world first…")
        "unpacking" -> fill("Unpacking the backup…")
        "swapping" -> fill("Putting the world back…")
        else -> fill("Working…")
    }

    /** A failure in plain words, by its code (the engine's own message has numbers and technical detail in it). */
    fun error(code: String, message: String = ""): String = when (code) {
        "no-world" -> fill("There is no world to back up yet. Start the server once so it creates one.")
        "disk-full" -> fill("Not enough free storage on this phone. Free some space and try again.")
        "busy" -> fill("A backup or restore is already running.")
        "missing" -> fill("That backup is gone.")
        "too-big" -> fill("That backup is too big to restore.")
        "bad-backup", "not-voxelport" -> fill("That backup is damaged, or it is not a VoxelPort backup.")
        "unsafe" -> fill("That backup has a file in the wrong place, so it was not restored.")
        "bad-name" -> fill("That is not one of VoxelPort's backups.")
        "failed" ->
            if (message.startsWith("The restore failed")) fill("The restore failed and your world was left as it was.")
            else fill("The backup failed.")
        else -> fill("The backup failed.")
    }

    /** How the result of the last job reads. */
    fun result(s: BackupStatus): String = when {
        s.phase == "failed" -> error(s.code, s.error)
        s.reason == "restore" -> fill("Restored. Start the server to play on it.")
        else -> fill("Backup saved.")
    }

    /** Restore needs the server stopped: say why a restore button is off. */
    fun restoreHint(serverRunning: Boolean): String =
        if (serverRunning) fill("Stop the server to restore a backup.") else ""
}
