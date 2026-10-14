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
