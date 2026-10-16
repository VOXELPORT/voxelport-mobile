package com.voxelport.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads https://voxelport.in/support.json (supporter perks wording and paid-hosting suggestions)
 * and keeps it for 12 hours, so changing them never needs an app update. It is a tiny file; with
 * the limit set to "Wi‑Fi only" it isn't fetched on mobile data at all. Whatever it says is
 * validated by [SupportLogic]; the tip destination is never read from it.
 */
object SupportRepo {
    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L
    private const val UA = "VoxelPort-Android (+https://voxelport.in)"

    /** Blocking (network): call it off the main thread. Never throws. */
    fun load(ctx: Context, force: Boolean = false): SupportLogic.Info {
        val file = File(ctx.filesDir, "support-cache.json")
        val cached = runCatching { file.takeIf { it.isFile }?.let { it.readText() to it.lastModified() } }.getOrNull()
            ?.takeIf { SupportLogic.isUsable(it.first) }
        val fresh = cached != null && System.currentTimeMillis() - cached.second < MAX_AGE_MS
        if (cached != null && fresh && !force) return SupportLogic.parse(cached.first)

        val blocked = DataLogic.downloadCheck(Prefs(ctx).dataLimitMb, DataMeter.net.value.metered) == DataLogic.DownloadCheck.BLOCKED
        if (!blocked) {
            try {
                val text = fetch()
                if (SupportLogic.isUsable(text)) {
                    runCatching { file.writeText(text) }
                    return SupportLogic.parse(text)
                }
            } catch (_: Exception) { /* offline, or the site is down: fall through to the last good copy */ }
        }
        return cached?.let { SupportLogic.parse(it.first) } ?: SupportLogic.DEFAULTS
    }

    private fun fetch(): String {
        val c = URL(SupportLogic.SUPPORT_URL).openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            c.connectTimeout = 6_000; c.readTimeout = 6_000
            c.instanceFollowRedirects = false
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val text = c.inputStream.bufferedReader().use { r ->
                val buf = CharArray(SupportLogic.MAX_JSON_CHARS + 1)
                val n = r.read(buf).coerceAtLeast(0)
                String(buf, 0, n)
            }
            return text
        } finally { c.disconnect() }
    }
}

/** Opens the phone's UPI app with a tip to VoxelPort. Returns a message to show if that wasn't possible, or null. */
fun tipWithUpi(ctx: Context, amountInr: Int?): String? {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SupportLogic.upiUri(amountInr))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (ctx.packageManager.queryIntentActivities(intent, 0).isNotEmpty()) {
        try { ctx.startActivity(intent); return null } catch (_: Exception) { /* fall through */ }
    }
    copyUpiId(ctx)
    return t("No UPI app found, so the UPI ID {id} was copied. Paste it into any payment app, or use GitHub Sponsors.", "id" to SupportLogic.UPI_ID)
}

fun copyUpiId(ctx: Context) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("VoxelPort UPI ID", SupportLogic.UPI_ID))
}

/** Opens an https link in the browser. */
fun openLink(ctx: Context, url: String) {
    if (!url.startsWith("https://")) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
