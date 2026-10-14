package com.voxelport.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The server console on its own screen: scrolls independently, command box rides above the keyboard. */
@Composable
fun ConsoleScreen(log: List<String>, alive: Boolean, onSend: (String) -> Unit, onBack: () -> Unit) {
    val listState = rememberLazyListState()
    var cmd by remember { mutableStateOf("") }

    // Follow new lines, unless the user scrolled up to read.
    val atBottom by remember { derivedStateOf { val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0; last >= listState.layoutInfo.totalItemsCount - 2 } }
    LaunchedEffect(log.size) { if (log.isNotEmpty() && atBottom) listState.scrollToItem(log.size - 1) }
    LaunchedEffect(Unit) { if (log.isNotEmpty()) listState.scrollToItem(log.size - 1) }

    fun send() { if (cmd.isNotBlank() && alive) { onSend(cmd.trim().removePrefix("/")); cmd = "" } }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CONSOLE", fontSize = 30.sp, fontWeight = FontWeight.Black, color = VP.Ink, modifier = Modifier.weight(1f))
            TextButton(onBack) { Text("Done") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(VP.Console).padding(10.dp), state = listState) {
            if (log.isEmpty()) item { Text("Nothing yet. Start the server to see its output here.", color = Color(0xFF8F8A82), fontSize = 12.sp) }
            itemsIndexed(log) { _, line ->
                Text(line, color = Color(0xFFE6E0D6), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in listOf("list", "save-all", "tps")) {
                Box(
                    Modifier.border(2.dp, VP.Ink).background(VP.Card).clickable(enabled = alive) { onSend(c) }.padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Text(c, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = if (alive) VP.Ink else VP.Muted) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                cmd, { cmd = it }, singleLine = true, enabled = alive, modifier = Modifier.weight(1f),
                placeholder = { Text(if (alive) "op YourName" else "Start the server first") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
            )
            VpButton("SEND", VP.Ink, enabled = alive && cmd.isNotBlank()) { send() }
        }
    }
}
