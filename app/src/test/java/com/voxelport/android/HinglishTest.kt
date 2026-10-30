package com.voxelport.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the Hinglish language complete. It reads the app's own source files and checks that every sentence a person
 * can see has a translation (or is listed as the same in both languages), that nothing is built from pieces or
 * with a `$value` inside (those cannot be translated), and that the dictionary has no leftovers.
 *
 * When this fails after you add text: write it as a full sentence in `Text("...")` or `t("... {value} ...", "value" to v)`,
 * then add its Hinglish to Hinglish.kt (or to I18nTestData if it reads the same in both languages).
 */
class HinglishTest {
    private class Lit(val file: String, val line: Int, val raw: String, val text: String, val templated: Boolean, val ui: Boolean, val direct: Boolean, val concat: Boolean) {
        override fun toString() = "$file:$line  \"$raw\""
    }

    private val sourceDir: File = listOf("src/main/java/com/voxelport/android", "app/src/main/java/com/voxelport/android")
        .map(::File).first { it.isDirectory }

    /** Files that carry both languages by hand (and are tested for it), or never reach a screen unmapped. */
    private val excluded = setOf("Hinglish.kt", "I18n.kt", "TextT.kt", "NoticeLogic.kt", "Invite.kt", "Backups.kt", "BackupRunner.kt",
        // Talks to the accounts service and a browser tab; nothing here is shown in the app.
        "AppAccount.kt")

    // ─── Reading the sources ─────────────────────────────────────────────────────

    /** (start, end, text between the quotes as written) for each ordinary string literal. */
    private fun scan(src: String): List<Triple<Int, Int, String>> {
        val out = mutableListOf<Triple<Int, Int, String>>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && src.startsWith("//", i) -> { while (i < src.length && src[i] != '\n') i++ }
                c == '/' && src.startsWith("/*", i) -> i = src.indexOf("*/", i + 2) + 2
                c == '"' && src.startsWith("\"\"\"", i) -> i = src.indexOf("\"\"\"", i + 3) + 3
                c == '"' -> {
                    var j = i + 1
                    var depth = 0
                    val text = StringBuilder()
                    while (j < src.length) {
                        val d = src[j]
                        if (d == '\\') { text.append(d).append(src[j + 1]); j += 2; continue }
                        if (depth == 0 && d == '"') break
                        if (d == '$' && src.getOrNull(j + 1) == '{') { depth++; text.append("\${"); j += 2; continue }
                        if (depth > 0) {
                            if (d == '{') depth++
                            if (d == '}') depth--
                        }
                        text.append(d); j++
                    }
                    out += Triple(i, j + 1, text.toString())
                    i = j + 1
                }
                c == '\'' && src.getOrNull(i + 2) == '\'' -> i += 3
                c == '\'' && src.getOrNull(i + 1) == '\\' -> i += 4
                else -> i++
            }
        }
        return out
    }

    private fun decode(t: String): String = Regex("""\\(u[0-9a-fA-F]{4}|.)""").replace(t) { m ->
        val e = m.groupValues[1]
        if (e[0] == 'u') e.substring(1).toInt(16).toChar().toString()
        else when (e) { "n" -> "\n"; "t" -> "\t"; "r" -> "\r"; "b" -> "\b"; else -> e }
    }

    private fun stripTemplates(t: String) =
        t.replace(Regex("""\$\{(?:[^{}]|\{[^{}]*\})*\}"""), "").replace(Regex("""\$[A-Za-z_]\w*"""), "")

    /** Does this look like words a person reads (a sentence or a label), rather than a key, a path or a flag? */
    private fun looksUi(t: String): Boolean {
        if (!Regex("[A-Za-z]{2}").containsMatchIn(t)) return false
        if (t.startsWith("^")) return false
        if (t.none { it.isWhitespace() }) {
            if (t.any { it.isDigit() }) return false
            if (!t[0].isUpperCase()) return false
        }
        return true
    }

    private val directCall = Regex("""(?:\bt\(|translate\([^,()]+,)\s*$""")

    private val literals: List<Lit> by lazy {
        val out = mutableListOf<Lit>()
        for (f in sourceDir.listFiles { x -> x.name.endsWith(".kt") && x.name !in excluded }!!.sortedBy { it.name }) {
            val src = f.readText().replace("\r\n", "\n")
            for ((start, end, raw) in scan(src)) {
                val before = src.substring(maxOf(0, start - 40), start)
                val after = src.substring(end, minOf(src.length, end + 12))
                val templated = raw.contains('$')
                val ui = if (templated) looksUi(stripTemplates(raw)) else looksUi(decode(raw))
                out += Lit(
                    f.name, src.substring(0, start).count { it == '\n' } + 1, raw, decode(raw), templated, ui,
                    direct = directCall.containsMatchIn(before),
                    concat = Regex("""^\s*\+""").containsMatchIn(after) || Regex("""\+\s*$""").containsMatchIn(before),
                )
            }
        }
        out
    }

    private val words = Hinglish.words
    private val same = I18nTestData.SAME_IN_BOTH

    // ─── The checks ──────────────────────────────────────────────────────────────

    @Test fun `the scanner finds the app's text (so the other checks mean something)`() {
        assertTrue("only found ${literals.size} literals", literals.size > 800)
        assertTrue(literals.any { it.text == "BACK UP NOW" && it.ui })
        assertTrue(literals.any { it.templated && it.raw.startsWith("\${limitMb / 1000}") })
    }

    @Test fun `every sentence a person can see has a Hinglish translation, or reads the same in both`() {
        val missing = literals.filter { !it.templated && (it.ui || it.direct) }
            .filter { it.text !in I18nTestData.NOT_SHOWN && it.text !in words && it.text !in same }
        assertTrue("Add these to Hinglish.kt (or I18nTestData.SAME_IN_BOTH / NOT_SHOWN):\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test fun `no sentence is built from pieces, because the pieces cannot be translated`() {
        val pieces = literals.filter { !it.templated && it.ui && it.concat && !it.direct && it.text !in I18nTestData.NOT_SHOWN }
        assertTrue("Write each of these as one sentence (or wrap each piece in t(...)):\n" + pieces.joinToString("\n"), pieces.isEmpty())
    }

    @Test fun `no sentence has a value written into it with a dollar sign`() {
        val bad = literals.filter { it.templated && it.ui && it.raw !in I18nTestData.TEMPLATED_NOT_TEXT }
        assertTrue("Write these as t(\"... {name} ...\", \"name\" to value):\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun `nothing in the language lists is left over from text that no longer exists`() {
        val texts = literals.map { it.text }.toSet()
        val raws = literals.map { it.raw }.toSet()
        val gone = words.keys.filter { it !in texts } + same.filter { it !in texts } + I18nTestData.NOT_SHOWN.filter { it !in texts } +
            I18nTestData.TEMPLATED_NOT_TEXT.filter { it !in raws }
        assertTrue("These are no longer in the app, remove them:\n" + gone.joinToString("\n"), gone.isEmpty())
    }

    @Test fun `a text is listed once, as translated or as the same in both`() {
        assertEquals(emptyList<String>(), same.filter { it in words })
        assertEquals(emptyList<String>(), I18nTestData.NOT_SHOWN.filter { it in words || it in same })
    }

    @Test fun `every translation keeps the values the English has`() {
        val placeholder = Regex("""\{(\w+)\}""")
        val wrong = words.filter { (en, hi) ->
            placeholder.findAll(en).map { it.groupValues[1] }.toSortedSet() != placeholder.findAll(hi).map { it.groupValues[1] }.toSortedSet()
        }
        assertEquals(emptyMap<String, String>(), wrong)
    }

    @Test fun `a translation is never empty and never the same as the English`() {
        assertEquals(emptyList<String>(), words.filter { (_, hi) -> hi.isBlank() }.keys.toList())
        assertEquals("Move these to I18nTestData.SAME_IN_BOTH:", emptyList<String>(), words.filter { (en, hi) -> en == hi }.keys.toList())
    }

    @Test fun `spaces at the edges are kept, because the pieces are joined with them`() {
        val wrong = words.filter { (en, hi) -> en.startsWith(" ") != hi.startsWith(" ") || en.endsWith(" ") != hi.endsWith(" ") }
        assertEquals(emptyMap<String, String>(), wrong)
    }

    @Test fun `no translation uses a character a typical phone font lacks`() {
        // Hinglish is typed in the English alphabet. Anything else is a typo or leftover (Devanagari would show as boxes on some phones).
        val odd = words.values.filter { v -> v.any { it.code in 0x0900..0x097F } }
        assertEquals(emptyList<String>(), odd)
    }

    // ─── Using the language ──────────────────────────────────────────────────────

    @Test fun `English text is shown as written, with the values filled in`() {
        assertEquals("Last backup: 5 min ago", translate(Lang.EN, "Last backup: {ago}", mapOf("ago" to "5 min ago")))
        assertEquals("Stop server", translate(Lang.EN, "Stop server"))
    }

    @Test fun `Hinglish text is looked up by the exact English and filled in`() {
        assertEquals("Server band karo", translate(Lang.HINGLISH, "Stop server"))
        assertEquals("Aakhri backup: 5 min pehle", translate(Lang.HINGLISH, "Last backup: {ago}", mapOf("ago" to "5 min pehle")))
    }

    @Test fun `text with no translation shows in English, never as a blank or a key`() {
        assertEquals("Something new", translate(Lang.HINGLISH, "Something new"))
        assertEquals("Something new 3", translate(Lang.HINGLISH, "Something new {n}", mapOf("n" to 3)))
    }

    @Test fun `a value that was not given stays visible instead of vanishing`() {
        assertEquals("Last backup: {ago}", translate(Lang.EN, "Last backup: {ago}", mapOf("other" to 1)))
    }

    @Test fun `the chosen language wins, else a Hindi phone gets Hinglish, else English`() {
        assertEquals(Lang.EN, Lang.pick(Lang.EN, "hi"))
        assertEquals(Lang.HINGLISH, Lang.pick(Lang.HINGLISH, "en"))
        assertEquals(Lang.HINGLISH, Lang.pick(null, "hi"))
        assertEquals(Lang.HINGLISH, Lang.pick(null, "HI"))
        assertEquals(Lang.EN, Lang.pick(null, "en"))
        assertEquals(Lang.EN, Lang.pick(null, null))
        assertEquals(Lang.EN, Lang.pick("klingon", "fr"))
    }

    @Test fun `a lookup with something odd in it does not blow up`() {
        assertFalse(translate(Lang.HINGLISH, "$", mapOf("x" to 1)).isEmpty())
        assertEquals("{}", translate(Lang.HINGLISH, "{}"))
    }
}
