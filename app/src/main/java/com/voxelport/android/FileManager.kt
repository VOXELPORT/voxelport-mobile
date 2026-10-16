package com.voxelport.android

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Paper = Color(0xFFF3EBDC)
private val Card = Color(0xFFFFFBF2)
private val Ink = Color(0xFF15120F)
private val Muted = Color(0xFF6B6052)
private val Red = Color(0xFFC8262B)
private val Grass = Color(0xFF5FAE3B)
private val Gold = Color(0xFFF6CB2F)

private fun human(n: Long): String {
    if (n < 1024) return "$n B"
    val u = listOf("KB", "MB", "GB")
    var v = n.toDouble(); var i = -1
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return (if (v >= 100) "%.0f".format(v) else "%.1f".format(v)) + " " + u[i]
}

/** A plain file browser for the server folder: see plugins/mods/config, import jars, delete. */
@Composable
fun FilesScreen(root: File, serverAlive: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dir by remember { mutableStateOf(root) }
    var tick by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<File?>(null) }

    val entries = remember(dir, tick) {
        (dir.listFiles() ?: emptyArray()).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }
    val rel = dir.toRelativeString(root).ifEmpty { "server" }.let { if (it == "server") "server" else "server/$it" }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val target = dir
            val n = withContext(Dispatchers.IO) {
                var ok = 0
                for (uri in uris) {
                    val name = fileName(ctx, uri) ?: continue
                    runCatching {
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            File(target, name).outputStream().use { input.copyTo(it, 1 shl 16) }
                        }
                        ok++
                    }
                }
                ok
            }
            tick++
            msg = when {
                n == 1 -> t("Added 1 file — restart the server to load it.")
                n > 1 -> t("Added {n} files — restart the server to load them.", "n" to n)
                else -> t("Couldn't add those files.")
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FILES", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Ink, modifier = Modifier.weight(1f))
            TextButton({ if (dir != root) dir = dir.parentFile ?: root else onBack() }) { Text(if (dir != root) "Up" else "Done") }
        }
        Text(rel, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 14.sp)

        // Quick jump to the folders people actually want.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (name in listOf("plugins", "mods", "config")) {
                val f = File(root, name)
                AssistChip(onClick = { f.mkdirs(); dir = f; tick++ }, label = { Text(name) })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("+ IMPORT HERE", Gold) { importer.launch(arrayOf("*/*")) }
            Btn("New folder", Card) {
                var i = 1; var nf = File(dir, "new folder")
                while (nf.exists()) nf = File(dir, "new folder $i").also { i++ }
                nf.mkdirs(); tick++
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = Ink, fontSize = 13.sp)
        Text("Drop plugin .jar files in plugins/ (mods/ for Fabric). Changes apply when the server restarts.", color = Muted, fontSize = 12.sp)

        if (entries.isEmpty()) {
            Text("Empty folder.", color = Muted, modifier = Modifier.padding(top = 12.dp))
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.path }) { f ->
                    Row(
                        Modifier.fillMaxWidth().background(Card).border(2.dp, Ink).clickable { if (f.isDirectory) { dir = f } }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (f.isDirectory) "📁" else "📄", fontSize = 20.sp, modifier = Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                if (!f.isDirectory) human(f.length())
                                else (f.listFiles()?.size ?: 0).let { n -> if (n == 1) t("1 item") else t("{n} items", "n" to n) },
                                color = Muted, fontSize = 12.sp,
                            )
                        }
                        TextButton({ confirmDelete = f }) { Text("Delete", color = Red) }
                    }
                }
            }
        }
    }

    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(t("Delete {name}?", "name" to f.name)) },
            text = { Text(if (f.isDirectory) "This folder and everything in it will be deleted." else "This file will be deleted.") },
            confirmButton = { TextButton({ f.deleteRecursively(); confirmDelete = null; tick++ }) { Text("Delete", color = Red) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun fileName(ctx: android.content.Context, uri: Uri): String? {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0) c.getString(i)?.let { return it.substringAfterLast('/').ifBlank { null } }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')
}

@Composable
private fun Btn(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick, shape = androidx.compose.ui.graphics.RectangleShape,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = if (color == Gold || color == Card) Ink else Color.White),
        modifier = Modifier.border(2.dp, Ink),
    ) { Text(label, fontWeight = FontWeight.Black) }
}
