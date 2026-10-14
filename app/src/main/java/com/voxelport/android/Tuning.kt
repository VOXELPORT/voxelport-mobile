package com.voxelport.android

import java.io.File

/**
 * Makes the server fit the phone it runs on. Phones have little RAM, slow storage and
 * throttle when hot, so a low-end phone gets a smaller world around each player, fewer
 * mobs, fewer background threads and a lighter garbage collector; a flagship gets more.
 * Everything here is plain logic (no Android calls) so it can be unit-tested.
 */
object Tuning {
    /** What we know about the phone. */
    data class Device(val ramMb: Int, val cores: Int)

    enum class Level(val id: String, val label: String) {
        LOW("low", "Low-end"), BALANCED("balanced", "Balanced"), HIGH("high", "High-end");
    }

    /** "auto" picks from the phone; otherwise the user's choice. */
    fun resolve(pref: String, d: Device): Level = when (pref) {
        "low" -> Level.LOW
        "balanced" -> Level.BALANCED
        "high" -> Level.HIGH
        else -> auto(d)
    }

    /** ramMb is what Android reports (a "4 GB" phone shows about 3.5 GB). */
    fun auto(d: Device): Level = when {
        d.ramMb <= 4600 || d.cores <= 4 -> Level.LOW
        d.ramMb <= 8800 -> Level.BALANCED
        else -> Level.HIGH
    }

    /** A heap that leaves Android room for itself, the JVM's own memory and other apps. */
    fun recommendedRamMb(d: Device): Int = ((d.ramMb * 28 / 100) / 256 * 256).coerceIn(1024, 4096)

    /** Largest heap the app lets you pick: half the phone, never more than 6 GB. */
    fun maxRamMb(d: Device): Int = (d.ramMb / 2).coerceIn(1024, 6144) / 256 * 256

    // ── JVM ──
    /**
     * JVM flags, without -jar and the paths. -XX:+IgnoreUnrecognizedVMOptions comes first so
     * a flag this Java build doesn't know can never stop the server from starting.
     */
    fun jvmFlags(level: Level, ramMb: Int, d: Device): List<String> {
        val xms = when (level) { Level.LOW -> 256; Level.BALANCED -> 512; Level.HIGH -> 1024 }.coerceAtMost(ramMb)
        val gcThreads = (d.cores / 2).coerceIn(2, 4)
        val gc = when (level) {
            // One thread-light collector and 2 compiler threads: less to fight over on few/slow cores.
            Level.LOW -> listOf("-XX:+UseSerialGC", "-XX:CICompilerCount=2")
            else -> listOf(
                "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100", "-XX:+ParallelRefProcEnabled",
                "-XX:ParallelGCThreads=$gcThreads", "-XX:ConcGCThreads=${(gcThreads / 2).coerceAtLeast(1)}",
            )
        }
        return listOf(
            "-XX:+IgnoreUnrecognizedVMOptions",
            "-Xms${xms}M", "-Xmx${ramMb}M",
        ) + gc + listOf(
            "-XX:-UsePerfData",            // no perf-data file in storage
            "-XX:+DisableExplicitGC",
            "-Djava.net.preferIPv4Stack=true",
            "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true",
            "-Dterminal.jline=false", "-Dterminal.ansi=false",
            // Our embedded Java reports as "…-internal"; Paper refuses to start on that without this.
            "-DPaper.IgnoreJavaVersion=true",
        )
    }

    // ── server.properties ──
    /** Settings chosen once, when the server is first installed. */
    fun initialProps(level: Level, saver: Boolean = false): Map<String, String> = mapOf(
        "motd" to "A VoxelPort phone server",
        "server-port" to "25565",
        "max-players" to when (level) { Level.LOW -> "6"; Level.BALANCED -> "10"; Level.HIGH -> "20" },
        "online-mode" to "true",
        "enable-rcon" to "false",
        "sync-chunk-writes" to "false",
    ) + profileProps(level, saver)

    /**
     * What a performance level sets in server.properties. With [saver] (Data saver) each player gets
     * a smaller world and fewer entity updates: the world data a player downloads is what mostly
     * decides how much mobile data hosting uses, and it grows with the square of the view distance.
     */
    fun profileProps(level: Level, saver: Boolean = false): Map<String, String> = mapOf(
        "view-distance" to when {
            saver -> if (level == Level.LOW) "3" else "4"
            else -> when (level) { Level.LOW -> "4"; Level.BALANCED -> "6"; Level.HIGH -> "8" }
        },
        "simulation-distance" to when {
            saver -> "3"
            else -> when (level) { Level.LOW -> "3"; Level.BALANCED -> "4"; Level.HIGH -> "6" }
        },
        "entity-broadcast-range-percentage" to when {
            saver -> "50"
            else -> when (level) { Level.LOW -> "60"; Level.BALANCED -> "80"; Level.HIGH -> "100" }
        },
        // Stop ticking the world when nobody is on: saves battery and heat.
        "pause-when-empty-seconds" to "30",
    )

    // ── Paper / Spigot / Bukkit config ──
    private fun p(file: String, path: String, value: Any): Triple<String, List<String>, String> =
        Triple(file, path.split('.'), value.toString())

    /** (file, key path, value). Only existing keys are changed (see YamlEdit). */
    fun yamlEdits(level: Level, saver: Boolean = false): List<Triple<String, List<String>, String>> {
        fun pick(low: Any, bal: Any, high: Any) = when (level) { Level.LOW -> low; Level.BALANCED -> bal; Level.HIGH -> high }
        // How far away a client is still sent other players and mobs. Data saver: only what is close.
        // Without it, Spigot's own values, so turning the saver off puts them back.
        val trackingEdits = if (saver) listOf(
            p("spigot.yml", "world-settings.default.entity-tracking-range.players", 32),
            p("spigot.yml", "world-settings.default.entity-tracking-range.animals", 24),
            p("spigot.yml", "world-settings.default.entity-tracking-range.monsters", 32),
            p("spigot.yml", "world-settings.default.entity-tracking-range.misc", 16),
            p("spigot.yml", "world-settings.default.entity-tracking-range.other", 32),
        ) else listOf(
            p("spigot.yml", "world-settings.default.entity-tracking-range.players", 48),
            p("spigot.yml", "world-settings.default.entity-tracking-range.misc", 32),
            p("spigot.yml", "world-settings.default.entity-tracking-range.other", 64),
        )
        return listOf(
            // Fewer chunk-writes per tick and background threads, so the main thread keeps its core.
            p("config/paper-global.yml", "chunk-system.worker-threads", pick(1, 2, 3)),
            p("config/paper-global.yml", "chunk-system.io-threads", pick(1, 1, 2)),
            p("config/paper-world-defaults.yml", "chunks.max-auto-save-chunks-per-tick", pick(6, 8, 16)),
            p("config/paper-world-defaults.yml", "environment.optimize-explosions", "true"),
            p("config/paper-world-defaults.yml", "tick-rates.mob-spawner", pick(3, 2, 1)),
            p("config/paper-world-defaults.yml", "tick-rates.container-update", pick(4, 3, 1)),
            p("config/paper-world-defaults.yml", "tick-rates.grass-spread", pick(4, 4, 1)),
            // Mob caps: mobs are the main per-tick cost on a small CPU.
            p("bukkit.yml", "spawn-limits.monsters", pick(30, 50, 70)),
            p("bukkit.yml", "spawn-limits.animals", pick(6, 10, 15)),
            p("bukkit.yml", "spawn-limits.water-animals", pick(3, 4, 5)),
            p("bukkit.yml", "spawn-limits.ambient", pick(1, 2, 15)),
            // Entities far away just idle.
            p("spigot.yml", "world-settings.default.entity-activation-range.animals", pick(12, 16, 32)),
            p("spigot.yml", "world-settings.default.entity-activation-range.monsters", pick(20, 24, 32)),
            p("spigot.yml", "world-settings.default.entity-activation-range.misc", pick(4, 8, 16)),
            p("spigot.yml", "world-settings.default.entity-tracking-range.animals", pick(32, 48, 48)),
            p("spigot.yml", "world-settings.default.entity-tracking-range.monsters", pick(32, 48, 48)),
        ) + trackingEdits // applied in order, so the saver's animals/monsters values replace the level's
    }

    // ── applying it ──
    private fun marker(server: File) = File(server, "voxelport-tuning.txt")
    private fun readMarker(server: File): Map<String, String> =
        marker(server).takeIf { it.isFile }?.readLines()?.mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap() ?: emptyMap()
    private fun writeMarker(server: File, m: Map<String, String>) = marker(server).writeText(m.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")

    /** What the markers record: the level, plus "+saver" with Data saver on. */
    private fun key(level: Level, saver: Boolean) = level.id + if (saver) "+saver" else ""

    /** Remembers that [level] is what server.properties was last tuned for. */
    fun markProps(server: File, level: Level, saver: Boolean = false) = writeMarker(server, readMarker(server) + ("props" to key(level, saver)))

    /** Writes a level's server.properties settings right now (used when the user picks a level or Data saver). */
    fun applyProps(server: File, level: Level, saver: Boolean = false) {
        ServerProps.write(File(server, "server.properties"), profileProps(level, saver))
        writeMarker(server, readMarker(server) + ("props" to key(level, saver)))
    }

    /**
     * Run before every start. Applies the level once per change: server.properties at once, and
     * the Paper/Spigot/Bukkit files as soon as the server has generated them (the first start
     * creates them, so they are tuned from the second start on). Your own edits afterwards are kept.
     */
    fun prepare(server: File, level: Level, saver: Boolean = false) {
        val k = key(level, saver)
        var m = readMarker(server)
        if (m["props"] != k) {
            runCatching { ServerProps.write(File(server, "server.properties"), profileProps(level, saver)) }
            m = m + ("props" to k)
        }
        if (m["yaml"] != k && File(server, "config/paper-global.yml").isFile) {
            var changed = 0
            for ((file, path, value) in yamlEdits(level, saver)) changed += runCatching { YamlEdit.apply(File(server, file), listOf(path to value)) }.getOrDefault(0)
            if (changed > 0) m = m + ("yaml" to k)
        }
        runCatching { writeMarker(server, m) }
    }
}
