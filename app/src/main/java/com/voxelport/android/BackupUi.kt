package com.voxelport.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun resultText(r: AddressBackupResult): String = when (r) {
    AddressBackupResult.SAVED -> t("Backed up. Remember your passphrase: VoxelPort can't recover it.")
    AddressBackupResult.RESTORED -> t("Restored. Make your server public to get your address back.")
    AddressBackupResult.DELETED -> t("Backup deleted.")
    AddressBackupResult.WRONG_PASSPHRASE -> t("That passphrase doesn't open the backup.")
    AddressBackupResult.NOT_FOUND -> t("There is no backup on your account.")
    AddressBackupResult.FAILED -> t("That didn't work. Check your connection and try again.")
}

/**
 * Your address, backed up to your Discord account and locked with a passphrase only you know. On a reinstalled app or a
 * new phone (or on the desktop app) you sign in with Discord, type the passphrase and get the same address back. VoxelPort
 * keeps only the locked copy and cannot open it.
 */
@Composable
fun AddressBackupPanel(acct: AccountState, tunnelOn: Boolean) {
    if (!acct.signedIn) return
    val ctx = LocalContext.current
    var dialog by remember { mutableStateOf("") } // "save", "restore" or "delete"
    val info = acct.backup
    val other = info != null && acct.ownId != null && info.vpId != acct.ownId

    // A finished backup or restore closes its dialog; a wrong passphrase keeps it open to try again.
    LaunchedEffect(acct.backupResult) {
        when (acct.backupResult) {
            AddressBackupResult.SAVED, AddressBackupResult.RESTORED, AddressBackupResult.DELETED, AddressBackupResult.NOT_FOUND -> dialog = ""
            else -> Unit
        }
    }

    Panel {
        PanelTitle("ADDRESS BACKUP")
        Text(
            when {
                info == null -> t("Back up your address to your Discord account, so you can get it back on a new phone or PC.")
                other -> t("Your Discord account has a backed-up address (ending {id}) that is not the one on this phone. Restore it to use that address here.", "id" to info.vpId.takeLast(4))
                else -> t("Your address is backed up to your Discord account.")
            },
            fontSize = 14.sp, fontWeight = if (other) FontWeight.Bold else FontWeight.Normal,
        )
        acct.backupResult?.let { if (dialog.isEmpty()) Text(resultText(it), color = if (it == AddressBackupResult.FAILED || it == AddressBackupResult.WRONG_PASSPHRASE) VP.Red else VP.Muted, fontSize = 13.sp) }
        VpButton("BACK UP MY ADDRESS", VP.Gold, Modifier.fillMaxWidth(), enabled = !acct.backupBusy) { AppAccount.clearBackupResult(); dialog = "save" }
        if (info != null) {
            VpButton("RESTORE MY ADDRESS", VP.Grass, Modifier.fillMaxWidth(), enabled = !acct.backupBusy) { AppAccount.clearBackupResult(); dialog = "restore" }
            TextButton({ AppAccount.clearBackupResult(); dialog = "delete" }, enabled = !acct.backupBusy) { Text("Delete backup", fontSize = 13.sp) }
        }
    }

    when (dialog) {
        "save" -> PassphraseDialog(
            title = "Back up your address", confirmLabel = "Back up", ask2 = true, tunnelOn = false, acct = acct,
            onConfirm = { AppAccount.saveBackup(ctx, it) }, onClose = { dialog = "" },
        )
        "restore" -> PassphraseDialog(
            title = "Restore your address", confirmLabel = "Restore", ask2 = false, tunnelOn = tunnelOn, acct = acct,
            onConfirm = { AppAccount.restoreBackup(ctx, it) }, onClose = { dialog = "" },
        )
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Delete the backup?") },
            text = { Text("The locked copy is removed from your Discord account. Your address on this phone is not changed.") },
            confirmButton = { TextButton({ AppAccount.deleteBackup(ctx) }) { Text("Delete") } },
            dismissButton = { TextButton({ dialog = "" }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PassphraseDialog(
    title: String, confirmLabel: String, ask2: Boolean, tunnelOn: Boolean, acct: AccountState,
    onConfirm: (String) -> Unit, onClose: () -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val problem = if (ask2) IdentityBackup.passphraseProblem(pass) else null
    val mismatch = ask2 && again.isNotEmpty() && again != pass
    val ready = if (ask2) pass.isNotEmpty() && problem == null && again == pass else pass.isNotEmpty()
    val field = KeyboardOptions(keyboardType = KeyboardType.Password)
    AlertDialog(
        onDismissRequest = { if (!acct.backupBusy) onClose() },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ask2) {
                    Text("Choose a passphrase of at least 12 characters. Only you know it: VoxelPort can't recover it, and without it the backup can't be opened.", fontSize = 14.sp)
                } else {
                    Text("Type the passphrase you chose when you backed up your address. This phone will then use that address, and the one it has now is left behind.", fontSize = 14.sp)
                    if (tunnelOn) Text("Stop the public address first, then restore.", color = VP.Red, fontSize = 13.sp)
                }
                OutlinedTextField(
                    pass, { pass = it.take(200) }, label = { Text("Passphrase") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = field,
                )
                if (ask2) {
                    OutlinedTextField(
                        again, { again = it.take(200) }, label = { Text("Type it again") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = field,
                    )
                    if (pass.isNotEmpty() && problem == IdentityBackup.Problem.TOO_SHORT) Text("Use at least 12 characters.", color = VP.Red, fontSize = 12.sp)
                    if (problem == IdentityBackup.Problem.TOO_SIMPLE) Text("Choose something less repetitive.", color = VP.Red, fontSize = 12.sp)
                    if (mismatch) Text("The two passphrases don't match.", color = VP.Red, fontSize = 12.sp)
                }
                if (acct.backupBusy) Text("Working…", color = VP.Muted, fontSize = 13.sp)
                acct.backupResult?.let { Text(resultText(it), color = VP.Red, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton({ onConfirm(pass) }, enabled = ready && !acct.backupBusy && !tunnelOn) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClose, enabled = !acct.backupBusy) { Text("Cancel") } },
    )
}
