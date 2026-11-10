package com.voxelport.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.voxelport.go.mobile.Mobile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.RandomAccessFile

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        // First: it records whether this install already had a device token, before anything creates one.
        AppAccount.init(applicationContext)
        DataMeter.init(applicationContext)
        setContent { VpTheme { Root() } }
    }
}

/**
 * Everyone must be in the VoxelPort Discord to host publicly (the same rule as the desktop app). Whoever is not
 * allowed sees the full-page sign-in once the accounts service has answered; everyone else gets the app.
 */
@Composable
private fun Root() {
    val ctx = LocalContext.current
    val acct by AppAccount.state.collectAsStateWithLifecycle()
    val tunnel by TunnelService.state.collectAsStateWithLifecycle()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // Ask the accounts service when the app opens and each time it comes back to the front (at most every 5 minutes).
    LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { AppAccount.refresh(ctx) } }
    val gated = !acct.gate.allowed && acct.reachable
    // The server keeps running, but it cannot stay public once the person is no longer allowed to host publicly.
    LaunchedEffect(gated, tunnel.on) { if (gated && tunnel.on) TunnelService.send(ctx, TunnelService.ACTION_STOP) }
    if (gated) DiscordGateScreen(
        acct, { AppAccount.signIn(ctx) }, { AppAccount.cancelSignIn() }, { AppAccount.openInvite(ctx) }, { AppAccount.refresh(ctx, force = true) },
    ) else App()
}

@SuppressLint("BatteryLife")
@Composable
private fun App() {
    val ctx = LocalContext.current
    val files = remember { Files(ctx) }
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val tunnel by TunnelService.state.collectAsStateWithLifecycle()
    val update by Updater.state.collectAsStateWithLifecycle()
    val acct by AppAccount.state.collectAsStateWithLifecycle()
    // Look for an app update when the app opens (at most every 6 hours; the footer button forces it).
    LaunchedEffect(Unit) { Updater.check(ctx) }

    // Data use: which connection we're on, what was used today, and the user's limit.
    val net by DataMeter.net.collectAsStateWithLifecycle()
    val usage by DataMeter.usage.collectAsStateWithLifecycle()
    var limitMb by remember { mutableIntStateOf(prefs.dataLimitMb) }
    var saver by remember { mutableStateOf(prefs.dataSaver) }
    var ask by remember { mutableStateOf<DataAsk?>(null) }
    var publicAsk by remember { mutableStateOf(false) }
    var saverWithPublic by remember { mutableStateOf(true) }
    var identityOpen by remember { mutableStateOf(false) }
    var deleteAsk by remember { mutableStateOf(false) }
    val hostCheck = DataLogic.hostCheck(limitMb, usage.bonusMb, usage.mobile, net.metered)

    var busy by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf(files.jreReady && files.serverReady) }
    var version by remember { mutableStateOf(files.version) }
    var alive by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var log by remember { mutableStateOf(listOf<String>()) }
    var eula by remember { mutableStateOf(prefs.eula) }
    var bedrock by remember { mutableStateOf(prefs.bedrock) }
    var screen by remember { mutableStateOf("home") }
    var versions by remember { mutableStateOf<List<String>>(emptyList()) }
    var pickVersion by remember { mutableStateOf(prefs.version.ifEmpty { version }) }
    var showVersions by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(if (files.serverReady) files.type else prefs.type) }
    // What is installed right now (read from disk once, not on every recomposition).
    var installedType by remember { mutableStateOf(files.type) }
    var setupOpen by remember { mutableStateOf(!(files.jreReady && files.serverReady)) }
    // The Play flow, who is online, the address test and a hidden fix card.
    var playing by remember { mutableStateOf(false) }
    var playStep by remember { mutableStateOf("") }
    var online by remember { mutableStateOf(listOf<String>()) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var fixHidden by remember { mutableStateOf("") }

    // Custom address box.
    var nameInput by remember { mutableStateOf(prefs.claimName) }
    var editingName by remember { mutableStateOf(false) }
    var claiming by remember { mutableStateOf(false) }
    // A result arrived (claimed or refused): stop spinning, and close the box once we own a name.
    LaunchedEffect(tunnel.name, tunnel.nameError) {
        if (tunnel.nameError.isNotEmpty() || tunnel.name.isNotEmpty()) claiming = false
        if (tunnel.name.isNotEmpty()) { editingName = false; nameInput = tunnel.name }
    }
    // "Copied" note under the invite buttons.
    var inviteCopied by remember { mutableStateOf(false) }
    LaunchedEffect(inviteCopied) { if (inviteCopied) { delay(3500); inviteCopied = false } }
    // If the relay never answers, don't spin forever.
    LaunchedEffect(claiming) { if (claiming) { delay(15_000); claiming = false } }

    val device = remember { deviceOf(ctx) }
    val totalMb = device.ramMb
    val recommendedMb = Tuning.recommendedRamMb(device)
    // RAM in 256 MB steps, up to half the phone's memory.
    val ramSteps = (Tuning.maxRamMb(device) - 1024) / 256
    val ramMax = 1024f + ramSteps * 256f
    var ram by remember { mutableFloatStateOf(((prefs.ramMb.toFloat().coerceIn(1024f, ramMax) - 1024f) / 256f).toInt() * 256f + 1024f) }

    // Poll the server process, its status (Server List Ping) and the console log.
    // Only while the app is on screen: the server runs in its own process, so nothing is lost when
    // we stop looking, and the phone isn't woken every 1.5 s in the background.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var hot by remember { mutableStateOf(false) }
    var battery by remember { mutableStateOf(100 to true) } // (percent, plugged in)
    LaunchedEffect(Unit) {
        var pos = 0L
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val a = withContext(Dispatchers.IO) { DataMeter.sample(); ServerService.isAlive(ctx) }
                alive = a
                status = if (a) withContext(Dispatchers.IO) { Mobile.pingLocal(25565).takeIf { it.isNotEmpty() }?.let { JSONObject(it) } } else null
                val (lines, p) = withContext(Dispatchers.IO) { tail(files.log.path, pos) }
                if (p < pos) { log = emptyList(); online = emptyList() }
                pos = p
                if (lines.isNotEmpty()) { log = (log + lines).takeLast(400); online = Players.apply(online, lines) }
                if (!a) online = emptyList()
                if (a) withContext(Dispatchers.IO) {
                    hot = Build.VERSION.SDK_INT >= 29 && ctx.getSystemService(PowerManager::class.java).currentThermalStatus >= 2 // moderate or worse
                    val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                    battery = (if (lvl >= 0 && scale > 0) lvl * 100 / scale else 100) to ((b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
                } else hot = false
                delay(if (a) 1500 else 3000)
            }
        }
    }

    fun install() = scope.launch {
        error = ""
        var bedrockProblem: String? = null
        try {
            withContext(Dispatchers.IO) {
                // Java unpacks to ~200 MB, Paper + the vanilla jar ~120 MB, plus the world: fail early, not half-way.
                if (!files.jreReady && ctx.filesDir.usableSpace < 1_200_000_000L)
                    error(t("Not enough free storage: VoxelPort needs about 1.2 GB free (this phone has {have} MB). Free some space and try again.", "have" to ctx.filesDir.usableSpace / 1_000_000))
                Setup.installJre(ctx, files) { busy = it }
                val v = prefs.version.ifEmpty { Setup.versionsFor(type, 1).firstOrNull() ?: error(t("No {type} versions found", "type" to type)) }
                val serverChanged = !files.serverReady || version != v || files.type != type
                if (serverChanged) {
                    if (type == "fabric") Setup.installFabric(files, v) { busy = it } else Setup.installPaper(files, v) { busy = it }
                }
                // On mobile data, don't fetch Geyser and Floodgate again when they're already there and the server didn't change.
                val keepBedrock = bedrock && !serverChanged && DataMeter.net.value.metered && files.bedrockComplete
                if (!keepBedrock) Setup.removeBedrock(files)
                if (bedrock && !keepBedrock) {
                    // The Bedrock add-ons come from another site than the server itself. If they cannot be fetched, the server
                    // is still worth having: Java players do not need them, so the install goes on and says what is missing.
                    try {
                        if (type == "fabric") {
                            // Bedrock on Fabric: Geyser + Floodgate (and Fabric API) from Modrinth.
                            for ((id, name) in listOf("P7dR8mSH" to "Fabric API", "wKkoqHrH" to "Geyser", "bWrNNfkb" to "Floodgate"))
                                if (!Modrinth.isInstalled(files, id)) Modrinth.install(files, id, name, "fabric", v) { busy = it }
                        } else Setup.installBedrock(files) { busy = it }
                    } catch (e: Exception) {
                        Setup.removeBedrock(files) // never leave half of Geyser and Floodgate behind
                        bedrockProblem = e.message ?: e.toString()
                    }
                }
                Setup.writeServerFiles(files, Tuning.resolve(prefs.profile, device), prefs.dataSaver)
                version = v
                pickVersion = v
            }
            installedType = files.type
            installed = true
            setupOpen = false
            bedrockProblem?.let {
                error = t("The server is ready, but Bedrock support couldn't be added: {detail} Java players can still join. Open Setup and install again to retry.", "detail" to it)
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        busy = ""
    }

    // Deletes the server (world, plugins, settings) from this phone. Backups, Java, the address and the sign-in stay.
    fun deleteServer() = scope.launch {
        error = ""
        val gone = withContext(Dispatchers.IO) { ServerDelete.delete(ctx.filesDir) }
        if (gone) {
            installed = false; version = ""; installedType = files.type; setupOpen = true
            status = null; log = emptyList(); pickVersion = prefs.version
        } else error = t("Couldn't delete the server files. Try again.")
    }

    // About how much setting up will download (0 = nothing, so there's nothing to ask about).
    fun installMb(): Int {
        val needsJar = !installed || type != installedType || prefs.version.isEmpty() || prefs.version != version
        return when {
            needsJar -> DataLogic.installEstimateMb(type == "fabric", bedrock)
            bedrock && !files.bedrockComplete -> 25
            else -> 0
        }
    }

    // On mobile data, ask before a big download (or refuse it when the user chose Wi-Fi only).
    fun installWithData() {
        val mb = installMb()
        val a = if (mb > 0) downloadAsk(limitMb, net.metered, usage.mobile, "Setting up your server", mb) { install() } else null
        if (a == null) install() else ask = a
    }

    fun startServer() {
        log = emptyList(); online = emptyList(); fixHidden = ""; testResult = null
        ServerService.send(ctx, ServerService.ACTION_START) { putExtra(ServerService.EXTRA_RAM_MB, ram.toInt()) }
    }

    fun askBatteryOptimization() {
        if (!ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }
    }

    fun makePublic() {
        if (!acct.gate.allowed) return // joining the Discord comes first (the tunnel service checks too)
        askBatteryOptimization()
        TunnelService.send(ctx, TunnelService.ACTION_START) { putExtra(TunnelService.EXTRA_BEDROCK, bedrock) }
    }

    // Data saver: a smaller world around each player. server.properties changes at once (like the
    // performance buttons); the rest applies from the next start.
    fun applySaver(on: Boolean) {
        saver = on; prefs.dataSaver = on
        if (installed) runCatching { Tuning.applyProps(files.server, Tuning.resolve(prefs.profile, device), on) }
    }

    // Restart: fully stop the server (and tunnel), wait for the :jvm process to
    // die, start again — and if it was public (or [forcePublic]), make it public again.
    fun restart(forcePublic: Boolean = false) = scope.launch {
        if (!alive) return@launch
        val wasPublic = tunnel.on || forcePublic
        error = ""; busy = "Restarting…"
        ServerService.send(ctx, ServerService.ACTION_STOP)
        // The server makes one last backup before it stops: don't give up while that is working.
        withContext(Dispatchers.IO) { ServerService.awaitStopped(ctx) }
        delay(500)
        startServer()
        if (wasPublic) {
            withContext(Dispatchers.IO) { var n = 0; while (n < 600) { if (ServerService.isAlive(ctx) && Mobile.pingLocal(25565).isNotEmpty()) break; delay(500); n++ } }
            TunnelService.send(ctx, TunnelService.ACTION_START) { putExtra(TunnelService.EXTRA_BEDROCK, bedrock) }
        }
        busy = ""
    }

    // A fix for a problem the console shows (see Fixes).
    fun applyFix(f: Fixes.Fix) {
        fixHidden = f.kind.name
        when (f.kind) {
            Fixes.Kind.OUT_OF_MEMORY -> {
                prefs.profile = "low"
                val lower = Fixes.lowerRam(ram.toInt(), recommendedMb)
                ram = lower.toFloat(); prefs.ramMb = lower
                if (installed) runCatching { Tuning.applyProps(files.server, Tuning.resolve("low", device), saver) }
                if (alive) restart() else startServer()
            }
            Fixes.Kind.PORT_IN_USE -> if (alive) restart() else startServer()
            Fixes.Kind.DISK_FULL -> screen = "files"
            Fixes.Kind.WORLD_BROKEN -> screen = "backups"
        }
    }

    fun testAddress(host: String, port: Int) = scope.launch {
        testing = true; testResult = null
        val r = withContext(Dispatchers.IO) { AddressTest.run(host, port) }
        testResult = AddressTest.explain(r)
        testing = false
    }

    // The Play button: install if needed, start the server, wait for it to be ready, then make it public. One step at a time,
    // each started once; it stops by itself on an error, a refusal, or when everything is online.
    fun play() { error = ""; playStep = ""; playing = true }
    LaunchedEffect(playing, installed, alive, status != null, tunnel.on, busy, ask, publicAsk, error) {
        if (!playing) { playStep = ""; return@LaunchedEffect }
        if (error.isNotEmpty()) { playing = false; return@LaunchedEffect }
        if (busy.isNotEmpty() || ask != null || publicAsk) return@LaunchedEffect
        when {
            !eula -> playing = false
            !installed -> if (playStep != "install") { playStep = "install"; installWithData() } else playing = false // the data question was declined
            !alive -> if (playStep != "start") { playStep = "start"; startServer() }
            status == null -> {} // starting: wait for the console's "Done!"
            tunnel.on -> playing = false
            playStep != "public" -> {
                playStep = "public"
                if (!acct.gate.allowed || hostCheck != DataLogic.HostCheck.OK) playing = false
                else if (net.metered) { saverWithPublic = !saver; publicAsk = true }
                else makePublic()
            }
        }
        return@LaunchedEffect
    }
    // The server crashed while starting (or never came up): stop the flow and say so, instead of waiting for nothing.
    var sawAlive by remember { mutableStateOf(false) }
    LaunchedEffect(playing, playStep, alive) {
        if (!playing || playStep != "start") { sawAlive = false; return@LaunchedEffect }
        if (alive) { sawAlive = true; return@LaunchedEffect }
        if (!sawAlive) delay(45_000)
        if (!alive) {
            playing = false
            error = t("The server stopped before it was ready. Open the console (More) to see why, or try again.")
        }
    }
    // Never stay "working" forever (a slow download can take a while, but not this long).
    LaunchedEffect(playing) { if (playing) { delay(20 * 60_000L); playing = false } }
    LaunchedEffect(tunnel.on) { if (!tunnel.on) testResult = null }
    val phoneReport = remember { PhoneCheck.check(device.ramMb, device.cores, ctx.filesDir.usableSpace, Build.VERSION.SDK_INT) }

    val homeScroll = rememberScrollState()
    val moreScroll = rememberScrollState()
    BackHandler(enabled = screen != "home") { screen = "home" }
    // The bottom bar goes away while the keyboard is up (the screens already make room for it).
    val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val showBar = screen in TABS && !imeUp

    Column(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f).then(if (showBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)) {
    AnimatedContent(
        screen, label = "screen",
        transitionSpec = {
            if (targetState != "home") (slideInHorizontally(tween(220)) { it / 8 } + fadeIn(tween(220))) togetherWith fadeOut(tween(140))
            else fadeIn(tween(220)) togetherWith (slideOutHorizontally(tween(220)) { it / 8 } + fadeOut(tween(160)))
        },
    ) { s ->
        when (s) {
            "files" -> FilesScreen(files.server, alive) { screen = "home" }
            "settings" -> SettingsScreen(files, alive, { screen = "home" }) { restart() }
            "browse" -> BrowseScreen(files, alive, { screen = "home" }) { restart() }
            "support" -> SupportScreen { screen = "home" }
            "backups" -> BackupsScreen(files, alive, status != null) { screen = "home" }
            "console" -> ConsoleScreen(log, alive, { line -> ServerService.send(ctx, ServerService.ACTION_COMMAND) { putExtra(ServerService.EXTRA_LINE, line) } }) { screen = "home" }
            "players" -> PlayersScreen(
                online, status?.optInt("max") ?: 0, alive, tunnel.address.ifEmpty { if (tunnel.port > 0) "${tunnel.host.ifEmpty { "play.voxelport.in" }}:${tunnel.port}" else "" },
                { cmd -> ServerService.send(ctx, ServerService.ACTION_COMMAND) { putExtra(ServerService.EXTRA_LINE, cmd) } },
                { val a = tunnel.address.ifEmpty { "${tunnel.host.ifEmpty { "play.voxelport.in" }}:${tunnel.port}" }; share(ctx, Invite.build(a, version = version, plainAddress = a, bedrockHost = null, bedrockPort = 0)) },
            )
            "more" -> Column(
                Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(moreScroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("MORE", fontSize = 30.sp, fontWeight = FontWeight.Black, color = VP.Ink)
                Panel {
                    PanelTitle("TOOLS")
                    if (installed) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            VpButton("SETTINGS", VP.Card, Modifier.weight(1f)) { screen = "settings" }
                            VpButton("CONSOLE", VP.Card, Modifier.weight(1f)) { screen = "console" }
                        }
                        VpButton("BACKUPS", VP.Card, Modifier.fillMaxWidth()) { screen = "backups" }
                        LastBackupLine(files)
                    } else Text("Set up your server on the Home page first.", color = VP.Muted, fontSize = 14.sp)
                }
                Panel {
                    PanelTitle("SERVER SETUP")
                        LaunchedEffect(type, showVersions) {
                            if (showVersions) { versions = emptyList(); runCatching { versions = withContext(Dispatchers.IO) { Setup.versionsFor(type, 12) } } }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(eula, { eula = it; prefs.eula = it }, enabled = !alive)
                                Text("I agree to the Minecraft EULA", fontSize = 14.sp, modifier = Modifier.weight(1f))
                                TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aka.ms/MinecraftEULA"))) }) { Text("Read") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(bedrock, { bedrock = it; prefs.bedrock = it }, enabled = !alive && busy.isEmpty())
                                Text("Let Bedrock players join (phones, consoles)", fontSize = 14.sp, modifier = Modifier.weight(1f))
                            }
                            Text(
                                t("RAM for the server: {ram} GB (phone has {total} GB)", "ram" to "%.2f".format(ram / 1024f).trimEnd('0').trimEnd('.'), "total" to "%.1f".format(totalMb / 1024f)),
                                fontSize = 14.sp,
                            )
                            Text(
                                if (ram > recommendedMb * 3 / 2) t("More than this phone can spare: Android may close the server. Recommended: {gb} GB.", "gb" to "%.1f".format(recommendedMb / 1024f))
                                else t("Recommended for this phone: {gb} GB.", "gb" to "%.1f".format(recommendedMb / 1024f)),
                                fontSize = 12.sp, color = if (ram > recommendedMb * 3 / 2) VP.Red else VP.Muted,
                            )
                            if (ramSteps >= 1) Slider(
                                ram, { ram = it }, onValueChangeFinished = { prefs.ramMb = ram.toInt() },
                                valueRange = 1024f..ramMax, steps = (ramSteps - 1).coerceAtLeast(0), enabled = !alive,
                            )

                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Minecraft version", fontSize = 11.sp, color = VP.Muted)
                                    Text("${version.ifEmpty { "—" }} · ${installedType.replaceFirstChar { it.uppercase() }}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                                TextButton({ showVersions = !showVersions }, enabled = !alive && busy.isEmpty()) { Text(if (showVersions) "Hide" else "Change") }
                            }
                            AnimatedVisibility(showVersions) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Server software", fontSize = 12.sp, color = VP.Muted)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for ((t, label) in listOf("paper" to "Paper · plugins", "fabric" to "Fabric · mods")) {
                                            Box(
                                                Modifier.border(2.dp, VP.Ink).background(if (type == t) VP.Gold else VP.Card)
                                                    .clickable { if (type != t) { type = t; prefs.type = t; prefs.version = ""; pickVersion = "" } }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                            ) { Text(label, fontSize = 13.sp, fontWeight = if (type == t) FontWeight.Black else FontWeight.Normal, color = VP.Ink) }
                                        }
                                    }
                                    if (versions.isEmpty()) Text("Loading versions…", color = VP.Muted, fontSize = 13.sp)
                                    FlowRowChips(versions, pickVersion) { v -> pickVersion = v; prefs.version = v }
                                    val changing = (pickVersion.isNotEmpty() && pickVersion != version) || (type != installedType && installed)
                                    if (changing) Text(
                                        t("Tap UPDATE / REPAIR to install {type} {version}.", "type" to type.replaceFirstChar { it.uppercase() }, "version" to pickVersion.ifEmpty { t("(latest)") }) +
                                            if (type != installedType && installed) " " + t("Your world is kept; plugins and mods don't carry over.") else "",
                                        color = VP.Ink, fontSize = 13.sp,
                                    )
                                }
                            }
                            if (installed) VpButton("UPDATE / REPAIR", VP.Gold, Modifier.fillMaxWidth(), enabled = busy.isEmpty() && !alive && eula) { installWithData() }
                            if (installed) {
                                val cannotDelete = ServerDelete.blocker(alive, tunnel.on) != null
                                VpButton("DELETE SERVER", VP.Red, Modifier.fillMaxWidth(), enabled = busy.isEmpty() && !cannotDelete) { deleteAsk = true }
                                if (cannotDelete) Text("Stop the server first to delete it.", color = VP.Muted, fontSize = 12.sp)
                            }
                        }
                }
                if (installed) AccountsPanel(files, alive) { restart() }
                DataPanel(
                    net, usage, limitMb, { limitMb = it; prefs.dataLimitMb = it },
                    saver, { applySaver(it) }, installed, alive, { restart() },
                )

                DiscordPanel(acct, { AppAccount.signIn(ctx) }, { AppAccount.cancelSignIn() }, { AppAccount.signOut(ctx) })
                AddressBackupPanel(acct, tunnel.on)
                TextButton({ identityOpen = true }) { Text("Back up or restore your address", fontSize = 13.sp) }

                SupportPanel { screen = "support" }

                // ── Version + manual update check ──
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t("VoxelPort {version} · build {build}", "version" to Updater.installedName(ctx), "build" to Updater.installedCode(ctx)),
                        color = VP.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        { Updater.check(ctx, force = true) },
                        enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading && update !is UpdateState.Installing,
                    ) { Text(if (update is UpdateState.Checking) "Checking…" else "Check for updates", fontSize = 12.sp) }
                }
                Spacer(Modifier.height(12.dp))
            }
            else -> Column(
                Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(homeScroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                UpdateBanner(update, alive)
                tunnel.announcement?.let { NoticeBanner(it, alive) }
                MobileDataBanner(net)
                DiscordBanner(acct, { AppAccount.signIn(ctx) }, { AppAccount.cancelSignIn() }, { AppAccount.openInvite(ctx) }, { AppAccount.refresh(ctx, force = true) })

                // ── Header ──
                val st = status
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(ImageBitmap.imageResource(R.drawable.vp_logo), null, Modifier.size(46.dp), filterQuality = FilterQuality.None)
                    Column(Modifier.weight(1f)) {
                        Text("VOXELPORT", fontSize = 26.sp, fontWeight = FontWeight.Black, color = VP.Ink)
                        Text("Host Minecraft from your phone", color = VP.Muted, fontSize = 13.sp)
                    }
                    when {
                        st != null -> StatusPill("ONLINE", VP.Grass)
                        alive -> StatusPill("STARTING", VP.Gold)
                        busy.isNotEmpty() -> StatusPill("WORKING", VP.Gold)
                        else -> StatusPill("STOPPED", VP.Muted)
                    }
                }

                if (!installed) PhoneCheckCard(phoneReport)
                val fixNow = remember(log) { Fixes.detect(log) }
                if (fixNow != null && status == null && !playing && fixNow.kind.name != fixHidden) FixCard(fixNow, { applyFix(fixNow) }, { fixHidden = fixNow.kind.name })

                // ── Server: one big button ──
                Panel {
                    PanelTitle(if (installed) "YOUR SERVER" else "SET UP YOUR SERVER")
                    Text(
                        if (installed) "${type.replaceFirstChar { it.uppercase() }} $version · Java 25"
                        else t(
                            "Sets up {type} (Java 25 is already inside the app). It downloads about {mb} MB: use Wi‑Fi if you can.",
                            "type" to type.replaceFirstChar { it.uppercase() }, "mb" to DataLogic.installEstimateMb(type == "fabric", bedrock),
                        ),
                        color = VP.Muted, fontSize = 14.sp,
                    )
                    if (busy.isNotEmpty()) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = VP.Grass, trackColor = TrackColor)
                        Text(busy, color = VP.Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    if (error.isNotEmpty()) Text(error, color = VP.Red, fontSize = 14.sp)

                    if (!installed) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(eula, { eula = it; prefs.eula = it }, enabled = !alive)
                            Text("I agree to the Minecraft EULA", fontSize = 14.sp, modifier = Modifier.weight(1f))
                            TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aka.ms/MinecraftEULA"))) }) { Text("Read") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(bedrock, { bedrock = it; prefs.bedrock = it }, enabled = !alive && busy.isEmpty())
                            Text("Let Bedrock players join (phones, consoles)", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        }
                    }

                    val st2 = status
                    PlaySteps(when { !installed -> 0; !alive || st2 == null -> 1; !tunnel.on -> 2; else -> 3 })
                    when {
                        playing -> {
                            VpButton(
                                when { !installed -> "INSTALLING…"; !alive || st2 == null -> "STARTING…"; else -> "GOING PUBLIC…" },
                                VP.Gold, Modifier.fillMaxWidth(), enabled = false,
                            ) {}
                            TextButton({ playing = false }) { Text("Cancel") }
                        }
                        !alive -> {
                            VpButton(if (installed) "PLAY" else "SET UP & PLAY", VP.Grass, Modifier.fillMaxWidth(), enabled = eula && busy.isEmpty()) { play() }
                            if (!installed && !eula) Text("Tick the EULA above to continue.", color = VP.Muted, fontSize = 12.sp)
                            else if (installed) Text("Starts the server and puts it online for your friends.", color = VP.Muted, fontSize = 12.sp)
                        }
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            VpButton("STOP", VP.Red, Modifier.weight(1f), enabled = busy.isEmpty()) {
                                playing = false
                                ServerService.send(ctx, ServerService.ACTION_STOP)
                                TunnelService.send(ctx, TunnelService.ACTION_STOP)
                            }
                            VpButton("RESTART", VP.Gold, Modifier.weight(1f), enabled = busy.isEmpty()) { restart() }
                        }
                    }
                    Text(
                        when {
                            st != null -> t("Online · {version} · {players}/{max} players", "version" to st.optString("version"), "players" to st.optInt("players"), "max" to st.optInt("max"))
                            alive -> "Starting… the first start on a phone can take a few minutes."
                            else -> "Stopped"
                        },
                        color = if (st != null) VP.GrassDark else VP.Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    )

                    if (installed && totalMb < 3800) Text(t("This phone has {ram} GB of RAM. Servers run best with 4 GB or more, so expect lag with more than a couple of players.", "ram" to "%.1f".format(totalMb / 1024f)), color = VP.Muted, fontSize = 13.sp)
                    if (alive && hot) Text("Your phone is hot and may slow the server down. Let it cool, or choose Low-end in Settings → Performance.", color = VP.Red, fontSize = 13.sp)
                    if (alive && !battery.second && battery.first <= 30) Text(t("Battery {percent}% and not charging. Plug in to keep the server running.", "percent" to battery.first), color = VP.Red, fontSize = 13.sp)

                    var guideDone by remember { mutableStateOf(prefs.batteryGuideDone) }
                    if (alive && !guideDone) {
                        BatteryGuideCard(
                            BatteryGuide.detect(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty()),
                            onOpen = { BatteryGuide.open(ctx, BatteryGuide.detect(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty())) },
                            onDismiss = { prefs.batteryGuideDone = true; guideDone = true },
                        )
                    }
                }

                if (installed && (alive || tunnel.on)) {
                // ── Public address ──
                Panel {
                    PanelTitle("PUBLIC ADDRESS")
                    if (!tunnel.on) {
                        Text("Friends anywhere can join, even on Jio/Airtel or mobile data. No port forwarding.", color = VP.Muted, fontSize = 14.sp)
                        VpButton("MAKE PUBLIC", VP.Diamond, Modifier.fillMaxWidth(), enabled = alive && hostCheck == DataLogic.HostCheck.OK && acct.gate.allowed) {
                            // On mobile data every player's game uses your plan: say so first.
                            if (net.metered) { saverWithPublic = !saver; publicAsk = true } else makePublic()
                        }
                        if (!alive) Text("Start the server first.", color = VP.Muted, fontSize = 12.sp)
                        when (hostCheck) {
                            DataLogic.HostCheck.WIFI_ONLY -> Text(
                                "You're on mobile data and VoxelPort is set to Wi‑Fi only. Connect to Wi‑Fi, or change the limit under Data use below.",
                                color = VP.Red, fontSize = 13.sp,
                            )
                            DataLogic.HostCheck.OVER_LIMIT -> {
                                Text(
                                    t("Today's mobile data limit ({limit}) is used up. Connect to Wi‑Fi to share your server, or add more for today.", "limit" to t(DataLogic.limitLabel(limitMb))),
                                    color = VP.Red, fontSize = 13.sp,
                                )
                                VpButton(t("ADD {mb} MB FOR TODAY", "mb" to DataLogic.BONUS_MB), VP.Gold, Modifier.fillMaxWidth()) { DataMeter.addBonus() }
                            }
                            DataLogic.HostCheck.OK -> if (net.metered) Text("You're on mobile data: Wi‑Fi is better for hosting.", color = VP.Muted, fontSize = 12.sp)
                        }
                    } else {
                        val publicHost = tunnel.host.ifEmpty { "play.voxelport.in" }
                        val plain = if (tunnel.port > 0) "$publicHost:${tunnel.port}" else ""
                        val main = tunnel.address.ifEmpty { plain }
                        Text(
                            if (tunnel.port > 0) t("Public") + (if (tunnel.pingMs > 0) t(" · {ms} ms to relay", "ms" to tunnel.pingMs) else "") else tunnel.status.ifEmpty { "Connecting…" },
                            color = if (tunnel.port > 0) VP.GrassDark else VP.Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        )
                        if (tunnel.port == 0 && tunnel.error.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth(), color = VP.Diamond, trackColor = TrackColor)
                        if (main.isNotEmpty()) {
                            Text("JAVA", fontSize = 11.sp, color = VP.Muted)
                            Text(main, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            val bedrockHost = if (tunnel.name.isNotEmpty()) "${tunnel.name}.voxelport.in" else publicHost
                            // The ready-to-send message: address, how Bedrock players join, and where the free hosting came from.
                            val invite = Invite.build(
                                main, version = version, plainAddress = plain,
                                bedrockHost = if (bedrock) bedrockHost else null, bedrockPort = if (bedrock) tunnel.port else 0,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                VpButton("COPY", VP.Gold, Modifier.weight(1f)) { copy(ctx, main) }
                                VpButton("SHARE", VP.Card, Modifier.weight(1f)) { share(ctx, invite) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton({ copy(ctx, invite); inviteCopied = true }) { Text("Copy invite message", fontSize = 13.sp) }
                            }
                            if (inviteCopied) Text("Copied. Paste it into WhatsApp or Discord.", color = VP.GrassDark, fontSize = 12.sp)
                            if (tunnel.address.isNotEmpty() && plain.isNotEmpty()) Text(t("Also works: {address}", "address" to plain), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = VP.Muted)
                            if (bedrock) {
                                Text("BEDROCK", fontSize = 11.sp, color = VP.Muted)
                                Text(t("Server {host} · Port {port}", "host" to bedrockHost, "port" to tunnel.port), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                            }

                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            if (tunnel.name.isNotEmpty() && !editingName) {
                                // Already claimed: show it, like the desktop app, instead of the claim box.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Your custom address", fontSize = 11.sp, color = VP.Muted)
                                        Text("${tunnel.name}.voxelport.in", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    }
                                    TextButton({ nameInput = tunnel.name; editingName = true; TunnelService.clearNameError() }) { Text("Change") }
                                    TextButton({ TunnelService.send(ctx, TunnelService.ACTION_RELEASE); nameInput = "" }) { Text("Remove", color = VP.Red) }
                                }
                            } else {
                                Text(if (tunnel.name.isEmpty()) "Get a custom address" else "Change your address", fontSize = 12.sp, color = VP.Muted)
                                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        nameInput, { nameInput = it.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' }.take(20) },
                                        singleLine = true, modifier = Modifier.weight(1f), suffix = { Text(".voxelport.in", fontSize = 13.sp) },
                                        isError = tunnel.nameError.isNotEmpty(),
                                    )
                                    VpButton(if (claiming) "…" else "CLAIM", VP.Grass, Modifier.padding(top = 4.dp), enabled = nameInput.length >= 3 && !claiming) {
                                        if (nameInput == tunnel.name) editingName = false
                                        else { claiming = true; TunnelService.clearNameError(); TunnelService.send(ctx, TunnelService.ACTION_CLAIM) { putExtra(TunnelService.EXTRA_NAME, nameInput) } }
                                    }
                                }
                                if (tunnel.nameError.isNotEmpty()) Text(tunnel.nameError, color = VP.Red, fontSize = 13.sp)
                                else Text("A custom name (friends join without a port number) is a supporter perk. 3–20 letters, numbers or dashes.", color = VP.Muted, fontSize = 12.sp)
                                if (editingName && tunnel.name.isNotEmpty()) TextButton({ editingName = false; nameInput = tunnel.name; TunnelService.clearNameError() }) { Text("Cancel") }
                            }
                        }
                        if (tunnel.notice.isNotEmpty()) Text(tunnel.notice, color = VP.Muted, fontSize = 12.sp)
                        if (tunnel.error.isNotEmpty()) Text(tunnel.error, color = VP.Red, fontSize = 13.sp)
                        Text(
                            t("Data this session: {used}", "used" to DataLogic.format(usage.session)) +
                                if (net.metered) t(" · on mobile data, {mobile} today", "mobile" to DataLogic.format(usage.mobile)) else t(" · on Wi‑Fi"),
                            color = VP.Muted, fontSize = 12.sp,
                        )
                        AddressTestRow(testing, testResult, tunnel.port > 0) { testAddress(publicHost, tunnel.port) }
                        VpButton("STOP PUBLIC", VP.Card, Modifier.fillMaxWidth()) { TunnelService.send(ctx, TunnelService.ACTION_STOP) }
                    }
                    TextButton({ identityOpen = true }) { Text("Back up or restore your address", fontSize = 13.sp) }
                }
                }

                if (installed) {
                // ── Console preview (the full console is its own screen) ──
                Panel {
                    PanelTitle("CONSOLE") { TextButton({ screen = "console" }) { Text("Open") } }
                    Column(Modifier.fillMaxWidth().background(VP.Console).padding(10.dp).heightIn(min = 56.dp)) {
                        if (log.isEmpty()) Text("No output yet.", color = Color(0xFF8F8A82), fontSize = 12.sp)
                        for (line in log.takeLast(6)) Text(line, color = Color(0xFFE6E0D6), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, lineHeight = 13.sp, maxLines = 2)
                    }
                }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    }
    if (showBar) VpNavBar(screen, if (type == "fabric") "Mods" else "Plugins") { screen = it }
    }

    ask?.let { a -> DataAskDialog(a) { ask = null } }
    if (deleteAsk) {
        val hasBackup = remember { Backups.list(files.backups).isNotEmpty() }
        AlertDialog(
            onDismissRequest = { deleteAsk = false },
            title = { Text("Delete this server?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This permanently deletes this server's world, plugins and settings from your phone. Your backups, your address and your Discord sign-in are kept. This can't be undone.", fontSize = 14.sp)
                    if (!hasBackup) Text("You have no backups of this world yet. Make one first if you want to keep it.", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = VP.Red)
                }
            },
            confirmButton = { TextButton({ deleteAsk = false; deleteServer() }) { Text("Delete", color = VP.Red) } },
            dismissButton = { TextButton({ deleteAsk = false }) { Text("Cancel") } },
        )
    }
    if (identityOpen) IdentityDialog(tunnel.on) { identityOpen = false }
    if (publicAsk) {
        AlertDialog(
            onDismissRequest = { publicAsk = false },
            title = { Text("You're on mobile data") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t("Everything your friends do on your server goes through this phone's connection: {amount}.", "amount" to DataLogic.perPlayerText()))
                    Text(
                        if (limitMb > 0) t(
                            "VoxelPort has used {used} on mobile data today, of your {limit} limit, and stops sharing when it's reached.",
                            "used" to DataLogic.format(usage.mobile), "limit" to t(DataLogic.limitLabel(limitMb)),
                        ) else t("VoxelPort has used {used} on mobile data today.", "used" to DataLogic.format(usage.mobile)),
                    )
                    Text("Wi‑Fi is much better for hosting: it's free and doesn't touch your plan.", fontWeight = FontWeight.Bold)
                    if (!saver) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(saverWithPublic, { saverWithPublic = it })
                        Text("Turn on Data saver (restarts the server first)", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {
                TextButton({
                    publicAsk = false
                    if (!saver && saverWithPublic) { applySaver(true); askBatteryOptimization(); restart(forcePublic = true) } else makePublic()
                }) { Text("Make public") }
            },
            dismissButton = { TextButton({ publicAsk = false; openWifiSettings(ctx) }) { Text("Wi‑Fi settings") } },
        )
    }
}

private val TrackColor = VP.Paper2

/** Wrapping grid of selectable version chips (rows of 3). */
@Composable
private fun FlowRowChips(items: List<String>, selected: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { v ->
                    val sel = v == selected
                    Box(
                        Modifier.border(2.dp, VP.Ink).background(if (sel) VP.Gold else VP.Card)
                            .clickable { onPick(v) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { Text(v, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Black else FontWeight.Normal, color = VP.Ink) }
                }
            }
        }
    }
}

private fun copy(ctx: android.content.Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("VoxelPort address", text))
}

/** Opens Android's share sheet with [message] (the invite: address, how to join, where it is hosted). */
private fun share(ctx: android.content.Context, message: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
    ctx.startActivity(Intent.createChooser(send, t("Share server address")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** New complete lines of [path] after byte offset [from]; returns them and the new offset. */
private fun tail(path: String, from: Long): Pair<List<String>, Long> {
    val f = java.io.File(path)
    if (!f.isFile) return emptyList<String>() to 0L
    val len = f.length()
    var start = if (len < from) 0L else from
    if (len - start > 256_000) start = len - 256_000
    if (len == start) return emptyList<String>() to start
    RandomAccessFile(f, "r").use { r ->
        r.seek(start)
        val buf = ByteArray((len - start).toInt())
        r.readFully(buf)
        val text = String(buf, Charsets.UTF_8)
        val end = text.lastIndexOf('\n')
        if (end < 0) return emptyList<String>() to start
        val lines = text.substring(0, end).split('\n').map { it.replace(Regex("\u001B\\[[;\\d]*m"), "") }
        return lines to start + text.substring(0, end + 1).toByteArray(Charsets.UTF_8).size
    }
}
