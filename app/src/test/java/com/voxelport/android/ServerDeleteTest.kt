package com.voxelport.android

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDeleteTest {
    private fun filesDir(): File = Files.createTempDirectory("vp-files").toFile().canonicalFile

    private fun populate(filesDir: File) {
        File(filesDir, "server/world/region").mkdirs()
        File(filesDir, "server/world/region/r.0.0.mca").writeText("world")
        File(filesDir, "server/plugins").mkdirs()
        File(filesDir, "server/plugins/Geyser.jar").writeText("jar")
        File(filesDir, "server/server.properties").writeText("motd=hi")
        File(filesDir, "backups").mkdirs()
        File(filesDir, "backups/2026-10-30_10-00-00_manual.zip").writeText("zip")
        File(filesDir, "jre25/lib").mkdirs()
        File(filesDir, "jre25/lib/libjli.so").writeText("java")
        File(filesDir, "console.log").writeText("log")
    }

    @Test fun `a running or public server cannot be deleted`() {
        assertEquals(ServerDelete.Block.RUNNING, ServerDelete.blocker(serverRunning = true, publicNow = false))
        assertEquals(ServerDelete.Block.RUNNING, ServerDelete.blocker(serverRunning = true, publicNow = true))
        assertEquals(ServerDelete.Block.PUBLIC, ServerDelete.blocker(serverRunning = false, publicNow = true))
        assertNull(ServerDelete.blocker(serverRunning = false, publicNow = false))
    }

    @Test fun `deleting removes the server and the log, and keeps backups and Java`() {
        val dir = filesDir(); populate(dir)
        assertTrue(ServerDelete.delete(dir))
        assertFalse(File(dir, "server").exists())
        assertFalse(File(dir, "console.log").exists())
        assertTrue("backups stay", File(dir, "backups/2026-10-30_10-00-00_manual.zip").isFile)
        assertTrue("Java stays", File(dir, "jre25/lib/libjli.so").isFile)
    }

    @Test fun `deleting when there is no server is fine`() {
        val dir = filesDir()
        assertTrue(ServerDelete.delete(dir))
    }

    @Test fun `only the app's own server folder is accepted`() {
        val dir = filesDir()
        File(dir, "server").mkdirs()
        assertTrue(ServerDelete.isOwnServerDir(File(dir, "server"), dir))
        assertFalse(ServerDelete.isOwnServerDir(dir, dir))
        assertFalse(ServerDelete.isOwnServerDir(File(dir, "server/.."), dir))
        assertFalse(ServerDelete.isOwnServerDir(File(dir, "backups"), dir))
        assertFalse(ServerDelete.isOwnServerDir(File(dir.parentFile, "server"), dir))
    }

    @Test fun `a server folder that is really a link to somewhere else is never deleted`() {
        val dir = filesDir()
        val elsewhere = Files.createTempDirectory("vp-elsewhere").toFile().canonicalFile
        File(elsewhere, "precious.txt").writeText("keep me")
        val link = runCatching { Files.createSymbolicLink(File(dir, "server").toPath(), elsewhere.toPath()) }.getOrNull()
        if (link == null) return // this machine cannot make links: nothing to test
        assertFalse(ServerDelete.isOwnServerDir(File(dir, "server"), dir))
        assertFalse(ServerDelete.delete(dir))
        assertTrue(File(elsewhere, "precious.txt").isFile)
    }
}
