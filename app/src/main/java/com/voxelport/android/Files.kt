package com.voxelport.android

import android.content.Context
import java.io.File

/** Where everything lives, inside the app's private storage. */
class Files(ctx: Context) {
    val jre = File(ctx.filesDir, "jre25")
    val server = File(ctx.filesDir, "server")
    val jar = File(server, "server.jar")
    val plugins = File(server, "plugins")
    val mods = File(server, "mods")
    /** "paper" or "fabric": which server software is installed. */
    val type get() = File(server, "voxelport-type.txt").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it == "fabric" } ?: "paper"
    val version get() = File(server, "voxelport-version.txt").takeIf { it.isFile }?.readText()?.trim() ?: ""
    val log = File(ctx.filesDir, "console.log")
    /** World backups (outside the server folder, so a server reinstall never touches them). */
    val backups = File(ctx.filesDir, "backups")
    val tmp = File(ctx.cacheDir, "jvm-tmp")

    val jreReady get() = File(jre, "lib/libjli.so").isFile && File(jre, "release").isFile
    val serverReady get() = jar.isFile
    val bedrockReady get() = plugins.listFiles()?.any { it.name.startsWith("Geyser", ignoreCase = true) } == true
    /** Both Geyser and Floodgate are installed (as plugins or as mods). */
    val bedrockComplete: Boolean
        get() {
            val names = listOf(plugins, mods).flatMap { dir -> dir.listFiles()?.map { it.name.lowercase() } ?: emptyList() }
            return names.any { it.startsWith("geyser") } && names.any { it.startsWith("floodgate") }
        }
}
