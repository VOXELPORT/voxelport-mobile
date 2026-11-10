package com.voxelport.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class PhoneCheckTest {
    private val gb = 1_000_000_000L

    @Test fun `a strong phone is a good host`() {
        val r = PhoneCheck.check(ramMb = 11_000, cores = 8, freeBytes = 50 * gb, sdk = 35)
        assertEquals(PhoneCheck.Level.GOOD, r.verdict)
        assertEquals(15, r.players)
        assertEquals(4, r.items.size)
    }

    @Test fun `a 4 GB phone works for a few friends`() {
        val r = PhoneCheck.check(ramMb = 3_600, cores = 8, freeBytes = 20 * gb, sdk = 33)
        assertEquals(PhoneCheck.Level.GOOD, r.verdict)
        assertEquals(5, r.players)
    }

    @Test fun `a 3 GB phone is warned`() {
        val r = PhoneCheck.check(ramMb = 2_900, cores = 8, freeBytes = 20 * gb, sdk = 33)
        assertEquals(PhoneCheck.Level.WARN, r.verdict)
        assertEquals(3, r.players)
    }

    @Test fun `a 2 GB phone is told plainly it may struggle`() {
        val r = PhoneCheck.check(ramMb = 1_800, cores = 8, freeBytes = 20 * gb, sdk = 33)
        assertEquals(PhoneCheck.Level.BAD, r.verdict)
        assertEquals(1, r.players)
        assertTrue(r.summary.contains("struggle"))
    }

    @Test fun `too little storage is the worst finding, even on a strong phone`() {
        val r = PhoneCheck.check(ramMb = 11_000, cores = 8, freeBytes = 800_000_000L, sdk = 35)
        assertEquals(PhoneCheck.Level.BAD, r.verdict)
        assertEquals(PhoneCheck.Level.BAD, r.items.first { it.title == "Storage" }.level)
    }

    @Test fun `enough to start but not much room is a warning`() {
        val r = PhoneCheck.check(ramMb = 11_000, cores = 8, freeBytes = 2 * gb, sdk = 35)
        assertEquals(PhoneCheck.Level.WARN, r.items.first { it.title == "Storage" }.level)
    }

    @Test fun `few cores and old Android are noted without blocking`() {
        val r = PhoneCheck.check(ramMb = 4_000, cores = 2, freeBytes = 20 * gb, sdk = 27)
        assertEquals(PhoneCheck.Level.WARN, r.items.first { it.title == "Processor" }.level)
        assertEquals(PhoneCheck.Level.OK, r.items.first { it.title == "Android" }.level)
        assertTrue(r.items.first { it.title == "Android" }.detail.contains("8.1"))
    }

    @Test fun `API levels map to Android names`() {
        assertEquals("15", PhoneCheck.androidName(35))
        assertEquals("14", PhoneCheck.androidName(34))
        assertEquals("12", PhoneCheck.androidName(31))
        assertEquals("8", PhoneCheck.androidName(26))
    }
}

class PlayersTest {
    private fun join(n: String) = "[12:00:01 INFO]: $n joined the game"
    private fun leave(n: String) = "[12:05:01 INFO]: $n left the game"

    @Test fun `joins add and leaves remove`() {
        var on = Players.apply(emptyList(), listOf(join("Steve"), join("Alex")))
        assertEquals(listOf("Steve", "Alex"), on)
        on = Players.apply(on, listOf(leave("Steve")))
        assertEquals(listOf("Alex"), on)
    }

    @Test fun `a repeated join does not list a player twice, and a leave of an unknown name is ignored`() {
        val on = Players.apply(listOf("Steve"), listOf(join("Steve"), leave("Ghost")))
        assertEquals(listOf("Steve"), on)
    }

    @Test fun `Bedrock names with a leading dot work`() {
        assertEquals(listOf(".Alex"), Players.apply(emptyList(), listOf(join(".Alex"))))
    }

    @Test fun `chat and other lines are ignored, even if they imitate a join`() {
        val lines = listOf(
            "[12:00:01 INFO]: <Steve> Alex joined the game",
            "[12:00:02 INFO]: Steve lost connection: Disconnected",
            "[12:00:03 INFO]: Done (3.2s)! For help, type \"help\"",
        )
        assertEquals(emptyList<String>(), Players.apply(emptyList(), lines))
    }

    @Test fun `only safe names become commands`() {
        assertEquals("kick Steve", Players.kick("Steve"))
        assertEquals("ban .Alex", Players.ban(".Alex"))
        assertEquals("whitelist add Notch", Players.allow("Notch"))
        assertEquals("whitelist remove Notch", Players.disallow("Notch"))
        assertNull(Players.kick("Steve; stop"))
        assertNull(Players.ban("a b"))
        assertNull(Players.allow(""))
        assertNull(Players.kick("x".repeat(40)))
    }
}

class FixesTest {
    private val start = "[12:00:00 INFO]: Starting minecraft server version 1.21.4"

    @Test fun `a server that finished starting has nothing to fix`() {
        val lines = listOf(start, "[12:00:09 ERROR]: java.lang.OutOfMemoryError", "[12:00:12 INFO]: Done (11.2s)! For help, type \"help\"")
        assertNull(Fixes.detect(lines))
    }

    @Test fun `out of memory is found and offers lighter settings`() {
        val fix = Fixes.detect(listOf(start, "Exception in thread \"Server thread\" java.lang.OutOfMemoryError: Java heap space"))
        assertEquals(Fixes.Kind.OUT_OF_MEMORY, fix?.kind)
    }

    @Test fun `a port already in use is found`() {
        assertEquals(Fixes.Kind.PORT_IN_USE, Fixes.detect(listOf(start, "**** FAILED TO BIND TO PORT!"))?.kind)
        assertEquals(Fixes.Kind.PORT_IN_USE, Fixes.detect(listOf(start, "java.net.BindException: Address already in use"))?.kind)
    }

    @Test fun `a full disk and a broken world are found`() {
        assertEquals(Fixes.Kind.DISK_FULL, Fixes.detect(listOf(start, "java.io.IOException: No space left on device"))?.kind)
        assertEquals(Fixes.Kind.WORLD_BROKEN, Fixes.detect(listOf(start, "Failed to load level data"))?.kind)
    }

    @Test fun `only the newest start counts, so an old crash is not shown again`() {
        val lines = listOf(start, "java.lang.OutOfMemoryError: Java heap space", "[12:10:00 INFO]: Starting minecraft server version 1.21.4", "[12:10:01 INFO]: Loading libraries")
        assertNull(Fixes.detect(lines))
    }

    @Test fun `no known problem gives nothing`() {
        assertNull(Fixes.detect(emptyList()))
        assertNull(Fixes.detect(listOf("[12:00:00 INFO]: Loading properties")))
    }

    @Test fun `the lighter memory setting steps down but stays sensible`() {
        assertEquals(1536, Fixes.lowerRam(2048, 3000))
        assertEquals(1024, Fixes.lowerRam(1024, 3000))
        assertEquals(1024, Fixes.lowerRam(1280, 3000))
        assertEquals(2048, Fixes.lowerRam(4096, 2048))
    }
}

class AddressTestTest {
    @Test fun `varints round trip`() {
        for (n in listOf(0, 1, 127, 128, 255, 25565, 767, 2_097_151)) {
            val out = ByteArrayOutputStream()
            AddressTest.writeVarInt(out, n)
            assertEquals(n, AddressTest.readVarInt(DataInputStream(ByteArrayInputStream(out.toByteArray()))))
        }
    }

    @Test fun `the handshake names the host and port`() {
        val bytes = AddressTest.handshake("play.voxelport.in", 26137)
        val din = DataInputStream(ByteArrayInputStream(bytes))
        val length = AddressTest.readVarInt(din)
        assertEquals(bytes.size - 1, length)
        assertEquals(0, AddressTest.readVarInt(din)) // packet id
        AddressTest.readVarInt(din) // protocol
        val hostLen = AddressTest.readVarInt(din)
        val host = ByteArray(hostLen).also { din.readFully(it) }
        assertEquals("play.voxelport.in", String(host))
        assertEquals(26137, din.readUnsignedShort())
        assertEquals(1, AddressTest.readVarInt(din)) // next state: status
    }

    private fun statusPacket(json: String): ByteArray {
        val text = json.toByteArray()
        val body = ByteArrayOutputStream()
        AddressTest.writeVarInt(body, 0)
        AddressTest.writeVarInt(body, text.size)
        body.write(text)
        val framed = ByteArrayOutputStream()
        AddressTest.writeVarInt(framed, body.size())
        framed.write(body.toByteArray())
        return framed.toByteArray()
    }

    @Test fun `a status reply is read and understood`() {
        val json = """{"version":{"name":"Paper 1.21.4","protocol":769},"players":{"max":10,"online":2}}"""
        val read = AddressTest.readStatus(ByteArrayInputStream(statusPacket(json)))
        assertEquals(json, read)
        val r = AddressTest.parse(read!!, 120) as AddressTest.Result.Online
        assertEquals("Paper 1.21.4", r.version)
        assertEquals(2, r.players)
        assertEquals(10, r.max)
        assertEquals(120, r.ms)
    }

    @Test fun `junk is not a Minecraft server`() {
        assertEquals(AddressTest.Result.NotMinecraft, AddressTest.parse("<html>", 5))
        val wrongId = ByteArrayOutputStream().apply { write(byteArrayOf(2, 1, 0)) }
        assertNull(AddressTest.readStatus(ByteArrayInputStream(wrongId.toByteArray())))
    }

    @Test fun `a real status exchange over a local socket works end to end`() {
        val json = """{"version":{"name":"Test"},"players":{"max":4,"online":1}}"""
        ServerSocket(0).use { server ->
            val t = thread {
                server.accept().use { c ->
                    val din = DataInputStream(c.getInputStream())
                    val len = AddressTest.readVarInt(din); din.readFully(ByteArray(len)) // handshake
                    din.readFully(ByteArray(2)) // status request
                    c.getOutputStream().write(statusPacket(json))
                }
            }
            val r = AddressTest.run("127.0.0.1", server.localPort, 3_000)
            t.join()
            assertTrue(r.toString(), r is AddressTest.Result.Online)
            assertEquals(1, (r as AddressTest.Result.Online).players)
        }
    }

    @Test fun `a closed port is refused and a silent server is reported as not answering`() {
        val port = ServerSocket(0).use { it.localPort } // free, then closed
        assertEquals(AddressTest.Result.Refused, AddressTest.run("127.0.0.1", port, 1_500))
        ServerSocket(0).use { server ->
            val t = thread { server.accept().use { it.close() } } // accepts, then hangs up
            val r = AddressTest.run("127.0.0.1", server.localPort, 1_500)
            t.join()
            assertEquals(AddressTest.Result.NoServer, r)
        }
    }

    @Test fun `every outcome has a plain explanation and only Online is good`() {
        val results = listOf(
            AddressTest.Result.Online("x", 0, 5, 40), AddressTest.Result.NoNetwork, AddressTest.Result.TimedOut,
            AddressTest.Result.Refused, AddressTest.Result.NoServer, AddressTest.Result.NotMinecraft,
        )
        for (r in results) {
            val (text, ok) = AddressTest.explain(r)
            assertTrue(text.isNotBlank())
            assertEquals(r is AddressTest.Result.Online, ok)
        }
        assertFalse(AddressTest.explain(AddressTest.Result.TimedOut).second)
    }
}
