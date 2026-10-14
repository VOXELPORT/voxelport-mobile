package com.voxelport.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files as NioFiles

class YamlEditTest {
    private val paper = """
        |_version: 30
        |
        |chunks:
        |  auto-save-interval: default
        |  max-auto-save-chunks-per-tick: 24   # per tick
        |  delay-chunk-unloads-by: 10s
        |environment:
        |  optimize-explosions: false
        |  treasure-maps:
        |    enabled: true
        |tick-rates:
        |  grass-spread: 1
        |  mob-spawner: 1
        |  sensor:
        |    villager:
        |      secondarypoisensor: 40
        |""".trimMargin()

    @Test fun setsOnlyTheNamedScalar() {
        val out = YamlEdit.set(paper, listOf("tick-rates", "mob-spawner"), "3")!!
        assertTrue(out.contains("  mob-spawner: 3\n"))
        // Everything else untouched, line for line.
        assertEquals(paper.lines().size, out.lines().size)
        assertEquals(paper.replace("  mob-spawner: 1", "  mob-spawner: 3"), out)
    }

    @Test fun keepsTrailingComments() {
        val out = YamlEdit.set(paper, listOf("chunks", "max-auto-save-chunks-per-tick"), "6")!!
        assertTrue(out.contains("  max-auto-save-chunks-per-tick: 6   # per tick"))
    }

    @Test fun sameKeyNameUnderDifferentParentsIsNotConfused() {
        val spigot = """
            |world-settings:
            |  default:
            |    entity-activation-range:
            |      animals: 32
            |      monsters: 32
            |    entity-tracking-range:
            |      animals: 48
            |      monsters: 48
            |""".trimMargin()
        val out = YamlEdit.set(spigot, listOf("world-settings", "default", "entity-tracking-range", "animals"), "32")!!
        assertTrue(out.contains("entity-activation-range:\n      animals: 32\n      monsters: 32"))
        assertTrue(out.contains("entity-tracking-range:\n      animals: 32\n      monsters: 48"))
    }

    @Test fun missingPathIsSkippedNotInvented() {
        assertNull(YamlEdit.set(paper, listOf("tick-rates", "does-not-exist"), "1"))
        assertNull(YamlEdit.set(paper, listOf("nope", "mob-spawner"), "1"))
    }

    @Test fun neverOverwritesAMapOrListHeader() {
        assertNull(YamlEdit.set(paper, listOf("environment", "treasure-maps"), "5"))
        assertNull(YamlEdit.set(paper, listOf("tick-rates", "sensor"), "5"))
    }

    @Test fun keepsWindowsLineEndings() {
        val crlf = paper.replace("\n", "\r\n")
        val out = YamlEdit.set(crlf, listOf("environment", "optimize-explosions"), "true")!!
        assertTrue(out.contains("optimize-explosions: true\r\n"))
        assertTrue(!out.contains("\r\r"))
    }

    @Test fun applyOnFileCountsAndSkips() {
        val f = NioFiles.createTempFile("paper", ".yml").toFile().apply { writeText(paper); deleteOnExit() }
        val n = YamlEdit.apply(f, listOf(
            listOf("tick-rates", "grass-spread") to "4",
            listOf("environment", "optimize-explosions") to "true",
            listOf("missing", "key") to "1",
        ))
        assertEquals(2, n)
        assertTrue(f.readText().contains("grass-spread: 4"))
        assertEquals(0, YamlEdit.apply(java.io.File("/no/such/file.yml"), listOf(listOf("a") to "1")))
    }
}
