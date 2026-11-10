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
import org.json.JSONObject
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
    /** The relay region in use ("in" or "eu") and the host players type with the port; set once it is chosen. */
    val region: String = "",
    val host: String = "",
    val error: String = "",
    /** Why the last claim failed (taken, too many tries…); shown next to the name box. */
    val nameError: String = "",
    /** Non-fatal heads-up from the relay (Bedrock unavailable, nothing answering on the port…). */
    val notice: String = "",
    /** What the relay's operator announced ("the relay will restart"), until it is withdrawn or the tunnel stops. */
    val announcement: Announcement? = null,
)

/** An announcement from the relay. [dueAtMs] is when it happens (wall clock), 0 when no time was given. */
data class Announcement(val message: String, val dueAtMs: Long)

/** Keeps the server reachable through the VoxelPort relay (Go tunnel, gomobile). */
class TunnelService : Service() {
    private var host: Host? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var dataWatch: Job? = null
    private var noticeJob: Job? = null
    /** The announcement last said in the game's chat, and when: a reconnect hands the same one over again. */
    private var lastSaid: Pair<String, Long>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DataMeter.init(this)
        Lang.init(this)
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
        // Everyone must be in the VoxelPort Discord to host publicly: the screen says so, and it is enforced here too.
        if (!AppAccount.mayHost(this)) {
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
        host = Mobile.start("android/${Updater.installedCode(this)}", prefs.token, 25565, if (bedrock) 19132 else 0, prefs.claimName, "auto", object : Listener {
            override fun onEvent(kind: String, text: String, port: Long, name: String, address: String, pingMs: Long) {
                // The relay's operator announcing something: "port" carries the seconds until it happens.
                if (kind == "notice") { announce(text, port.toInt()); return }
                _state.update { s ->
                    when (kind) {
                        "region" -> s.copy(region = text, host = address)
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
     * The relay's operator announced something (or withdrew it, with an empty [text]). It shows as a banner, is said in
     * the game's chat so players hear it too (now, 5 and 1 minutes before, and when due), and reaches the host as a
     * notification when the app is closed.
     */
    private fun announce(text: String, seconds: Int) {
        noticeJob?.cancel()
        noticeJob = null
        val message = NoticeLogic.chatSafe(text)
        if (message.isEmpty()) {
            lastSaid = null
            _state.update { it.copy(announcement = null) }
            return
        }
        val now = System.currentTimeMillis()
        val again = lastSaid?.let { it.first == message && now - it.second < NoticeLogic.REPEAT_QUIET_MS } == true
        if (!again) lastSaid = message to now
        val left = seconds.coerceAtLeast(0)
        _state.update { it.copy(announcement = Announcement(message, if (left > 0) now + left * 1000L else 0L)) }
        if (!again) Notify.announce(this, "VoxelPort announcement", message + if (left > 0) " (" + NoticeLogic.timeLeftPhrase(left.toLong(), Lang.current) + ")" else "")

        val plan = NoticeLogic.plan(message, left, Lang.current, again)
        if (plan.isEmpty()) return
        noticeJob = scope.launch {
            var elapsed = 0
            for ((at, line) in plan) {
                delay((at - elapsed) * 1000L)
                elapsed = at
                // Only when the server is running: sending a command would otherwise start its service.
                if (ServerService.isAlive(this@TunnelService)) {
                    runCatching { ServerService.send(this@TunnelService, ServerService.ACTION_COMMAND) { putExtra(ServerService.EXTRA_LINE, "say $line") } }
                }
            }
        }
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
                                t("VoxelPort has used {used} of today's {limit} on mobile data.", "used" to DataLogic.format(use.mobile), "limit" to t(DataLogic.limitLabel(limit))) + " " +
                                    t("Sharing stops at the limit. Connect to Wi‑Fi to keep your server public."),
                            )
                        // With the app closed, the notification is where you see who is playing, the address to share, and what hosting costs.
                        if (++ticks % 3 == 0) {
                            val now = _state.value
                            val address = now.address.ifEmpty { if (now.port > 0) "${now.host.ifEmpty { "play.voxelport.in" }}:${now.port}" else "" }
                            val players = runCatching { JSONObject(Mobile.pingLocal(25565)).optInt("players", -1) }.getOrDefault(-1)
                            val parts = listOfNotNull(
                                if (players >= 0) t("{n} players online", "n" to players) else null,
                                address.ifEmpty { null },
                                if (net.metered) t("{used} mobile data today", "used" to DataLogic.format(use.mobile)) else null,
                            )
                            if (address.isNotEmpty()) Notify.update(this@TunnelService, "Minecraft server is public", parts.joinToString(" · "))
                        }
                    }
                    DataLogic.HostCheck.OVER_LIMIT -> {
                        Notify.alert(
                            this@TunnelService, "Stopped sharing your server",
                            t("Today's mobile-data limit ({limit}) is used up, so your server is no longer public.", "limit" to t(DataLogic.limitLabel(limit))) + " " +
                                t("It is still running on your phone. Connect to Wi‑Fi to make it public again."),
                        )
                        stopSharing(); return@launch
                    }
                    DataLogic.HostCheck.WIFI_ONLY -> {
                        Notify.alert(
                            this@TunnelService, "Stopped sharing your server",
                            t("Your phone is on mobile data and VoxelPort is set to Wi‑Fi only, so your server is no longer public.") + " " +
                                t("It is still running on your phone. Connect to Wi‑Fi to make it public again."),
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
        noticeJob?.cancel()
        noticeJob = null
        lastSaid = null
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
