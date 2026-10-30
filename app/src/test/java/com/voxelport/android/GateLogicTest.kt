package com.voxelport.android

import com.voxelport.android.GateLogic.Banner
import com.voxelport.android.GateLogic.Config
import com.voxelport.android.GateLogic.Failure
import com.voxelport.android.GateLogic.LastGood
import com.voxelport.android.GateLogic.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GateLogicTest {
    private val day = GateLogic.DAY
    private val now = 1_000_000_000_000L

    private fun gate(
        existing: Boolean = false, session: Boolean = true, config: Config? = null, status: Status? = null,
        failure: Failure? = null, lastGood: LastGood? = null, at: Long = now,
    ) = GateLogic.evaluate(existing, session, config, status, failure, lastGood, at)

    private val member = Status(true, null)

    @Test fun `a member of the Discord may host`() {
        val g = gate(status = member)
        assertTrue(g.allowed); assertEquals(Banner.NONE, g.banner); assertEquals("ok", g.reason)
    }

    @Test fun `no session means sign in, for new and old installs alike once grace is over`() {
        val over = Config(graceUntil = now - 1, serverNow = now, at = now, inviteUrl = null)
        for (existing in listOf(false, true)) {
            val g = gate(existing = existing, session = false, config = over)
            assertFalse(g.allowed); assertTrue(g.mustSignIn); assertEquals("signed_out", g.reason); assertEquals(Banner.BLOCKED, g.banner)
        }
    }

    @Test fun `a person who is not in the server must join, and can check again`() {
        val g = gate(status = Status(false, "not_in_server"))
        assertFalse(g.allowed); assertFalse(g.mustSignIn); assertEquals("not_in_server", g.reason)
    }

    @Test fun `a recheck answer asks for a new sign-in`() {
        val g = gate(status = Status(false, "recheck"))
        assertFalse(g.allowed); assertTrue(g.mustSignIn)
    }

    @Test fun `a rejected session means sign in again, never fail open`() {
        val g = gate(failure = Failure.UNAUTHORIZED, lastGood = LastGood(true, now))
        assertFalse(g.allowed); assertTrue(g.mustSignIn); assertEquals("session_rejected", g.reason)
    }

    @Test fun `an unreachable service keeps trusting the last good answer for a week`() {
        assertTrue(gate(failure = Failure.UNREACHABLE, lastGood = LastGood(true, now - 6 * day)).allowed)
        val notMember = gate(failure = Failure.UNREACHABLE, lastGood = LastGood(false, now - 6 * day))
        assertFalse(notMember.allowed); assertEquals("not_in_server", notMember.reason)
    }

    @Test fun `after a week offline, or with nothing known, an outage never locks anyone out`() {
        assertEquals("offline_ok", gate(failure = Failure.UNREACHABLE, lastGood = LastGood(false, now - 8 * day)).reason)
        assertTrue(gate(failure = Failure.UNREACHABLE, lastGood = LastGood(false, now - 8 * day)).allowed)
        assertTrue(gate(failure = Failure.UNREACHABLE).allowed)
    }

    @Test fun `a missing session is never treated as an outage`() {
        assertFalse(gate(session = false, failure = Failure.UNREACHABLE, lastGood = LastGood(true, now)).allowed)
    }

    @Test fun `grace helps installs that existed before the requirement`() {
        val config = Config(graceUntil = now + 3 * day - 1, serverNow = now, at = now, inviteUrl = null)
        val g = gate(existing = true, session = false, config = config)
        assertTrue(g.allowed); assertEquals(Banner.GRACE, g.banner); assertEquals(3L, g.graceDaysLeft)
    }

    @Test fun `grace never helps a new install`() {
        val config = Config(graceUntil = now + 3 * day, serverNow = now, at = now, inviteUrl = null)
        val g = gate(existing = false, session = false, config = config)
        assertFalse(g.allowed); assertNull(g.graceDaysLeft)
    }

    @Test fun `when grace is over an old install is held to the same rule`() {
        val config = Config(graceUntil = now - 1, serverNow = now, at = now, inviteUrl = null)
        assertFalse(gate(existing = true, session = false, config = config).allowed)
        assertTrue(gate(existing = true, session = true, config = config, status = member).allowed)
    }

    @Test fun `no grace date means existing installs are never blocked`() {
        val g = gate(existing = true, session = false, config = Config(null, now, now, null))
        assertTrue(g.allowed); assertNull(g.graceDaysLeft)
    }

    @Test fun `setting the phone clock back cannot extend grace`() {
        // Grace ends one hour after the server's clock at the time it was read; the phone clock then jumps a day back.
        val config = Config(graceUntil = now + 3_600_000, serverNow = now, at = now, inviteUrl = null)
        assertTrue(gate(existing = true, session = false, config = config, at = now + 1_000).allowed)
        assertTrue(gate(existing = true, session = false, config = config, at = now - day).allowed) // elapsed counts as zero, not negative
        assertFalse(gate(existing = true, session = false, config = config, at = now + 2 * 3_600_000).allowed)
    }

    @Test fun `a grace banner shows only for someone who is not yet allowed`() {
        val config = Config(graceUntil = now + day, serverNow = now, at = now, inviteUrl = null)
        assertEquals(Banner.NONE, gate(existing = true, config = config, status = member).banner)
        assertEquals(Banner.GRACE, gate(existing = true, session = false, config = config).banner)
    }
}
