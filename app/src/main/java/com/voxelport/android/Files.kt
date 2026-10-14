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
    val version get() = File(server, "voxelport-version.txt").takeIf { it.isFile }?.readText()?.trim() ?: ""
    val log = File(ctx.filesDir, "console.log")
    val tmp = File(ctx.cacheDir, "jvm-tmp")

    val jreReady get() = File(jre, "lib/libjli.so").isFile && File(jre, "release").isFile
    val serverReady get() = jar.isFile
    val bedrockReady get() = plugins.listFiles()?.any { it.name.startsWith("Geyser", ignoreCase = true) } == true
}
