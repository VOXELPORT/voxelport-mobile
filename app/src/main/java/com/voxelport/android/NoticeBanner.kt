package com.voxelport.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** What the relay's operator announced ("The VoxelPort relay will restart."), with the time left counting down. */
@Composable
fun NoticeBanner(a: Announcement, serverRunning: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(a) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    Banner {
        Text("ANNOUNCEMENT", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = VP.OnGold)
        // The operator's own words: shown as written.
        Text(a.message, fontSize = 14.sp, color = VP.OnGold)
        if (a.dueAtMs > 0) {
            val left = ((a.dueAtMs - now) / 1000).coerceAtLeast(0)
            Text(t("Happens {when}.", "when" to NoticeLogic.timeLeftPhrase(left, Lang.current)), fontSize = 12.sp, color = VP.OnGold)
        }
        if (serverRunning) Text("Players on your server are told in the game's chat.", fontSize = 12.sp, color = VP.OnGold)
    }
}
