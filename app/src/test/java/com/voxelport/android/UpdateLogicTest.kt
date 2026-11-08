package com.voxelport.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private val sha = "ab".repeat(32)
    private fun json(code: String = "7", url: String = "/dl/voxelport-android.apk?v=ab12cd34", sha256: String = sha, size: String = "53179362") =
        """{"versionCode":$code,"versionName":"1.0.0","url":"$url","sha256":"$sha256","size":$size,"notes":"Faster start"}"""

    @Test fun parsesAGoodManifestAndResolvesARelativeUrl() {
        val m = UpdateLogic.parse(json())!!
        assertEquals(7L, m.versionCode)
        assertEquals("1.0.0", m.versionName)
        assertEquals("https://voxelport.in/dl/voxelport-android.apk?v=ab12cd34", m.url)
        assertEquals(53179362L, m.size)
        assertEquals("Faster start", m.notes)
    }

    @Test fun newerMeansHigherVersionCodeNotHigherName() {
        val m = UpdateLogic.parse(json(code = "7"))!!
        assertTrue(UpdateLogic.isNewer(m, 6))
        assertFalse(UpdateLogic.isNewer(m, 7))
        assertFalse(UpdateLogic.isNewer(m, 8)) // never offers a downgrade
    }

    @Test fun refusesAnythingNotOnOurOwnSiteOverHttps() {
        for (bad in listOf(
            "https://evil.example/dl/x.apk",
            "http://voxelport.in/dl/x.apk",
            "https://voxelport.in.evil.example/dl/x.apk",
            "https://voxelport.in@evil.example/dl/x.apk",
            "https://evil.example@voxelport.in/dl/x.apk",
            "https://voxelport.in:8443/dl/x.apk",
            "https://voxelport.in/other/x.apk",
            "https://voxelport.in/dl/../x.apk",
            "https://voxelport.in/dl/x.zip",
        )) {
            assertFalse(bad, UpdateLogic.trustedApkUrl(bad))
            assertNull(bad, UpdateLogic.parse(json(url = bad)))
        }
        assertTrue(UpdateLogic.trustedApkUrl("https://voxelport.in/dl/voxelport-android.apk?v=1"))
    }

    @Test fun rejectsMalformedManifests() {
        assertNull(UpdateLogic.parse("not json"))
        assertNull(UpdateLogic.parse("{}"))
        assertNull(UpdateLogic.parse(json(code = "0")))
        assertNull(UpdateLogic.parse(json(code = "-3")))
        assertNull(UpdateLogic.parse(json(sha256 = "xyz")))
        assertNull(UpdateLogic.parse(json(sha256 = "ab".repeat(31)))) // too short
        assertNull(UpdateLogic.parse(json(size = "0")))
        assertNull(UpdateLogic.parse(json(size = "999999999999")))
        assertNotNull(UpdateLogic.parse(json(sha256 = sha.uppercase()))) // case doesn't matter
    }

    @Test fun bothOfOurSitesAreTrustedAndNothingElse() {
        assertTrue(UpdateLogic.trustedApkUrl("https://voxelport.github.io/dl/voxelport-android.apk?v=ab12cd34"))
        assertTrue(UpdateLogic.trustedUrl("https://voxelport.github.io/dl/android.json?t=1"))
        assertTrue(UpdateLogic.trustedUrl("https://voxelport.in/dl/android.json"))
        for (bad in listOf(
            "https://voxelport.github.io.evil.example/dl/x.apk",
            "https://evil.example/voxelport.github.io/dl/x.apk",
            "https://github.io/dl/x.apk",
            "https://other.github.io/dl/x.apk",
            "http://voxelport.github.io/dl/x.apk",
            "https://voxelport.github.io@evil.example/dl/x.apk",
            "https://voxelport.github.io:8443/dl/x.apk",
            "https://voxelport.github.io/dl/../x.apk",
            "https://voxelport.github.io/other/x.apk",
        )) {
            assertFalse(bad, UpdateLogic.trustedApkUrl(bad))
            assertNull(bad, UpdateLogic.parse(json(url = bad)))
        }
        assertFalse(UpdateLogic.trustedUrl("https://evil.example/dl/android.json"))
        assertFalse(UpdateLogic.trustedUrl("not a url"))
    }

    @Test fun aRelativeApkAddressStaysOnTheSiteTheFeedCameFrom() {
        val fromMain = UpdateLogic.parse(json(), "https://voxelport.github.io/dl/android.json")!!
        assertEquals("https://voxelport.github.io/dl/voxelport-android.apk?v=ab12cd34", fromMain.url)
        val fromOld = UpdateLogic.parse(json(), "https://voxelport.in/dl/android.json")!!
        assertEquals("https://voxelport.in/dl/voxelport-android.apk?v=ab12cd34", fromOld.url)
        // An absolute address on the other trusted site is accepted too (a feed may name it), but never a foreign one.
        assertNotNull(UpdateLogic.parse(json(url = "https://voxelport.in/dl/x.apk"), "https://voxelport.github.io/dl/android.json"))
        assertNull(UpdateLogic.parse(json(url = "https://evil.example/dl/x.apk"), "https://voxelport.github.io/dl/android.json"))
    }

    @Test fun theNewestFeedWinsAndTheMainSiteWinsATie() {
        val main = UpdateLogic.parse(json(code = "19"), "https://voxelport.github.io/dl/android.json")!!
        val old = UpdateLogic.parse(json(code = "18"), "https://voxelport.in/dl/android.json")!!
        val sameOld = UpdateLogic.parse(json(code = "19"), "https://voxelport.in/dl/android.json")!!
        assertEquals(main, UpdateLogic.newest(listOf(main, old)))
        assertEquals(main, UpdateLogic.newest(listOf(old, main)))               // order does not matter when one is newer
        assertEquals(main, UpdateLogic.newest(listOf(main, sameOld)))           // a tie keeps the first (the main site)
        assertEquals(old, UpdateLogic.newest(listOf(old)))                      // one feed down: the other still counts
        assertNull(UpdateLogic.newest(emptyList()))
        assertEquals(listOf("https://voxelport.github.io/dl/android.json", "https://voxelport.in/dl/android.json"), UpdateLogic.MANIFEST_URLS)
    }

    @Test fun checksAtMostEverySixHoursUnlessAsked() {
        val h = 3600_000L
        val now = 100 * h
        assertTrue(UpdateLogic.shouldCheck(now, 0, false))                  // never checked
        assertFalse(UpdateLogic.shouldCheck(now, now - 1 * h, false))       // an hour ago
        assertTrue(UpdateLogic.shouldCheck(now, now - 7 * h, false))        // a while ago
        assertTrue(UpdateLogic.shouldCheck(now, now - 1 * h, true))         // the user asked
        assertTrue(UpdateLogic.shouldCheck(now, now + 5 * h, false))        // clock went backwards
    }

    @Test fun onlyTheSameAppWithTheSameKeyIsAccepted() {
        val mine = setOf("aa")
        assertTrue(UpdateLogic.sameApp("com.voxelport.android", "com.voxelport.android", mine, mine))
        assertFalse(UpdateLogic.sameApp("com.voxelport.android", "com.evil", mine, mine))
        assertFalse(UpdateLogic.sameApp("com.voxelport.android", null, mine, mine))
        assertFalse(UpdateLogic.sameApp("com.voxelport.android", "com.voxelport.android", mine, setOf("bb")))
        assertTrue(UpdateLogic.sameApp("com.voxelport.android", "com.voxelport.android", mine, emptySet())) // unreadable: installer decides
    }
}
