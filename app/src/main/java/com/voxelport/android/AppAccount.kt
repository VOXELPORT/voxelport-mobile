package com.voxelport.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom

/** What the screens show about the Discord account and whether hosting publicly is allowed. */
data class AccountState(
    val signedIn: Boolean = false,
    val discordName: String? = null,
    val planName: String? = null,
    val rank: String? = null,
    val gate: GateLogic.Gate = GateLogic.Gate(allowed = false, mustSignIn = true, banner = GateLogic.Banner.BLOCKED, reason = "signed_out", graceDaysLeft = null),
    /** The accounts service has answered at least once, so the full sign-in page is worth showing. */
    val reachable: Boolean = false,
    val inviteUrl: String? = null,
    val signingIn: Boolean = false,
    /** The last sign-in attempt did not finish (not cancelled): shown as a short note. */
    val signInFailed: Boolean = false,
    /** Why this install could not be linked to the account (the sign-in itself still counts). */
    val linkError: String? = null,
    /** The encrypted address backup the account holds, if any. */
    val backup: AccountLogic.BackupInfo? = null,
    /** This phone's own 12-character ID, to tell whether the backup is for the address it has now. */
    val ownId: String? = null,
    val backupBusy: Boolean = false,
    /** What the last backup or restore did; the screen shows it once. */
    val backupResult: AddressBackupResult? = null,
)

enum class AddressBackupResult { SAVED, RESTORED, DELETED, WRONG_PASSPHRASE, NOT_FOUND, FAILED }

/**
 * "Continue with Discord", the same flow and rules as the desktop app (see [AccountLogic] and [GateLogic]).
 * The session is a random token the accounts service can revoke; it lives only in this app's private storage.
 * The device token is sent once, over HTTPS, when signing in, so the service can link this install to the account.
 */
object AppAccount {
    private const val UA = "VoxelPort-Android (+https://voxelport.in)"
    private const val SIGN_IN_TIMEOUT_MS = 10 * 60 * 1000
    private const val EXCHANGE_PATIENCE_MS = 4 * 60 * 1000L
    private const val EXCHANGE_RETRY_MS = 1000L
    private const val FALLBACK_INVITE = "https://discord.gg/JAUfmey2fP"
    private const val REFRESH_EVERY_MS = 5 * 60 * 1000L
    private const val RETURN_PAGE = "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>VoxelPort</title>" +
        "<body style=\"font-family:system-ui;text-align:center;margin-top:20vh\"><h2>You can return to VoxelPort now.</h2><p>You may close this tab.</p>"

    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var loaded = false
    private var existingInstall = false
    private var session: String? = null
    private var discordName: String? = null
    private var planName: String? = null
    private var rank: String? = null
    private var config: GateLogic.Config? = null
    private var status: GateLogic.Status? = null
    private var failure: GateLogic.Failure? = null
    private var lastGood: GateLogic.LastGood? = null
    private var linkError: String? = null
    private var signInFailed = false
    private var backup: AccountLogic.BackupInfo? = null
    private var backupBusy = false
    private var backupResult: AddressBackupResult? = null
    private var appCtx: Context? = null
    private var flow: Job? = null
    private var listener: ServerSocket? = null
    private var lastRefresh = 0L

    private fun saved(ctx: Context) = ctx.applicationContext.getSharedPreferences("voxelport_account", Context.MODE_PRIVATE)

    /**
     * Reads what was saved. It must run before this install's device token is created: an install that already has one
     * existed before the Discord requirement shipped.
     */
    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        val app = ctx.applicationContext
        appCtx = app
        val p = saved(app)
        if (!p.contains("existing")) {
            p.edit().putBoolean("existing", app.getSharedPreferences("voxelport", Context.MODE_PRIVATE).contains("token")).apply()
        }
        existingInstall = p.getBoolean("existing", false)
        session = p.getString("session", null)
        discordName = p.getString("name", null)
        planName = p.getString("plan", null)
        rank = p.getString("rank", null)
        if (p.contains("good_at")) lastGood = GateLogic.LastGood(p.getBoolean("good_member", false), p.getLong("good_at", 0L))
        if (p.contains("cfg_now")) {
            config = GateLogic.Config(
                graceUntil = p.getLong("cfg_grace", -1L).takeIf { it >= 0 }, serverNow = p.getLong("cfg_now", 0L),
                at = p.getLong("cfg_at", 0L), inviteUrl = p.getString("cfg_invite", null),
            )
        }
        loaded = true
        publish()
    }

    /** True when this install may host publicly right now, from what is saved (safe to call from a service). */
    fun mayHost(ctx: Context): Boolean { init(ctx); return _state.value.gate.allowed }

    @Synchronized
    private fun publish(signing: Boolean = _state.value.signingIn) {
        val now = System.currentTimeMillis()
        val gate = GateLogic.evaluate(existingInstall, session != null, config, status, failure, lastGood, now)
        _state.value = AccountState(
            signedIn = session != null, discordName = discordName, planName = planName, rank = rank, gate = gate,
            reachable = config != null || status != null, inviteUrl = config?.inviteUrl ?: FALLBACK_INVITE,
            signingIn = signing, signInFailed = signInFailed, linkError = linkError,
            backup = backup, ownId = ownId(), backupBusy = backupBusy, backupResult = backupResult,
        )
    }

    /** This phone's own 12-character ID, or null before the app has made a code (reading it would create one). */
    private fun ownId(): String? {
        val app = appCtx ?: return null
        if (!app.getSharedPreferences("voxelport", Context.MODE_PRIVATE).contains("token")) return null
        return IdentityBackup.shortId(Prefs(app).token)
    }

    @Synchronized
    private fun persist(ctx: Context) {
        val e = saved(ctx).edit()
        if (session == null) e.remove("session").remove("name").remove("plan").remove("rank").remove("good_at").remove("good_member")
        else {
            e.putString("session", session).putString("name", discordName).putString("plan", planName).putString("rank", rank)
            lastGood?.let { e.putLong("good_at", it.at).putBoolean("good_member", it.member) }
        }
        config?.let { e.putLong("cfg_grace", it.graceUntil ?: -1L).putLong("cfg_now", it.serverNow).putLong("cfg_at", it.at).putString("cfg_invite", it.inviteUrl) }
        e.apply()
    }

    @Synchronized
    private fun forgetAccount(ctx: Context) {
        session = null; discordName = null; planName = null; rank = null; status = null; failure = null; lastGood = null; linkError = null
        backup = null; backupResult = null
        persist(ctx)
    }

    private class Reply(val code: Int, val text: String)

    private fun call(method: String, path: String, body: String? = null, bearer: String? = null): Reply? = try {
        val c = URL(AccountLogic.BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.instanceFollowRedirects = false
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        Reply(code, text)
    } catch (_: Exception) { null }

    @Synchronized
    private fun readSummary(ctx: Context, s: AccountLogic.Summary, now: Long) {
        if (s.serverNow != null) config = GateLogic.Config(s.graceUntil, s.serverNow, now, s.inviteUrl ?: config?.inviteUrl)
        if (s.member != null) {
            status = GateLogic.Status(s.member, s.reason)
            lastGood = GateLogic.LastGood(s.member, now)
            failure = null
        }
        if (s.discordName != null) discordName = s.discordName
        if (s.planName != null) planName = s.planName
        if (s.rank != null) rank = s.rank
        if (s.backupKnown) backup = s.backup
        persist(ctx)
    }

    /**
     * Asks the accounts service where this install stands: the grace period (public) and, when signed in, the
     * membership. At most every 5 minutes unless [force] ("Check again").
     */
    fun refresh(ctx: Context, force: Boolean = false) {
        init(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < REFRESH_EVERY_MS && now >= lastRefresh) return
        lastRefresh = now
        val app = ctx.applicationContext
        scope.launch {
            val stamp = System.currentTimeMillis()
            call("GET", "/api/app/config")?.takeIf { it.code in 200..299 }?.let { r -> AccountLogic.parseSummary(r.text)?.let { readSummary(app, it, stamp) } }
            val current = session
            if (current != null) {
                val r = call("GET", "/api/app/status", bearer = current)
                when {
                    r == null || r.code >= 500 -> failure = GateLogic.Failure.UNREACHABLE
                    r.code == 401 -> { forgetAccount(app); failure = GateLogic.Failure.UNAUTHORIZED }
                    r.code in 200..299 -> AccountLogic.parseSummary(r.text)?.let { readSummary(app, it, System.currentTimeMillis()) } ?: run { failure = GateLogic.Failure.UNREACHABLE }
                    else -> failure = GateLogic.Failure.UNREACHABLE
                }
            }
            publish()
        }
    }

    /** Opens the browser for Discord and waits for it to come back on a port of this phone. A second tap joins the first. */
    fun signIn(ctx: Context) {
        init(ctx)
        if (flow?.isActive == true) return
        val app = ctx.applicationContext
        signInFailed = false
        publish(signing = true)
        flow = scope.launch {
            val ok = try { runFlow(app) } catch (_: Exception) { Outcome.FAILED }
            signInFailed = ok == Outcome.FAILED
            publish(signing = false)
        }
    }

    fun cancelSignIn() {
        runCatching { listener?.close() }
        flow?.cancel()
    }

    private enum class Outcome { SIGNED_IN, CANCELLED, FAILED }

    private fun runFlow(app: Context): Outcome {
        val verifier = AccountLogic.verifierFrom(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        listener = server
        try {
            server.soTimeout = SIGN_IN_TIMEOUT_MS
            val url = AccountLogic.startUrl(AccountLogic.challengeFor(verifier), server.localPort)
            try { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: ActivityNotFoundException) { return Outcome.FAILED }
            val callback = waitForCallback(server) ?: return Outcome.CANCELLED
            val code = when (callback) {
                is AccountLogic.Callback.Code -> callback.code
                else -> return Outcome.FAILED
            }
            val body = JSONObject().put("code", code).put("verifier", verifier).put("deviceToken", Prefs(app).token).toString()
            // The browser is still in front when the code arrives, and Android 15 cuts a background app's internet access, so the
            // first try can fail before it leaves the phone. Keep trying (the code lives 5 minutes) until the user is back in VoxelPort.
            val giveUpAt = System.currentTimeMillis() + EXCHANGE_PATIENCE_MS
            var answer = call("POST", "/api/app/exchange", body)
            while (answer == null && System.currentTimeMillis() < giveUpAt) {
                if (flow?.isCancelled == true) return Outcome.CANCELLED
                try { Thread.sleep(EXCHANGE_RETRY_MS) } catch (_: InterruptedException) { return Outcome.CANCELLED }
                answer = call("POST", "/api/app/exchange", body)
            }
            val r = answer ?: return Outcome.FAILED
            if (r.code !in 200..299) return Outcome.FAILED
            val s = AccountLogic.parseSummary(r.text) ?: return Outcome.FAILED
            val newSession = s.session ?: return Outcome.FAILED
            synchronized(this) { session = newSession; linkError = s.linkError; failure = null }
            readSummary(app, s, System.currentTimeMillis())
            return Outcome.SIGNED_IN
        } finally {
            runCatching { server.close() }
            listener = null
        }
    }

    /** Serves the browser until it brings a code or an error. Null when cancelled or when nobody came back in time. */
    private fun waitForCallback(server: ServerSocket): AccountLogic.Callback? {
        while (true) {
            val sock = try { server.accept() } catch (_: SocketTimeoutException) { return null } catch (_: java.io.IOException) { return null }
            sock.use { s ->
                s.soTimeout = 5_000
                val line = try { BufferedReader(InputStreamReader(s.getInputStream())).readLine().orEmpty() } catch (_: Exception) { "" }
                val cb = AccountLogic.parseCallback(line)
                val ok = cb !is AccountLogic.Callback.Ignore
                val html = if (ok) RETURN_PAGE else ""
                val head = "HTTP/1.1 ${if (ok) "200 OK" else "404 Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n"
                runCatching { s.getOutputStream().apply { write(head.toByteArray()); write(html.toByteArray()); flush() } }
                if (ok) return cb
            }
        }
    }

    /** Forgets the account on this phone, and asks the service to end the session (it forgets it by itself otherwise). */
    fun signOut(ctx: Context) {
        init(ctx)
        val app = ctx.applicationContext
        val old = session
        forgetAccount(app)
        publish()
        if (old != null) scope.launch { call("POST", "/api/app/signout", "{}", old) }
    }

    /** Backs this phone's address up to the account, encrypted with [passphrase] (the screen checks its strength first). */
    fun saveBackup(ctx: Context, passphrase: String) = runBackup(ctx) { app, bearer ->
        val token = Prefs(app).token
        val vpId = IdentityBackup.shortId(token)
        val body = JSONObject().put("vpId", vpId).put("blob", IdentityBackup.encrypt(token, passphrase)).toString()
        val r = call("PUT", "/api/app/identity", body, bearer) ?: return@runBackup AddressBackupResult.FAILED
        when {
            r.code in 200..299 -> { backup = AccountLogic.BackupInfo(vpId, System.currentTimeMillis()); AddressBackupResult.SAVED }
            r.code == 401 -> { rejected(app); AddressBackupResult.FAILED }
            else -> AddressBackupResult.FAILED
        }
    }

    /** Gets the address back from the account's backup: this phone then uses it (stop the public address first). */
    fun restoreBackup(ctx: Context, passphrase: String) = runBackup(ctx) { app, bearer ->
        val r = call("GET", "/api/app/identity", bearer = bearer) ?: return@runBackup AddressBackupResult.FAILED
        when {
            r.code == 404 -> return@runBackup AddressBackupResult.NOT_FOUND
            r.code == 401 -> { rejected(app); return@runBackup AddressBackupResult.FAILED }
            r.code !in 200..299 -> return@runBackup AddressBackupResult.FAILED
        }
        val answer = JSONObject(r.text)
        val vpId = answer.getString("vpId")
        val token = IdentityBackup.decrypt(answer.getJSONObject("blob"), passphrase, vpId) ?: return@runBackup AddressBackupResult.WRONG_PASSPHRASE
        if (Prefs(app).restoreToken(token)) AddressBackupResult.RESTORED else AddressBackupResult.FAILED
    }

    fun deleteBackup(ctx: Context) = runBackup(ctx) { app, bearer ->
        val r = call("DELETE", "/api/app/identity", bearer = bearer) ?: return@runBackup AddressBackupResult.FAILED
        when {
            r.code in 200..299 -> { backup = null; AddressBackupResult.DELETED }
            r.code == 401 -> { rejected(app); AddressBackupResult.FAILED }
            else -> AddressBackupResult.FAILED
        }
    }

    /** The screen has shown the result of the last backup or restore. */
    fun clearBackupResult() { backupResult = null; publish() }

    private fun rejected(app: Context) { forgetAccount(app); failure = GateLogic.Failure.UNAUTHORIZED }

    private fun runBackup(ctx: Context, work: (Context, String) -> AddressBackupResult) {
        init(ctx)
        val app = ctx.applicationContext
        val bearer = session ?: return
        if (backupBusy) return
        backupBusy = true; backupResult = null; publish()
        scope.launch {
            val result = try { work(app, bearer) } catch (_: Exception) { AddressBackupResult.FAILED }
            backupBusy = false; backupResult = result; publish()
        }
    }

    /** Opens the Discord invite in the browser. */
    fun openInvite(ctx: Context) {
        val url = _state.value.inviteUrl ?: FALLBACK_INVITE
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
