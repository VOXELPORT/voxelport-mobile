package com.voxelport.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import com.voxelport.go.mobile.Host
import com.voxelport.go.mobile.Listener
import com.voxelport.go.mobile.Mobile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the public tunnel is doing, for the UI (same process). */
data class TunnelState(
    val on: Boolean = false,
    val status: String = "",
    val port: Int = 0,
    val name: String = "",
    val address: String = "",
    val pingMs: Int = 0,
    val error: String = "",
    /** Why the last claim failed (taken, too many tries…); shown next to the name box. */
    val nameError: String = "",
    /** Non-fatal heads-up from the relay (Bedrock unavailable, nothing answering on the port…). */
    val notice: String = "",
)

/** Keeps the server reachable through the VoxelPort relay (Go tunnel, gomobile). */
class TunnelService : Service() {
    private var host: Host? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var dataWatch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DataMeter.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getBooleanExtra(EXTRA_BEDROCK, false))
            ACTION_CLAIM -> intent.getStringExtra(EXTRA_NAME)?.let { host?.claimName(it) }
            ACTION_RELEASE -> { host?.releaseName(); Prefs(this).claimName = ""; _state.update { it.copy(name = "", address = "", nameError = "") } }
            ACTION_STOP -> { stopTunnel(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun start(bedrock: Boolean) {
        Notify.foreground(this, "Minecraft server is public", "Friends can join while this runs")
        if (host != null) return
        // The screen already refuses to start sharing over the limit; this is the same rule, enforced here too.
        val use = DataMeter.usage.value
        if (DataLogic.hostCheck(Prefs(this).dataLimitMb, use.bonusMb, use.mobile, DataMeter.net.value.metered) != DataLogic.HostCheck.OK) {
            stopSelf()
            return
        }
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoxelPort:tunnel").apply { acquire() }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VoxelPort:tunnel").apply { acquire() }
        val prefs = Prefs(this)
        _state.value = TunnelState(on = true, status = "Connecting…")
        host = Mobile.start(prefs.token, 25565, if (bedrock) 19132 else 0, prefs.claimName, object : Listener {
            override fun onEvent(kind: String, text: String, port: Long, name: String, address: String, pingMs: Long) {
                _state.update { s ->
                    when (kind) {
                        "port" -> s.copy(port = port.toInt(), status = "Public", error = "")
                        "name" -> { prefs.claimName = name; s.copy(name = name, address = address, nameError = "") }
                        "name_error" -> s.copy(nameError = text)
                        "ping" -> s.copy(pingMs = pingMs.toInt())
                        "status" -> s.copy(status = text)
                        "log", "udp_error", "listing_error" -> s.copy(notice = text)
                        "fatal" -> s.copy(error = text, status = "Stopped")
                        "error" -> s.copy(error = text)
                        // "players" carries a joining player's IP address: never show it.
                        else -> s
                    }
                }
            }
        })
        DataMeter.beginSession()
        watchData(prefs)
    }

    /**
     * While public: keeps count of the data used, warns when the day's mobile limit is nearly
     * reached, and stops sharing (the server itself keeps running) when it is reached, or when
     * the phone drops to mobile data while the limit is "Wi‑Fi only".
     */
    private fun watchData(prefs: Prefs) {
        dataWatch?.cancel()
        dataWatch = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(5_000)
                DataMeter.sample()
                val use = DataMeter.usage.value
                val net = DataMeter.net.value
                val limit = prefs.dataLimitMb
                when (DataLogic.hostCheck(limit, use.bonusMb, use.mobile, net.metered)) {
                    DataLogic.HostCheck.OK -> {
                        if (net.metered && DataLogic.level(use.mobile, limit, use.bonusMb) == DataLogic.Level.WARN && DataMeter.firstWarningToday())
                            Notify.alert(
                                this@TunnelService, "Mobile data nearly used up",
                                "VoxelPort has used ${DataLogic.format(use.mobile)} of today's ${DataLogic.limitLabel(limit)} on mobile data. " +
                                    "Sharing stops at the limit. Connect to Wi‑Fi to keep your server public.",
                            )
                        // With the app closed, the notification is where you can see what hosting costs.
                        if (net.metered && ++ticks % 6 == 0)
                            Notify.update(this@TunnelService, "Minecraft server is public", "On mobile data · ${DataLogic.format(use.mobile)} used today")
                    }
                    DataLogic.HostCheck.OVER_LIMIT -> {
                        Notify.alert(
                            this@TunnelService, "Stopped sharing your server",
                            "Today's mobile-data limit (${DataLogic.limitLabel(limit)}) is used up, so your server is no longer public. " +
                                "It is still running on your phone. Connect to Wi‑Fi to make it public again.",
                        )
                        stopSharing(); return@launch
                    }
                    DataLogic.HostCheck.WIFI_ONLY -> {
                        Notify.alert(
                            this@TunnelService, "Stopped sharing your server",
                            "Your phone is on mobile data and VoxelPort is set to Wi‑Fi only, so your server is no longer public. " +
                                "It is still running on your phone. Connect to Wi‑Fi to make it public again.",
                        )
                        stopSharing(); return@launch
                    }
                }
            }
        }
    }

    private fun stopSharing() {
        stopTunnel()
        stopSelf()
    }

    private fun stopTunnel() {
        dataWatch?.cancel()
        dataWatch = null
        host?.stop()
        host = null
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        _state.value = TunnelState()
    }

    override fun onDestroy() {
        stopTunnel()
        // The server's notification shares this one's id: if the server is still running, keep its notification.
        if (ServerService.isAlive(this)) {
            stopForeground(STOP_FOREGROUND_DETACH)
            Notify.update(this, "Minecraft server running", "Tap to open VoxelPort")
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_CLAIM = "claim"
        const val ACTION_RELEASE = "release"
        const val EXTRA_BEDROCK = "bedrock"
        const val EXTRA_NAME = "name"

        private val _state = MutableStateFlow(TunnelState())
        val state: StateFlow<TunnelState> = _state

        /** Clears the name error once the user starts a new claim. */
        fun clearNameError() = _state.update { it.copy(nameError = "") }

        fun send(ctx: Context, action: String, extras: Intent.() -> Unit = {}) {
            val i = Intent(ctx, TunnelService::class.java).setAction(action).apply(extras)
            if (action == ACTION_START) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
