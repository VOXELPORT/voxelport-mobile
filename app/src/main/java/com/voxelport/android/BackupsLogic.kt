package com.voxelport.android

/**
 * The words on the Backups screen, as plain functions of the language (no Android calls), so they are unit-tested.
 * Every sentence is written out in full so the language file ([Hinglish]) can translate it.
 */
object BackupsLogic {
    /** "12 min ago" for how long ago [thenMs] was. */
    fun ago(lang: String, nowMs: Long, thenMs: Long): String {
        val min = ((nowMs - thenMs).coerceAtLeast(0) / 60_000L).toInt()
        val hours = min / 60
        val days = hours / 24
        return when {
            min < 1 -> translate(lang, "just now")
            min < 60 -> translate(lang, "{n} min ago", mapOf("n" to min))
            hours == 1 -> translate(lang, "1 hour ago")
            hours < 24 -> translate(lang, "{n} hours ago", mapOf("n" to hours))
            days == 1 -> translate(lang, "1 day ago")
            else -> translate(lang, "{n} days ago", mapOf("n" to days))
        }
    }

    /** What an automatic-backup interval is called: 10 → "10 min", 60 → "1 hour", 240 → "4 hours". */
    fun interval(lang: String, minutes: Int): String = when {
        minutes < 60 -> translate(lang, "{n} min", mapOf("n" to minutes))
        minutes == 60 -> translate(lang, "1 hour")
        minutes % 60 == 0 -> translate(lang, "{n} hours", mapOf("n" to minutes / 60))
        else -> translate(lang, "{n} min", mapOf("n" to minutes))
    }

    /** Why a backup was made, in a few words. */
    fun reason(lang: String, reason: String): String = when (reason) {
        "auto" -> translate(lang, "Automatic")
        "manual" -> translate(lang, "You made this")
        "stop" -> translate(lang, "When the server stopped")
        "before-restore" -> translate(lang, "Saved before a restore")
        else -> reason
    }

    /** "340 KB", "12 MB", "1.4 GB". */
    fun size(bytes: Long): String = when {
        bytes < 1_000_000L -> "${(bytes / 1000L).coerceAtLeast(1)} KB"
        bytes < 1_000_000_000L -> "${bytes / 1_000_000L} MB"
        else -> "%.1f GB".format(java.util.Locale.ROOT, bytes / 1e9)
    }

    /** What a job is doing, for the progress line. */
    fun phase(lang: String, phase: String): String = when (phase) {
        "starting" -> translate(lang, "Getting ready…")
        "zipping" -> translate(lang, "Copying your world…")
        "checking" -> translate(lang, "Checking the backup…")
        "saving-current" -> translate(lang, "Saving your current world first…")
        "unpacking" -> translate(lang, "Unpacking the backup…")
        "swapping" -> translate(lang, "Putting the world back…")
        else -> translate(lang, "Working…")
    }

    /** A failure in plain words, by its code (the engine's own message has numbers and technical detail in it). */
    fun error(lang: String, code: String, message: String = ""): String = when (code) {
        "no-world" -> translate(lang, "There is no world to back up yet. Start the server once so it creates one.")
        "disk-full" -> translate(lang, "Not enough free storage on this phone. Free some space and try again.")
        "busy" -> translate(lang, "A backup or restore is already running.")
        "missing" -> translate(lang, "That backup is gone.")
        "too-big" -> translate(lang, "That backup is too big to restore.")
        "bad-backup", "not-voxelport" -> translate(lang, "That backup is damaged, or it is not a VoxelPort backup.")
        "unsafe" -> translate(lang, "That backup has a file in the wrong place, so it was not restored.")
        "bad-name" -> translate(lang, "That is not one of VoxelPort's backups.")
        "failed" ->
            if (message.startsWith("The restore failed")) translate(lang, "The restore failed and your world was left as it was.")
            else translate(lang, "The backup failed.")
        else -> translate(lang, "The backup failed.")
    }

    /** How the result of the last job reads. */
    fun result(lang: String, s: BackupStatus): String = when {
        s.phase == "failed" -> error(lang, s.code, s.error)
        s.reason == "restore" -> translate(lang, "Restored. Start the server to play on it.")
        else -> translate(lang, "Backup saved.")
    }

    /** Restore needs the server stopped: say why a restore button is off. */
    fun restoreHint(lang: String, serverRunning: Boolean): String =
        if (serverRunning) translate(lang, "Stop the server to restore a backup.") else ""
}
