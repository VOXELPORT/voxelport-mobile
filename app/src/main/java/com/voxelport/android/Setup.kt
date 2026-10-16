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

    /** Stable Paper (Minecraft) versions, newest first (skips -rc/-pre). */
    fun paperVersions(limit: Int = 12): List<String> {
        val versions = getJson(PAPER).getJSONObject("versions")
        val out = ArrayList<String>()
        for (group in versions.keys()) {
            val list = versions.getJSONArray(group)
            for (i in 0 until list.length()) {
                val v = list.getString(i)
                if (!v.contains('-')) out.add(v)
            }
        }
        return out.take(limit)
    }

    private const val FABRIC = "https://meta.fabricmc.net/v2/versions"

    /** Stable Minecraft versions Fabric supports, newest first. */
    fun fabricVersions(limit: Int = 12): List<String> {
        val arr = getJsonArray("$FABRIC/game")
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            if (g.optBoolean("stable")) out.add(g.getString("version"))
        }
        return out.take(limit)
    }

    fun versionsFor(type: String, limit: Int = 12) = if (type == "fabric") fabricVersions(limit) else paperVersions(limit)

    /** Fabric's server launcher jar for [version] (it fetches the vanilla server on first start). */
    fun installFabric(f: Files, version: String, progress: (String) -> Unit) {
        progress(t("Finding Fabric for {version}…", "version" to version))
        val loaders = getJsonArray("$FABRIC/loader/$version")
        var loader = ""
        for (i in 0 until loaders.length()) {
            val l = loaders.getJSONObject(i).getJSONObject("loader")
            if (l.optBoolean("stable")) { loader = l.getString("version"); break }
        }
        if (loader.isEmpty()) error(t("Fabric doesn't support Minecraft {version} yet", "version" to version))
        val installers = getJsonArray("$FABRIC/installer")
        var installer = ""
        for (i in 0 until installers.length()) {
            val ins = installers.getJSONObject(i)
            if (ins.optBoolean("stable")) { installer = ins.getString("version"); break }
        }
        if (installer.isEmpty()) error("Couldn't find the Fabric installer")
        f.server.mkdirs()
        download("$FABRIC/loader/$version/$loader/$installer/server/jar", f.jar, null, "Fabric $version", progress)
        File(f.server, "voxelport-version.txt").writeText(version)
        File(f.server, "voxelport-type.txt").writeText("fabric")
        f.mods.mkdirs()
    }

    /** Latest stable Paper release (skips -rc/-pre versions). */
    fun latestPaperVersion(): String = paperVersions(1).firstOrNull() ?: error("PaperMC returned no versions")

    fun installPaper(f: Files, version: String, progress: (String) -> Unit) {
        progress(t("Finding Paper {version}…", "version" to version))
        val builds = getJsonArray("$PAPER/versions/$version/builds")
        var best: JSONObject? = null
        for (i in 0 until builds.length()) {
            val b = builds.getJSONObject(i)
            val stable = b.optString("channel") == "STABLE"
            val bestStable = best?.optString("channel") == "STABLE"
            if (best == null || (stable && !bestStable) || (stable == bestStable && b.getInt("id") > best.getInt("id"))) best = b
        }
        val dl = best?.getJSONObject("downloads")?.getJSONObject("server:default") ?: error(t("No Paper download for {version}", "version" to version))
        f.server.mkdirs()
        download(dl.getString("url"), f.jar, dl.getJSONObject("checksums").getString("sha256"), "Paper $version", progress)
        File(f.server, "voxelport-version.txt").writeText(version)
        File(f.server, "voxelport-type.txt").writeText("paper")
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
        for (d in listOf(f.plugins, f.mods)) d.listFiles()?.filter { it.name.startsWith("geyser", true) || it.name.startsWith("floodgate", true) }?.forEach { it.delete() }
    }

    /** First-run files: the EULA the user accepted, and phone-friendly defaults. */
    fun writeServerFiles(f: Files, level: Tuning.Level, saver: Boolean = false) {
        f.server.mkdirs()
        File(f.server, "eula.txt").writeText("# Accepted in the VoxelPort app (https://aka.ms/MinecraftEULA)\neula=true\n")
        val props = File(f.server, "server.properties")
        if (!props.exists()) {
            // First install: defaults sized for this phone (see Tuning).
            ServerProps.write(props, Tuning.initialProps(level, saver))
            Tuning.markProps(f.server, level, saver)
        }
    }

    internal fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
        }

    internal fun getText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) error(t("The download server ({host}) answered HTTP {code}.", "host" to URL(url).host, "code" to c.responseCode))
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    internal fun getJson(url: String) = JSONObject(getText(url))
    internal fun getJsonArray(url: String) = org.json.JSONArray(getText(url))

    /** Downloads [url] to [dest]; when [hash] is given it must match ([algo], e.g. SHA-256 / SHA-512). */
    internal fun download(url: String, dest: File, hash: String?, label: String, progress: (String) -> Unit, algo: String = "SHA-256") {
        val part = File(dest.path + ".part")
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) error(t("HTTP {code} while downloading {label}", "code" to c.responseCode, "label" to label))
            val total = c.contentLengthLong
            val md = MessageDigest.getInstance(algo)
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
                            DataMeter.sample() // so a big download is counted as it happens, on the connection it used
                            progress(if (pct >= 0) t("Downloading {label}… {pct}%", "label" to label, "pct" to pct) else t("Downloading {label}… {mb} MB", "label" to label, "mb" to done / 1_000_000))
                        }
                    }
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (hash != null && !got.equals(hash, ignoreCase = true)) {
                part.delete()
                error(t("The {label} download was corrupted (checksum mismatch). Try again.", "label" to label))
            }
            dest.delete()
            if (!part.renameTo(dest)) error(t("Couldn't save {label}", "label" to label))
        } finally {
            c.disconnect()
        }
    }
}
