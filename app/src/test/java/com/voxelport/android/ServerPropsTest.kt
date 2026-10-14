package com.voxelport.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files as NioFiles

class ServerPropsTest {
    private fun file(text: String) = NioFiles.createTempFile("sp", ".properties").toFile().apply { writeText(text); deleteOnExit() }

    @Test fun writesChangesAndKeepsEverythingElse() {
        val f = file("#Minecraft server properties\n#Mon Oct 12\nmotd=Hello\nonline-mode=true\nmax-players=20\n")
        ServerProps.write(f, mapOf("online-mode" to "false", "pvp" to "false"))
        val t = f.readText()
        assertTrue(t.startsWith("#Minecraft server properties\n#Mon Oct 12\nmotd=Hello\n"))
        assertTrue(t.contains("online-mode=false\n"))
        assertTrue(t.contains("max-players=20\n"))
        assertTrue(t.trimEnd().endsWith("pvp=false")) // new keys are appended
        assertEquals("false", ServerProps.read(f)["online-mode"])
    }

    @Test fun crackedSwitchRoundTrips() {
        val f = file("online-mode=true\nenforce-secure-profile=true\n")
        ServerProps.write(f, mapOf("online-mode" to "false", "enforce-secure-profile" to "false"))
        assertEquals("false", ServerProps.read(f)["online-mode"])
        ServerProps.write(f, mapOf("online-mode" to "true", "enforce-secure-profile" to "true"))
        assertEquals("true", ServerProps.read(f)["online-mode"])
        assertEquals(2, f.readLines().count { it.contains("=") }) // no duplicates piling up
    }

    @Test fun nonAsciiAndBackslashesAreEscapedForMinecraft() {
        val f = file("motd=x\n")
        ServerProps.write(f, mapOf("motd" to "Café §a A\\B"))
        assertTrue(f.readText().contains("motd=Caf\\u00e9 \\u00a7a A\\\\B"))
        assertEquals("Café §a A\\B", ServerProps.read(f)["motd"]) // and reads back identically
    }

    @Test fun missingFileIsCreatedAndReadsEmpty() {
        val dir = NioFiles.createTempDirectory("sp").toFile()
        val f = java.io.File(dir, "server.properties")
        assertEquals(emptyMap<String, String>(), ServerProps.read(f))
        ServerProps.write(f, mapOf("a" to "1"))
        assertEquals("1", ServerProps.read(f)["a"])
    }
}
