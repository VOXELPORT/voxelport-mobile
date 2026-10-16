package com.voxelport.android

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * The language of the app: English, or Hinglish (Hindi written in the English alphabet).
 *
 * English is the source text. A language file ([Hinglish]) maps the exact English text to its translation;
 * text with no translation shows in English, so a missing entry is never a broken screen. Every
 * `Text("...")` goes through [translate] (see TextT.kt), so fixed text is translated without changing
 * the screens. Text with a value inside is written once with `t("Online · {version}", "version" to v)`.
 */
object Lang {
    const val EN = "en"
    const val HINGLISH = "hinglish"

    /** The language now. Compose state: changing it redraws every screen at once. */
    var current by mutableStateOf(EN)
        private set

    /** What was chosen, else Hinglish on a Hindi phone, else English. */
    fun pick(stored: String?, systemLanguage: String?): String = when {
        stored != null && (stored == EN || stored == HINGLISH) -> stored
        systemLanguage?.lowercase(Locale.ROOT) == "hi" -> HINGLISH
        else -> EN
    }

    private var ready = false

    /** Reads the choice. Call when an activity or a service that shows text starts. */
    fun init(ctx: Context) {
        current = pick(Prefs(ctx).language, Locale.getDefault().language)
        ready = true
    }

    /** [init], once: for code (like notifications) that can run before any screen has. */
    fun ensure(ctx: Context) {
        if (!ready) init(ctx)
    }

    fun set(ctx: Context, lang: String) {
        if (lang != EN && lang != HINGLISH) return
        Prefs(ctx).language = lang
        current = lang
    }
}

private val PLACEHOLDER = Regex("\\{(\\w+)\\}")

/** [text] in [lang], with each `{name}` replaced by its value. Anything untranslated stays English. */
fun translate(lang: String, text: String, vars: Map<String, Any?> = emptyMap()): String {
    val found = if (lang == Lang.HINGLISH) Hinglish.words[text] ?: text else text
    if (vars.isEmpty()) return found
    return PLACEHOLDER.replace(found) { m -> if (vars.containsKey(m.groupValues[1])) vars[m.groupValues[1]].toString() else m.value }
}

/** [text] in the app's language now. */
fun t(text: String, vararg vars: Pair<String, Any?>): String = translate(Lang.current, text, vars.toMap())
