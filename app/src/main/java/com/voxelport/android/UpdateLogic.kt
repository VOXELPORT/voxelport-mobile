package com.voxelport.android

import org.json.JSONObject
import java.net.URI

/**
 * The rules of the self-updater, kept free of Android calls so they can be unit-tested.
 *
 * The app fetches [MANIFEST_URL], a small JSON file the publish script writes next to the APK:
 *   { "versionCode": 7, "versionName": "1.0.0", "url": "/dl/voxelport-android.apk?v=ab12cd34",
 *     "sha256": "…64 hex…", "size": 53179362, "notes": "What changed" }
 * Updates are decided by versionCode (the visible versionName can stay put).
 */
object UpdateLogic {
    const val HOST = "voxelport.in"
    const val MANIFEST_URL = "https://$HOST/dl/android.json"
    private const val MAX_APK_BYTES = 300L * 1024 * 1024

    data class Manifest(
        val versionCode: Long, val versionName: String, val url: String,
        val sha256: String, val size: Long, val notes: String,
    )

    /** Only our own site, over https, under /dl/, and only an .apk: a hijacked manifest can't point elsewhere. */
    fun trustedApkUrl(url: String): Boolean = runCatching {
        val u = URI(url)
        u.scheme == "https" && u.host == HOST && u.port == -1 && u.userInfo == null &&
            (u.path ?: "").startsWith("/dl/") && !(u.path ?: "").contains("..") && (u.path ?: "").endsWith(".apk")
    }.getOrDefault(false)

    /** Parses and validates the manifest; null if anything is off (a bad file never offers an update). */
    fun parse(text: String): Manifest? = runCatching {
        val o = JSONObject(text)
        val code = o.getLong("versionCode")
        val sha = o.getString("sha256").lowercase()
        val size = o.getLong("size")
        val url = URI(MANIFEST_URL).resolve(o.getString("url")).toString()
        if (code <= 0 || !Regex("[0-9a-f]{64}").matches(sha) || size !in 1..MAX_APK_BYTES || !trustedApkUrl(url)) return null
        Manifest(code, o.optString("versionName"), url, sha, size, o.optString("notes").take(600))
    }.getOrNull()

    fun isNewer(m: Manifest, installedCode: Long): Boolean = m.versionCode > installedCode

    /** Check at most every [everyMs] unless the user asked. */
    fun shouldCheck(nowMs: Long, lastMs: Long, force: Boolean, everyMs: Long = 6L * 3600_000): Boolean =
        force || lastMs <= 0 || nowMs - lastMs >= everyMs || nowMs < lastMs

    /** Same package and (when both can be read) the same signing key as the running app. */
    fun sameApp(installedPackage: String, archivePackage: String?, installedCerts: Set<String>, archiveCerts: Set<String>): Boolean {
        if (archivePackage != installedPackage) return false
        if (installedCerts.isEmpty() || archiveCerts.isEmpty()) return true // unreadable here; the installer enforces it anyway
        return installedCerts == archiveCerts
    }
}
