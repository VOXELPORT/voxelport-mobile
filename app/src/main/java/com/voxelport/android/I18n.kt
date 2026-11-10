package com.voxelport.android

private val PLACEHOLDER = Regex("\\{(\\w+)\\}")

/** [text] with each `{name}` replaced by its value. A value that was not given stays visible instead of vanishing. */
fun fill(text: String, vars: Map<String, Any?> = emptyMap()): String {
    if (vars.isEmpty()) return text
    return PLACEHOLDER.replace(text) { m -> if (vars.containsKey(m.groupValues[1])) vars[m.groupValues[1]].toString() else m.value }
}

/** The app's text is English. Text with a value inside is written once: `t("Online · {version}", "version" to v)`. */
fun t(text: String, vararg vars: Pair<String, Any?>): String = fill(text, vars.toMap())
