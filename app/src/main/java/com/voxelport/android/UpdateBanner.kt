package com.voxelport.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
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

    AnimatedVisibility(show || state is UpdateState.UpToDate) {
        Column(
            Modifier.fillMaxWidth().background(if (state is UpdateState.Failed) VP.Card else VP.Gold).border(3.dp, VP.Ink).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                is UpdateState.Available -> {
                    val m = state.m
                    Text("UPDATE AVAILABLE", fontWeight = FontWeight.Black, fontSize = 15.sp, color = VP.Ink)
                    if (m.notes.isNotBlank()) Text(m.notes, fontSize = 14.sp, color = VP.Ink)
                    Text(
                        "%.0f MB".format(m.size / 1_000_000.0) + if (Updater.metered(ctx)) " · you're on mobile data" else "",
                        fontSize = 12.sp, color = VP.Ink,
                    )
                    if (serverRunning) Text("Your server will be saved and stopped while the app updates.", fontSize = 12.sp, color = VP.Ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VpButton("UPDATE NOW", VP.Ink, Modifier.weight(1f)) { Updater.install(ctx) }
                        VpButton("LATER", VP.Card, Modifier.weight(1f)) { hidden = true }
                    }
                }
                is UpdateState.Downloading -> {
                    Text("DOWNLOADING UPDATE… ${state.pct}%", fontWeight = FontWeight.Black, fontSize = 15.sp, color = VP.Ink)
                    LinearProgressIndicator(progress = { state.pct / 100f }, Modifier.fillMaxWidth(), color = VP.Ink, trackColor = VP.Card)
                }
                is UpdateState.Installing -> {
                    Text("UPDATING", fontWeight = FontWeight.Black, fontSize = 15.sp, color = VP.Ink)
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = VP.Ink, trackColor = VP.Card)
                    Text(state.note, fontSize = 13.sp, color = VP.Ink)
                    Text("If Android asks, tap Update. The app restarts when it's done.", fontSize = 12.sp, color = VP.Ink)
                }
                is UpdateState.NeedsPermission -> {
                    Text("ONE QUICK STEP", fontWeight = FontWeight.Black, fontSize = 15.sp, color = VP.Ink)
                    Text("Android needs you to allow VoxelPort to install updates. Switch on “Allow from this source”, come back, and tap Update again.", fontSize = 14.sp, color = VP.Ink)
                    VpButton("UPDATE NOW", VP.Ink, Modifier.fillMaxWidth()) { Updater.install(ctx) }
                }
                is UpdateState.Failed -> {
                    Text("UPDATE PROBLEM", fontWeight = FontWeight.Black, fontSize = 15.sp, color = VP.Red)
                    Text(state.message, fontSize = 14.sp, color = VP.Ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.m != null) VpButton("TRY AGAIN", VP.Gold, Modifier.weight(1f)) { Updater.install(ctx) }
                        VpButton("CLOSE", VP.Card, Modifier.weight(1f)) { Updater.dismiss() }
                    }
                }
                is UpdateState.UpToDate -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("You're up to date.", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VP.Ink, modifier = Modifier.weight(1f))
                        TextButton({ Updater.dismiss() }) { Text("OK", color = VP.Ink) }
                    }
                }
                else -> {}
            }
        }
    }
}
