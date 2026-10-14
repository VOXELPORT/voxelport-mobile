package com.voxelport.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val AUTHME_ID = "3IEZ9vol" // AuthMe ReReloaded (maintained fork) on Modrinth

/** True if server.properties lets players in without a Minecraft account check (online-mode=false). */
fun isCracked(files: Files): Boolean = ServerProps.read(File(files.server, "server.properties"))["online-mode"] == "false"

/**
 * "Cracked" (offline-mode) switch: lets people without a paid Minecraft account join.
 * It is a normal server setting, but it removes the account check, so anyone can join
 * under any name (including an admin's). The panel says so and offers a login plugin.
 */
@Composable
fun AccountsPanel(files: Files, alive: Boolean, onRestart: () -> Unit) {
    val scope = rememberCoroutineScope()
    val propsFile = remember { File(files.server, "server.properties") }
    var cracked by remember { mutableStateOf(isCracked(files)) }
    var confirm by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var hasLogin by remember { mutableStateOf(Modrinth.isInstalled(files, AUTHME_ID)) }

    fun set(on: Boolean) {
        runCatching {
            ServerProps.write(propsFile, mapOf("online-mode" to (!on).toString(), "enforce-secure-profile" to (!on).toString()))
        }.onSuccess { cracked = on; changed = true; msg = "" }.onFailure { msg = "Couldn't save: ${it.message}" }
    }

    Panel {
        PanelTitle("PLAYER ACCOUNTS")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Allow cracked players", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    if (cracked) "ON — people without a Minecraft account can join" else "OFF — only players with a Minecraft account",
                    color = if (cracked) VP.Red else VP.Muted, fontSize = 12.sp,
                )
            }
            Switch(cracked, { if (it) confirm = true else set(false) })
        }

        if (cracked) {
            Text(
                "Anyone can join under any name, even an admin's. Turn on the whitelist in Settings, or add a login plugin so players must register a password.",
                color = VP.Ink, fontSize = 13.sp,
            )
            if (files.type == "paper") {
                if (hasLogin) Text("Login plugin installed (AuthMe). Players use /register and /login.", color = VP.GrassDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                else VpButton(if (busy.isEmpty()) "ADD LOGIN PROTECTION (AUTHME)" else busy, VP.Grass, Modifier.fillMaxWidth(), enabled = busy.isEmpty()) {
                    scope.launch {
                        busy = "Installing…"; msg = ""
                        try {
                            withContext(Dispatchers.IO) { Modrinth.install(files, AUTHME_ID, "AuthMe", "paper", files.version) { busy = it.take(40) } }
                            hasLogin = true; changed = true; msg = "AuthMe installed."
                        } catch (e: Exception) { msg = e.message ?: "Couldn't install AuthMe" }
                        busy = ""
                    }
                }
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = VP.Ink, fontSize = 13.sp)
        if (changed) {
            if (alive) {
                Text("Restart the server to apply.", color = VP.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                VpButton("RESTART NOW", VP.Gold, Modifier.fillMaxWidth()) { onRestart(); changed = false }
            } else Text("Applies the next time you start the server.", color = VP.Muted, fontSize = 13.sp)
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Allow cracked players?") },
            text = {
                Text(
                    "This turns off Minecraft's account check, so people without a paid account can join.\n\n" +
                        "• Anyone can join with any name — including yours or an admin's — unless you use the whitelist or a login plugin.\n" +
                        "• Switching changes every player's ID. Existing inventories, ops and whitelist entries made in the other mode won't match.\n" +
                        "• Skins may not show for cracked players.",
                )
            },
            confirmButton = { TextButton({ confirm = false; set(true) }) { Text("Allow cracked") } },
            dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
        )
    }
}
