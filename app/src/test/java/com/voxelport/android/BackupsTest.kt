package com.voxelport.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Calendar
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupsTest {
    private val cleanup = mutableListOf<File>()

    @After fun tidy() { cleanup.forEach { it.deleteRecursively() } }

    private class Server(val root: File, val serverDir: File, val backupDir: File)

    /** A scratch folder with a fake server in it: a world with a Nether and some region files. */
    private fun server(level: String = "world", nether: Boolean = true): Server {
        val root = Files.createTempDirectory("vp-backup-").toFile().also { cleanup += it }
        val s = File(root, "server").apply { mkdirs() }
        File(s, "server.properties").writeText("motd=Test\nlevel-name=$level\nserver-port=25565\n")
        File(s, "$level/region").mkdirs()
        File(s, "$level/level.dat").writeBytes(bytes(2000))
        File(s, "$level/region/r.0.0.mca").writeBytes(bytes(50_000))
        File(s, "$level/region/r.0.1.mca").writeBytes(bytes(30_000))
        File(s, "$level/session.lock").writeText("held by the running server")
        if (nether) {
            File(s, "${level}_nether/DIM-1").mkdirs()
            File(s, "${level}_nether/level.dat").writeBytes(bytes(500))
        }
        File(s, "ops.json").writeText("""[{"name":"Steve"}]""")
        File(s, "server.jar").writeText("not part of a backup")
        File(s, "plugins").mkdirs()
        File(s, "plugins/keep.yml").writeText("a plugin file")
        return Server(root, s, File(root, "backups"))
    }

    private fun bytes(n: Int) = ByteArray(n).also { Random(n.toLong()).nextBytes(it) }

    private fun make(s: Server, reason: String = "manual", at: Long = System.currentTimeMillis()) =
        Backups.create(s.serverDir, s.backupDir, reason, at, serverType = "paper", version = "1.21.11", appBuild = "11")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, sec: Int): Long =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, sec) }.timeInMillis

    private fun zipNames(f: File): List<String> = ZipFile(f).use { z -> z.entries().asSequence().map { it.name }.toList() }

    private fun code(block: () -> Unit): String {
        try { block() } catch (e: BackupException) { return e.code }
        fail("expected a BackupException")
        return ""
    }

    // ─── Making a backup ─────────────────────────────────────────────────────────

    @Test fun `a backup holds the world, the Nether and the player lists, and nothing else`() {
        val s = server()
        val b = make(s)
        val names = zipNames(File(s.backupDir, b.name))
        assertTrue("world/level.dat" in names)
        assertTrue("world/region/r.0.0.mca" in names)
        assertTrue("world_nether/level.dat" in names)
        assertTrue("ops.json" in names)
        assertTrue(Backups.META in names)
        assertFalse("the lock the server holds is never saved", names.any { it.contains("session.lock") })
        assertFalse("only world data", "server.jar" in names || names.any { it.startsWith("plugins/") })
        assertEquals(listOf("world", "world_nether"), b.worlds.sorted())
    }

    @Test fun `the backup is named by time and why, and listed newest first`() {
        val s = server()
        val early = make(s, "auto", at(2026, 10, 16, 10, 0, 0))
        val late = make(s, "manual", at(2026, 10, 16, 11, 30, 5))
        assertEquals("2026-10-16_10-00-00_auto.zip", early.name)
        assertEquals("2026-10-16_11-30-05_manual.zip", late.name)
        val list = Backups.list(s.backupDir)
        assertEquals(listOf(late.name, early.name), list.map { it.name })
        assertEquals(listOf("manual", "auto"), list.map { it.reason })
        assertEquals(at(2026, 10, 16, 11, 30, 5), list[0].createdAt)
    }

    @Test fun `two backups in the same second do not overwrite each other`() {
        val s = server()
        val t = at(2026, 1, 2, 3, 4, 5)
        assertNotEquals(make(s, "manual", t).name, make(s, "manual", t).name)
        assertEquals(2, Backups.list(s.backupDir).size)
    }

    @Test fun `the metadata records what the backup is`() {
        val s = server()
        val b = make(s, "stop")
        val (meta, _) = Backups.readInfo(File(s.backupDir, b.name))
        assertEquals("world", meta.levelName)
        assertEquals("stop", meta.reason)
        assertEquals("paper", meta.serverType)
        assertEquals("1.21.11", meta.version)
    }

    @Test fun `a world with another name is found and backed up`() {
        val s = server(level = "Survival World", nether = false)
        assertEquals(listOf("Survival World"), make(s).worlds)
    }

    @Test fun `a level-name that is a path is ignored`() {
        val s = server()
        File(s.serverDir, "server.properties").writeText("level-name=../../secrets\n")
        assertEquals("world", Backups.levelName(s.serverDir))
        File(s.serverDir, "server.properties").writeText("level-name=\n")
        assertEquals("world", Backups.levelName(s.serverDir))
    }

    @Test fun `with no world yet it says so and leaves nothing behind`() {
        val s = server()
        File(s.serverDir, "world").deleteRecursively()
        File(s.serverDir, "world_nether").deleteRecursively()
        assertEquals("no-world", code { make(s) })
        assertTrue(s.backupDir.listFiles().isNullOrEmpty())
    }

    @Test fun `progress is reported and finishes at 100 percent`() {
        val s = server()
        val seen = mutableListOf<Triple<String, Long, Long>>()
        Backups.create(s.serverDir, s.backupDir, "manual") { p, d, t -> seen += Triple(p, d, t) }
        assertTrue(seen.size >= 2 && seen.all { it.first == "zipping" })
        assertEquals(0L, seen.first().second)
        assertEquals(seen.last().third, seen.last().second)
    }

    // ─── Restoring ───────────────────────────────────────────────────────────────

    @Test fun `restoring puts the world back exactly, even after it was wrecked`() {
        val s = server()
        val region = File(s.serverDir, "world/region/r.0.0.mca")
        val before = region.readBytes()
        val b = make(s)

        region.writeText("corrupted by a creeper")
        File(s.serverDir, "world/region/r.0.1.mca").delete()
        File(s.serverDir, "world/new-junk.txt").writeText("built after the backup")
        File(s.serverDir, "ops.json").writeText("[]")

        val res = Backups.restore(s.serverDir, s.backupDir, b.name)
        assertTrue(before.contentEquals(region.readBytes()))
        assertTrue(File(s.serverDir, "world/region/r.0.1.mca").isFile)
        assertFalse(File(s.serverDir, "world/new-junk.txt").exists())
        assertEquals("""[{"name":"Steve"}]""", File(s.serverDir, "ops.json").readText())
        assertEquals("world", res.levelName)
        assertTrue("plugins are not touched", File(s.serverDir, "plugins/keep.yml").isFile)
        assertTrue(File(s.serverDir, "server.jar").isFile)
    }

    @Test fun `before restoring the current world is saved so a restore can be undone`() {
        val s = server()
        val b = make(s, "manual", at(2026, 10, 1, 8, 0, 0))
        File(s.serverDir, "world/important-new-build.txt").writeText("two hours of work")

        val res = Backups.restore(s.serverDir, s.backupDir, b.name)
        assertTrue(res.safetyBackup!!.endsWith("_before-restore.zip"))
        assertFalse(File(s.serverDir, "world/important-new-build.txt").exists())

        Backups.restore(s.serverDir, s.backupDir, res.safetyBackup!!)
        assertEquals("two hours of work", File(s.serverDir, "world/important-new-build.txt").readText())
    }

    @Test fun `no temporary folders are left after a restore`() {
        val s = server()
        val b = make(s)
        Backups.restore(s.serverDir, s.backupDir, b.name)
        assertTrue(s.serverDir.list()!!.none { it.startsWith(".vp-") })
    }

    @Test fun `restoring a backup that used another world name reports it`() {
        val s = server(level = "oldworld", nether = false)
        val b = make(s)
        File(s.serverDir, "oldworld").deleteRecursively()
        File(s.serverDir, "newworld").mkdirs()
        File(s.serverDir, "newworld/level.dat").writeText("x")
        File(s.serverDir, "server.properties").writeText("level-name=newworld\n")

        val res = Backups.restore(s.serverDir, s.backupDir, b.name)
        assertEquals("oldworld", res.levelName)
        assertEquals("newworld", res.previousLevelName)
        assertTrue(File(s.serverDir, "oldworld/level.dat").isFile)
        assertFalse("the other world was moved out of the way (and saved in the safety backup)", File(s.serverDir, "newworld").exists())
    }

    @Test fun `a name that is not one of ours is refused including path tricks`() {
        val s = server()
        for (bad in listOf("../../etc/passwd", "x.zip", "2026-10-16_10-00-00_auto.zip/../../x", "..\\..\\x.zip", "")) {
            assertEquals(bad, "bad-name", code { Backups.restore(s.serverDir, s.backupDir, bad) })
            assertEquals(bad, "bad-name", code { Backups.delete(s.backupDir, bad) })
        }
    }

    @Test fun `a zip that is not a VoxelPort backup or is damaged is refused before anything is touched`() {
        val s = server()
        s.backupDir.mkdirs()
        val worldBefore = File(s.serverDir, "world/level.dat").readBytes()

        val foreign = File(s.backupDir, "2026-01-01_00-00-00_manual.zip")
        ZipOutputStream(foreign.outputStream()).use { z -> z.putNextEntry(ZipEntry("hello.txt")); z.write("hello".toByteArray()); z.closeEntry() }
        assertEquals("not-voxelport", code { Backups.restore(s.serverDir, s.backupDir, foreign.name) })

        val good = make(s, "manual", at(2026, 2, 1, 1, 1, 1))
        val goodFile = File(s.backupDir, good.name)
        val all = goodFile.readBytes()
        goodFile.writeBytes(all.copyOf(all.size / 2))
        assertEquals("bad-backup", code { Backups.restore(s.serverDir, s.backupDir, good.name) })

        assertTrue("the world was not touched", worldBefore.contentEquals(File(s.serverDir, "world/level.dat").readBytes()))
        assertTrue(s.serverDir.list()!!.none { it.startsWith(".vp-") })
    }

    private fun zipWith(file: File, meta: JSONObject, vararg entries: Pair<String, String>) {
        ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(ZipEntry(Backups.META)); z.write(meta.toString().toByteArray()); z.closeEntry()
            for ((name, body) in entries) { z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() }
        }
    }

    private fun metaFor(worlds: List<String>) = JSONObject().put("format", 1).put("createdAt", "x").put("reason", "manual").put("levelName", "world").put("worlds", JSONArray(worlds))

    @Test fun `a backup that tries to write outside the server folder is refused`() {
        val s = server()
        s.backupDir.mkdirs()
        val name = "2026-02-02_02-02-02_manual.zip"
        zipWith(File(s.backupDir, name), metaFor(listOf("world")), "world/level.dat" to "x", "../../evil.txt" to "pwned")
        // Java's zip reader accepts the name, so this proves our own check is what stops it.
        val c = code { Backups.restore(s.serverDir, s.backupDir, name) }
        assertTrue(c == "unsafe" || c == "failed" || c == "bad-backup")
        assertFalse(File(s.root, "evil.txt").exists())
        assertFalse(File(s.root.parentFile, "evil.txt").exists())
    }

    @Test fun `metadata that names other folders cannot make a restore replace them`() {
        val s = server()
        s.backupDir.mkdirs()
        val name = "2026-03-03_03-03-03_manual.zip"
        zipWith(File(s.backupDir, name), metaFor(listOf("world", "plugins")), "world/level.dat" to "x", "plugins/evil.jar" to "evil")
        assertEquals("bad-backup", code { Backups.restore(s.serverDir, s.backupDir, name) })
        assertEquals("a plugin file", File(s.serverDir, "plugins/keep.yml").readText())
        assertFalse(File(s.serverDir, "plugins/evil.jar").exists())
    }

    // ─── Housekeeping ────────────────────────────────────────────────────────────

    private fun dummy(dir: File, vararg specs: Pair<String, Int>) {
        dir.mkdirs()
        for ((name, size) in specs) File(dir, name).writeBytes(ByteArray(size))
    }

    @Test fun `retention keeps the newest automatic backups and never touches manual ones`() {
        val dir = File(Files.createTempDirectory("vp-ret-").toFile().also { cleanup += it }, "b")
        dummy(dir, "2026-10-01_10-00-00_manual.zip" to 10, "2026-10-02_10-00-00_auto.zip" to 10, "2026-10-03_10-00-00_auto.zip" to 10,
            "2026-10-04_10-00-00_stop.zip" to 10, "2026-10-05_10-00-00_auto.zip" to 10)
        val removed = Backups.applyRetention(dir, keep = 2)
        assertEquals(listOf("2026-10-02_10-00-00_auto.zip", "2026-10-03_10-00-00_auto.zip"), removed.sorted())
        assertEquals(listOf("2026-10-05_10-00-00_auto.zip", "2026-10-04_10-00-00_stop.zip", "2026-10-01_10-00-00_manual.zip"), Backups.list(dir).map { it.name })
    }

    @Test fun `retention also trims by total size but always keeps the newest two`() {
        val dir = File(Files.createTempDirectory("vp-ret-").toFile().also { cleanup += it }, "b")
        dummy(dir, "2026-10-01_10-00-00_auto.zip" to 400, "2026-10-02_10-00-00_auto.zip" to 400, "2026-10-03_10-00-00_auto.zip" to 400, "2026-10-04_10-00-00_auto.zip" to 400)
        Backups.applyRetention(dir, keep = 10, budgetBytes = 900)
        assertEquals(listOf("2026-10-04_10-00-00_auto.zip", "2026-10-03_10-00-00_auto.zip"), Backups.list(dir).map { it.name })
        Backups.applyRetention(dir, keep = 10, budgetBytes = 1)
        assertEquals("never below the newest two", 2, Backups.list(dir).size)
    }

    @Test fun `only the last three before-restore copies are kept`() {
        val dir = File(Files.createTempDirectory("vp-ret-").toFile().also { cleanup += it }, "b")
        dummy(dir, *(1..5).map { "2026-10-0${it}_10-00-00_before-restore.zip" to 5 }.toTypedArray())
        Backups.applyRetention(dir)
        assertEquals(3, Backups.list(dir).size)
        assertEquals("2026-10-03_10-00-00_before-restore.zip", Backups.list(dir)[2].name)
    }

    @Test fun `only our own file names are listed`() {
        val dir = File(Files.createTempDirectory("vp-ret-").toFile().also { cleanup += it }, "b")
        dummy(dir, "notes.txt" to 1, "2026-10-01_10-00-00_auto.zip.partial" to 1, "2026-10-01_10-00-00_oops.zip" to 1, "2026-10-01_10-00-00_auto.zip" to 1)
        assertEquals(listOf("2026-10-01_10-00-00_auto.zip"), Backups.list(dir).map { it.name })
        assertTrue(Backups.list(File(dir, "missing")).isEmpty())
    }

    @Test fun `deleting a backup removes just that file`() {
        val s = server()
        val a = make(s, "manual", at(2026, 6, 1, 1, 0, 0))
        val b = make(s, "manual", at(2026, 6, 1, 2, 0, 0))
        Backups.delete(s.backupDir, a.name)
        assertEquals(listOf(b.name), Backups.list(s.backupDir).map { it.name })
    }

    // ─── Is a backup worth making? ───────────────────────────────────────────────

    @Test fun `a world nobody wrote to since the last backup is not worth another`() {
        val s = server()
        val now = System.currentTimeMillis()
        s.serverDir.walkTopDown().forEach { it.setLastModified(now - 10_000) }
        assertFalse(Backups.worldChangedSince(s.serverDir, now - 5_000))
        File(s.serverDir, "world/region/r.0.0.mca").setLastModified(now)
        assertTrue(Backups.worldChangedSince(s.serverDir, now - 5_000))
    }

    @Test fun `the server rewriting level dat on every autosave does not count as a change`() {
        val s = server()
        val now = System.currentTimeMillis()
        s.serverDir.walkTopDown().forEach { it.setLastModified(now - 10_000) }
        File(s.serverDir, "world/level.dat").setLastModified(now)
        File(s.serverDir, "world/session.lock").setLastModified(now)
        assertFalse(Backups.worldChangedSince(s.serverDir, now - 5_000))
    }

    // ─── Settings ────────────────────────────────────────────────────────────────

    @Test fun `settings default to on, every 30 minutes, keeping 3`() {
        assertEquals(BackupSettings(true, 30, 3), BackupSettings.parse(null))
        assertEquals(BackupSettings(true, 30, 3), BackupSettings.parse(""))
        assertEquals(BackupSettings(true, 30, 3), BackupSettings.parse("not json"))
    }

    @Test fun `stored settings are used and out-of-range ones fall back`() {
        assertEquals(BackupSettings(false, 60, 10), BackupSettings.parse("""{"auto":false,"intervalMin":60,"keep":10}"""))
        assertEquals(BackupSettings(true, 30, 3), BackupSettings.parse("""{"auto":"yes","intervalMin":1,"keep":999}"""))
        assertEquals(BackupSettings(false, 15, 5), BackupSettings.parse(BackupSettings(false, 15, 5).toJson()))
    }

    @Test fun `parseName reads our names and nothing else`() {
        assertEquals("auto", Backups.parseName("2026-10-16_10-00-00_auto.zip")!!.second)
        assertEquals("before-restore", Backups.parseName("2026-10-16_10-00-00_before-restore-2.zip")!!.second)
        assertNull(Backups.parseName("2026-10-16_10-00-00_oops.zip"))
        assertNull(Backups.parseName("../2026-10-16_10-00-00_auto.zip"))
    }
}
