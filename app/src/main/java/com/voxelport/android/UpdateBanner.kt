package com.voxelport.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The "update available" card at the top of the home screen (and its download/install progress). */
@Composable
fun UpdateBanner(state: UpdateState, serverRunning: Boolean) {
    val ctx = LocalContext.current
    // "Later" hides it for this launch only.
    var hidden by remember(state) { mutableStateOf(false) }
    val show = !hidden && state !is UpdateState.Idle && state !is UpdateState.Checking && state !is UpdateState.UpToDate
    var ask by remember { mutableStateOf<DataAsk?>(null) }

    // The update is a ~50 MB download: on mobile data, ask first (or refuse if the user chose Wi-Fi only).
    fun update(m: UpdateLogic.Manifest?) {
        val net = DataMeter.net.value
        val mb = ((m?.size ?: 0L) / DataLogic.MB).toInt().coerceAtLeast(1)
        val a = downloadAsk(Prefs(ctx).dataLimitMb, net.metered, DataMeter.usage.value.mobile, "The VoxelPort update", mb) { Updater.install(ctx) }
        if (a == null) Updater.install(ctx) else ask = a
    }
    ask?.let { a -> DataAskDialog(a) { ask = null } }

    AnimatedVisibility(show || state is UpdateState.UpToDate) {
        // Update/progress states sit on a gold banner (dark text); Failed and Up-to-date sit on a card (light text).
        val onCard = state is UpdateState.Failed || state is UpdateState.UpToDate
        val fg = if (onCard) VP.Ink else VP.OnGold
        Banner(if (onCard) VP.Card else VP.Gold) {
            when (state) {
                is UpdateState.Available -> {
                    val m = state.m
                    Text("UPDATE AVAILABLE", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = fg)
                    if (m.notes.isNotBlank()) Text(m.notes, fontSize = 14.sp, color = fg)
                    Text(
                        "%.0f MB".format(m.size / 1_000_000.0) + if (Updater.metered(ctx)) t(" · you're on mobile data: Wi‑Fi is better for this") else "",
                        fontSize = 12.sp, color = fg,
                    )
                    if (serverRunning) Text("Your server will be saved and stopped while the app updates.", fontSize = 12.sp, color = fg)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VpButton("UPDATE NOW", VP.Grass, Modifier.weight(1f)) { update(m) }
                        VpGhostButton("LATER", Modifier.weight(1f)) { hidden = true }
                    }
                }
                is UpdateState.Downloading -> {
                    Text(t("DOWNLOADING UPDATE… {pct}%", "pct" to state.pct), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = fg)
                    LinearProgressIndicator(progress = { state.pct / 100f }, Modifier.fillMaxWidth(), color = VP.OnGold, trackColor = VP.OnGold.copy(alpha = 0.25f))
                }
                is UpdateState.Installing -> {
                    Text("UPDATING", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = fg)
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = VP.OnGold, trackColor = VP.OnGold.copy(alpha = 0.25f))
                    Text(state.note, fontSize = 13.sp, color = fg)
                    Text("If Android asks, tap Update. The app restarts when it's done.", fontSize = 12.sp, color = fg)
                }
                is UpdateState.NeedsPermission -> {
                    Text("ONE QUICK STEP", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = fg)
                    Text("Android needs you to allow VoxelPort to install updates. Switch on “Allow from this source”, come back, and tap Update again.", fontSize = 14.sp, color = fg)
                    // Already agreed to the download; this is just the second tap after the Android settings screen.
                    VpButton("UPDATE NOW", VP.Grass, Modifier.fillMaxWidth()) { Updater.install(ctx) }
                }
                is UpdateState.Failed -> {
                    Text("UPDATE PROBLEM", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = VP.Red)
                    Text(state.message, fontSize = 14.sp, color = fg)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.m != null) VpButton("TRY AGAIN", VP.Grass, Modifier.weight(1f)) { update(state.m) }
                        VpGhostButton("CLOSE", Modifier.weight(1f)) { Updater.dismiss() }
                    }
                }
                is UpdateState.UpToDate -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("You're up to date.", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = fg, modifier = Modifier.weight(1f))
                        TextButton({ Updater.dismiss() }) { Text("OK", color = VP.Grass) }
                    }
                }
                else -> {}
            }
        }
    }
}
