package com.voxelport.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * World backups. A backup is one .zip with the server's world folders (and the small player lists), plus a
 * voxelport-backup.json that says what it is. The format is the same as the desktop app's, so a world can
 * be moved between a phone and a PC. Restoring first saves a "before restore" copy of what is there now, so a
 * restore can always be undone.
 *
 * This is only files: telling a running server to save first, and the schedule, are in BackupRunner.kt.
 * Plain Kotlin with no Android calls, so it is unit-tested on a normal JVM.
 */
class BackupException(val code: String, message: String) : Exception(message)

data class BackupEntry(val name: String, val createdAt: Long, val reason: String, val size: Long)

data class BackupResult(val name: String, val size: Long, val createdAt: Long, val reason: String, val worlds: List<String>)

/** What the backup says about itself (voxelport-backup.json). */
data class BackupMeta(val levelName: String, val worlds: List<String>, val reason: String, val serverType: String?, val version: String?)

/** Automatic backup settings. Phones have little storage, so fewer copies are kept than on a PC. */
data class BackupSettings(val auto: Boolean = true, val intervalMin: Int = 30, val keep: Int = 3) {
    companion object {
        val INTERVALS = listOf(10, 15, 30, 60, 120, 240)
        val KEEPS = listOf(2, 3, 5, 10)
        val INTERVAL_RANGE = 5..720
        val KEEP_RANGE = 1..30

        /** Settings from JSON text; anything missing or out of range falls back to the default. */
        fun parse(text: String?): BackupSettings {
            val d = BackupSettings()
            if (text.isNullOrBlank()) return d
            val j = runCatching { JSONObject(text) }.getOrNull() ?: return d
            return BackupSettings(
                auto = if (j.has("auto") && j.get("auto") is Boolean) j.getBoolean("auto") else d.auto,
                intervalMin = j.optInt("intervalMin", d.intervalMin).takeIf { it in INTERVAL_RANGE } ?: d.intervalMin,
                keep = j.optInt("keep", d.keep).takeIf { it in KEEP_RANGE } ?: d.keep,
            )
        }
    }

    fun toJson(): String = JSONObject().put("auto", auto).put("intervalMin", intervalMin).put("keep", keep).toString()
}

object Backups {
    const val META = "voxelport-backup.json"
    private const val FORMAT = 1
    val REASONS = listOf("auto", "manual", "stop", "before-restore")
    private val NAME = Regex("^(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})-(\\d{2})-(\\d{2})_(auto|manual|stop|before-restore)(?:-(\\d+))?\\.zip$")

    /** The small files worth keeping with the world: who may join, who is an operator. */
    val CONFIG_FILES = listOf("whitelist.json", "ops.json", "banned-players.json", "banned-ips.json")

    /** Held open by the running server and recreated on start: never part of a backup. */
    private const val SESSION_LOCK = "session.lock"

    private const val MB = 1024L * 1024
    private const val SPARE_BYTES = 200 * MB

    /** Automatic backups of one server never take more than this; the oldest go first (always keeping the newest two). */
    const val AUTO_BUDGET_BYTES = 2L * 1024 * MB
    private const val KEEP_BEFORE_RESTORE = 3
    private const val MAX_RESTORE_BYTES = 100L * 1024 * MB

    // ─── Names ────────────────────────────────────────────────────────────────────────────

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date(ms))

    /** { time in ms, reason } for one of our file names, or null for anything else. */
    fun parseName(name: String): Pair<Long, String>? {
        val m = NAME.matchEntire(name) ?: return null
        val g = m.groupValues
        val cal = Calendar.getInstance().apply {
            clear()
            set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        }
        return cal.timeInMillis to g[7]
    }

    private fun uniqueName(dir: File, ms: Long, reason: String): String {
        val base = "${stamp(ms)}_$reason"
        var name = "$base.zip"
        var i = 2
        while (File(dir, name).exists()) name = "$base-${i++}.zip"
        return name
    }

    /** Backups of one server, newest first. */
    fun list(dir: File): List<BackupEntry> =
        (dir.listFiles() ?: emptyArray()).mapNotNull { f ->
            val p = parseName(f.name) ?: return@mapNotNull null
            if (f.isFile) BackupEntry(f.name, p.first, p.second, f.length()) else null
        }.sortedWith(compareByDescending<BackupEntry> { it.createdAt }.thenByDescending { it.name })

    // ─── What a backup contains ───────────────────────────────────────────────────────────

    /** A world folder name must be a plain name: never a path. */
    fun isPlainName(s: String?): Boolean =
        s != null && s.isNotEmpty() && s.length <= 100 && s != "." && s != ".." && !s.startsWith(".") &&
            s.none { it < ' ' || it == '\u007f' || it in "\\/:*?\"<>|" }

    /** The level-name from server.properties ("world" when it is missing or not a plain name). */
    fun levelName(serverDir: File): String {
        val f = File(serverDir, "server.properties")
        if (f.isFile) {
            runCatching {
                val props = Properties().apply { FileInputStream(f).use { load(it) } }
                props.getProperty("level-name")?.trim()?.takeIf { isPlainName(it) }?.let { return it }
            }
        }
        return "world"
    }

    /** The three folders a world can be spread over (Paper keeps the Nether and the End next to it). */
    fun worldNames(level: String) = listOf(level, "${level}_nether", "${level}_the_end")

    private fun isRealDir(f: File) = f.isDirectory && !java.nio.file.Files.isSymbolicLink(f.toPath())

    fun existingWorlds(serverDir: File, level: String) = worldNames(level).filter { isRealDir(File(serverDir, it)) }

    private class Item(val file: File, val rel: String, val size: Long, val time: Long)

    private fun collect(dir: File, rel: String, out: MutableList<Item>) {
        for (f in dir.listFiles() ?: return) {
            if (f.name == SESSION_LOCK) continue
            val r = "$rel/${f.name}"
            when {
                java.nio.file.Files.isSymbolicLink(f.toPath()) -> continue
                f.isDirectory -> collect(f, r, out)
                f.isFile -> out += Item(f, r, f.length(), f.lastModified())
            }
        }
    }

    /**
     * True when something in the world was written after [sinceMs]. The level.dat files are ignored: the server
     * rewrites them on every autosave even when nobody has played, which would make every backup look needed.
     */
    fun worldChangedSince(serverDir: File, sinceMs: Long): Boolean {
        val level = levelName(serverDir)
        fun changed(dir: File): Boolean = (dir.listFiles() ?: emptyArray()).any { f ->
            when {
                f.name == SESSION_LOCK || f.name == "level.dat" || f.name == "level.dat_old" || f.name == "uid.dat" -> false
                java.nio.file.Files.isSymbolicLink(f.toPath()) -> false
                f.isDirectory -> changed(f)
                else -> f.lastModified() > sinceMs
            }
        }
        return existingWorlds(serverDir, level).any { changed(File(serverDir, it)) }
    }

    // ─── Making a backup ──────────────────────────────────────────────────────────────────

    /**
     * Zips the world(s) of a server into [backupDir]. The file only gets its final name once it has been written and
     * checked, so a half-finished backup can never be mistaken for a good one.
     */
    fun create(
        serverDir: File, backupDir: File, reason: String, nowMs: Long = System.currentTimeMillis(),
        serverType: String? = null, version: String? = null, appBuild: String? = null,
        onProgress: (phase: String, done: Long, total: Long) -> Unit = { _, _, _ -> },
    ): BackupResult {
        require(reason in REASONS) { "Unknown backup reason." }
        val level = levelName(serverDir)
        val worlds = existingWorlds(serverDir, level)
        if (worlds.isEmpty()) throw BackupException("no-world", "There is no world to back up yet. Start the server once so it creates one.")

        val items = mutableListOf<Item>()
        for (w in worlds) collect(File(serverDir, w), w, items)
        for (c in CONFIG_FILES) File(serverDir, c).takeIf { it.isFile }?.let { items += Item(it, c, it.length(), it.lastModified()) }
        val total = items.sumOf { it.size }

        backupDir.mkdirs()
        val free = backupDir.usableSpace
        if (free < total + SPARE_BYTES) throw BackupException("disk-full", "Not enough free storage for a backup (it needs about ${total / MB + 1} MB).")

        val name = uniqueName(backupDir, nowMs, reason)
        val partial = File(backupDir, "$name.partial")
        val meta = JSONObject()
            .put("format", FORMAT).put("createdAt", java.time.Instant.ofEpochMilli(nowMs).toString()).put("reason", reason)
            .put("levelName", level).put("worlds", JSONArray(worlds))
            .put("serverType", serverType ?: JSONObject.NULL).put("version", version ?: JSONObject.NULL).put("appVersion", appBuild ?: JSONObject.NULL)

        try {
            var done = 0L
            onProgress("zipping", 0, total)
            ZipOutputStream(BufferedOutputStream(FileOutputStream(partial), 64 * 1024)).use { zip ->
                // Region files are already compressed inside, so the fastest level is plenty on a phone.
                zip.setLevel(Deflater.BEST_SPEED)
                zip.putNextEntry(ZipEntry(META))
                zip.write(meta.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                val buf = ByteArray(64 * 1024)
                for (item in items) {
                    zip.putNextEntry(ZipEntry(item.rel).also { e -> e.time = item.time })
                    FileInputStream(item.file).use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            zip.write(buf, 0, n)
                            done += n
                        }
                    }
                    zip.closeEntry()
                    onProgress("zipping", done, total)
                }
            }
            val written = readInfo(partial) // proves the zip can be opened and has a world in it
            if (written.first.levelName != level) throw BackupException("bad-backup", "The backup could not be verified.")
            if (!partial.renameTo(File(backupDir, name))) throw BackupException("failed", "The backup could not be saved.")
        } catch (e: Exception) {
            partial.delete()
            if (e is BackupException) throw e
            throw BackupException("failed", "The backup failed: ${e.message}")
        }
        return BackupResult(name, File(backupDir, name).length(), parseName(name)!!.first, reason, worlds)
    }

    // ─── Reading a backup ─────────────────────────────────────────────────────────────────

    /** Opens a backup and reads its metadata and the total size of what is inside. Throws when it is not a readable VoxelPort backup. */
    fun readInfo(file: File): Pair<BackupMeta, Long> {
        val zip = try { ZipFile(file) } catch (e: Exception) { throw BackupException("bad-backup", "That backup file can't be read (${e.message}).") }
        zip.use {
            val names = HashSet<String>()
            var uncompressed = 0L
            for (e in it.entries()) {
                if (e.isDirectory) continue
                names += e.name
                uncompressed += e.size.coerceAtLeast(0)
            }
            val metaEntry = it.getEntry(META) ?: throw BackupException("not-voxelport", "That file is not a VoxelPort backup.")
            if (metaEntry.size > 64 * 1024) throw BackupException("bad-backup", "That backup is damaged.")
            val j = try { JSONObject(it.getInputStream(metaEntry).use { s -> String(s.readBytes(), Charsets.UTF_8) }) }
            catch (e: Exception) { throw BackupException("bad-backup", "That backup is damaged (${e.message}).") }
            val level = j.optString("levelName")
            val own = if (isPlainName(level)) worldNames(level) else emptyList()
            val worlds = (j.optJSONArray("worlds")?.let { a -> (0 until a.length()).map { i -> a.optString(i) } } ?: emptyList())
            // A backup may only ever put back the folders of its own world, whatever its metadata claims.
            if (j.optInt("format") != FORMAT || worlds.isEmpty() || !worlds.all { w -> w in own }) {
                throw BackupException("bad-backup", "That backup is from a newer VoxelPort or is damaged.")
            }
            if ("$level/level.dat" !in names) throw BackupException("bad-backup", "That backup has no world in it.")
            return BackupMeta(level, worlds, j.optString("reason"), j.optString("serverType").ifEmpty { null }, j.optString("version").ifEmpty { null }) to uncompressed
        }
    }

    private fun extractAll(file: File, dest: File, onBytes: (Long) -> Unit) {
        val root = dest.canonicalPath + File.separator
        ZipFile(file).use { zip ->
            val buf = ByteArray(64 * 1024)
            for (e in zip.entries()) {
                val name = e.name
                val target = File(dest, name)
                if (name.contains('\\') || name.contains(':') || name.startsWith("/") || !target.canonicalPath.startsWith(root)) {
                    throw BackupException("unsafe", "That backup contains a file outside its folder, so it was not restored.")
                }
                if (e.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                zip.getInputStream(e).use { input ->
                    FileOutputStream(target).use { out ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            onBytes(n.toLong())
                        }
                    }
                }
            }
        }
    }

    // ─── Restoring ────────────────────────────────────────────────────────────────────────

    private fun rename(from: File, to: File) {
        // A virus scanner or the media indexer can briefly hold a folder.
        repeat(5) { attempt ->
            if (from.renameTo(to)) return
            Thread.sleep(200L * (attempt + 1))
        }
        throw java.io.IOException("could not move ${from.name}")
    }

    class Restored(val levelName: String, val previousLevelName: String, val safetyBackup: String?)

    /**
     * Puts a backup back. The server must be stopped. First saves what is there now as a "before restore" backup
     * (if that fails, nothing is touched), then unpacks into a temporary folder, checks it, and only then swaps
     * the folders over. If the swap fails half way, the old world is put back. The caller sets level-name when it changed.
     */
    fun restore(
        serverDir: File, backupDir: File, name: String,
        serverType: String? = null, version: String? = null, appBuild: String? = null,
        onProgress: (phase: String, done: Long, total: Long) -> Unit = { _, _, _ -> },
    ): Restored {
        if (parseName(name) == null) throw BackupException("bad-name", "That is not one of VoxelPort's backups.")
        val file = File(backupDir, name)
        if (!file.isFile) throw BackupException("missing", "That backup is gone.")

        onProgress("checking", 0, 0)
        val (meta, uncompressed) = readInfo(file)
        if (uncompressed > MAX_RESTORE_BYTES) throw BackupException("too-big", "That backup is too big to restore.")
        if (serverDir.usableSpace < uncompressed + SPARE_BYTES) {
            throw BackupException("disk-full", "Not enough free storage to restore (it needs about ${uncompressed / MB + 1} MB).")
        }

        val previous = levelName(serverDir)
        var safety: BackupResult? = null
        if (existingWorlds(serverDir, previous).isNotEmpty()) {
            onProgress("saving-current", 0, 0)
            safety = create(serverDir, backupDir, "before-restore", serverType = serverType, version = version, appBuild = appBuild)
        }

        val id = System.currentTimeMillis()
        val tmp = File(serverDir, ".vp-restore-$id")
        val old = File(serverDir, ".vp-old-$id")
        val moved = mutableListOf<String>()
        val placed = mutableListOf<String>()
        try {
            tmp.mkdirs()
            var done = 0L
            onProgress("unpacking", 0, uncompressed)
            extractAll(file, tmp) { n -> done += n; onProgress("unpacking", done, uncompressed) }
            if (!File(tmp, "${meta.levelName}/level.dat").isFile) throw BackupException("bad-backup", "That backup has no world in it.")

            onProgress("swapping", 0, 0)
            old.mkdirs()
            for (w in (worldNames(previous) + worldNames(meta.levelName)).distinct()) {
                if (!isRealDir(File(serverDir, w))) continue
                rename(File(serverDir, w), File(old, w))
                moved += w
            }
            for (w in meta.worlds) {
                if (!isRealDir(File(tmp, w))) continue
                rename(File(tmp, w), File(serverDir, w))
                placed += w
            }
            for (c in CONFIG_FILES) File(tmp, c).takeIf { it.isFile }?.copyTo(File(serverDir, c), overwrite = true)
        } catch (e: Exception) {
            // Put everything back the way it was.
            for (w in placed) File(serverDir, w).deleteRecursively()
            for (w in moved) runCatching { rename(File(old, w), File(serverDir, w)) }
            tmp.deleteRecursively()
            old.deleteRecursively()
            if (e is BackupException) throw e
            throw BackupException("failed", "The restore failed and your world was left as it was: ${e.message}")
        }
        tmp.deleteRecursively()
        old.deleteRecursively() // the "before restore" backup holds the previous world
        return Restored(meta.levelName, previous, safety?.name)
    }

    // ─── Housekeeping ─────────────────────────────────────────────────────────────────────

    fun delete(backupDir: File, name: String) {
        if (parseName(name) == null) throw BackupException("bad-name", "That is not one of VoxelPort's backups.")
        File(backupDir, name).delete()
    }

    /**
     * Removes old automatic backups: keeps the newest [keep] of the automatic ones (and the ones made when the server
     * stopped), then trims further while they take more than [budgetBytes] (never below the newest two). Manual
     * backups are never removed here; "before restore" copies keep only the last few. Returns the names removed.
     */
    fun applyRetention(backupDir: File, keep: Int = BackupSettings().keep, budgetBytes: Long = AUTO_BUDGET_BYTES): List<String> {
        val all = list(backupDir)
        val removed = mutableListOf<String>()
        fun remove(b: BackupEntry) { if (File(backupDir, b.name).delete()) removed += b.name }

        val automatic = all.filter { it.reason == "auto" || it.reason == "stop" }
        automatic.drop(keep).forEach(::remove)
        val kept = automatic.take(keep).toMutableList()
        var total = kept.sumOf { it.size }
        while (kept.size > 2 && total > budgetBytes) {
            val oldest = kept.removeAt(kept.lastIndex)
            total -= oldest.size
            remove(oldest)
        }
        all.filter { it.reason == "before-restore" }.drop(KEEP_BEFORE_RESTORE).forEach(::remove)
        return removed
    }
}
