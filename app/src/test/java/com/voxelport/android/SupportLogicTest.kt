package com.voxelport.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportLogicTest {
    private fun host(id: String = "good-host", name: String = "Good Host", url: String = "https://example.com/?ref=vp", extra: String = "") =
        """{"id":"$id","name":"$name","blurb":"For small groups.","url":"$url","affiliate":true$extra}"""

    private fun json(vararg hosts: String, hostsExtra: String = "") =
        """{"version":1,"hosts":{"intro":{"desktop":"PC intro","android":"Phone intro"},"disclosure":"Affiliate links."$hostsExtra,"items":[${hosts.joinToString(",")}]}}"""

    @Test fun tipsAlwaysGoToTheBuiltInUpiId() {
        assertEquals("upi://pay?pa=trazhub@upi&pn=VoxelPort&cu=INR&tn=Support%20VoxelPort", SupportLogic.upiUri())
        assertEquals("upi://pay?pa=trazhub@upi&pn=VoxelPort&cu=INR&tn=Support%20VoxelPort&am=50.00", SupportLogic.upiUri(50))
        assertEquals(SupportLogic.upiUri(), SupportLogic.upiUri(0))        // nonsense amounts are dropped
        assertEquals(SupportLogic.upiUri(), SupportLogic.upiUri(-5))
        assertEquals(SupportLogic.upiUri(), SupportLogic.upiUri(10_000_000))
        // Nothing in a downloaded support.json can change it: it isn't even read from there.
        val info = SupportLogic.parse("""{"version":1,"donate":{"upiId":"attacker@upi"},"upi":"attacker@upi"}""")
        assertSame(SupportLogic.DEFAULTS.supporter, info.supporter)
        assertTrue(SupportLogic.PRESETS.all { SupportLogic.upiUri(it).contains("pa=trazhub@upi&") })
    }

    @Test fun onlyHttpsLinksToRealDomainsAreAccepted() {
        for (ok in listOf("https://example.com/", "https://www.host.co.in/minecraft?ref=abc", "https://a.b.example.org/x#y"))
            assertTrue(ok, SupportLogic.isSafeHostUrl(ok))
        for (bad in listOf(
            "http://example.com/", "javascript:alert(1)", "file:///etc/passwd", "data:text/html,x", "ftp://example.com/",
            "https://user:pw@example.com/", "https://example.com:8443/", "https://localhost/", "https://127.0.0.1/", "https://10.0.0.5/",
            "https://[::1]/", "https://printer.local/", "https://intranet/", "not a url", "", "https://example.com/" + "a".repeat(600),
        )) assertFalse(bad, SupportLogic.isSafeHostUrl(bad))
        assertFalse(SupportLogic.isSafeHostUrl(null))
    }

    @Test fun hostingSuggestionsAreFilteredPerDeviceCleanedAndCapped() {
        val text = json(
            host(),
            host(id = "pc-only", name = "PC <b>Only</b>", extra = ""","for":["desktop"]"""),
            host(id = "phone-only", name = "Phone Only", extra = ""","for":["android"]"""),
            host(id = "good-host", name = "Duplicate id"),
            host(id = "Bad Id!", name = "Bad id"),
            host(id = "no-name", name = "   "),
            host(id = "http-link", url = "http://example.com/"),
            host(id = "evil", url = "javascript:alert(1)"),
        )
        val phone = SupportLogic.parse(text, "android").hosts
        assertEquals(listOf("good-host", "phone-only"), phone.items.map { it.id })
        assertEquals("Phone intro", phone.intro)
        assertEquals("Affiliate links.", phone.disclosure)
        val pc = SupportLogic.parse(text, "desktop").hosts
        assertEquals(listOf("good-host", "pc-only"), pc.items.map { it.id })
        assertFalse(pc.items[1].name.contains('<') || pc.items[1].name.contains('>'))
        assertEquals("PC intro", pc.intro)

        val many = json(*Array(20) { host(id = "h$it", name = "Host $it") })
        assertEquals(6, SupportLogic.parse(many).hosts.items.size)
    }

    @Test fun noValidHostsMeansNoSectionAndAffiliateLinksAlwaysHaveADisclosure() {
        for (t in listOf(
            """{"version":1}""", """{"version":1,"hosts":{"items":[]}}""",
            """{"version":1,"hosts":{"items":[{"id":"x","name":"x","url":"http://x.com"}]}}""",
            """{"version":1,"hosts":"junk"}""",
        )) assertTrue(t, SupportLogic.parse(t).hosts.items.isEmpty())
        val noDisclosure = """{"version":1,"hosts":{"items":[${host()}]}}"""
        val h = SupportLogic.parse(noDisclosure).hosts
        assertEquals(1, h.items.size)
        assertEquals(SupportLogic.DEFAULT_DISCLOSURE, h.disclosure)
        assertTrue(h.disclosure.contains("affiliate", ignoreCase = true))
    }

    @Test fun supporterTiersAreValidatedWithFallbacks() {
        assertSame(SupportLogic.DEFAULT_SUPPORTER, SupportLogic.parseSupporter(null))
        val bad = org.json.JSONObject("""{"tiers":[{"name":"Free","inr":0,"perks":["x"]}]}""")
        assertSame(SupportLogic.DEFAULT_SUPPORTER, SupportLogic.parseSupporter(bad))
        val good = org.json.JSONObject(
            """{"intro":"Hi","how":"Email us.","tiers":[{"name":"Supporter","inr":99.4,"perks":["One","","Two"]},{"name":"Bad","inr":-5,"perks":["x"]}]}""",
        )
        val s = SupportLogic.parseSupporter(good)
        assertEquals(1, s.tiers.size)
        assertEquals(SupportLogic.Tier("Supporter", 99, listOf("One", "Two")), s.tiers[0])
        assertEquals("Email us.", s.how)
        assertEquals(SupportLogic.DEFAULT_SUPPORTER.fine, s.fine)
    }

    @Test fun badOrOversizedJsonFallsBackToDefaults() {
        for (t in listOf<String?>(null, "", "not json", "[]", """{"version":2}""", """{"version":1,"pad":"${"x".repeat(70_000)}"}"""))
            assertSame(SupportLogic.DEFAULTS, SupportLogic.parse(t))
        assertFalse(SupportLogic.isUsable("not json"))
        assertFalse(SupportLogic.isUsable("""{"version":3}"""))
        assertFalse(SupportLogic.isUsable(null))
        assertTrue(SupportLogic.isUsable("""{"version":1}"""))
        assertNotNull(SupportLogic.parse("""{"version":1}"""))
    }

    @Test fun theBuiltInPerksAreWellFormed() {
        val d = SupportLogic.DEFAULT_SUPPORTER
        assertTrue(d.tiers.size >= 2 && d.tiers.all { it.inr > 0 && it.perks.isNotEmpty() })
        assertTrue(d.how.contains(SupportLogic.CONTACT))
    }
}
