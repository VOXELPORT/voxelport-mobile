package com.voxelport.android

/**
 * The secret code (token) behind this install's public port and custom address. Plain logic,
 * so it can be unit-tested; Prefs does the storing and the Go library the final check.
 */
object IdentityLogic {
    private val shape = Regex("^vp_[A-Za-z0-9_-]{10,}$") // same shape the relay and Go library accept

    /** What a pasted code looks like once spaces, line breaks and quote marks around it are removed. */
    fun normalize(raw: String): String = raw.filterNot { it.isWhitespace() }.trim('"', '\'', '`', '“', '”')

    fun looksValid(raw: String): Boolean = shape.matches(normalize(raw))

    /** A code is a secret: anyone who has it can take over the address, so it is never shown in full on screen. */
    fun masked(code: String): String = if (code.length <= 8) "…" else code.take(6) + "…" + code.takeLast(2)
}
