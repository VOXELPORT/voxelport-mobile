package com.voxelport.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxelport.go.mobile.Mobile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.RandomAccessFile

private val Paper = Color(0xFFF3EBDC)
private val Card = Color(0xFFFFFBF2)
private val Ink = Color(0xFF15120F)
private val Muted = Color(0xFF6B6052)
private val Red = Color(0xFFC8262B)
private val Grass = Color(0xFF5FAE3B)
private val Gold = Color(0xFFF6CB2F)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { MaterialTheme { App() } }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun App() {
    val ctx = LocalContext.current
    val files = remember { Files(ctx) }
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val tunnel by TunnelService.state.collectAsStateWithLifecycle()

    var busy by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf(files.jreReady && files.serverReady) }
    var version by remember { mutableStateOf(files.server.resolve("voxelport-version.txt").takeIf { it.isFile }?.readText()?.trim() ?: "") }
    var alive by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var log by remember { mutableStateOf(listOf<String>()) }
    var eula by remember { mutableStateOf(prefs.eula) }
    var bedrock by remember { mutableStateOf(prefs.bedrock) }
    var ram by remember { mutableFloatStateOf(prefs.ramMb.toFloat()) }
    var name by remember { mutableStateOf(prefs.claimName) }
    var cmd by remember { mutableStateOf("") }
    var screen by remember { mutableStateOf("main") }
    var versions by remember { mutableStateOf<List<String>>(emptyList()) }
    var pickVersion by remember { mutableStateOf(prefs.version.ifEmpty { version }) }
    var showVersions by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(if (files.serverReady) files.type else prefs.type) }

    val totalMb = remember {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        (mi.totalMem / 1_048_576).toInt()
    }
    val maxRam = (totalMb / 2).coerceIn(1024, 6144).toFloat()

    // Poll the server process, its status (Server List Ping) and the console log.
    LaunchedEffect(Unit) {
        var pos = 0L
        while (true) {
            val a = ServerService.isAlive(ctx)
            alive = a
            status = if (a) withContext(Dispatchers.IO) { Mobile.pingLocal(25565).takeIf { it.isNotEmpty() }?.let { JSONObject(it) } } else null
            val (lines, p) = withContext(Dispatchers.IO) { tail(files.log.path, pos) }
            if (p < pos) log = emptyList()
            pos = p
            if (lines.isNotEmpty()) log = (log + lines).takeLast(300)
            delay(1500)
        }
    }

    fun install() = scope.launch {
        error = ""
        try {
            withContext(Dispatchers.IO) {
                Setup.installJre(ctx, files) { busy = it }
                val v = prefs.version.ifEmpty { Setup.versionsFor(type, 1).firstOrNull() ?: error("No $type versions found") }
                if (!files.serverReady || version != v || files.type != type) {
                    if (type == "fabric") Setup.installFabric(files, v) { busy = it } else Setup.installPaper(files, v) { busy = it }
                }
                Setup.removeBedrock(files)
                if (bedrock) {
                    if (type == "fabric") {
                        // Bedrock on Fabric: Geyser + Floodgate (and Fabric API) from Modrinth.
                        for ((id, name) in listOf("P7dR8mSH" to "Fabric API", "wKkoqHrH" to "Geyser", "bWrNNfkb" to "Floodgate"))
                            if (!Modrinth.isInstalled(files, id)) Modrinth.install(files, id, name, "fabric", v) { busy = it }
                    } else Setup.installBedrock(files) { busy = it }
                }
                Setup.writeServerFiles(files)
                version = v
                pickVersion = v
            }
            installed = true
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        busy = ""
    }

    // Restart: fully stop the server (and tunnel), wait for the :jvm process to
    // die, start again — and if it was public, make it public again.
    fun restart() = scope.launch {
        if (!alive) return@launch
        val wasPublic = tunnel.on
        error = ""; busy = "Restarting…"
        ServerService.send(ctx, ServerService.ACTION_STOP)
        withContext(Dispatchers.IO) { var n = 0; while (ServerService.isAlive(ctx) && n < 150) { delay(400); n++ } }
        delay(500)
        ServerService.send(ctx, ServerService.ACTION_START) { putExtra(ServerService.EXTRA_RAM_MB, ram.toInt()) }
        if (wasPublic) {
            withContext(Dispatchers.IO) { var n = 0; while (n < 600) { if (ServerService.isAlive(ctx) && Mobile.pingLocal(25565).isNotEmpty()) break; delay(500); n++ } }
            TunnelService.send(ctx, TunnelService.ACTION_START) { putExtra(TunnelService.EXTRA_BEDROCK, bedrock) }
        }
        busy = ""
    }

    androidx.activity.compose.BackHandler(enabled = screen != "main") { screen = "main" }
    when (screen) {
        "files" -> { FilesScreen(files.server, alive) { screen = "main" }; return }
        "settings" -> { SettingsScreen(files, alive, { screen = "main" }) { restart() }; return }
        "browse" -> { BrowseScreen(files, alive, { screen = "main" }) { restart() }; return }
    }

    Column(
        Modifier.fillMaxSize().background(Paper).systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("VOXELPORT", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Ink)
        Text("Host a Minecraft server from this phone — free, no port forwarding.", color = Muted)

        Panel {
            Text("1 · SERVER", fontWeight = FontWeight.Black, color = Ink)
            Text(
                if (installed) "${type.replaceFirstChar { it.uppercase() }} $version + Java 25 installed" else "Downloads ${type.replaceFirstChar { it.uppercase() }} (latest) and sets up Java 25. About 60 MB.",
                color = Muted, fontSize = 14.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(bedrock, { bedrock = it; prefs.bedrock = it }, enabled = !alive && busy.isEmpty())
                Text("Let Bedrock players join (phones, consoles)", fontSize = 14.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(eula, { eula = it; prefs.eula = it }, enabled = !alive)
                Text("I agree to the Minecraft EULA", fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aka.ms/MinecraftEULA"))) }) { Text("Read") }
            }
            Text("RAM for the server: ${ram.toInt() / 1024f} GB (phone has ${totalMb / 1024} GB)", fontSize = 14.sp)
            Slider(ram, { ram = (it / 256).toInt() * 256f; prefs.ramMb = ram.toInt() }, valueRange = 1024f..maxRam, enabled = !alive)
            if (busy.isNotEmpty()) Text(busy, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            if (error.isNotEmpty()) Text(error, color = Red, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(if (installed) "UPDATE / REPAIR" else "INSTALL", Gold, enabled = busy.isEmpty() && !alive && eula) { install() }
                if (!alive) Btn("START", Grass, enabled = installed && eula && busy.isEmpty()) {
                    ServerService.send(ctx, ServerService.ACTION_START) { putExtra(ServerService.EXTRA_RAM_MB, ram.toInt()) }
                }
                else {
                    Btn("STOP", Red, enabled = busy.isEmpty()) {
                        ServerService.send(ctx, ServerService.ACTION_STOP)
                        TunnelService.send(ctx, TunnelService.ACTION_STOP)
                    }
                    Btn("RESTART", Gold, enabled = busy.isEmpty()) { restart() }
                }
            }
            Btn(if (type == "fabric") "ADD MODS (MODRINTH)" else "ADD PLUGINS (MODRINTH)", Grass, enabled = installed) { screen = "browse" }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("SETTINGS", Card, enabled = installed) { screen = "settings" }
                Btn("FILES", Card, enabled = installed) { screen = "files" }
            }
            val st = status
            Text(
                when {
                    st != null -> "● Online · ${st.optString("version")} · ${st.optInt("players")}/${st.optInt("max")} players"
                    alive -> "● Starting… (first start on a phone can take a few minutes)"
                    else -> "○ Stopped"
                },
                color = if (st != null) Grass else Muted, fontWeight = FontWeight.Bold,
            )

            // Version section: shows the installed version and lets you switch.
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Version: ${version.ifEmpty { "—" }}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                TextButton({ showVersions = !showVersions }, enabled = !alive && busy.isEmpty()) { Text(if (showVersions) "Hide" else "Change") }
            }
            if (showVersions) {
                LaunchedEffect(type) { versions = emptyList(); runCatching { versions = withContext(Dispatchers.IO) { Setup.versionsFor(type, 12) } } }
                Text("Server software", fontSize = 12.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((t, label) in listOf("paper" to "Paper · plugins", "fabric" to "Fabric · mods")) {
                        Box(
                            Modifier.border(2.dp, Ink).background(if (type == t) Gold else Card)
                                .clickable { if (type != t) { type = t; prefs.type = t; prefs.version = ""; pickVersion = "" } }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) { Text(label, fontSize = 13.sp, fontWeight = if (type == t) FontWeight.Black else FontWeight.Normal, color = Ink) }
                    }
                }
                if (type != files.type && files.serverReady)
                    Text("Switching to ${type.replaceFirstChar { it.uppercase() }} keeps your world, but plugins and mods don't carry over. Tap UPDATE / REPAIR to switch.", color = Ink, fontSize = 13.sp)
                if (versions.isEmpty()) Text("Loading versions…", color = Muted, fontSize = 13.sp)
                FlowRowChips(versions, pickVersion) { v ->
                    pickVersion = v; prefs.version = v; showVersions = false
                }
                if (pickVersion.isNotEmpty() && pickVersion != version)
                    Text("Tap UPDATE / REPAIR to install Minecraft $pickVersion.", color = Ink, fontSize = 13.sp)
            }
        }

        Panel {
            Text("2 · MAKE PUBLIC", fontWeight = FontWeight.Black, color = Ink)
            if (!tunnel.on) {
                Text("Friends anywhere can join, even on Jio/Airtel or mobile data. No port forwarding.", color = Muted, fontSize = 14.sp)
                Btn("MAKE PUBLIC", Color(0xFF43C8D8), enabled = alive) {
                    if (!(ctx.getSystemService(PowerManager::class.java)).isIgnoringBatteryOptimizations(ctx.packageName)) {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }
                    TunnelService.send(ctx, TunnelService.ACTION_START) { putExtra(TunnelService.EXTRA_BEDROCK, bedrock) }
                }
            } else {
                val java = when {
                    tunnel.address.isNotEmpty() -> tunnel.address
                    tunnel.port > 0 -> "play.voxelport.in:${tunnel.port}"
                    else -> ""
                }
                Text(tunnel.status + if (tunnel.pingMs > 0) " · ${tunnel.pingMs} ms to relay" else "", color = Muted, fontSize = 13.sp)
                if (java.isNotEmpty()) {
                    Text("JAVA", fontSize = 12.sp, color = Muted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(java, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        TextButton({ copy(ctx, java) }) { Text("COPY") }
                    }
                    if (bedrock) {
                        Text("BEDROCK", fontSize = 12.sp, color = Muted)
                        val host = if (tunnel.name.isNotEmpty()) "${tunnel.name}.voxelport.in" else "play.voxelport.in"
                        Text("Server $host · Port ${tunnel.port}", fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                    }
                    Text("Free custom address", fontSize = 12.sp, color = Muted)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(name, { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(20) },
                            singleLine = true, modifier = Modifier.weight(1f), suffix = { Text(".voxelport.in") })
                        Btn("CLAIM", Ink, enabled = name.length >= 3) {
                            TunnelService.send(ctx, TunnelService.ACTION_CLAIM) { putExtra(TunnelService.EXTRA_NAME, name) }
                        }
                    }
                }
                if (tunnel.error.isNotEmpty()) Text(tunnel.error, color = Red, fontSize = 14.sp)
                Btn("STOP PUBLIC", Card) { TunnelService.send(ctx, TunnelService.ACTION_STOP) }
            }
        }

        Panel {
            Text("CONSOLE", fontWeight = FontWeight.Black, color = Ink)
            val listState = rememberLazyListState()
            LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.size - 1) }
            LazyColumn(Modifier.fillMaxWidth().height(280.dp).background(Color(0xFF1D1B1A)).padding(8.dp), state = listState) {
                items(log) { Text(it, color = Color(0xFFE6E0D6), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val send = {
                    if (cmd.isNotBlank()) ServerService.send(ctx, ServerService.ACTION_COMMAND) { putExtra(ServerService.EXTRA_LINE, cmd.trim().removePrefix("/")) }
                    cmd = ""
                }
                OutlinedTextField(cmd, { cmd = it }, singleLine = true, placeholder = { Text("op YourName") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }), enabled = alive)
                Btn("SEND", Ink, enabled = alive) { send() }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Card).border(3.dp, Ink).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
}

@Composable
private fun Btn(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick, enabled = enabled, shape = androidx.compose.ui.graphics.RectangleShape,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = if (color == Gold || color == Card) Ink else Color.White),
        modifier = Modifier.border(2.dp, Ink),
    ) { Text(label, fontWeight = FontWeight.Black) }
}

/** Wrapping grid of selectable version chips (rows of 3). */
@Composable
private fun FlowRowChips(items: List<String>, selected: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { v ->
                    val sel = v == selected
                    Box(
                        Modifier.border(2.dp, Ink).background(if (sel) Gold else Card)
                            .clickable { onPick(v) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { Text(v, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Black else FontWeight.Normal, color = Ink) }
                }
            }
        }
    }
}

private fun copy(ctx: android.content.Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("VoxelPort address", text))
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
