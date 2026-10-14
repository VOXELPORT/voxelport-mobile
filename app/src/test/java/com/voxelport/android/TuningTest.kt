package com.voxelport.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files as NioFiles

class TuningTest {
    private val low = Tuning.Device(3500, 8)       // "4 GB" phone
    private val mid = Tuning.Device(7400, 8)       // "8 GB" phone (Nothing Phone 1)
    private val high = Tuning.Device(11500, 8)     // "12 GB" phone
    private fun tmp() = NioFiles.createTempDirectory("vp").toFile().apply { deleteOnExit() }

    @Test fun autoPicksByRamAndCores() {
        assertEquals(Tuning.Level.LOW, Tuning.auto(low))
        assertEquals(Tuning.Level.BALANCED, Tuning.auto(mid))
        assertEquals(Tuning.Level.HIGH, Tuning.auto(high))
        assertEquals(Tuning.Level.LOW, Tuning.auto(Tuning.Device(7400, 4))) // few cores = old/slow
        assertEquals(Tuning.Level.LOW, Tuning.resolve("low", high))          // an explicit choice wins
        assertEquals(Tuning.Level.HIGH, Tuning.resolve("high", low))
        assertEquals(Tuning.Level.BALANCED, Tuning.resolve("auto", mid))
        assertEquals(Tuning.Level.BALANCED, Tuning.resolve("garbage", mid))
    }

    @Test fun recommendedRamLeavesRoomForAndroid() {
        assertEquals(1024, Tuning.recommendedRamMb(low))
        assertEquals(2048, Tuning.recommendedRamMb(mid))
        assertEquals(3072, Tuning.recommendedRamMb(high))
        assertEquals(4096, Tuning.recommendedRamMb(Tuning.Device(24000, 8))) // capped
        assertEquals(1024, Tuning.recommendedRamMb(Tuning.Device(2000, 8)))  // floor
        for (d in listOf(low, mid, high)) assertTrue(Tuning.recommendedRamMb(d) <= Tuning.maxRamMb(d))
        assertEquals(0, Tuning.maxRamMb(mid) % 256)
    }

    @Test fun jvmFlagsAreSafeForEveryLevel() {
        for (level in Tuning.Level.values()) for (d in listOf(low, mid, high)) {
            val f = Tuning.jvmFlags(level, 2048, d)
            assertEquals("unknown flags must be ignored, not fatal", "-XX:+IgnoreUnrecognizedVMOptions", f.first())
            val gcs = f.filter { it in setOf("-XX:+UseSerialGC", "-XX:+UseG1GC", "-XX:+UseParallelGC") }
            assertEquals("exactly one collector ($level)", 1, gcs.size)
            assertTrue(f.contains("-Xmx2048M"))
            assertTrue(f.contains("-DPaper.IgnoreJavaVersion=true"))
            val xms = f.first { it.startsWith("-Xms") }.removePrefix("-Xms").removeSuffix("M").toInt()
            assertTrue("Xms <= Xmx", xms <= 2048)
        }
        // A tiny heap never gets a bigger initial heap than max.
        val tiny = Tuning.jvmFlags(Tuning.Level.HIGH, 512, high)
        assertTrue(tiny.contains("-Xms512M"))
        assertEquals("-XX:+UseSerialGC", Tuning.jvmFlags(Tuning.Level.LOW, 1024, low).first { it.contains("GC") })
        assertTrue(Tuning.jvmFlags(Tuning.Level.BALANCED, 2048, mid).contains("-XX:+UseG1GC"))
    }

    @Test fun lowerLevelsMeanSmallerWorldsAndFewerMobs() {
        fun n(level: Tuning.Level, key: String) = Tuning.profileProps(level)[key]!!.toInt()
        assertTrue(n(Tuning.Level.LOW, "view-distance") < n(Tuning.Level.BALANCED, "view-distance"))
        assertTrue(n(Tuning.Level.BALANCED, "view-distance") < n(Tuning.Level.HIGH, "view-distance"))
        assertTrue(n(Tuning.Level.LOW, "simulation-distance") < n(Tuning.Level.HIGH, "simulation-distance"))
        for (l in Tuning.Level.values()) assertTrue(n(l, "simulation-distance") <= n(l, "view-distance"))
        // A fresh server is premium-only; "cracked" must be a deliberate choice.
        assertEquals("true", Tuning.initialProps(Tuning.Level.BALANCED)["online-mode"])
        // Mob caps shrink with the level too.
        fun cap(l: Tuning.Level) = Tuning.yamlEdits(l).first { it.second == listOf("spawn-limits", "monsters") }.third.toInt()
        assertTrue(cap(Tuning.Level.LOW) < cap(Tuning.Level.HIGH))
    }

    @Test fun prepareAppliesOncePerLevelAndKeepsYourEdits() {
        val server = tmp()
        File(server, "server.properties").writeText("# props\nmotd=Hi\nview-distance=10\n")
        Tuning.prepare(server, Tuning.Level.LOW)
        var p = ServerProps.read(File(server, "server.properties"))
        assertEquals("4", p["view-distance"])
        assertEquals("Hi", p["motd"])            // untouched keys survive

        // The player raises view distance by hand; the same level must not undo it.
        ServerProps.write(File(server, "server.properties"), mapOf("view-distance" to "7"))
        Tuning.prepare(server, Tuning.Level.LOW)
        assertEquals("7", ServerProps.read(File(server, "server.properties"))["view-distance"])

        // Picking another level applies that level.
        Tuning.prepare(server, Tuning.Level.HIGH)
        p = ServerProps.read(File(server, "server.properties"))
        assertEquals("8", p["view-distance"])
    }

    @Test fun yamlTuningWaitsForTheFirstStartThenAppliesOnce() {
        val server = tmp()
        File(server, "server.properties").writeText("motd=Hi\n")
        Tuning.prepare(server, Tuning.Level.LOW) // first start: Paper hasn't made its configs yet
        assertFalse(File(server, "voxelport-tuning.txt").readText().contains("yaml="))

        // Paper generated its files during that first run.
        File(server, "config").mkdirs()
        File(server, "config/paper-global.yml").writeText("chunk-system:\n  io-threads: -1\n  worker-threads: -1\n")
        File(server, "config/paper-world-defaults.yml").writeText("tick-rates:\n  mob-spawner: 1\n")
        File(server, "bukkit.yml").writeText("spawn-limits:\n  monsters: 70\n  animals: 10\n")
        Tuning.prepare(server, Tuning.Level.LOW)
        assertTrue(File(server, "config/paper-global.yml").readText().contains("worker-threads: 1"))
        assertTrue(File(server, "config/paper-world-defaults.yml").readText().contains("mob-spawner: 3"))
        assertTrue(File(server, "bukkit.yml").readText().contains("monsters: 30"))
        assertTrue(File(server, "voxelport-tuning.txt").readText().contains("yaml=low"))

        // A hand edit is left alone on the next start.
        File(server, "bukkit.yml").writeText("spawn-limits:\n  monsters: 99\n  animals: 10\n")
        Tuning.prepare(server, Tuning.Level.LOW)
        assertTrue(File(server, "bukkit.yml").readText().contains("monsters: 99"))
    }

    @Test fun prepareSurvivesAMissingServerFolder() {
        Tuning.prepare(File("/no/such/folder"), Tuning.Level.BALANCED) // must not throw
    }
}
