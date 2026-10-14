package com.voxelport.android

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * Measures how much of the phone's data VoxelPort uses, and whether the connection is a data
 * plan (mobile data, or Wi‑Fi the user marked metered) or ordinary Wi‑Fi.
 *
 * The figure is the app's whole network traffic (the tunnel, downloads, everything in both of its
 * processes) as Android counts it, read every few seconds and credited to whichever kind of
 * connection was active then. It is an estimate, close to what Android's own "data usage" screen
 * shows. Lives in the main process only; its numbers are kept in their own preferences file.
 */
object DataMeter {
    data class Net(val metered: Boolean = false, val cellular: Boolean = false, val online: Boolean = true)

    /** [session] is the total (any connection) since the tunnel last started. */
    data class Usage(val mobile: Long = 0, val wifi: Long = 0, val session: Long = 0, val bonusMb: Int = 0)

    private val _net = MutableStateFlow(Net())
    val net: StateFlow<Net> = _net
    private val _usage = MutableStateFlow(Usage())
    val usage: StateFlow<Usage> = _usage

    /** Readings further apart than this are not credited: we don't know which connection they used. */
    private const val MAX_GAP_MS = 10 * 60_000L

    private var store: SharedPreferences? = null
    private var ready = false
    private var session = 0L

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        val app = ctx.applicationContext
        store = app.getSharedPreferences("voxelport_data", Context.MODE_PRIVATE)
        ready = true
        val cm = app.getSystemService(ConnectivityManager::class.java)
        _net.value = netOf(cm.activeNetwork?.let { cm.getNetworkCapabilities(it) })
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { sample(); _net.value = netOf(caps) }
                override fun onLost(network: Network) { sample(); _net.value = Net(online = false) }
            })
        }
        publish(read())
    }

    private fun netOf(c: NetworkCapabilities?): Net = if (c == null) Net(online = false) else Net(
        // "Not metered" is Android's word for ordinary Wi-Fi; everything else may cost the user data.
        metered = !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        cellular = c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
    )

    private fun today() = LocalDate.now().toString()

    private fun read(): DataLogic.Counters {
        val s = store ?: return DataLogic.Counters(today(), 0, 0, 0)
        val c = DataLogic.Counters(s.getString("day", "") ?: "", s.getLong("mobile", 0), s.getLong("wifi", 0), s.getInt("bonus_mb", 0))
        // A new day starts from zero (the old numbers are replaced on the next write).
        return if (c.day == today()) c else DataLogic.Counters(today(), 0, 0, 0)
    }

    private fun write(c: DataLogic.Counters) {
        store?.edit()?.putString("day", c.day)?.putLong("mobile", c.mobile)?.putLong("wifi", c.wifi)?.putInt("bonus_mb", c.bonusMb)?.apply()
    }

    private fun publish(c: DataLogic.Counters) {
        _usage.value = Usage(c.mobile, c.wifi, session, c.bonusMb)
    }

    /** Reads Android's traffic counter and credits what was used since the last reading. Cheap; call often. */
    @Synchronized
    fun sample() {
        val s = store ?: return
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return // this phone can't say
        val total = rx + tx
        val now = System.currentTimeMillis()
        val lastTotal = s.getLong("uid_last", -1)
        val lastAt = s.getLong("t_last", 0)
        s.edit().putLong("uid_last", total).putLong("t_last", now).apply()

        var used = DataLogic.delta(lastTotal, total)
        if (now - lastAt > MAX_GAP_MS || now < lastAt) used = 0
        if (used <= 0) { publish(read()); return }
        session += used
        val c = DataLogic.add(read(), today(), used, _net.value.metered)
        write(c)
        publish(c)
    }

    /** The public tunnel just started: count this session from zero. */
    @Synchronized
    fun beginSession() { sample(); session = 0; publish(read()) }

    /** Lets today's mobile limit go [DataLogic.BONUS_MB] further (today only). */
    @Synchronized
    fun addBonus() {
        val c = read().let { it.copy(bonusMb = it.bonusMb + DataLogic.BONUS_MB) }
        write(c); publish(c)
    }

    /** True the first time it is asked each day: used so the "nearly used up" alert shows once. */
    @Synchronized
    fun firstWarningToday(): Boolean {
        val s = store ?: return false
        if (s.getString("warned_day", "") == today()) return false
        s.edit().putString("warned_day", today()).apply()
        return true
    }
}
