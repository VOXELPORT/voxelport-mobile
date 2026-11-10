package com.voxelport.android

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Opens Android's Wi‑Fi screen, so "use Wi‑Fi" is one tap away. */
fun openWifiSettings(ctx: Context) {
    runCatching { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** A question (or a refusal) before something that uses mobile data. [go] null means there is nothing to confirm. */
class DataAsk(val title: String, val body: String, val go: String?, val onGo: () -> Unit = {})

@Composable
fun DataAskDialog(ask: DataAsk, onClose: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(ask.title) },
        text = { Text(ask.body) },
        confirmButton = {
            if (ask.go != null) TextButton({ onClose(); ask.onGo() }) { Text(ask.go) }
            else TextButton(onClose) { Text("Close") }
        },
        dismissButton = { TextButton({ onClose(); openWifiSettings(ctx) }) { Text("Wi‑Fi settings") } },
    )
}

/** What to do before a download of about [mb] MB, given the connection and the user's limit setting. */
fun downloadAsk(limitMb: Int, metered: Boolean, mobileToday: Long, what: String, mb: Int, onGo: () -> Unit): DataAsk? {
    val thing = fill(what)
    return when (DataLogic.downloadCheck(limitMb, metered)) {
        DataLogic.DownloadCheck.OK -> null
        DataLogic.DownloadCheck.ASK -> DataAsk(
            fill("Download on mobile data?"),
            fill("{what} is about {mb} MB. You're on mobile data, so it counts against your plan (VoxelPort has used {used} today).",
                mapOf("what" to thing, "mb" to mb, "used" to DataLogic.format(mobileToday)),
            ) + "\n\n" + fill("Wi‑Fi is better for this: it's free and faster."),
            fill("Download anyway"), onGo,
        )
        DataLogic.DownloadCheck.BLOCKED -> DataAsk(
            fill("Connect to Wi‑Fi first"),
            fill("{what} is about {mb} MB, and VoxelPort is set to Wi‑Fi only. Connect to Wi‑Fi, or change the limit under Data use on the main screen.",
                mapOf("what" to thing, "mb" to mb),
            ),
            null,
        )
    }
}

/** The yellow "you're on mobile data" card at the top of the home screen. */
@Composable
fun MobileDataBanner(net: DataMeter.Net) {
    val ctx = LocalContext.current
    var hidden by remember { mutableStateOf(false) }
    AnimatedVisibility(net.metered && !hidden) {
        Banner {
            Text("YOU'RE ON MOBILE DATA", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = VP.OnGold)
            Text("Hosting and downloads use your data plan. Wi‑Fi is much better for this: it's free and faster.", fontSize = 13.sp, color = VP.OnGold)
            Text("VoxelPort counts what it uses and stops sharing at your daily limit.", fontSize = 13.sp, color = VP.OnGold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VpButton("WI‑FI SETTINGS", VP.Grass, Modifier.weight(1f)) { openWifiSettings(ctx) }
                VpGhostButton("OK", Modifier.weight(1f)) { hidden = true }
            }
        }
    }
}

/** Today's data use, the daily limit, Data saver, and the Wi‑Fi advice. */
@Composable
fun DataPanel(
    net: DataMeter.Net, usage: DataMeter.Usage,
    limitMb: Int, onLimit: (Int) -> Unit,
    saver: Boolean, onSaver: (Boolean) -> Unit,
    installed: Boolean, alive: Boolean, onRestart: () -> Unit,
) {
    val ctx = LocalContext.current
    var saverChanged by remember { mutableStateOf(false) }
    val level = DataLogic.level(usage.mobile, limitMb, usage.bonusMb)
    val fraction = DataLogic.fraction(usage.mobile, limitMb, usage.bonusMb)

    Panel {
        PanelTitle("DATA USE")
        Text(
            when {
                !net.online -> "No connection"
                net.metered -> "On mobile data (counts against your plan)"
                else -> "On Wi‑Fi (doesn't use your plan)"
            },
            color = when { !net.online -> VP.Muted; net.metered -> VP.Red; else -> VP.GrassDark },
            fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
        Text(t("Today: {mobile} on mobile data · {wifi} on Wi‑Fi", "mobile" to DataLogic.format(usage.mobile), "wifi" to DataLogic.format(usage.wifi)), fontSize = 14.sp, color = VP.Ink)
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction }, modifier = Modifier.fillMaxWidth(),
                color = when (level) { DataLogic.Level.OK -> VP.Grass; DataLogic.Level.WARN -> VP.Gold; DataLogic.Level.OVER -> VP.Red },
                trackColor = VP.outline,
            )
            Text(
                t("{used} of {limit}", "used" to DataLogic.format(usage.mobile), "limit" to t(DataLogic.limitLabel(limitMb))) +
                    if (usage.bonusMb > 0) t(" + {mb} MB added today", "mb" to usage.bonusMb) else "",
                fontSize = 12.sp, color = if (level == DataLogic.Level.OVER) VP.Red else VP.Muted,
            )
        }

        Text("Mobile data limit per day", fontSize = 12.sp, color = VP.Muted)
        LimitChips(limitMb, onLimit)
        Text(
            when (limitMb) {
                DataLogic.WIFI_ONLY -> "Hosting and big downloads only work on Wi‑Fi, so nothing uses your plan."
                DataLogic.UNLIMITED -> "No limit: hosting can use as much mobile data as your players do."
                else -> t("Sharing your server stops for the day at {limit} of mobile data. Downloads count too.", "limit" to t(DataLogic.limitLabel(limitMb)))
            },
            fontSize = 12.sp, color = VP.Muted,
        )
        if (level == DataLogic.Level.OVER) VpButton(t("ADD {mb} MB FOR TODAY", "mb" to DataLogic.BONUS_MB), VP.Gold, Modifier.fillMaxWidth()) { DataMeter.addBonus() }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Data saver", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    "A smaller world around each player and fewer far-away updates, so every player uses much less data. Turn it off to get your performance level's distances back.",
                    color = VP.Muted, fontSize = 12.sp,
                )
            }
            Switch(saver, { onSaver(it); saverChanged = true })
        }
        if (saverChanged) {
            if (alive) {
                Text("Restart the server to use it.", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                VpButton("RESTART NOW", VP.Gold, Modifier.fillMaxWidth()) { onRestart(); saverChanged = false }
            } else Text(if (installed) "Applies the next time you start the server." else "Applies when the server is installed.", color = VP.Muted, fontSize = 13.sp)
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Best on Wi‑Fi", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            t("On Wi‑Fi hosting doesn't touch your plan. On mobile data every player's game goes through this phone: {amount}.", "amount" to DataLogic.perPlayerText()),
            fontSize = 12.sp, color = VP.Muted,
        )
        Text("On a 1 GB/day plan, that's a few players for a few hours.", fontSize = 12.sp, color = VP.Muted)
        if (net.metered) VpButton("OPEN WI‑FI SETTINGS", VP.Card, Modifier.fillMaxWidth()) { openWifiSettings(ctx) }
        Text(
            "These numbers are VoxelPort's own estimate. Android's Settings → Network & internet → App data usage shows the exact figure.",
            fontSize = 11.sp, color = VP.Muted,
        )
    }
}

@Composable
private fun LimitChips(selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DataLogic.LIMITS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { l ->
                    val sel = l == selected
                    Box(
                        Modifier.weight(1f).border(2.dp, VP.Ink).background(if (sel) VP.Gold else VP.Card)
                            .clickable { onPick(l) }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            DataLogic.limitLabel(l), fontSize = 13.sp, color = VP.Ink, maxLines = 1, softWrap = false,
                            fontWeight = if (sel) FontWeight.Black else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

private val VP.outline get() = androidx.compose.ui.graphics.Color(0xFFE6DCC6)
