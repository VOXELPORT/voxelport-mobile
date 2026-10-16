package com.voxelport.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import kotlinx.coroutines.delay
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs the Minecraft server. Lives in its own ":jvm" process: Java runs inside
 * this process, and when the server stops (System.exit) the process ends with it.
 */
class ServerService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var schedule: BackupSchedule? = null
    private var backupRunner: BackupRunner? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Lang.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getIntExtra(EXTRA_RAM_MB, 2048))
            ACTION_COMMAND -> intent.getStringExtra(EXTRA_LINE)?.let { if (running) Jvm.command(it) }
            ACTION_STOP -> stop()
            ACTION_BACKUP -> backupNow()
        }
        return START_NOT_STICKY
    }

    private fun start(ramMb: Int) {
        Notify.foreground(this, "Minecraft server running", "Tap to open VoxelPort")
        if (running) return
        running = true
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoxelPort:server").apply { acquire() }
        val f = Files(this)
        f.tmp.mkdirs()
        val device = deviceOf(this)
        val prefs = Prefs(this)
        val profile = prefs.profile
        val saver = prefs.dataSaver
        runCatching { f.log.delete() } // so "the server is ready" can only come from this run
        val place = BackupPlace.of(f)
        val runner = BackupRunner(place, appBuild(this)).also { backupRunner = it }
        schedule = BackupSchedule(place, runner, { Jvm.command(it) }).also { it.start() }
        thread(name = "minecraft-server") {
            // Fit the server to this phone (view distance, mob limits, threads) and pick JVM flags for it.
            // Data saver also shrinks the world sent to each player, so hosting uses less data.
            val level = Tuning.resolve(profile, device)
            runCatching { Tuning.prepare(f.server, level, saver) }
            val args = (
                Tuning.jvmFlags(level, ramMb, device) + listOf(
                    "-Djava.io.tmpdir=${f.tmp.path}",
                    "-Duser.home=${f.server.path}",
                    "-jar", f.jar.path, "--nogui",
                )
            ).toTypedArray()
            val rc = Jvm.launch(f.jre.path, f.server.path, args, f.log.path)
            // Reached only if Java returned instead of exiting the process.
            File(f.log.path).appendText("\n[VoxelPort] Server stopped (code $rc).\n")
            Process.killProcess(Process.myPid())
        }
    }

    private fun stop() {
        TunnelService.send(this, TunnelService.ACTION_STOP) // no server, nothing to make public
        if (!running) {
            stopSelf()
            Process.killProcess(Process.myPid())
            return
        }
        // One last backup first (only if the world changed since the last), then the server saves and exits by itself.
        thread(name = "vp-stop") {
            runCatching { schedule?.backupBeforeStop() }
            schedule?.stop()
            Jvm.command("stop")
            // Paper saves the world and exits by itself; this is the backstop.
            Thread.sleep(45_000)
            Process.killProcess(Process.myPid())
        }
    }

    /** "Back up now" from the app, while the server is running: the server is told to save, then the world is copied. */
    private fun backupNow() {
        val runner = backupRunner
        // Only once the server is up: before that it cannot be asked to save.
        if (!running || runner == null || schedule?.ready != true) return
        thread(name = "vp-backup-now") { runner.run("manual", live = true, command = { Jvm.command(it) }) }
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_COMMAND = "command"
        const val ACTION_STOP = "stop"
        const val ACTION_BACKUP = "backup"
        const val EXTRA_RAM_MB = "ram"
        const val EXTRA_LINE = "line"

        @Volatile private var running = false

        fun send(ctx: Context, action: String, extras: Intent.() -> Unit = {}) {
            val i = Intent(ctx, ServerService::class.java).setAction(action).apply(extras)
            if (action == ACTION_START) ctx.startForegroundService(i) else ctx.startService(i)
        }

        /** The app's build number, written into each backup so a restore can tell which app made it. */
        fun appBuild(ctx: Context): String = Updater.installedCode(ctx).toString()

        /**
         * Waits for the server process to end after a stop was asked for. The server may make one last backup before it
         * stops, so the wait starts over while a backup is working (it gives up after a minute of nothing happening).
         */
        suspend fun awaitStopped(ctx: Context) {
            val backups = BackupRunner(BackupPlace.of(Files(ctx)), appBuild(ctx))
            var n = 0
            while (isAlive(ctx) && n < 150) {
                delay(400)
                if (backups.isBusy()) n = 0 else n++
            }
        }

        /** True while the :jvm process (and so the server) is alive. */
        fun isAlive(ctx: Context): Boolean {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            return am.runningAppProcesses?.any { it.processName.endsWith(":jvm") } == true
        }
    }
}
