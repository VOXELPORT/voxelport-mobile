package com.voxelport.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Where the self-updater is. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val m: UpdateLogic.Manifest) : UpdateState
    data class Downloading(val m: UpdateLogic.Manifest, val pct: Int) : UpdateState
    data class Installing(val m: UpdateLogic.Manifest, val note: String) : UpdateState
    /** Android needs "install unknown apps" switched on for VoxelPort first. */
    data class NeedsPermission(val m: UpdateLogic.Manifest) : UpdateState
    data class Failed(val message: String, val m: UpdateLogic.Manifest?) : UpdateState
}

/**
 * Updates the app from voxelport.in (the app is sideloaded, so there is no Play Store to do it).
 * Checks a small manifest, downloads the APK only when asked, verifies size, SHA-256, package name
 * and signing key, then hands it to Android's installer.
 */
object Updater {
    private const val UA = "VoxelPort-Android (+https://voxelport.in)"
    const val ACTION_RESULT = "com.voxelport.android.UPDATE_RESULT"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun installedCode(ctx: Context): Long = PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    fun installedName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    /** True on mobile data / metered Wi-Fi: worth a heads-up before a 50 MB download. */
    fun metered(ctx: Context): Boolean = ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    /** Looks for an update (at most every 6 hours unless [force]). */
    fun check(ctx: Context, force: Boolean = false) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Installing) return
        val prefs = Prefs(ctx.applicationContext)
        val now = System.currentTimeMillis()
        if (!UpdateLogic.shouldCheck(now, prefs.lastUpdateCheck, force)) return
        _state.value = UpdateState.Checking
        val app = ctx.applicationContext
        scope.launch {
            try {
                // Both feeds are read, and the newer one counts, so a release published on only one of them is never missed.
                // A changing query so neither a CDN nor the phone serves a stale manifest.
                var failure: Exception? = null
                var answered = false
                val found = UpdateLogic.MANIFEST_URLS.mapNotNull { feed ->
                    val text = try { httpText("$feed?t=${now / 60_000}").also { answered = true } } catch (e: Exception) { if (failure == null) failure = e; null }
                    text?.let { UpdateLogic.parse(it, feed) }
                }
                if (!answered) throw failure ?: IllegalStateException("no connection") // neither feed could be reached: say why
                prefs.lastUpdateCheck = now
                val m = UpdateLogic.newest(found) ?: error("The update information on our website looked wrong.")
                _state.value = when {
                    UpdateLogic.isNewer(m, installedCode(app)) -> UpdateState.Available(m)
                    force -> UpdateState.UpToDate // only say so when the user asked
                    else -> UpdateState.Idle
                }
            } catch (e: Exception) {
                // A failed background check is not worth nagging about; a forced one is.
                _state.value = if (force) UpdateState.Failed(t("Couldn't check for updates: {detail}", "detail" to (e.message ?: t("no connection"))), null) else UpdateState.Idle
            }
        }
    }

    /** Downloads, verifies and installs the update offered in [UpdateState.Available]. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val m = when (val s = _state.value) {
            is UpdateState.Available -> s.m
            is UpdateState.NeedsPermission -> s.m
            is UpdateState.Failed -> s.m ?: return
            else -> return
        }
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(m)
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        scope.launch {
            try {
                // Installing replaces the app and stops everything: save and stop the server first.
                if (ServerService.isAlive(app)) {
                    _state.value = UpdateState.Installing(m, "Stopping the server and saving the world…")
                    ServerService.send(app, ServerService.ACTION_STOP)
                    TunnelService.send(app, TunnelService.ACTION_STOP)
                    ServerService.awaitStopped(app)
                }
                val apk = download(app, m)
                _state.value = UpdateState.Installing(m, "Installing…")
                verifyApk(app, apk)
                commit(app, apk)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: e.toString(), m)
            }
        }
    }

    /** Hides the offer until the next launch or check. */
    fun dismiss() { if (_state.value is UpdateState.Available || _state.value is UpdateState.Failed || _state.value is UpdateState.UpToDate) _state.value = UpdateState.Idle }

    // ── download + verify ──
    private fun download(app: Context, m: UpdateLogic.Manifest): File {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "voxelport-update.apk")
        val part = File(dir, "voxelport-update.apk.part")
        val c = open(m.url)
        try {
            if (c.responseCode != 200) error(t("The update download failed (HTTP {code}).", "code" to c.responseCode))
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var last = -1
            c.inputStream.use { input ->
                part.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (done > m.size + 1024) error("The update is bigger than expected.")
                        val pct = (done * 100 / m.size).toInt().coerceIn(0, 100)
                        if (pct != last) { last = pct; DataMeter.sample(); _state.value = UpdateState.Downloading(m, pct) }
                    }
                }
            }
            if (done != m.size) { part.delete(); error("The update download was cut short. Try again.") }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (got != m.sha256) { part.delete(); error("The update didn't match its checksum, so it was not installed. Try again.") }
            if (!part.renameTo(out)) error("Couldn't save the update.")
            return out
        } finally { c.disconnect() }
    }

    private fun certs(info: PackageInfo?): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info?.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") info?.signatures
        return sigs?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }?.toSet() ?: emptySet()
    }

    /** Same app, same signing key as this one: anything else is refused before Android is even asked. */
    private fun verifyApk(app: Context, apk: File) {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
        val mine = pm.getPackageInfo(app.packageName, flags)
        if (!UpdateLogic.sameApp(app.packageName, archive?.packageName, certs(mine), certs(archive))) {
            apk.delete()
            error("The downloaded file isn't a genuine VoxelPort update, so it was not installed.")
        }
    }

    // ── hand over to Android ──
    private fun commit(app: Context, apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            // Android 12+ may skip the confirmation once this app has updated itself before.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input -> session.openWrite("voxelport.apk", 0, apk.length()).use { o -> input.copyTo(o, 1 shl 16); session.fsync(o) } }
            val result = Intent(app, UpdateReceiver::class.java).setAction(ACTION_RESULT).setPackage(app.packageName)
            val pending = PendingIntent.getBroadcast(app, id, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    internal fun onInstallStatus(app: Context, status: Int, message: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                confirm?.let { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle // the app is replaced and restarts
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = UpdateState.Failed("The update was cancelled.", (_state.value as? UpdateState.Installing)?.m)
            else -> _state.value = UpdateState.Failed(message?.takeIf { it.isNotBlank() } ?: t("Android couldn't install the update (code {code}).", "code" to status), (_state.value as? UpdateState.Installing)?.m)
        }
    }

    // ── tiny HTTP helpers (https to our own site only, no redirects) ──
    private fun open(url: String): HttpURLConnection {
        require(UpdateLogic.trustedUrl(url)) { "Refusing to download from outside VoxelPort's own website." }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Cache-Control", "no-cache")
            connectTimeout = 15_000; readTimeout = 30_000
            instanceFollowRedirects = false
        }
    }

    private fun httpText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) error(t("The website answered HTTP {code}", "code" to c.responseCode))
            return c.inputStream.bufferedReader().use { it.readText().take(20_000) }
        } finally { c.disconnect() }
    }
}

/** Receives Android's answer to an update install (confirmation needed / success / failure). */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
        Updater.onInstallStatus(context.applicationContext, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), confirm)
    }
}
