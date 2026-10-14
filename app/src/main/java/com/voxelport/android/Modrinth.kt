package com.voxelport.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Modrinth (modrinth.com) client: search plugins (Paper) or mods (Fabric), pick the
 * newest version compatible with the installed Minecraft version, install it with
 * its required dependencies, and remember what was installed so it can be removed.
 */
object Modrinth {
    private const val API = "https://api.modrinth.com/v2"

    data class Hit(
        val id: String, val slug: String, val title: String, val desc: String,
        val downloads: Long, val icon: String?, val author: String,
    )

    /** An add-on VoxelPort installed (tracked in voxelport-addons.json). */
    data class Addon(val id: String, val title: String, val file: String, val dir: String)

    /** Loaders to search/install for: Paper runs Paper, Spigot and Bukkit plugins. */
    fun loaders(type: String) = if (type == "fabric") listOf("fabric") else listOf("paper", "spigot", "bukkit")
    fun noun(type: String) = if (type == "fabric") "mods" else "plugins"
    fun dir(f: Files, type: String) = if (type == "fabric") f.mods else f.plugins

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun search(type: String, mc: String, query: String, anyVersion: Boolean, offset: Int): List<Hit> {
        val facets = JSONArray().apply {
            put(JSONArray(if (type == "fabric") listOf("project_type:mod") else listOf("project_type:mod", "project_type:plugin")))
            put(JSONArray(loaders(type).map { "categories:$it" }))
            if (!anyVersion && mc.isNotEmpty()) put(JSONArray(listOf("versions:$mc")))
        }
        val index = if (query.isBlank()) "downloads" else "relevance"
        val url = "$API/search?query=${enc(query.trim())}&facets=${enc(facets.toString())}&limit=20&offset=$offset&index=$index"
        val hits = Setup.getJson(url).getJSONArray("hits")
        return (0 until hits.length()).map { i ->
            val h = hits.getJSONObject(i)
            Hit(
                id = h.getString("project_id"), slug = h.optString("slug"), title = h.getString("title"),
                desc = h.optString("description"), downloads = h.optLong("downloads"),
                icon = if (h.isNull("icon_url")) null else h.optString("icon_url").ifEmpty { null },
                author = h.optString("author"),
            )
        }
    }

    /** Newest compatible version: prefers a release, falls back to beta/alpha. */
    private fun bestVersion(projectId: String, type: String, mc: String): JSONObject? {
        var url = "$API/project/$projectId/version?loaders=${enc(JSONArray(loaders(type)).toString())}"
        if (mc.isNotEmpty()) url += "&game_versions=${enc(JSONArray(listOf(mc)).toString())}"
        val arr = Setup.getJsonArray(url)
        var fallback: JSONObject? = null
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            if (v.optString("version_type") == "release") return v
            if (fallback == null) fallback = v
        }
        return fallback
    }

    // ── what's installed ──
    private fun registry(f: Files) = File(f.server, "voxelport-addons.json")

    fun installed(f: Files): List<Addon> {
        val file = registry(f)
        if (!file.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Addon(o.getString("id"), o.getString("title"), o.getString("file"), o.getString("dir"))
            }
        }.getOrDefault(emptyList()).filter { File(File(f.server, it.dir), it.file).isFile }
    }

    private fun save(f: Files, list: List<Addon>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("file", it.file).put("dir", it.dir)) }
        registry(f).writeText(arr.toString())
    }

    fun isInstalled(f: Files, id: String) = installed(f).any { it.id == id }

    fun remove(f: Files, id: String) {
        val all = installed(f)
        all.filter { it.id == id }.forEach { File(File(f.server, it.dir), it.file).delete() }
        save(f, all.filter { it.id != id })
    }

    /** Installs [projectId] (and its required dependencies). Returns the titles installed. */
    fun install(
        f: Files, projectId: String, title: String, type: String, mc: String, depth: Int = 0,
        /** Called with each file's size before it is downloaded; may throw to stop (see [BigDownload]). */
        sizeGuard: ((title: String, bytes: Long) -> Unit)? = null,
        progress: (String) -> Unit,
    ): List<String> {
        val v = bestVersion(projectId, type, mc)
            ?: error("$title has no ${if (type == "fabric") "Fabric" else "Paper"} version for Minecraft $mc")
        val done = ArrayList<String>()

        // Required dependencies first (e.g. Fabric API for most mods).
        val deps = v.optJSONArray("dependencies") ?: JSONArray()
        for (i in 0 until deps.length()) {
            val d = deps.getJSONObject(i)
            if (d.optString("dependency_type") != "required" || d.isNull("project_id") || depth >= 2) continue
            val depId = d.getString("project_id")
            if (isInstalled(f, depId)) continue
            val depTitle = runCatching { Setup.getJson("$API/project/$depId").getString("title") }.getOrDefault("a required library")
            done += install(f, depId, depTitle, type, mc, depth + 1, sizeGuard, progress)
        }

        val files = v.getJSONArray("files")
        var pick: JSONObject = files.getJSONObject(0)
        for (i in 0 until files.length()) if (files.getJSONObject(i).optBoolean("primary")) pick = files.getJSONObject(i)
        val name = File(pick.getString("filename")).name
        if (!name.endsWith(".jar", ignoreCase = true)) error("$title ships as ${name.substringAfterLast('.')}, not a .jar")
        sizeGuard?.invoke(title, pick.optLong("size"))
        val target = dir(f, type).apply { mkdirs() }
        val dest = File(target, name)

        // Replace an older copy of the same project.
        installed(f).filter { it.id == projectId }.forEach { File(File(f.server, it.dir), it.file).delete() }

        val sha512 = pick.optJSONObject("hashes")?.optString("sha512")?.ifEmpty { null }
        Setup.download(pick.getString("url"), dest, sha512, title, progress, "SHA-512")

        val list = installed(f).filter { it.id != projectId } + Addon(projectId, title, name, target.name)
        save(f, list)
        return done + title
    }
}
