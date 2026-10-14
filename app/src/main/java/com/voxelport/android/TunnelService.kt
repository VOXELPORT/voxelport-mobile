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

    override fun onBind(intent: Intent?): IBinder? = null

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
    }

    private fun stopTunnel() {
        host?.stop()
        host = null
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        _state.value = TunnelState()
    }

    override fun onDestroy() {
        stopTunnel()
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
