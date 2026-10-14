package com.voxelport.android

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Back up or restore the secret code that your public port and custom address belong to.
 * Uninstalling the app, clearing its data or switching phones makes a new code, and an address
 * (and any supporter perks on it) stays with the old one, so this lets you carry it over.
 */
@Composable
fun IdentityDialog(tunnelOn: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }

    fun copyBackup() {
        val clip = ClipData.newPlainText("VoxelPort backup code", prefs.token)
        // Android 13+: keep the code out of the clipboard preview.
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        msg = "Backup code copied. Paste it somewhere private (a password manager or a note to yourself)."
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Your address backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Your public port and custom address belong to a secret code kept on this phone. If you uninstall VoxelPort, clear its data or switch phones, " +
                        "a new code is made and the old address is left behind (supporter perks included). Copy the code to keep it.",
                    fontSize = 14.sp,
                )
                Text("Anyone who has the code can take over your address, so keep it private.", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                VpButton("COPY BACKUP CODE", VP.Gold, Modifier.fillMaxWidth()) { copyBackup() }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Restore on a reinstalled app or a new phone", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                OutlinedTextField(
                    code, { code = it.take(200) }, label = { Text("Paste your backup code") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (tunnelOn) Text("Stop the public address first, then restore.", color = VP.Red, fontSize = 12.sp)
                VpButton("RESTORE", VP.Grass, Modifier.fillMaxWidth(), enabled = !tunnelOn && IdentityLogic.looksValid(code)) { confirm = true }
                if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } },
    )

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Use this code?") },
            text = { Text("This phone will use the address that belongs to that code. The address it has now (if any) is left behind, and can only be recovered with this phone's own backup code.") },
            confirmButton = {
                TextButton({
                    confirm = false
                    msg = if (prefs.restoreToken(code)) { code = ""; "Restored. Make your server public to get your address back." }
                    else "That doesn't look like a VoxelPort backup code."
                }) { Text("Restore") }
            },
            dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
        )
    }
}
