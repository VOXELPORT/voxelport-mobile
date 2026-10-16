package com.voxelport.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class BackupRunnerTest {
    private val cleanup = mutableListOf<File>()

    @After fun tidy() { cleanup.forEach { it.deleteRecursively() } }

    private class Rig(val root: File, val place: BackupPlace) {
        val server get() = place.server
        val log get() = place.log
        val backups get() = place.backups
    }

    private fun rig(level: String = "world", withWorld: Boolean = true): Rig {
        val root = Files.createTempDirectory("vp-runner-").toFile().also { cleanup += it }
        val s = File(root, "server").apply { mkdirs() }
        File(s, "server.properties").writeText("level-name=$level\n")
        if (withWorld) {
            File(s, "$level/region").mkdirs()
            File(s, "$level/level.dat").writeBytes(ByteArray(300).also { Random(1).nextBytes(it) })
            File(s, "$level/region/r.0.0.mca").writeBytes(ByteArray(20_000).also { Random(2).nextBytes(it) })
        }
        return Rig(root, BackupPlace(s, File(root, "console.log"), File(root, "backups"), { "paper" }, { "1.21.11" }))
    }

    /** A pretend console: remembers every line typed, and (like Minecraft) answers a flush with "Saved the game". */
    private class Console(val log: File, val onLine: (String) -> Unit = {}) {
        val lines = mutableListOf<String>()
        fun type(line: String) {
            synchronized(lines) { lines += line }
            if (line == "save-all flush") log.appendText("[12:00:01 INFO]: Saved the game\n")
            onLine(line)
        }
        fun typed(): List<String> = synchronized(lines) { lines.toList() }
    }

    // ─── A live backup ───────────────────────────────────────────────────────────

    @Test fun `a live backup holds saving, waits until the server has saved, copies, then turns saving back on`() {
        val r = rig()
        r.log.writeText("[12:00:00 INFO]: Done (3.2s)! For help, type \"help\"\n")
        var backupsWhenSaveOn = -1
        val console = Console(r.log) { if (it == "save-on") backupsWhenSaveOn = Backups.list(r.backups).size }
        val runner = BackupRunner(r.place, "11")

        val st = runner.run("manual", live = true, command = console::type)

        assertEquals("", st.error)
        assertEquals("done", st.phase)
        assertEquals(listOf("save-off", "save-all flush", "save-on"), console.typed())
        assertEquals("the copy was finished before saving was switched on again", 1, backupsWhenSaveOn)
        assertEquals(st.lastName, Backups.list(r.backups).single().name)
        assertFalse(runner.isBusy())
    }

    @Test fun `an older save message in the log does not count as the answer`() {
        val r = rig()
        r.log.writeText("[11:00:00 INFO]: Saved the game\n")
        // This console never answers, so the runner has to give up waiting and back up what is on disk.
        val silent = Console(File(r.root, "elsewhere.log"))
        val started = System.currentTimeMillis()
        val st = BackupRunner(r.place, "11", saveWaitMs = 600).run("manual", live = true, command = silent::type)
        assertTrue("it waited for an answer", System.currentTimeMillis() - started >= 500)
        assertEquals("", st.error)
        assertEquals(listOf("save-off", "save-all flush", "save-on"), silent.typed())
    }

    @Test fun `saving is switched back on even when the backup fails`() {
        val r = rig(withWorld = false)
        val console = Console(r.log)
        val st = BackupRunner(r.place, "11").run("auto", live = true, command = console::type)

        assertEquals("no-world", st.code)
        assertEquals("failed", st.phase)
        assertEquals("save-on", console.typed().last())
        assertFalse("a failure does not leave the screen waiting", BackupRunner(r.place, "11").isBusy())
    }

    @Test fun `saving is switched back on even when typing into the console blows up half way`() {
        val r = rig()
        val seen = mutableListOf<String>()
        val st = BackupRunner(r.place, "11").run("manual", live = true, command = { line ->
            seen += line
            if (line == "save-all flush") throw IllegalStateException("console closed")
        })
        assertEquals("failed", st.phase)
        assertEquals("save-on", seen.last())
    }

    @Test fun `a stopped server is backed up without typing anything`() {
        val r = rig()
        val console = Console(r.log)
        val st = BackupRunner(r.place, "11").run("manual", live = false, command = console::type)
        assertEquals("", st.error)
        assertEquals(emptyList<String>(), console.typed())
        assertEquals(1, Backups.list(r.backups).size)
    }

    // ─── Only one job at a time ──────────────────────────────────────────────────

    @Test fun `a second backup is refused while one is running, and works again after`() {
        val r = rig()
        val inFlush = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = BackupRunner(r.place, "11")
        val console = Console(r.log) { if (it == "save-all flush") { inFlush.countDown(); release.await(10, TimeUnit.SECONDS) } }
        var firstResult: BackupStatus? = null
        val t = thread { firstResult = first.run("manual", live = true, command = console::type) }

        assertTrue(inFlush.await(10, TimeUnit.SECONDS))
        assertTrue(first.isBusy())
        // Another runner object (the screen's process) sees it through the status file.
        val other = BackupRunner(r.place, "11")
        assertTrue(other.isBusy())
        assertEquals("busy", other.run("manual", live = false).code)
        assertEquals("busy", first.run("manual", live = false).code)
        assertEquals("busy", other.restore("2020-01-01_00-00-00_manual.zip").code)

        release.countDown()
        t.join(10_000)
        assertEquals("", firstResult!!.error)
        assertFalse(first.isBusy())
        assertEquals("", other.run("manual", live = false).error)
    }

    @Test fun `a job whose process was killed stops blocking after a while`() {
        val r = rig()
        val clock = longArrayOf(System.currentTimeMillis())
        val runner = BackupRunner(r.place, "11", now = { clock[0] })
        r.backups.mkdirs()
        File(r.backups, "status.json").writeText(BackupStatus(true, "manual", "copying", 5, 10, updatedAt = clock[0]).toJson())

        assertTrue(runner.isBusy())
        assertEquals("busy", runner.run("manual", live = false).code)

        clock[0] += BackupStatus.STALE_MS + 1000
        assertFalse(runner.isBusy())
        assertEquals("", runner.run("manual", live = false).error)
    }

    // ─── Settings, status, restore ───────────────────────────────────────────────

    @Test fun `settings are kept in a file and lowering keep trims the old backups at once`() {
        val r = rig()
        val runner = BackupRunner(r.place, "11")
        assertEquals(BackupSettings(), runner.settings())

        val base = System.currentTimeMillis()
        for (i in 0 until 5) Backups.create(r.server, r.backups, "auto", base + i * 2000L)
        assertEquals(5, Backups.list(r.backups).size)

        runner.saveSettings(BackupSettings(auto = false, intervalMin = 60, keep = 2))
        assertEquals(BackupSettings(auto = false, intervalMin = 60, keep = 2), BackupRunner(r.place, "11").settings())
        assertEquals(2, Backups.list(r.backups).size)
    }

    @Test fun `the status survives the trip through the file, and garbage reads as idle`() {
        val s = BackupStatus(true, "restore", "unpacking", 123, 456, "oops", "disk-full", 99, "x.zip", 7)
        assertEquals(s, BackupStatus.parse(s.toJson()))
        assertEquals(BackupStatus(), BackupStatus.parse("not json"))
        assertEquals(BackupStatus(), BackupStatus.parse(null))
    }

    @Test fun `restoring sets level-name back when the backup was of another world`() {
        val r = rig(level = "world")
        val runner = BackupRunner(r.place, "11")
        val made = runner.run("manual", live = false)

        // The host switched to another world afterwards.
        File(r.server, "server.properties").writeText("level-name=other\n")
        File(r.server, "other").mkdirs()
        File(r.server, "other/level.dat").writeText("x")

        val st = runner.restore(made.lastName)
        assertEquals("", st.error)
        assertEquals("done", st.phase)
        assertEquals("world", Backups.levelName(r.server))
        assertTrue("the world that was in use is saved first", Backups.list(r.backups).any { it.reason == "before-restore" })
        assertEquals("a failed restore leaves a message", "missing", runner.restore("2020-01-01_00-00-00_manual.zip").code)
    }

    @Test fun `the time of the last backup ignores the before-restore safety copies`() {
        val r = rig()
        val runner = BackupRunner(r.place, "11")
        assertEquals(0L, runner.lastBackupAt())
        val t = System.currentTimeMillis()
        Backups.create(r.server, r.backups, "manual", t - 600_000)
        Backups.create(r.server, r.backups, "before-restore", t)
        assertEquals(Backups.list(r.backups).first { it.reason == "manual" }.createdAt, runner.lastBackupAt())
    }

    // ─── Reading the server's log ────────────────────────────────────────────────

    @Test fun `only what the server wrote after the mark is looked at`() {
        val f = File(Files.createTempDirectory("vp-log-").toFile().also { cleanup += it }, "console.log")
        assertEquals("", BackupRunner.readFrom(f, 0))
        f.writeText("old line\n")
        val mark = f.length()
        assertEquals("", BackupRunner.readFrom(f, mark))
        f.appendText("new line\n")
        assertEquals("new line\n", BackupRunner.readFrom(f, mark))
        // The log was started over (the server restarted): read it from the top.
        f.writeText("x\n")
        assertEquals("x\n", BackupRunner.readFrom(f, mark))
    }

    @Test fun `a huge log is only read from the end`() {
        val f = File(Files.createTempDirectory("vp-log-").toFile().also { cleanup += it }, "console.log")
        f.writeText("first\n" + "a".repeat(2_000_000) + "\nSaved the game\n")
        val read = BackupRunner.readFrom(f, 0)
        assertTrue(read.length <= 512_000)
        assertTrue(BackupRunner.SAVED.containsMatchIn(read))
    }

    @Test fun `the server counts as ready only after it printed Done`() {
        val f = File(Files.createTempDirectory("vp-log-").toFile().also { cleanup += it }, "console.log")
        assertFalse(BackupSchedule.serverReady(f))
        f.writeText("[12:00:00 INFO]: Preparing level \"world\"\n")
        assertFalse(BackupSchedule.serverReady(f))
        f.appendText("[12:00:09 INFO]: Done (8.91s)! For help, type \"help\"\n")
        assertTrue(BackupSchedule.serverReady(f))
    }

    // ─── The schedule ────────────────────────────────────────────────────────────

    private class Sched(val rig: Rig, val clock: LongArray, val console: Console, val schedule: BackupSchedule, val runner: BackupRunner)

    private fun sched(): Sched {
        val r = rig()
        val clock = longArrayOf(System.currentTimeMillis())
        val console = Console(r.log)
        val runner = BackupRunner(r.place, "11", now = { clock[0] })
        runner.saveSettings(BackupSettings(auto = true, intervalMin = 10, keep = 5))
        val schedule = BackupSchedule(r.place, runner, console::type, now = { clock[0] })
        schedule.ready = true
        return Sched(r, clock, console, schedule, runner)
    }

    private fun count(s: Sched) = Backups.list(s.rig.backups).size

    @Test fun `the first automatic backup is made at once, the next only when it is due and the world changed`() {
        val s = sched()
        s.schedule.tick()
        assertEquals(1, count(s))
        assertEquals("auto", Backups.list(s.rig.backups).single().reason)

        s.clock[0] += 3 * 60_000 // not due yet
        s.schedule.tick()
        assertEquals(1, count(s))

        s.clock[0] += 60 * 60_000 // due, but nobody has played
        s.schedule.tick()
        assertEquals("nothing new means no new backup", 1, count(s))

        val region = File(s.rig.server, "world/region/r.0.0.mca")
        assertTrue(region.setLastModified(s.clock[0] - 60_000))
        s.schedule.tick()
        assertEquals("the world changed, so now it is backed up", 2, count(s))
    }

    @Test fun `the server rewriting level dat on its own does not count as a change`() {
        val s = sched()
        s.schedule.tick()
        s.clock[0] += 60 * 60_000
        assertTrue(File(s.rig.server, "world/level.dat").setLastModified(s.clock[0] - 1000))
        s.schedule.tick()
        assertEquals(1, count(s))
    }

    @Test fun `with automatic backups off nothing is made on a schedule or at a stop`() {
        val s = sched()
        s.runner.saveSettings(BackupSettings(auto = false, intervalMin = 10, keep = 5))
        s.schedule.tick()
        s.schedule.backupBeforeStop()
        assertEquals(0, count(s))
        assertEquals(emptyList<String>(), s.console.typed())
    }

    @Test fun `a stop gets one last backup if the world changed, and none if there is nothing new or one was just made`() {
        val s = sched()
        s.schedule.tick()                       // a backup now
        s.clock[0] += 60_000
        s.schedule.backupBeforeStop()           // just made: no second one
        assertEquals(1, count(s))

        s.clock[0] += 30 * 60_000
        s.schedule.backupBeforeStop()           // long ago, but nothing changed
        assertEquals(1, count(s))

        File(s.rig.server, "world/region/r.0.0.mca").setLastModified(s.clock[0] - 5000)
        s.schedule.backupBeforeStop()           // changed
        assertEquals(2, count(s))
        assertEquals("stop", Backups.list(s.rig.backups).first().reason)
        assertEquals("save-on", s.console.typed().last())
    }

    @Test fun `nothing is done before the server is ready`() {
        val s = sched()
        s.schedule.ready = false
        s.schedule.backupBeforeStop()
        assertEquals(0, count(s))
        assertNotEquals(true, s.schedule.ready)
    }

    @Test fun `no automatic backup starts while another job is running`() {
        val s = sched()
        File(s.rig.backups, "x").mkdirs()
        File(s.rig.backups, "status.json").writeText(BackupStatus(true, "manual", "copying", updatedAt = s.clock[0]).toJson())
        s.schedule.tick()
        assertEquals(0, count(s))
    }
}
