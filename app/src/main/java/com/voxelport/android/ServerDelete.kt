package com.voxelport.android

import java.io.File

/**
 * Deleting the server from the phone: the world, plugins, mods and settings in the server folder, and the console log.
 * Plain logic (no Android calls) so the safety rules can be unit-tested. Nothing outside the server folder is touched:
 * world backups (kept beside it, never inside it), the Java runtime, the address and the Discord sign-in all stay.
 */
object ServerDelete {
    enum class Block { RUNNING, PUBLIC }

    /** Why the server cannot be deleted right now, or null. A running server has its files open, and a public one is in use. */
    fun blocker(serverRunning: Boolean, publicNow: Boolean): Block? = when {
        serverRunning -> Block.RUNNING
        publicNow -> Block.PUBLIC
        else -> null
    }

    /** True only for the app's own `server` folder: a path that resolves anywhere else (a link, a parent) is refused. */
    fun isOwnServerDir(dir: File, filesDir: File): Boolean =
        runCatching { dir.canonicalFile == File(filesDir.canonicalFile, "server") }.getOrDefault(false)

    /** Deletes the server folder and console log inside [filesDir]. True when the server folder is gone afterwards. */
    fun delete(filesDir: File): Boolean {
        val server = File(filesDir, "server")
        if (!isOwnServerDir(server, filesDir)) return false
        if (server.exists() && !server.deleteRecursively()) return false
        File(filesDir, "console.log").delete()
        return !server.exists()
    }
}
