package com.voxelport.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colour for a [PhoneCheck.Level]. */
fun levelColor(l: PhoneCheck.Level): Color = when (l) {
    PhoneCheck.Level.GOOD -> VP.Grass
    PhoneCheck.Level.OK -> VP.Diamond
    PhoneCheck.Level.WARN -> VP.Gold
    PhoneCheck.Level.BAD -> VP.Red
}

/** First-launch card: what this phone can do. */
@Composable
fun PhoneCheckCard(report: PhoneCheck.Report) {
    // Short by default, so the Play button stays on screen; open by itself when something is wrong.
    var open by remember { mutableStateOf(report.verdict == PhoneCheck.Level.BAD) }
    Panel {
        PanelTitle("YOUR PHONE") { TextButton({ open = !open }) { Text(if (open) "Hide" else "Details") } }
        Text(report.summary, fontWeight = FontWeight.Bold, color = levelColor(report.verdict), fontSize = 14.sp)
        if (open) for (item in report.items) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top = 5.dp).size(10.dp).clip(RoundedCornerShape(999.dp)).background(levelColor(item.level)))
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(item.detail, color = VP.Muted, fontSize = 13.sp)
                }
            }
        }
    }
}

/** A known server problem with its one-tap fix. */
@Composable
fun FixCard(fix: Fixes.Fix, onFix: () -> Unit, onDismiss: () -> Unit) {
    Banner(VP.Gold) {
        Text("Something went wrong", fontWeight = FontWeight.Black, color = VP.OnGold, fontSize = 15.sp)
        Text(fix.message, color = VP.OnGold, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onFix, shape = RoundedCornerShape(VP.RadiusSm), modifier = Modifier.heightIn(min = 44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VP.OnGold, contentColor = VP.Gold),
            ) { Text(fix.button, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1) }
            TextButton(onDismiss) { Text("Hide", color = VP.OnGold) }
        }
    }
}

/** The three steps of getting a server online, with the current one lit. [step]: 0 install, 1 start, 2 share, 3 done. */
@Composable
fun PlaySteps(step: Int) {
    val labels = listOf(t("Install"), t("Start"), t("Share"))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, label ->
            val done = step > i
            val now = step == i
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.size(22.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (done) VP.Grass else if (now) VP.Gold else VP.Paper2)
                        .border(1.dp, VP.Line, RoundedCornerShape(999.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(if (done) "✓" else "${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Black, color = if (done || now) Color(0xFF07140B) else VP.Muted) }
                Text(label, fontSize = 12.sp, fontWeight = if (now) FontWeight.Bold else FontWeight.Normal, color = if (done || now) VP.Ink else VP.Muted)
            }
            if (i < labels.lastIndex) HorizontalDivider(Modifier.weight(1f).padding(horizontal = 8.dp), color = VP.Line)
        }
    }
}

/** "TEST MY ADDRESS" and what it found. */
@Composable
fun AddressTestRow(testing: Boolean, result: Pair<String, Boolean>?, enabled: Boolean, onTest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VpButton(if (testing) "TESTING…" else "TEST MY ADDRESS", VP.Card, Modifier.fillMaxWidth(), enabled = enabled && !testing, onClick = onTest)
        if (testing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = VP.Diamond, trackColor = VP.Paper2)
        result?.let { (text, ok) -> Text(text, color = if (ok) VP.GrassDark else VP.Red, fontSize = 13.sp, fontWeight = if (ok) FontWeight.Bold else FontWeight.Normal) }
    }
}

// ── Bottom bar ──

/** The pages reachable from the bottom bar. */
val TABS = listOf("home", "players", "browse", "files", "more")

@Composable
fun VpNavBar(current: String, pluginsLabel: String, onPick: (String) -> Unit) {
    NavigationBar(containerColor = VP.Card, contentColor = VP.Ink, tonalElevation = 0.dp) {
        for (tab in TABS) {
            val label = when (tab) {
                "home" -> "Home"; "players" -> "Players"; "browse" -> pluginsLabel; "files" -> "Files"; else -> "More"
            }
            val selected = current == tab
            NavigationBarItem(
                selected = selected, onClick = { onPick(tab) },
                icon = { NavIcon(tab, if (selected) VP.Grass else VP.Muted) },
                label = { Text(label, fontSize = 11.sp, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedTextColor = VP.Ink, unselectedTextColor = VP.Muted, indicatorColor = VP.Paper2,
                ),
            )
        }
    }
}

/** Small line icons drawn by hand (no icon library in the app). */
@Composable
private fun NavIcon(tab: String, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val s = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        when (tab) {
            "home" -> {
                val p = Path().apply {
                    moveTo(w * 0.12f, w * 0.48f); lineTo(w * 0.5f, w * 0.14f); lineTo(w * 0.88f, w * 0.48f)
                    moveTo(w * 0.22f, w * 0.42f); lineTo(w * 0.22f, w * 0.84f); lineTo(w * 0.78f, w * 0.84f); lineTo(w * 0.78f, w * 0.42f)
                }
                drawPath(p, color, style = s)
            }
            "players" -> {
                drawCircle(color, w * 0.15f, Offset(w * 0.5f, w * 0.3f), style = s)
                val p = Path().apply { moveTo(w * 0.2f, w * 0.85f); cubicTo(w * 0.2f, w * 0.52f, w * 0.8f, w * 0.52f, w * 0.8f, w * 0.85f) }
                drawPath(p, color, style = s)
            }
            "browse" -> {
                drawLine(color, Offset(w * 0.5f, w * 0.15f), Offset(w * 0.5f, w * 0.85f), s.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.15f, w * 0.5f), Offset(w * 0.85f, w * 0.5f), s.width, StrokeCap.Round)
            }
            "files" -> {
                val p = Path().apply {
                    moveTo(w * 0.12f, w * 0.78f); lineTo(w * 0.12f, w * 0.22f); lineTo(w * 0.4f, w * 0.22f); lineTo(w * 0.48f, w * 0.34f)
                    lineTo(w * 0.88f, w * 0.34f); lineTo(w * 0.88f, w * 0.78f); close()
                }
                drawPath(p, color, style = s)
            }
            else -> for (i in 0..2) drawCircle(color, w * 0.07f, Offset(w * (0.22f + 0.28f * i), w * 0.5f))
        }
    }
}

// ── Players ──

@Composable
fun PlayersScreen(
    online: List<String>, count: Int, max: Int, alive: Boolean, address: String,
    whitelist: List<String>, whitelistOn: Boolean, bedrockOk: Boolean, note: Pair<String, Boolean>?, waiting: Boolean,
    onCommand: (String) -> Unit, onWhitelist: (String) -> Unit, onShare: () -> Unit,
) {
    var banAsk by remember { mutableStateOf<String?>(null) }
    // The server's own count (from its status) is the truth; the names come from its console and can lag behind it.
    val shown = if (count >= 0) maxOf(count, online.size) else online.size
    var name by remember { mutableStateOf("") }
    var bedrock by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("PLAYERS", fontSize = 30.sp, fontWeight = FontWeight.Black, color = VP.Ink)
        Panel {
            PanelTitle(if (alive) t("ONLINE NOW: {n}", "n" to shown) + (if (max > 0) " / $max" else "") else t("ONLINE NOW"))
            when {
                !alive -> Text("Start your server to see who is playing.", color = VP.Muted, fontSize = 14.sp)
                online.isEmpty() && shown > 0 -> Text(t("{n} players are online, but their names are not known yet. Names appear as players join while this app is open.", "n" to shown), color = VP.Muted, fontSize = 14.sp)
                online.isEmpty() -> {
                    Text("Nobody is online yet.", color = VP.Muted, fontSize = 14.sp)
                    if (address.isNotEmpty()) VpButton("SHARE MY ADDRESS", VP.Gold, Modifier.fillMaxWidth(), onClick = onShare)
                }
                else -> {
                for (p in online) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        TextButton({ Players.kick(p)?.let(onCommand) }) { Text("Kick") }
                        TextButton({ banAsk = p }) { Text("Ban", color = VP.Red) }
                    }
                }
                if (shown > online.size) Text(t("+ {n} more online, names not known yet.", "n" to (shown - online.size)), color = VP.Muted, fontSize = 13.sp)
                }
            }
        }
        Panel {
            PanelTitle("WHITELIST") { StatusPill(if (whitelistOn) "ON" else "OFF", if (whitelistOn) VP.Grass else VP.Muted) }
            Text("With the whitelist on, only players you add can join. Good for a server with just your friends. Add your own name too, or you will be locked out.", color = VP.Muted, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' || c == '-' }.take(32) },
                    singleLine = true, modifier = Modifier.weight(1f), label = { Text("Player name") },
                )
                VpButton("ADD", VP.Grass, Modifier.padding(top = 8.dp), enabled = alive && name.isNotEmpty() && !waiting) {
                    Whitelist.addCommand(name, bedrock)?.let(onWhitelist); name = ""
                }
            }
            if (bedrockOk) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(bedrock, { bedrock = it })
                Text("Bedrock player (phone, console, Windows 10)", fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
            if (waiting) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = VP.Diamond, trackColor = VP.Paper2) }
            note?.let { (text, ok) -> Text(text, color = if (ok) VP.GrassDark else VP.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VpButton("TURN ON", VP.Card, Modifier.weight(1f), enabled = alive && !waiting) { onWhitelist("whitelist on") }
                VpButton("TURN OFF", VP.Card, Modifier.weight(1f), enabled = alive && !waiting) { onWhitelist("whitelist off") }
            }
            if (whitelist.isEmpty()) Text("Nobody is on the whitelist yet.", color = VP.Muted, fontSize = 13.sp)
            else {
                Text(t("ON THE WHITELIST: {n}", "n" to whitelist.size), color = VP.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                for (p in whitelist) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton({ Whitelist.removeCommand(p)?.let(onWhitelist) }, enabled = alive && !waiting) { Text("Remove", color = VP.Red) }
                }
            }
            Text("Needs a running server. To see everything it did, open Console under More.", color = VP.Muted, fontSize = 12.sp)
        }
    }
    banAsk?.let { p ->
        AlertDialog(
            onDismissRequest = { banAsk = null },
            title = { Text("Ban this player?") },
            text = { Text(t("{name} will not be able to join again until you unban them in the Console (pardon {name}).", "name" to p), fontSize = 14.sp) },
            confirmButton = { TextButton({ Players.ban(p)?.let(onCommand); banAsk = null }) { Text("Ban", color = VP.Red) } },
            dismissButton = { TextButton({ banAsk = null }) { Text("Cancel") } },
        )
    }
}
