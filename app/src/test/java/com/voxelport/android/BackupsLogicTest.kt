package com.voxelport.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupsLogicTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val now = 1_000_000_000_000L

    private fun ago(msBefore: Long) = BackupsLogic.ago(now, now - msBefore)

    @Test fun `how long ago reads naturally`() {
        assertEquals("just now", ago(0))
        assertEquals("just now", ago(59_000))
        assertEquals("1 min ago", ago(min))
        assertEquals("5 min ago", ago(5 * min))
        assertEquals("59 min ago", ago(59 * min))
        assertEquals("1 hour ago", ago(hour))
        assertEquals("1 hour ago", ago(hour + 59 * min))
        assertEquals("2 hours ago", ago(2 * hour))
        assertEquals("23 hours ago", ago(23 * hour))
        assertEquals("1 day ago", ago(24 * hour))
        assertEquals("2 days ago", ago(49 * hour))
        assertEquals("30 days ago", ago(30 * 24 * hour))
    }

    @Test fun `a clock that is a little ahead does not give a negative time`() {
        assertEquals("just now", BackupsLogic.ago(now, now + 5 * min))
    }

    @Test fun `the schedule choices are named`() {
        assertEquals("10 min", BackupsLogic.interval(10))
        assertEquals("1 hour", BackupsLogic.interval(60))
        assertEquals("2 hours", BackupsLogic.interval(120))
        assertEquals("4 hours", BackupsLogic.interval(240))
        assertEquals("90 min", BackupsLogic.interval(90))
        for (m in BackupSettings.INTERVALS) assertFalse(BackupsLogic.interval(m).contains("{"))
    }

    @Test fun `sizes are short`() {
        assertEquals("1 KB", BackupsLogic.size(0))
        assertEquals("1 KB", BackupsLogic.size(500))
        assertEquals("340 KB", BackupsLogic.size(340_000))
        assertEquals("12 MB", BackupsLogic.size(12_400_000))
        assertEquals("1.4 GB", BackupsLogic.size(1_400_000_000))
        assertEquals("1.4 GB", BackupsLogic.size(1_400_000_000)) // not "1,4" in a comma-decimal locale
    }

    @Test fun `every reason has a name, and an unknown one is shown as it is`() {
        for (r in Backups.REASONS) {
            assertTrue(r, BackupsLogic.reason(r) != r)
        }
        assertEquals("something", BackupsLogic.reason("something"))
    }

    @Test fun `every step of a job has words, and an unknown step does not show a code`() {
        for (p in listOf("starting", "zipping", "checking", "saving-current", "unpacking", "swapping")) {
            val text = BackupsLogic.phase(p)
            assertTrue(p, text.endsWith("…") && text != "Working…")
        }
        assertEquals("Working…", BackupsLogic.phase("never-heard-of-it"))
    }

    @Test fun `each failure is explained in plain words, never with a technical detail`() {
        val codes = listOf("no-world", "disk-full", "busy", "missing", "too-big", "bad-backup", "not-voxelport", "unsafe", "bad-name", "failed")
        for (c in codes) {
            val text = BackupsLogic.error(c, "java.io.IOException: boom at /data/user/0/secret")
            assertFalse("$c leaked a detail", text.contains("IOException") || text.contains("/data"))
            assertTrue(c, text.isNotBlank())
        }
        assertEquals("The backup failed.", BackupsLogic.error("something-new", "odd internal text"))
    }

    @Test fun `a failed restore says the world was left as it was`() {
        val text = BackupsLogic.error("failed", "The restore failed and your world was left as it was: disk error")
        assertEquals("The restore failed and your world was left as it was.", text)
        assertEquals("The backup failed.", BackupsLogic.error("failed", "The backup failed: disk error"))
    }

    @Test fun `the result line says what happened`() {
        assertEquals("Backup saved.", BackupsLogic.result(BackupStatus(phase = "done", reason = "manual")))
        assertEquals("Backup saved.", BackupsLogic.result(BackupStatus(phase = "done", reason = "auto")))
        assertEquals("Restored. Start the server to play on it.", BackupsLogic.result(BackupStatus(phase = "done", reason = "restore")))
        assertEquals(
            "That backup is gone.",
            BackupsLogic.result(BackupStatus(phase = "failed", reason = "restore", code = "missing", error = "x")),
        )
    }

    @Test fun `the restore hint only shows while the server runs`() {
        assertEquals("", BackupsLogic.restoreHint(false))
        assertEquals("Stop the server to restore a backup.", BackupsLogic.restoreHint(true))
    }
}
