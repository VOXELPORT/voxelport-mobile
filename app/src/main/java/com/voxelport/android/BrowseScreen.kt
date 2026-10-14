package com.voxelport.android

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

private val Paper = Color(0xFFF3EBDC)
private val Card = Color(0xFFFFFBF2)
private val Ink = Color(0xFF15120F)
private val Muted = Color(0xFF6B6052)
private val Red = Color(0xFFC8262B)
private val Grass = Color(0xFF5FAE3B)
private val Gold = Color(0xFFF6CB2F)

private object Icons {
    private val cache = LruCache<String, ImageBitmap>(60)
    suspend fun get(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val c = Setup.open(url)
                c.inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()?.also { cache.put(url, it) }
            }.getOrNull()
        }
    }
}

@Composable
private fun Icon(url: String?) {
    val img by produceState<ImageBitmap?>(null, url) { value = url?.let { Icons.get(it) } }
    Box(Modifier.size(56.dp).background(Color(0xFFE6DCC6)).border(2.dp, Ink)) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private fun compact(n: Long) = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.0fK".format(n / 1_000.0)
    else -> n.toString()
}

/** One-tap plugin (Paper) / mod (Fabric) installs from Modrinth. */
@Composable
fun BrowseScreen(files: Files, alive: Boolean, onBack: () -> Unit, onRestart: () -> Unit) {
    val scope = rememberCoroutineScope()
    val type = files.type
    val mc = files.version
    val noun = Modrinth.noun(type)

    var tab by remember { mutableStateOf("browse") }
    var query by remember { mutableStateOf("") }
    var anyVersion by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<Modrinth.Hit>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var more by remember { mutableStateOf(false) }
    var installedTick by remember { mutableIntStateOf(0) }
    val installed = remember(installedTick) { Modrinth.installed(files) }
    var working by remember { mutableStateOf<String?>(null) } // project id being installed
    var progress by remember { mutableStateOf("") }
    var changed by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    suspend fun load(offset: Int) {
        loading = true; error = ""
        try {
            val r = withContext(Dispatchers.IO) { Modrinth.search(type, mc, query, anyVersion, offset) }
            hits = if (offset == 0) r else hits + r
            more = r.size >= 20
        } catch (e: Exception) {
            error = "Couldn't reach Modrinth: ${e.message}"
        }
        loading = false
    }

    // Search as you type (and when the filter changes).
    LaunchedEffect(query, anyVersion) { delay(if (query.isEmpty()) 0 else 400); load(0) }

    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(noun.uppercase(), fontSize = 30.sp, fontWeight = FontWeight.Black, color = Ink, modifier = Modifier.weight(1f))
            TextButton(onBack) { Text("Done") }
        }
        Text("From Modrinth · ${type.replaceFirstChar { it.uppercase() }} ${mc.ifEmpty { "" }}", color = Muted, fontSize = 13.sp)

        TabRow(if (tab == "browse") 0 else 1, containerColor = Paper, contentColor = Ink) {
            Tab(tab == "browse", { tab = "browse" }, text = { Text("BROWSE", fontWeight = FontWeight.Black) })
            Tab(tab == "installed", { tab = "installed" }, text = { Text("INSTALLED (${installed.size})", fontWeight = FontWeight.Black) })
        }

        if (changed) {
            Row(
                Modifier.fillMaxWidth().background(Gold).border(2.dp, Ink).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (alive) "Restart the server to load your changes." else "Start the server to load your changes.", fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f))
                if (alive) TextButton({ onRestart(); changed = false; note = "Restarting…" }) { Text("RESTART", color = Ink, fontWeight = FontWeight.Black) }
            }
        }
        if (note.isNotEmpty()) Text(note, color = Ink, fontSize = 13.sp)

        if (tab == "browse") {
            OutlinedTextField(
                query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search $noun…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(anyVersion, { anyVersion = it })
                Text("Show $noun for any Minecraft version", fontSize = 13.sp)
            }
            if (error.isNotEmpty()) Text(error, color = Red, fontSize = 13.sp)
            if (loading && hits.isEmpty()) Text("Loading…", color = Muted)
            if (!loading && hits.isEmpty() && error.isEmpty()) Text(
                if (anyVersion) "Nothing found." else "Nothing found for Minecraft $mc. Tick the box above to see all versions.", color = Muted, fontSize = 13.sp,
            )
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hits, key = { it.id }) { h ->
                    val done = installed.any { it.id == h.id }
                    Row(Modifier.fillMaxWidth().background(Card).border(2.dp, Ink).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(h.icon)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(h.title, fontWeight = FontWeight.Black, color = Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${h.author} · ${compact(h.downloads)} downloads", color = Muted, fontSize = 11.sp)
                            Text(h.desc, color = Ink, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (working == h.id) Text(progress, color = Muted, fontSize = 11.sp)
                        }
                        Button(
                            {
                                scope.launch {
                                    working = h.id; progress = "Starting…"; note = ""
                                    try {
                                        val names = withContext(Dispatchers.IO) { Modrinth.install(files, h.id, h.title, type, mc) { progress = it } }
                                        installedTick++; changed = true
                                        note = "Installed ${names.joinToString(", ")}."
                                    } catch (e: Exception) {
                                        note = e.message ?: "Install failed"
                                    }
                                    working = null
                                }
                            },
                            enabled = working == null && !done, shape = androidx.compose.ui.graphics.RectangleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = if (done) Color(0xFFBFB6A3) else Grass, contentColor = Color.White),
                            modifier = Modifier.border(2.dp, Ink),
                        ) { Text(if (done) "INSTALLED" else if (working == h.id) "…" else "INSTALL", fontWeight = FontWeight.Black, fontSize = 12.sp) }
                    }
                }
                if (more) item {
                    TextButton({ scope.launch { load(hits.size) } }, enabled = !loading) { Text(if (loading) "Loading…" else "Load more") }
                }
            }
        } else {
            if (installed.isEmpty()) Text("Nothing installed from here yet. Anything you add in Files shows up there.", color = Muted, fontSize = 13.sp)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(installed, key = { it.id }) { a ->
                    Row(Modifier.fillMaxWidth().background(Card).border(2.dp, Ink).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.title, fontWeight = FontWeight.Black, color = Ink)
                            Text("${a.dir}/${a.file}", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton({ Modrinth.remove(files, a.id); installedTick++; changed = true }) { Text("Remove", color = Red) }
                    }
                }
            }
        }
    }
}
