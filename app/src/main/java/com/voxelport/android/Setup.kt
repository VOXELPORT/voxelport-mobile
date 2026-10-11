package com.voxelport.android

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Installs the Java runtime (bundled) and downloads the server jars (official sources, SHA-256 checked). */
object Setup {
    private const val UA = "VoxelPort-Android (+https://voxelport.in)"
    private const val PAPER = "https://fill.papermc.io/v3/projects/paper"
    private const val GEYSER = "https://download.geysermc.org/v2/projects"

    /** Unpacks assets/jre25-arm64.tar.xz into files/jre25. */
    fun installJre(ctx: Context, f: Files, progress: (String) -> Unit) {
        if (f.jreReady) return
        progress("Unpacking Java 25…")
        val tmp = File(f.jre.parentFile, "jre25.partial")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val root = tmp.canonicalPath + File.separator
        ctx.assets.open("jre25-arm64.tar.xz").use { raw ->
            TarArchiveInputStream(XZInputStream(BufferedInputStream(raw, 1 shl 16))).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    val out = File(tmp, e.name)
                    if (!out.canonicalPath.startsWith(root) && out.canonicalPath != tmp.canonicalPath) continue
                    when {
                        e.isDirectory -> out.mkdirs()
                        e.isSymbolicLink -> { out.parentFile?.mkdirs(); runCatching { Os.symlink(e.linkName, out.path) } }
                        else -> {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                        }
                    }
                }
            }
        }
        f.jre.deleteRecursively()
        if (!tmp.renameTo(f.jre)) error("Couldn't install Java")
    }

    /** Latest stable Paper release (skips -rc/-pre versions). */
    fun latestPaperVersion(): String {
        val versions = getJson(PAPER).getJSONObject("versions")
        for (group in versions.keys()) {
            val list = versions.getJSONArray(group)
            for (i in 0 until list.length()) {
                val v = list.getString(i)
                if (!v.contains('-')) return v
            }
        }
        error("PaperMC returned no versions")
    }

    fun installPaper(f: Files, version: String, progress: (String) -> Unit) {
        progress("Finding Paper $version…")
        val builds = getJsonArray("$PAPER/versions/$version/builds")
        var best: JSONObject? = null
        for (i in 0 until builds.length()) {
            val b = builds.getJSONObject(i)
            val stable = b.optString("channel") == "STABLE"
            val bestStable = best?.optString("channel") == "STABLE"
            if (best == null || (stable && !bestStable) || (stable == bestStable && b.getInt("id") > best.getInt("id"))) best = b
        }
        val dl = best?.getJSONObject("downloads")?.getJSONObject("server:default") ?: error("No Paper download for $version")
        f.server.mkdirs()
        download(dl.getString("url"), f.jar, dl.getJSONObject("checksums").getString("sha256"), "Paper $version", progress)
        File(f.server, "voxelport-version.txt").writeText(version)
    }

    /** Geyser + Floodgate let Bedrock (phone/console) players join. */
    fun installBedrock(f: Files, progress: (String) -> Unit) {
        f.plugins.mkdirs()
        for (project in listOf("geyser", "floodgate")) {
            val b = getJson("$GEYSER/$project/versions/latest/builds/latest")
            val spigot = b.getJSONObject("downloads").getJSONObject("spigot")
            val url = "$GEYSER/$project/versions/${b.getString("version")}/builds/${b.getInt("build")}/downloads/spigot"
            f.plugins.listFiles()?.filter { it.name.startsWith(project, ignoreCase = true) }?.forEach { it.delete() }
            download(url, File(f.plugins, spigot.getString("name")), spigot.getString("sha256"), project.replaceFirstChar { it.uppercase() }, progress)
        }
    }

    fun removeBedrock(f: Files) {
        f.plugins.listFiles()?.filter { it.name.startsWith("geyser", true) || it.name.startsWith("floodgate", true) }?.forEach { it.delete() }
    }

    /** First-run files: the EULA the user accepted, and phone-friendly defaults. */
    fun writeServerFiles(f: Files) {
        f.server.mkdirs()
        File(f.server, "eula.txt").writeText("# Accepted in the VoxelPort app (https://aka.ms/MinecraftEULA)\neula=true\n")
        val props = File(f.server, "server.properties")
        if (!props.exists()) {
            props.writeText(
                """
                |motd=A VoxelPort phone server
                |server-port=25565
                |max-players=10
                |view-distance=6
                |simulation-distance=4
                |online-mode=true
                |enable-rcon=false
                |sync-chunk-writes=false
                |""".trimMargin()
            )
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
        }

    private fun getText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} from ${URL(url).host}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun getJson(url: String) = JSONObject(getText(url))
    private fun getJsonArray(url: String) = org.json.JSONArray(getText(url))

    private fun download(url: String, dest: File, sha256: String, label: String, progress: (String) -> Unit) {
        val part = File(dest.path + ".part")
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} downloading $label")
            val total = c.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            progress(if (pct >= 0) "Downloading $label… $pct%" else "Downloading $label… ${done / 1_000_000} MB")
                        }
                    }
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(sha256, ignoreCase = true)) {
                part.delete()
                error("$label download was corrupted (checksum mismatch). Try again.")
            }
            dest.delete()
            if (!part.renameTo(dest)) error("Couldn't save $label")
        } finally {
            c.disconnect()
        }
    }
}
