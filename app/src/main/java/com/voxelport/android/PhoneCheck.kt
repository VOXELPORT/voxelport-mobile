package com.voxelport.android

/**
 * "Can this phone host a server?" Looks at memory, free space, processor cores and Android's version, and says in plain
 * words what to expect. Plain logic (the screen reads the numbers from the phone and passes them in), so it can be unit-tested.
 */
object PhoneCheck {
    enum class Level { GOOD, OK, WARN, BAD }

    data class Item(val title: String, val detail: String, val level: Level)

    data class Report(
        val items: List<Item>,
        /** The worst thing found. */
        val verdict: Level,
        /** One sentence for the top of the card. */
        val summary: String,
        /** About how many friends can play at once without lag. */
        val players: Int,
    )

    /** Free space the first install needs (Java ~200 MB, the server ~120 MB, the world, a backup). Same bar as the installer. */
    const val NEEDED_BYTES = 1_200_000_000L
    private const val COMFORT_BYTES = 2_500_000_000L

    /** [ramMb] as Android reports it (a "4 GB" phone shows about 3.5 GB). */
    fun check(ramMb: Int, cores: Int, freeBytes: Long, sdk: Int): Report {
        val ram = "%.1f".format(ramMb / 1024f)
        val items = listOf(
            when {
                ramMb >= 7000 -> Item(t("Memory"), t("{gb} GB of RAM: plenty. A big server is fine.", "gb" to ram), Level.GOOD)
                ramMb >= 3400 -> Item(t("Memory"), t("{gb} GB of RAM: good for a small server with friends.", "gb" to ram), Level.GOOD)
                ramMb >= 2400 -> Item(t("Memory"), t("{gb} GB of RAM: it works, but keep to a few players and close other apps.", "gb" to ram), Level.WARN)
                else -> Item(t("Memory"), t("{gb} GB of RAM is very little. Android may close the server when you open other apps.", "gb" to ram), Level.BAD)
            },
            when {
                freeBytes >= COMFORT_BYTES -> Item(t("Storage"), t("{gb} GB free: enough.", "gb" to gb(freeBytes)), Level.GOOD)
                freeBytes >= NEEDED_BYTES -> Item(t("Storage"), t("{gb} GB free: enough to start, but the world grows. Free some space soon.", "gb" to gb(freeBytes)), Level.WARN)
                else -> Item(t("Storage"), t("Only {gb} GB free. Setting up needs about 1.2 GB. Free some space first.", "gb" to gb(freeBytes)), Level.BAD)
            },
            when {
                cores >= 8 -> Item(t("Processor"), t("{n} cores: fast.", "n" to cores), Level.GOOD)
                cores >= 6 -> Item(t("Processor"), t("{n} cores: good.", "n" to cores), Level.GOOD)
                cores >= 4 -> Item(t("Processor"), t("{n} cores: fine for a small server.", "n" to cores), Level.OK)
                else -> Item(t("Processor"), t("{n} cores: slow. Expect lag with more than one or two players.", "n" to cores), Level.WARN)
            },
            if (sdk >= 29) Item(t("Android"), t("Android {v}: supported.", "v" to androidName(sdk)), Level.GOOD)
            else Item(t("Android"), t("Android {v}: works, but older phones are slower and close background apps sooner.", "v" to androidName(sdk)), Level.OK),
        )
        val verdict = items.maxOf { it.level }
        val players = when {
            ramMb >= 7000 && cores >= 8 -> 15
            ramMb >= 5500 && cores >= 6 -> 8
            ramMb >= 3400 -> 5
            ramMb >= 2400 -> 3
            else -> 1
        }
        val summary = when (verdict) {
            Level.GOOD -> t("This phone is a good host. About {n} friends can play at once.", "n" to players)
            Level.OK -> t("This phone can host. About {n} friends can play at once.", "n" to players)
            Level.WARN -> t("This phone can host, with limits. Plan for about {n} friends at once.", "n" to players)
            Level.BAD -> t("This phone may struggle. See what to fix below.")
        }
        return Report(items, verdict, summary, players)
    }

    private fun gb(bytes: Long) = "%.1f".format(bytes / 1_000_000_000f)

    /** Android's marketing version for an API level. */
    fun androidName(sdk: Int): String = when {
        sdk >= 35 -> "15"
        sdk == 34 -> "14"
        sdk == 33 -> "13"
        sdk == 32 || sdk == 31 -> "12"
        sdk == 30 -> "11"
        sdk == 29 -> "10"
        sdk == 28 -> "9"
        sdk == 27 -> "8.1"
        else -> "8"
    }
}
