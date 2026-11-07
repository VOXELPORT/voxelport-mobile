package com.voxelport.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private val Paper = VP.Paper
private val Card = VP.Card
private val Ink = VP.Ink
private val Muted = VP.Muted
private val Grass = VP.Grass
private val Gold = VP.Gold

private class IntField(val key: String, val label: String, val def: Int, val min: Int, val max: Int)

private val INTS = listOf(
    IntField("max-players", "Max players", 10, 1, 100),
    IntField("view-distance", "View distance (chunks)", 6, 3, 32),
    IntField("simulation-distance", "Simulation distance (chunks)", 4, 3, 32),
    IntField("spawn-protection", "Spawn protection (blocks)", 16, 0, 64),
)
private val SWITCHES = listOf(
    "hardcore" to "Hardcore (one life)",
    "pvp" to "PvP",
    "white-list" to "Whitelist (only approved players)",
    "allow-flight" to "Allow flight",
)
private val DEFAULTS = mapOf(
    "motd" to "A VoxelPort phone server", "gamemode" to "survival", "difficulty" to "easy",
    "level-seed" to "", "hardcore" to "false", "pvp" to "true", "white-list" to "false", "allow-flight" to "false",
) + INTS.associate { it.key to it.def.toString() }

/** server.properties as a form, like the desktop app's Gameplay settings. */
@Composable
fun SettingsScreen(files: Files, alive: Boolean, onBack: () -> Unit, onRestart: () -> Unit) {
    val file = remember { File(files.server, "server.properties") }
    val v = remember { mutableStateMapOf<String, String>().apply { putAll(DEFAULTS); putAll(ServerProps.read(file).filterKeys { it in DEFAULTS }) } }
    var msg by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val device = remember { deviceOf(ctx) }
    var profile by remember { mutableStateOf(prefs.profile) }
    val level = Tuning.resolve(profile, device)

    fun save(): Boolean {
        val out = LinkedHashMap<String, String>()
        for (f in INTS) out[f.key] = (v[f.key]?.toIntOrNull() ?: f.def).coerceIn(f.min, f.max).also { v[f.key] = it.toString() }.toString()
        for ((k, _) in SWITCHES) out[k] = v[k] ?: "false"
        for (k in listOf("motd", "gamemode", "difficulty", "level-seed")) out[k] = v[k] ?: ""
        // Hardcore worlds are always hard difficulty.
        if (out["hardcore"] == "true") out["difficulty"] = "hard"
        return runCatching { ServerProps.write(file, out) }.onFailure { msg = t("Couldn't save: {detail}", "detail" to it.message) }.isSuccess
    }

    Column(
        Modifier.fillMaxSize().background(Paper).systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SETTINGS", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Ink, modifier = Modifier.weight(1f))
            TextButton(onBack) { Text("Done") }
        }
        Text("Minecraft ${files.version.ifEmpty { "—" }} (${files.type.replaceFirstChar { it.uppercase() }})", color = Muted, fontSize = 13.sp)

        AccountsPanel(files, alive, onRestart)

        // Performance: fit the server to this phone.
        Panel {
            PanelTitle("PERFORMANCE")
            Text(t("This phone: {ram} GB RAM · {cores} cores", "ram" to "%.1f".format(device.ramMb / 1024f), "cores" to device.cores), color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((id, label) in listOf("auto" to "Auto", "low" to "Low-end", "balanced" to "Balanced", "high" to "High-end")) {
                    val sel = profile == id
                    VpButton(label, if (sel) VP.Gold else VP.Card, Modifier.weight(1f)) {
                        profile = id; prefs.profile = id
                        val lv = Tuning.resolve(id, device)
                        runCatching { Tuning.applyProps(files.server, lv, prefs.dataSaver) }
                        for ((k, value) in Tuning.profileProps(lv, prefs.dataSaver)) if (k in v) v[k] = value
                        msg = t("{level} applied. Restart the server to use it.", "level" to t(lv.label))
                    }
                }
            }
            Text(
                (if (profile == "auto") t("Auto chose {level} for this phone.", "level" to t(level.label)) + " " else "") + t(
                    when (level) {
                        Tuning.Level.LOW -> "Small view distance, fewer mobs, a lighter garbage collector and fewer background threads. Best for phones with 4 GB or less."
                        Tuning.Level.BALANCED -> "A good middle: moderate view distance and mob limits, tuned memory use. Best for most phones."
                        Tuning.Level.HIGH -> "Bigger view distance and more mobs. For phones with 10 GB+ and good cooling."
                    },
                ),
                fontSize = 13.sp, color = VP.Ink,
            )
            Text("View and simulation distance (below) change at once; the engine tuning applies from the next start.", color = Muted, fontSize = 12.sp)
        }

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).border(1.dp, VP.Line, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("GAMEPLAY", fontWeight = FontWeight.Black, color = Ink)
            OutlinedTextField(v["motd"] ?: "", { v["motd"] = it.take(120).replace("\n", " ") }, label = { Text("Description (MOTD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Dropdown("Game mode", v["gamemode"] ?: "survival", listOf("survival", "creative", "adventure", "spectator")) { v["gamemode"] = it }
            Dropdown("Difficulty", v["difficulty"] ?: "easy", listOf("peaceful", "easy", "normal", "hard")) { v["difficulty"] = it }
            for (f in INTS) {
                OutlinedTextField(
                    v[f.key] ?: "", { s -> v[f.key] = s.filter { c -> c.isDigit() }.take(3) },
                    label = { Text(t("{label} · {min}–{max}", "label" to t(f.label), "min" to f.min, "max" to f.max)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(v["level-seed"] ?: "", { v["level-seed"] = it.take(64) }, label = { Text("World seed (new worlds only)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            for ((k, label) in SWITCHES) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(v[k] == "true", { v[k] = it.toString() })
                    Text(label, fontSize = 14.sp)
                }
            }
        }

        if (msg.isNotEmpty()) Text(msg, color = Ink, fontSize = 13.sp)
        Button(
            { if (save()) msg = if (alive) "Saved. Restart the server to apply." else "Saved." },
            shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, VP.Line, RoundedCornerShape(10.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = Grass, contentColor = Color.White),
        ) { Text("SAVE SETTINGS", fontWeight = FontWeight.Black) }
        if (alive) Button(
            { if (save()) { msg = "Saved — restarting…"; onRestart(); onBack() } },
            shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, VP.Line, RoundedCornerShape(10.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
        ) { Text("SAVE & RESTART", fontWeight = FontWeight.Black) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Dropdown(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 12.sp, color = Muted)
        Box {
            OutlinedButton({ open = true }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(value.replaceFirstChar { it.uppercase() }, color = Ink, modifier = Modifier.weight(1f))
                Text("▾", color = Ink)
            }
            DropdownMenu(open, { open = false }) {
                for (o in options) DropdownMenuItem({ Text(o.replaceFirstChar { it.uppercase() }) }, { onPick(o); open = false })
            }
        }
    }
}
