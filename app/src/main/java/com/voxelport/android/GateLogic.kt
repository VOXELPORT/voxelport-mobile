package com.voxelport.android

/**
 * Decides whether this install may host publicly. Plain logic, kept free of Android calls so it can be unit-tested.
 * It follows the desktop app's rules (discordGate.js) exactly, so both apps answer the same way:
 *
 *  - an install with no saved session must sign in, and a rejected session (401) means "sign in again";
 *  - when the accounts service cannot be asked, the last good answer is trusted for a week, then people are not
 *    stopped (an outage must never lock anyone out), but a missing session never fails open;
 *  - the grace period is set on the server and only ever helps installs that existed before the Discord
 *    requirement. It is judged on the server's clock plus the time elapsed since it was read, and elapsed time
 *    never goes negative, so setting the phone's clock back cannot extend it.
 */
object GateLogic {
    const val DAY = 86_400_000L
    const val OFFLINE_HOLD_MS = 7 * DAY

    /** What the accounts service said about the grace period, and when we read it (phone clock, ms). */
    data class Config(val graceUntil: Long?, val serverNow: Long, val at: Long, val inviteUrl: String?)

    /** The last successful membership answer. [reason] is e.g. "not_in_server", "screening" or "recheck". */
    data class Status(val member: Boolean, val reason: String?)

    data class LastGood(val member: Boolean, val at: Long)

    enum class Failure { UNREACHABLE, UNAUTHORIZED }

    enum class Banner { NONE, GRACE, BLOCKED }

    /**
     * [allowed]: may host publicly. [mustSignIn]: the next step is signing in (not joining the server).
     * [reason]: "ok", "offline_ok", "signed_out", "session_rejected", "recheck", "not_in_server", "screening", ...
     */
    data class Gate(val allowed: Boolean, val mustSignIn: Boolean, val banner: Banner, val reason: String, val graceDaysLeft: Long?)

    private data class Verdict(val allowed: Boolean, val mustSignIn: Boolean, val reason: String)

    fun evaluate(
        existingInstall: Boolean,
        hasSession: Boolean,
        config: Config?,
        status: Status?,
        failure: Failure?,
        lastGood: LastGood?,
        now: Long,
    ): Gate {
        val graceUntil = config?.graceUntil
        val serverNow = config?.let { it.serverNow + maxOf(0L, now - it.at) }
        val graceActive = existingInstall && !(graceUntil != null && serverNow != null && serverNow >= graceUntil)
        val graceDaysLeft = if (graceActive && graceUntil != null && serverNow != null) maxOf(0L, ceilDiv(graceUntil - serverNow, DAY)) else null

        val verdict = membership(hasSession, status, failure, lastGood, now)
        if (graceActive) {
            return Gate(true, false, if (verdict.allowed) Banner.NONE else Banner.GRACE, verdict.reason, graceDaysLeft)
        }
        return Gate(verdict.allowed, verdict.mustSignIn, if (verdict.allowed) Banner.NONE else Banner.BLOCKED, verdict.reason, null)
    }

    private fun membership(hasSession: Boolean, status: Status?, failure: Failure?, lastGood: LastGood?, now: Long): Verdict {
        if (!hasSession) return Verdict(false, true, "signed_out")
        if (failure == Failure.UNAUTHORIZED) return Verdict(false, true, "session_rejected")
        if (failure == Failure.UNREACHABLE || status == null) {
            // The service cannot be asked: trust the last good answer for a week, then stop getting in people's way.
            if (lastGood == null || now - lastGood.at > OFFLINE_HOLD_MS) return Verdict(true, false, "offline_ok")
            return if (lastGood.member) Verdict(true, false, "ok") else Verdict(false, false, "not_in_server")
        }
        if (status.member) return Verdict(true, false, "ok")
        val reason = status.reason ?: "not_in_server"
        return Verdict(false, reason == "recheck", reason)
    }

    private fun ceilDiv(a: Long, b: Long): Long = Math.floorDiv(a + b - 1, b)
}
