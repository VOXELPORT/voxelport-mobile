package com.voxelport.android

import java.io.File

/**
 * Sets single scalar values in Paper/Spigot/Bukkit YAML files without a YAML library:
 * walks the indentation, finds the exact key path, and replaces just that value
 * (comments, ordering and every other line stay as they were). A path that isn't in
 * the file is skipped, never invented, so a config from another version can't break.
 */
object YamlEdit {
    private val KEY = Regex("""^(\s*)([A-Za-z0-9_.\-]+):(\s*)(.*?)(\s+#.*)?$""")

    /** Returns the text with [path] set to [value], or null if the path wasn't found. */
    fun set(text: String, path: List<String>, value: String): String? {
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split("\r\n", "\n").toMutableList()
        val stack = ArrayList<Pair<Int, String>>() // (indent, key) of the open parents
        for (i in lines.indices) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("- ")) continue
            val m = KEY.matchEntire(line) ?: continue
            val indent = m.groupValues[1].length
            val key = m.groupValues[2]
            val inline = m.groupValues[4]
            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.size - 1)
            val here = stack.map { it.second } + key
            if (here == path) {
                // Only replace a plain scalar, never a map/list header.
                if (inline.isEmpty() || inline.startsWith("{") || inline.startsWith("[") || inline == "|" || inline == ">") return null
                lines[i] = "${m.groupValues[1]}$key:${m.groupValues[3].ifEmpty { " " }}$value${m.groupValues[5]}"
                return lines.joinToString(nl)
            }
            if (inline.isEmpty()) stack.add(indent to key)
        }
        return null
    }

    /** Applies every (path → value) to [file]. Returns how many were set; missing file or paths are skipped. */
    fun apply(file: File, edits: List<Pair<List<String>, String>>): Int {
        if (!file.isFile) return 0
        var text = file.readText(Charsets.UTF_8)
        var n = 0
        for ((path, value) in edits) {
            val next = set(text, path, value) ?: continue
            text = next; n++
        }
        if (n > 0) file.writeText(text, Charsets.UTF_8)
        return n
    }
}
