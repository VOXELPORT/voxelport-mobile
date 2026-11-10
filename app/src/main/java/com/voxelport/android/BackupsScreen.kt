package com.voxelport.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Backups of the world: make one now, let the app make them by itself, go back to an earlier one, or keep a copy
 * outside the app. The work runs in [BackupRunner] (in the server's own process while it is running).
 *
 * @param alive the server process is running; @param online it is up and accepting players (safe to ask it to save)
 */
@Composable
fun BackupsScreen(files: Files, alive: Boolean, online: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember { BackupRunner(BackupPlace.of(files), ServerService.appBuild(ctx)) }
    var settings by remember { mutableStateOf(runner.settings()) }
    var status by remember { mutableStateOf(BackupStatus()) }
    var items by remember { mutableStateOf<List<BackupEntry>>(emptyList()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var restoring by remember { mutableStateOf<BackupEntry?>(null) }
    var deleting by remember { mutableStateOf<BackupEntry?>(null) }
    var saving by remember { mutableStateOf<BackupEntry?>(null) }
    var note by remember { mutableStateOf("") }

    // Watch the job (it may be running in the server's process) and the list, while this screen is on.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val (s, l) = withContext(Dispatchers.IO) { runner.status() to Backups.list(files.backups) }
                status = s; items = l; now = System.currentTimeMillis()
                delay(if (s.isActive(now)) 400 else 2000)
            }
        }
    }
    val active = status.isActive(now)

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val entry = saving
        saving = null
        if (uri != null && entry != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { out -> File(files.backups, entry.name).inputStream().use { it.copyTo(out) } } != null
                }.getOrDefault(false)
            }
            note = if (ok) t("Copy saved.") else t("Could not save the copy.")
        }
    }

    fun backupNow() {
        note = ""
        if (alive) ServerService.send(ctx, ServerService.ACTION_BACKUP)
        else scope.launch(Dispatchers.IO) { runner.run("manual", live = false) }
    }

    fun saveSettings(s: BackupSettings) {
        settings = s
        scope.launch(Dispatchers.IO) { runner.saveSettings(s) }
    }

    val dateFmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Column(
        Modifier.fillMaxSize().background(VP.Paper).systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BACKUPS", fontSize = 30.sp, fontWeight = FontWeight.Black, color = VP.Ink, modifier = Modifier.weight(1f))
            TextButton(onBack) { Text("Done") }
        }

        Panel {
            PanelTitle("BACK UP YOUR WORLD")
            Text(
                "A backup is a copy of your world. If something goes wrong (a mistake, a crash, someone breaking your builds), you can go back to it.",
                color = VP.Muted, fontSize = 13.sp,
            )
            val last = items.firstOrNull { it.reason != "before-restore" }
            Text(
                if (last == null) t("No backup yet.") else t("Last backup: {ago}", "ago" to BackupsLogic.ago(now, last.createdAt)),
                fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (last == null) VP.Red else VP.GrassDark,
            )
            if (active) {
                if (status.total > 0) LinearProgressIndicator({ (status.done.toFloat() / status.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = VP.Grass)
                else LinearProgressIndicator(Modifier.fillMaxWidth(), color = VP.Grass)
                Text(BackupsLogic.phase(status.phase), fontSize = 13.sp, color = VP.Ink)
            } else if (status.finishedAt > 0 && now - status.finishedAt < 120_000) {
                Text(BackupsLogic.result(status), fontSize = 13.sp, color = if (status.phase == "failed") VP.Red else VP.GrassDark)
            }
            if (note.isNotEmpty()) Text(note, fontSize = 13.sp, color = VP.Ink)
            VpButton(
                if (active && status.reason != "restore") "BACKING UP…" else "BACK UP NOW", VP.Grass, Modifier.fillMaxWidth(),
                enabled = !active && (!alive || online),
            ) { backupNow() }
            if (alive && !online) Text("Wait until the server is online, then you can back it up.", color = VP.Muted, fontSize = 12.sp)
            else if (alive) Text("The server is asked to save first, so the copy is complete. Players are not kicked.", color = VP.Muted, fontSize = 12.sp)
        }

        Panel {
            PanelTitle("AUTOMATIC BACKUPS")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Back up automatically", fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(settings.auto, { saveSettings(settings.copy(auto = it)) })
            }
            if (settings.auto) {
                Text("Back up every", fontSize = 12.sp, color = VP.Muted)
                Choices((BackupSettings.INTERVALS + settings.intervalMin).distinct().sorted(), settings.intervalMin, { BackupsLogic.interval(it) }) {
                    saveSettings(settings.copy(intervalMin = it))
                }
                Text("Keep the newest automatic backups", fontSize = 12.sp, color = VP.Muted)
                Choices((BackupSettings.KEEPS + settings.keep).distinct().sorted(), settings.keep, { it.toString() }) {
                    saveSettings(settings.copy(keep = it))
                }
            }
            Text(
                "A backup is only made when something in the world changed, and one more is made when you stop the server. Backups you make yourself are never deleted.",
                color = VP.Muted, fontSize = 12.sp,
            )
        }

        Panel {
            PanelTitle("YOUR BACKUPS")
            if (items.isEmpty()) Text("Nothing here yet. Tap BACK UP NOW to make the first one.", color = VP.Muted, fontSize = 13.sp)
            else {
                val total = items.sumOf { it.size }
                Text(
                    if (items.size == 1) t("1 backup uses {size} of storage.", "size" to BackupsLogic.size(total))
                    else t("{n} backups use {size} of storage.", "n" to items.size, "size" to BackupsLogic.size(total)),
                    color = VP.Muted, fontSize = 12.sp,
                )
            }
            for (b in items) {
                Column(Modifier.fillMaxWidth().border(2.dp, VP.Ink).background(VP.Paper).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text(dateFmt.format(Date(b.createdAt)), fontWeight = FontWeight.Black, fontSize = 15.sp)
                        Text(
                            "${BackupsLogic.reason(b.reason)} · ${BackupsLogic.size(b.size)} · ${BackupsLogic.ago(now, b.createdAt)}",
                            color = VP.Muted, fontSize = 12.sp,
                        )
                    }
                    VpButton("RESTORE", VP.Gold, Modifier.fillMaxWidth(), enabled = !alive && !active) { restoring = b }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VpButton("SAVE COPY", VP.Card, Modifier.weight(1f), enabled = !active) { saving = b; saveLauncher.launch(b.name) }
                        VpButton("DELETE", VP.Card, Modifier.weight(1f), enabled = !active) { deleting = b }
                    }
                }
            }
            val hint = BackupsLogic.restoreHint(alive)
            if (hint.isNotEmpty() && items.isNotEmpty()) Text(hint, color = VP.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(24.dp))
    }

    restoring?.let { b ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    t(
                        "Your world goes back to how it was on {when}. What is there now is saved as a backup first, so you can undo this.",
                        "when" to dateFmt.format(Date(b.createdAt)),
                    ),
                )
            },
            confirmButton = {
                TextButton({
                    restoring = null
                    note = ""
                    if (ServerService.isAlive(ctx)) note = BackupsLogic.restoreHint(true)
                    else scope.launch(Dispatchers.IO) { runner.restore(b.name) }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton({ restoring = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { b ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this backup?") },
            text = { Text(t("The backup from {when} is removed from this phone for good.", "when" to dateFmt.format(Date(b.createdAt)))) },
            confirmButton = {
                TextButton({
                    deleting = null
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { Backups.delete(files.backups, b.name) } }
                        items = withContext(Dispatchers.IO) { Backups.list(files.backups) }
                    }
                }) { Text("Delete", color = VP.Red) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
}

/** "Last backup: 12 min ago" under the BACKUPS button on the home screen; looks again now and then while the app is on screen. */
@Composable
fun LastBackupLine(files: Files) {
    var last by remember { mutableLongStateOf(-1L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                last = withContext(Dispatchers.IO) { Backups.list(files.backups).firstOrNull { it.reason != "before-restore" }?.createdAt ?: 0L }
                now = System.currentTimeMillis()
                delay(20_000)
            }
        }
    }
    if (last < 0) return
    Text(
        if (last == 0L) t("No backup yet.") else t("Last backup: {ago}", "ago" to BackupsLogic.ago(now, last)),
        color = if (last == 0L) VP.Red else VP.Muted, fontSize = 12.sp,
    )
}

/** A row of tappable choices (rows of 3), one selected. */
@Composable
private fun Choices(options: List<Int>, selected: Int, label: (Int) -> String, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { v ->
                    val sel = v == selected
                    Box(
                        Modifier.border(2.dp, VP.Ink).background(if (sel) VP.Gold else VP.Card).clickable { onPick(v) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { Text(label(v), fontSize = 13.sp, fontWeight = if (sel) FontWeight.Black else FontWeight.Normal, color = VP.Ink) }
                }
            }
        }
    }
}
