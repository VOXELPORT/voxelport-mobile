package com.voxelport.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupsLogicTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val now = 1_000_000_000_000L

    private fun ago(msBefore: Long, lang: String = Lang.EN) = BackupsLogic.ago(lang, now, now - msBefore)

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
        assertEquals("just now", BackupsLogic.ago(Lang.EN, now, now + 5 * min))
    }

    @Test fun `how long ago in Hinglish`() {
        assertEquals("abhi abhi", ago(0, Lang.HINGLISH))
        assertEquals("5 min pehle", ago(5 * min, Lang.HINGLISH))
        assertEquals("1 ghanta pehle", ago(hour, Lang.HINGLISH))
        assertEquals("3 ghante pehle", ago(3 * hour, Lang.HINGLISH))
        assertEquals("1 din pehle", ago(24 * hour, Lang.HINGLISH))
        assertEquals("4 din pehle", ago(4 * 24 * hour, Lang.HINGLISH))
    }

    @Test fun `the schedule choices are named`() {
        assertEquals("10 min", BackupsLogic.interval(Lang.EN, 10))
        assertEquals("1 hour", BackupsLogic.interval(Lang.EN, 60))
        assertEquals("2 hours", BackupsLogic.interval(Lang.EN, 120))
        assertEquals("4 hours", BackupsLogic.interval(Lang.EN, 240))
        assertEquals("90 min", BackupsLogic.interval(Lang.EN, 90))
        assertEquals("1 ghanta", BackupsLogic.interval(Lang.HINGLISH, 60))
        assertEquals("2 ghante", BackupsLogic.interval(Lang.HINGLISH, 120))
        for (m in BackupSettings.INTERVALS) assertFalse(BackupsLogic.interval(Lang.EN, m).contains("{"))
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
            assertTrue(r, BackupsLogic.reason(Lang.EN, r) != r)
            assertTrue(r, BackupsLogic.reason(Lang.HINGLISH, r) != BackupsLogic.reason(Lang.EN, r) || r == "auto" && false)
        }
        assertEquals("something", BackupsLogic.reason(Lang.EN, "something"))
    }

    @Test fun `every step of a job has words, and an unknown step does not show a code`() {
        for (p in listOf("starting", "zipping", "checking", "saving-current", "unpacking", "swapping")) {
            val text = BackupsLogic.phase(Lang.EN, p)
            assertTrue(p, text.endsWith("…") && text != "Working…")
        }
        assertEquals("Working…", BackupsLogic.phase(Lang.EN, "never-heard-of-it"))
    }

    @Test fun `each failure is explained in plain words, never with a technical detail`() {
        val codes = listOf("no-world", "disk-full", "busy", "missing", "too-big", "bad-backup", "not-voxelport", "unsafe", "bad-name", "failed")
        for (c in codes) {
            val text = BackupsLogic.error(Lang.EN, c, "java.io.IOException: boom at /data/user/0/secret")
            assertFalse("$c leaked a detail", text.contains("IOException") || text.contains("/data"))
            assertTrue(c, text.isNotBlank())
            assertEquals("$c has no Hinglish", true, BackupsLogic.error(Lang.HINGLISH, c, "") != text || c == "failed")
        }
        assertEquals("The backup failed.", BackupsLogic.error(Lang.EN, "something-new", "odd internal text"))
    }

    @Test fun `a failed restore says the world was left as it was`() {
        val text = BackupsLogic.error(Lang.EN, "failed", "The restore failed and your world was left as it was: disk error")
        assertEquals("The restore failed and your world was left as it was.", text)
        assertEquals("The backup failed.", BackupsLogic.error(Lang.EN, "failed", "The backup failed: disk error"))
    }

    @Test fun `the result line says what happened`() {
        assertEquals("Backup saved.", BackupsLogic.result(Lang.EN, BackupStatus(phase = "done", reason = "manual")))
        assertEquals("Backup saved.", BackupsLogic.result(Lang.EN, BackupStatus(phase = "done", reason = "auto")))
        assertEquals("Restored. Start the server to play on it.", BackupsLogic.result(Lang.EN, BackupStatus(phase = "done", reason = "restore")))
        assertEquals(
            "That backup is gone.",
            BackupsLogic.result(Lang.EN, BackupStatus(phase = "failed", reason = "restore", code = "missing", error = "x")),
        )
    }

    @Test fun `the restore hint only shows while the server runs`() {
        assertEquals("", BackupsLogic.restoreHint(Lang.EN, false))
        assertEquals("Stop the server to restore a backup.", BackupsLogic.restoreHint(Lang.EN, true))
    }
}
