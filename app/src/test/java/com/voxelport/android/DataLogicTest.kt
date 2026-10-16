package com.voxelport.android

import com.voxelport.android.DataLogic.Counters
import com.voxelport.android.DataLogic.DownloadCheck
import com.voxelport.android.DataLogic.HostCheck
import com.voxelport.android.DataLogic.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataLogicTest {
    private val mb = DataLogic.MB

    @Test fun deltaHandlesFirstReadingAndReboots() {
        assertEquals(0, DataLogic.delta(-1, 5_000))          // nothing to compare with yet
        assertEquals(300, DataLogic.delta(1_000, 1_300))
        assertEquals(0, DataLogic.delta(1_000, 1_000))
        assertEquals(700, DataLogic.delta(9_000_000, 700))   // counters started over after a restart
        assertEquals(0, DataLogic.delta(100, -1))            // phone can't say
    }

    @Test fun usageIsSplitByConnectionAndStartsOverEachDay() {
        var c = Counters("2026-10-15", 0, 0, 0)
        c = DataLogic.add(c, "2026-10-15", 5 * mb, metered = true)
        c = DataLogic.add(c, "2026-10-15", 20 * mb, metered = false)
        c = DataLogic.add(c, "2026-10-15", 1 * mb, metered = true)
        assertEquals(6 * mb, c.mobile)
        assertEquals(20 * mb, c.wifi)

        // A new day forgets yesterday, including any extra allowance, and counts this reading in the new day.
        c = c.copy(bonusMb = 250)
        val next = DataLogic.add(c, "2026-10-16", 2 * mb, metered = true)
        assertEquals(Counters("2026-10-16", 2 * mb, 0, 0), next)

        // Negative readings never subtract.
        assertEquals(next, DataLogic.add(next, "2026-10-16", -5, metered = true))
    }

    @Test fun limitLevelsWarnAtEightyPercentAndStopAtTheLimit() {
        assertEquals(Level.OK, DataLogic.level(100 * mb, 500, 0))
        assertEquals(Level.OK, DataLogic.level(399 * mb, 500, 0))
        assertEquals(Level.WARN, DataLogic.level(400 * mb, 500, 0))
        assertEquals(Level.WARN, DataLogic.level(499 * mb, 500, 0))
        assertEquals(Level.OVER, DataLogic.level(500 * mb, 500, 0))
        assertEquals(Level.OVER, DataLogic.level(900 * mb, 500, 0))
        // "Add 250 MB for today" moves the line.
        assertEquals(Level.OK, DataLogic.level(500 * mb, 500, 250))
        assertEquals(Level.WARN, DataLogic.level(600 * mb, 500, 250))
        assertEquals(Level.OVER, DataLogic.level(750 * mb, 500, 250))
        // No limit, and Wi-Fi only (which has no byte limit to count against).
        assertEquals(Level.OK, DataLogic.level(50_000 * mb, DataLogic.UNLIMITED, 0))
        assertEquals(Level.OK, DataLogic.level(50_000 * mb, DataLogic.WIFI_ONLY, 0))
    }

    @Test fun fractionIsClampedAndAbsentWithoutALimit() {
        assertEquals(0.5f, DataLogic.fraction(250 * mb, 500, 0)!!, 0.001f)
        assertEquals(1f, DataLogic.fraction(900 * mb, 500, 0)!!, 0.001f)
        assertNull(DataLogic.fraction(10 * mb, DataLogic.UNLIMITED, 0))
        assertNull(DataLogic.fraction(10 * mb, DataLogic.WIFI_ONLY, 0))
    }

    @Test fun hostingRules() {
        // Wi-Fi is always fine, whatever happened on mobile data.
        assertEquals(HostCheck.OK, DataLogic.hostCheck(500, 0, 5_000 * mb, metered = false))
        assertEquals(HostCheck.OK, DataLogic.hostCheck(DataLogic.WIFI_ONLY, 0, 0, metered = false))
        // Mobile data: fine under the limit, refused over it, never with Wi-Fi only.
        assertEquals(HostCheck.OK, DataLogic.hostCheck(500, 0, 100 * mb, metered = true))
        assertEquals(HostCheck.OVER_LIMIT, DataLogic.hostCheck(500, 0, 500 * mb, metered = true))
        assertEquals(HostCheck.OK, DataLogic.hostCheck(500, 250, 500 * mb, metered = true))
        assertEquals(HostCheck.WIFI_ONLY, DataLogic.hostCheck(DataLogic.WIFI_ONLY, 0, 0, metered = true))
        assertEquals(HostCheck.OK, DataLogic.hostCheck(DataLogic.UNLIMITED, 0, 9_000 * mb, metered = true))
    }

    @Test fun bigDownloadsAskOnMobileDataOnly() {
        assertEquals(DownloadCheck.OK, DataLogic.downloadCheck(500, metered = false))
        assertEquals(DownloadCheck.OK, DataLogic.downloadCheck(DataLogic.WIFI_ONLY, metered = false))
        assertEquals(DownloadCheck.ASK, DataLogic.downloadCheck(500, metered = true))
        assertEquals(DownloadCheck.ASK, DataLogic.downloadCheck(DataLogic.UNLIMITED, metered = true))
        assertEquals(DownloadCheck.BLOCKED, DataLogic.downloadCheck(DataLogic.WIFI_ONLY, metered = true))
    }

    @Test fun downloadQuestionsAreWordedPerSituation() {
        assertNull(downloadAsk(500, false, 0, "Setup", 110, Lang.EN) {})
        val ask = downloadAsk(500, true, 42 * mb, "Setting up your server", 110, Lang.EN) {}!!
        assertEquals("Download anyway", ask.go)
        assertTrue(ask.body.contains("110 MB") && ask.body.contains("42 MB") && ask.body.contains("Wi‑Fi"))
        val blocked = downloadAsk(DataLogic.WIFI_ONLY, true, 0, "Setting up your server", 110, Lang.EN) {}!!
        assertNull(blocked.go) // nothing to confirm: it is refused
    }

    @Test fun labelsAndSizesReadNaturally() {
        assertEquals("Wi‑Fi only", DataLogic.limitLabel(DataLogic.WIFI_ONLY))
        assertEquals("No limit", DataLogic.limitLabel(DataLogic.UNLIMITED))
        assertEquals("250 MB", DataLogic.limitLabel(250))
        assertEquals("1 GB", DataLogic.limitLabel(1000))
        assertEquals("2 GB", DataLogic.limitLabel(2000))
        assertEquals("0 MB", DataLogic.format(0))
        assertEquals("<1 MB", DataLogic.format(300_000))
        assertEquals("1 MB", DataLogic.format(1_000_000))
        assertEquals("53 MB", DataLogic.format(52_700_000))
        assertEquals("999 MB", DataLogic.format(999_000_000))
        assertEquals("1.2 GB", DataLogic.format(1_234_000_000))
        // Every choice offered in the app has a label.
        for (l in DataLogic.LIMITS) assertTrue(DataLogic.limitLabel(l).isNotBlank())
    }

    @Test fun installEstimatesCoverTheFirstStartToo() {
        assertTrue(DataLogic.installEstimateMb(fabric = false, bedrock = false) >= 100)
        assertTrue(DataLogic.installEstimateMb(fabric = false, bedrock = true) > DataLogic.installEstimateMb(fabric = false, bedrock = false))
        assertTrue(DataLogic.installEstimateMb(fabric = true, bedrock = false) < DataLogic.installEstimateMb(fabric = false, bedrock = false))
    }

    @Test fun bigDownloadMessageNamesTheFileAndTheSize() {
        val e = BigDownload("Create", 46_000_000)
        assertTrue(e.message!!.contains("Create") && e.message!!.contains("46 MB") && e.message!!.contains("INSTALL again"))
    }
}
