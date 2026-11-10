package com.voxelport.android

/**
 * The rules for keeping VoxelPort from eating a phone's mobile data. Plain logic (no Android
 * calls) so it can be unit-tested; DataMeter does the measuring and the screens show the result.
 */
object DataLogic {
    const val MB = 1_000_000L

    /** The daily limit setting: -1 = host and download on Wi-Fi only, 0 = no limit, otherwise MB per day. */
    const val WIFI_ONLY = -1
    const val UNLIMITED = 0
    const val DEFAULT_LIMIT_MB = 500
    /** What "add more for today" adds. */
    const val BONUS_MB = 250
    /** One plugin/mod bigger than this asks before downloading on mobile data. */
    const val BIG_DOWNLOAD_MB = 8

    val LIMITS = listOf(WIFI_ONLY, 250, 500, 1000, 2000, UNLIMITED)

    fun limitLabel(limitMb: Int) = when (limitMb) {
        WIFI_ONLY -> "Wi‑Fi only"
        UNLIMITED -> "No limit"
        else -> if (limitMb >= 1000 && limitMb % 1000 == 0) "${limitMb / 1000} GB" else "$limitMb MB"
    }

    // ── measuring ──
    /**
     * Bytes used since the last reading. [last] < 0 means there was no earlier reading; a reading
     * lower than the last one means the phone restarted (its counters start over).
     */
    fun delta(last: Long, now: Long): Long = when {
        last < 0 || now < 0 -> 0
        now >= last -> now - last
        else -> now
    }

    /** Today's tally of what the app used, split by whether the connection counts against a data plan. */
    data class Counters(val day: String, val mobile: Long, val wifi: Long, val bonusMb: Int)

    /** Adds [bytes] used on a [metered] (mobile) or unmetered (Wi-Fi) connection; a new day starts from zero. */
    fun add(c: Counters, today: String, bytes: Long, metered: Boolean): Counters {
        val b = bytes.coerceAtLeast(0)
        val base = if (c.day == today) c else Counters(today, 0, 0, 0)
        return if (metered) base.copy(mobile = base.mobile + b) else base.copy(wifi = base.wifi + b)
    }

    // ── limits ──
    enum class Level { OK, WARN, OVER }

    /** How close today's mobile use is to the limit: WARN from 80%, OVER at the limit. */
    fun level(mobileUsed: Long, limitMb: Int, bonusMb: Int): Level {
        if (limitMb <= 0) return Level.OK
        val limit = (limitMb + bonusMb) * MB
        return when {
            mobileUsed >= limit -> Level.OVER
            mobileUsed * 10 >= limit * 8 -> Level.WARN
            else -> Level.OK
        }
    }

    /** 0..1 of the day's limit used, or null when there is no byte limit. */
    fun fraction(mobileUsed: Long, limitMb: Int, bonusMb: Int): Float? {
        if (limitMb <= 0) return null
        return (mobileUsed.toFloat() / ((limitMb + bonusMb) * MB)).coerceIn(0f, 1f)
    }

    enum class HostCheck { OK, WIFI_ONLY, OVER_LIMIT }

    /** May the server be made public (or stay public) right now? Wi-Fi is always fine. */
    fun hostCheck(limitMb: Int, bonusMb: Int, mobileUsed: Long, metered: Boolean): HostCheck = when {
        !metered -> HostCheck.OK
        limitMb == WIFI_ONLY -> HostCheck.WIFI_ONLY
        level(mobileUsed, limitMb, bonusMb) == Level.OVER -> HostCheck.OVER_LIMIT
        else -> HostCheck.OK
    }

    enum class DownloadCheck { OK, ASK, BLOCKED }

    /** Big downloads: free on Wi-Fi, a question on mobile data, refused if the user chose Wi-Fi only. */
    fun downloadCheck(limitMb: Int, metered: Boolean): DownloadCheck = when {
        !metered -> DownloadCheck.OK
        limitMb == WIFI_ONLY -> DownloadCheck.BLOCKED
        else -> DownloadCheck.ASK
    }

    // ── estimates (rough, and labelled that way on screen) ──
    /** About how much setting up a server downloads, including what the first start fetches. */
    fun installEstimateMb(fabric: Boolean, bedrock: Boolean): Int = (if (fabric) 70 else 110) + (if (bedrock) 25 else 0)

    /** What one player online costs in mobile data, in a few words ("about 10–50 MB per hour…"). */
    fun perPlayerText() =
        fill("about 10–50 MB per hour for each player online (much more while they explore new land)")

    // ── text ──
    fun format(bytes: Long): String = when {
        bytes <= 0 -> "0 MB"
        bytes < MB -> "<1 MB"
        bytes < 1_000 * MB -> "${(bytes + MB / 2) / MB} MB"
        else -> "%.1f GB".format(bytes / (1_000.0 * MB))
    }
}

/** A plugin/mod download that is big enough to ask first on mobile data. */
class BigDownload(val title: String, val bytes: Long) :
    Exception(t("{title} is {size}. You're on mobile data: tap INSTALL again to download it anyway, or wait for Wi‑Fi.", "title" to title, "size" to DataLogic.format(bytes)))
