package com.voxelport.android

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/**
 * The plain rules of "Continue with Discord", free of Android calls so they can be unit-tested. The flow is the
 * desktop app's: the browser does the Discord sign-in on the accounts service and is sent back to a port on this
 * phone (127.0.0.1) with a one-time code. The code is only useful together with the PKCE verifier, which never
 * leaves the app, and is traded for a revocable app session.
 */
object AccountLogic {
    const val BASE = "https://accounts.voxelport.in"
    private val CODE = Regex("^[A-Za-z0-9_-]{43}$")
    private val ERROR = Regex("^[a-z_]{1,40}$")
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** A fresh PKCE verifier from 32 random bytes: 43 URL-safe characters. */
    fun verifierFrom(random: ByteArray): String = b64.encodeToString(random)

    /** The PKCE challenge the accounts service checks the verifier against (SHA-256, URL-safe, no padding). */
    fun challengeFor(verifier: String): String = b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** The page that starts the sign-in; the service sends the browser back to http://127.0.0.1:[port]/?code=… */
    fun startUrl(challenge: String, port: Int): String = "$BASE/api/auth/discord/start?app=1&challenge=$challenge&port=$port"

    sealed interface Callback {
        data class Code(val code: String) : Callback
        data class Failed(val reason: String) : Callback
        /** Anything else the browser asks for (a favicon, a probe): answered with 404 and ignored. */
        data object Ignore : Callback
    }

    /** Reads the first line of the browser's request ("GET /?code=… HTTP/1.1"). Only a well-formed code or error counts. */
    fun parseCallback(requestLine: String): Callback {
        val parts = requestLine.trim().split(' ')
        if (parts.size < 2 || parts[0] != "GET") return Callback.Ignore
        val target = parts[1]
        val path = target.substringBefore('?')
        if (path != "/") return Callback.Ignore
        val query = target.substringAfter('?', "")
        val params = query.split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=', "") }
        params["code"]?.let { if (CODE.matches(it)) return Callback.Code(it) }
        params["error"]?.let { if (ERROR.matches(it)) return Callback.Failed(it) }
        return Callback.Ignore
    }

    /** The encrypted address backup the account holds: which address it is for (the 12-character ID) and when it was made. */
    data class BackupInfo(val vpId: String, val updatedAt: Long)

    /** What /api/app/exchange, /api/app/status and /api/app/config answer (fields a call does not send stay null). */
    data class Summary(
        val session: String?,
        val discordName: String?,
        val member: Boolean?,
        val reason: String?,
        val planName: String?,
        val rank: String?,
        val graceUntil: Long?,
        val serverNow: Long?,
        val inviteUrl: String?,
        val linkError: String?,
        /** The answer said something about the backup (status and sign-in do, the public config does not). */
        val backupKnown: Boolean = false,
        val backup: BackupInfo? = null,
    )

    fun parseSummary(text: String): Summary? = runCatching {
        val o = JSONObject(text)
        fun str(k: String) = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotEmpty() }
        val plan = o.optJSONObject("plan")
        Summary(
            session = str("session"),
            discordName = str("discordName"),
            member = if (o.has("member") && !o.isNull("member")) o.getBoolean("member") else null,
            reason = str("reason"),
            planName = plan?.optString("name")?.takeIf { it.isNotEmpty() },
            rank = str("rank"),
            graceUntil = if (o.has("graceUntil") && !o.isNull("graceUntil")) o.getLong("graceUntil") else null,
            serverNow = if (o.has("serverNow") && !o.isNull("serverNow")) o.getLong("serverNow") else null,
            inviteUrl = str("inviteUrl")?.takeIf { it.startsWith("https://") },
            linkError = str("linkError")?.take(300),
            backupKnown = o.has("identityBackup"),
            backup = o.optJSONObject("identityBackup")?.let { b ->
                val id = b.optString("vpId")
                if (Regex("^[0-9a-f]{12}$").matches(id)) BackupInfo(id, b.optLong("updatedAt")) else null
            },
        )
    }.getOrNull()

    fun rankLabel(rank: String?): String = when (rank) {
        "supporter" -> "Supporter"
        "supporter_plus" -> "Supporter+"
        null, "", "free" -> "Free"
        else -> rank
    }

    /** True for a rank that earns a badge (Supporter or Supporter+). */
    fun hasBadge(rank: String?): Boolean = rank == "supporter" || rank == "supporter_plus"

    /** "Supporter+" or, for a paid plan, "Supporter+ (Plus)": the rank with the plan it comes from. */
    fun rankText(rank: String?, planName: String?): String =
        rankLabel(rank) + if (planName != null && planName != "Free" && planName != "Supporter") " ($planName)" else ""
}
