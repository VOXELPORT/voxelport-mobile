package com.voxelport.android

import java.io.File
import java.util.Properties

/** Reads and writes server.properties, keeping the comments and the keys we don't touch. */
object ServerProps {
    fun read(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val p = Properties()
        file.reader(Charsets.UTF_8).use { p.load(it) }
        return p.stringPropertyNames().associateWith { p.getProperty(it) }
    }

    /** Sets [changes] (adding missing keys), leaving everything else in the file as it was. */
    fun write(file: File, changes: Map<String, String>) {
        val lines = if (file.isFile) file.readLines(Charsets.UTF_8).toMutableList() else mutableListOf("#Minecraft server properties")
        val left = changes.toMutableMap()
        for (i in lines.indices) {
            val line = lines[i]
            if (line.isBlank() || line.startsWith("#") || line.startsWith("!")) continue
            val key = line.substringBefore('=').trim()
            if (key in left) lines[i] = "$key=${escape(left.remove(key)!!)}"
        }
        for ((k, v) in left) lines.add("$k=${escape(v)}")
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    // Minecraft reads this file as Latin-1 properties, so non-ASCII must be \uXXXX.
    private fun escape(v: String): String = buildString {
        for (c in v) when {
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> {}
            c.code > 126 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}
