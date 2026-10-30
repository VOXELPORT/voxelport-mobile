package com.voxelport.android

import com.voxelport.android.AccountLogic.Callback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountLogicTest {
    private val code = "A".repeat(43)

    @Test fun `the PKCE challenge matches the RFC 7636 example`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", AccountLogic.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun `a verifier from 32 bytes is 43 URL-safe characters`() {
        val v = AccountLogic.verifierFrom(ByteArray(32) { (it * 8 + 250).toByte() })
        assertEquals(43, v.length)
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(v))
        assertEquals(43, AccountLogic.challengeFor(v).length)
    }

    @Test fun `the sign-in link carries the challenge and the loopback port`() {
        assertEquals(
            "https://accounts.voxelport.in/api/auth/discord/start?app=1&challenge=abc&port=40123",
            AccountLogic.startUrl("abc", 40123),
        )
    }

    @Test fun `the browser coming back with a code is understood`() {
        assertEquals(Callback.Code(code), AccountLogic.parseCallback("GET /?code=$code HTTP/1.1"))
    }

    @Test fun `a sign-in the service refused is understood`() {
        assertEquals(Callback.Failed("signin_failed"), AccountLogic.parseCallback("GET /?error=signin_failed HTTP/1.1"))
    }

    @Test fun `anything else the browser asks for is ignored`() {
        for (line in listOf(
            "GET /favicon.ico HTTP/1.1", "GET / HTTP/1.1", "GET /?code=short HTTP/1.1", "GET /?code=${"A".repeat(44)} HTTP/1.1",
            "GET /?code=$code! HTTP/1.1", "GET /other?code=$code HTTP/1.1", "POST /?code=$code HTTP/1.1", "GET /?error=Bad-Input HTTP/1.1",
            "GET /?error= HTTP/1.1", "", "garbage",
        )) assertEquals(line, Callback.Ignore, AccountLogic.parseCallback(line))
    }

    @Test fun `the exchange answer is read`() {
        val s = AccountLogic.parseSummary(
            """{"session":"tok","plan":{"id":"plus","name":"Plus"},"rank":"supporter","member":true,"discordName":"Traz",
               "graceUntil":null,"serverNow":1700000000000,"inviteUrl":"https://discord.gg/abc","linkError":"Already linked."}""",
        )!!
        assertEquals("tok", s.session); assertEquals("Traz", s.discordName); assertEquals(true, s.member)
        assertEquals("Plus", s.planName); assertEquals("supporter", s.rank); assertNull(s.graceUntil)
        assertEquals(1700000000000L, s.serverNow); assertEquals("https://discord.gg/abc", s.inviteUrl); assertEquals("Already linked.", s.linkError)
    }

    @Test fun `a non-member answer keeps its reason`() {
        val s = AccountLogic.parseSummary("""{"member":false,"reason":"not_in_server","serverNow":5,"graceUntil":9}""")!!
        assertEquals(false, s.member); assertEquals("not_in_server", s.reason); assertEquals(9L, s.graceUntil)
    }

    @Test fun `the public config has no session or member fields`() {
        val s = AccountLogic.parseSummary("""{"graceUntil":null,"serverNow":42,"inviteUrl":null}""")!!
        assertNull(s.session); assertNull(s.member); assertEquals(42L, s.serverNow)
    }

    @Test fun `a bad answer is not trusted`() {
        assertNull(AccountLogic.parseSummary("not json"))
        assertNull(AccountLogic.parseSummary(""))
    }

    @Test fun `an invite that is not https is dropped`() {
        assertNull(AccountLogic.parseSummary("""{"serverNow":1,"inviteUrl":"http://evil.example/x"}""")!!.inviteUrl)
        assertNull(AccountLogic.parseSummary("""{"serverNow":1,"inviteUrl":"javascript:alert(1)"}""")!!.inviteUrl)
    }

    @Test fun `ranks read as the desktop app shows them`() {
        assertEquals("Free", AccountLogic.rankLabel("free"))
        assertEquals("Free", AccountLogic.rankLabel(null))
        assertEquals("Supporter", AccountLogic.rankLabel("supporter"))
        assertEquals("Supporter+", AccountLogic.rankLabel("supporter_plus"))
        assertEquals("Supporter+ (Pro)", AccountLogic.rankText("supporter_plus", "Pro"))
        assertEquals("Supporter", AccountLogic.rankText("supporter", "Supporter"))
        assertEquals("Free", AccountLogic.rankText("free", "Free"))
        assertTrue(AccountLogic.hasBadge("supporter")); assertFalse(AccountLogic.hasBadge("free"))
    }
}

class AccountBackupParseTest {
    @Test fun `the status carries the address backup when there is one`() {
        val s = AccountLogic.parseSummary("""{"member":true,"serverNow":1,"identityBackup":{"vpId":"0c67f05015ae","updatedAt":1700000000000}}""")!!
        assertTrue(s.backupKnown)
        assertEquals(AccountLogic.BackupInfo("0c67f05015ae", 1700000000000L), s.backup)
    }

    @Test fun `no backup is known and empty, not unknown`() {
        val s = AccountLogic.parseSummary("""{"member":true,"serverNow":1,"identityBackup":null}""")!!
        assertTrue(s.backupKnown); assertNull(s.backup)
    }

    @Test fun `the public config says nothing about a backup`() {
        val s = AccountLogic.parseSummary("""{"graceUntil":null,"serverNow":1,"inviteUrl":null}""")!!
        assertFalse(s.backupKnown); assertNull(s.backup)
    }

    @Test fun `a backup with a malformed id is ignored`() {
        assertNull(AccountLogic.parseSummary("""{"serverNow":1,"identityBackup":{"vpId":"NOT-AN-ID","updatedAt":5}}""")!!.backup)
    }
}
