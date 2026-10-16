package com.voxelport.android

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * What a backup or restore is doing now. Kept in a file, because the server runs in its own process (":jvm")
 * while the screen is in the app's, and both need to see it.
 */
data class BackupStatus(
    val running: Boolean = false,
    val reason: String = "",
    val phase: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val error: String = "",
    val code: String = "",
    val finishedAt: Long = 0,
    val lastName: String = "",
    val updatedAt: Long = 0,
) {
    /** True while a job is working: it said so, and wrote something recently (else its process was killed). */
    fun isActive(nowMs: Long) = running && nowMs - updatedAt < STALE_MS

    fun toJson(): String = JSONObject()
        .put("running", running).put("reason", reason).put("phase", phase).put("done", done).put("total", total)
        .put("error", error).put("code", code).put("finishedAt", finishedAt).put("lastName", lastName).put("updatedAt", updatedAt).toString()

    companion object {
        /** A job that has not written anything for this long is treated as dead (the process was killed). */
        const val STALE_MS = 3 * 60 * 1000L

        fun parse(text: String?): BackupStatus {
            val j = runCatching { JSONObject(text ?: "") }.getOrNull() ?: return BackupStatus()
            return BackupStatus(
                j.optBoolean("running"), j.optString("reason"), j.optString("phase"), j.optLong("done"), j.optLong("total"),
                j.optString("error"), j.optString("code"), j.optLong("finishedAt"), j.optString("lastName"), j.optLong("updatedAt"),
            )
        }
    }
}

/** The folders a backup works with (plain paths, so the logic can be tested without an Android context). */
class BackupPlace(val server: File, val log: File, val backups: File, val type: () -> String = { "paper" }, val version: () -> String = { "" }) {
    companion object {
        fun of(files: Files) = BackupPlace(files.server, files.log, files.backups, { files.type }, { files.version })
    }
}

/**
 * Runs backups and restores for the one server in this app. [command] types a line into the running server's
 * console (only used for a live server). The file work is in [Backups].
 */
class BackupRunner(
    private val place: BackupPlace, private val appBuild: String,
    private val now: () -> Long = System::currentTimeMillis, private val saveWaitMs: Long = SAVE_WAIT_MS,
) {
    val dir: File get() = place.backups
    private val settingsFile get() = File(dir, "settings.json")
    private val statusFile get() = File(dir, "status.json")
    private val inThisProcess = AtomicBoolean(false)

    fun settings(): BackupSettings = BackupSettings.parse(runCatching { settingsFile.readText() }.getOrNull())

    fun saveSettings(s: BackupSettings) {
        dir.mkdirs()
        settingsFile.writeText(s.toJson())
        Backups.applyRetention(dir, s.keep) // lowering "keep" trims old copies at once
    }

    fun status(): BackupStatus = BackupStatus.parse(runCatching { statusFile.readText() }.getOrNull())

    /** True while a backup or restore is working, in either process. */
    fun isBusy(): Boolean {
        if (inThisProcess.get()) return true
        return status().isActive(now())
    }

    fun lastBackupAt(): Long = Backups.list(dir).firstOrNull { it.reason != "before-restore" }?.createdAt ?: 0L

    private fun write(s: BackupStatus) {
        dir.mkdirs()
        val tmp = File(dir, "status.json.tmp")
        tmp.writeText(s.copy(updatedAt = now()).toJson())
        if (!tmp.renameTo(statusFile)) { statusFile.writeText(tmp.readText()); tmp.delete() }
    }

    private fun failure(reason: String, e: Exception): BackupStatus {
        val code = (e as? BackupException)?.code ?: "failed"
        return BackupStatus(false, reason, "failed", error = e.message ?: "The backup failed.", code = code, finishedAt = now(), updatedAt = now())
    }

    /** A progress callback that writes the status at most about 4 times a second. */
    private fun throttled(reason: String): (String, Long, Long) -> Unit {
        var last = 0L
        return { phase, done, total ->
            val t = now()
            if (done == 0L || done >= total || t - last >= 250) {
                last = t
                write(BackupStatus(true, reason, phase, done, total))
            }
        }
    }

    /**
     * Makes a backup now. [reason]: "manual", "auto" or "stop". For a live server (running in this process) it is told
     * to save and to hold its saves while the files are copied; saving is always switched back on afterwards.
     */
    fun run(reason: String, live: Boolean, command: (String) -> Unit = {}): BackupStatus {
        if (!inThisProcess.compareAndSet(false, true)) return BackupStatus(error = "A backup or restore is already running.", code = "busy")
        if (status().isActive(now())) {
            inThisProcess.set(false)
            return BackupStatus(error = "A backup or restore is already running.", code = "busy")
        }
        var holding = false
        try {
            write(BackupStatus(true, reason, "starting"))
            if (live) {
                holding = true
                val mark = place.log.length()
                command("save-off")
                command("save-all flush")
                waitForSaved(mark, saveWaitMs) // if the server is slow, back up what is on disk
            }
            val result = Backups.create(place.server, dir, reason, now(), serverType = place.type(), version = place.version(), appBuild = appBuild, onProgress = throttled(reason))
            Backups.applyRetention(dir, settings().keep)
            val done = BackupStatus(false, reason, "done", finishedAt = now(), lastName = result.name)
            write(done)
            return done
        } catch (e: Exception) {
            val f = failure(reason, e)
            write(f)
            return f
        } finally {
            if (holding) command("save-on")
            inThisProcess.set(false)
        }
    }

    /** Puts a backup back. The server must be stopped (the caller checks). Sets level-name when the backup used another world. */
    fun restore(name: String): BackupStatus {
        if (!inThisProcess.compareAndSet(false, true)) return BackupStatus(error = "A backup or restore is already running.", code = "busy")
        if (status().isActive(now())) {
            inThisProcess.set(false)
            return BackupStatus(error = "A backup or restore is already running.", code = "busy")
        }
        try {
            write(BackupStatus(true, "restore", "checking"))
            val r = Backups.restore(place.server, dir, name, serverType = place.type(), version = place.version(), appBuild = appBuild, onProgress = throttled("restore"))
            if (r.levelName != r.previousLevelName) ServerProps.write(File(place.server, "server.properties"), mapOf("level-name" to r.levelName))
            val done = BackupStatus(false, "restore", "done", finishedAt = now(), lastName = r.safetyBackup ?: "")
            write(done)
            return done
        } catch (e: Exception) {
            val f = failure("restore", e)
            write(f)
            return f
        } finally {
            inThisProcess.set(false)
        }
    }

    /** Waits until the server says it has saved (after `save-all flush`), looking only at what it wrote after [mark]. */
    private fun waitForSaved(mark: Long, timeoutMs: Long): Boolean {
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            if (SAVED.containsMatchIn(readFrom(place.log, mark))) return true
            Thread.sleep(300)
        }
        return false
    }

    companion object {
        /** What a Minecraft server says when `save-all` is done ("Saved the game"; older versions: "Saved the world"). */
        val SAVED = Regex("Saved the (?:game|world)", RegexOption.IGNORE_CASE)
        const val SAVE_WAIT_MS = 60_000L

        /** Text written to [file] after byte offset [from] (the whole file if it was started over meanwhile). */
        fun readFrom(file: File, from: Long): String {
            if (!file.isFile) return ""
            val len = file.length()
            val start = if (len < from) 0L else from
            if (len - start > 512_000) return readFrom(file, len - 512_000) // only the recent end matters
            if (len == start) return ""
            return RandomAccessFile(file, "r").use { r ->
                r.seek(start)
                val buf = ByteArray((len - start).toInt())
                r.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        }
    }
}

/**
 * The schedule, running next to the server (in its process): about once a minute it looks whether an automatic
 * backup is due, and only makes one if the world was written to since the last (nobody has played = nothing new).
 * A clean stop gets one last backup. [command] types a line into the server's console.
 */
class BackupSchedule(private val place: BackupPlace, private val runner: BackupRunner, private val command: (String) -> Unit, private val now: () -> Long = System::currentTimeMillis) {
    @Volatile var ready = false
        internal set
    @Volatile private var stopped = false

    fun start() {
        thread(name = "vp-backups", isDaemon = true) {
            while (!stopped) {
                Thread.sleep(if (ready) CHECK_MS else READY_CHECK_MS)
                if (!ready) { ready = serverReady(place.log); continue }
                runCatching { tick() }
            }
        }
    }

    fun stop() { stopped = true }

    /** Backup names only hold whole seconds, so a write in the same second as the stamp counts as already backed up. */
    private fun worthIt(last: Long) = last == 0L || Backups.worldChangedSince(place.server, last + NAME_SLACK_MS)

    fun tick() {
        val s = runner.settings()
        if (!s.auto || runner.isBusy()) return
        val last = runner.lastBackupAt()
        if (now() - last >= s.intervalMin * 60_000L && worthIt(last)) runner.run("auto", live = true, command = command)
    }

    /** One last backup before a stop, unless there is nothing new or one was just made. Blocks until it is done. */
    fun backupBeforeStop() {
        if (!ready) return
        val s = runner.settings()
        if (!s.auto || runner.isBusy()) return
        val last = runner.lastBackupAt()
        if (now() - last < RECENT_MS || !worthIt(last)) return
        runner.run("stop", live = true, command = command)
    }

    companion object {
        private const val CHECK_MS = 60_000L
        private const val READY_CHECK_MS = 5_000L
        private const val RECENT_MS = 2 * 60_000L
        private const val NAME_SLACK_MS = 1_000L
        private val DONE = Regex("Done \\([^)]*\\)!")

        /** The server prints "Done (12.3s)! For help, type "help"" once it accepts players. */
        fun serverReady(log: File): Boolean = DONE.containsMatchIn(BackupRunner.readFrom(log, 0))
    }
}
