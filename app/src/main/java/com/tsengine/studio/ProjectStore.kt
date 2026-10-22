package com.tsengine.studio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class Project(val scene: Scene, val timeline: Timeline) {
    var name = "untitled"
    var target = Target.LANDSCAPE
    val skins = ConcurrentHashMap<String, Skin>()
}

/** All persistence is app-specific external storage (…/Android/data/<pkg>/files). No network, no cloud. */
class ProjectStore(private val ctx: Context) {
    private val root: File = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    val projectsDir = File(root, "projects").apply { mkdirs() }
    val exportsDir = File(root, "exports").apply { mkdirs() }

    private fun safe(n: String) = n.trim().replace(Regex("[^A-Za-z0-9_\\- ]"), "_").ifEmpty { "untitled" }
    fun dir(name: String) = File(projectsDir, safe(name)).apply { mkdirs() }
    fun assets(name: String) = File(dir(name), "assets").apply { mkdirs() }
    fun list(): List<String> = projectsDir.listFiles()?.filter { File(it, "project.json").exists() }?.map { it.name }?.sorted() ?: emptyList()

    fun save(p: Project, audio: AudioEngine) {
        val o = JSONObject()
        o.put("version", 1); o.put("name", p.name); o.put("target", p.target.name)
        synchronized(p.scene) {
            o.put("scene", p.scene.toJson()); o.put("timeline", p.timeline.toJson())
        }
        o.put("audio", audio.toJson())
        val f = File(dir(p.name), "project.json"); val tmp = File(dir(p.name), "project.json.tmp")
        tmp.writeText(o.toString(2)); if (f.exists()) f.delete(); tmp.renameTo(f)
    }

    fun load(name: String, p: Project, audio: AudioEngine): Boolean {
        val f = File(dir(name), "project.json"); if (!f.exists()) return false
        return try {
            val o = JSONObject(f.readText())
            p.name = o.optString("name", name)
            p.target = Target.valueOf(o.optString("target", "LANDSCAPE"))
            p.timeline.pause()
            synchronized(p.scene) {
                p.scene.loadJson(o.getJSONObject("scene"))
                p.timeline.loadJson(o.getJSONObject("timeline"))
            }
            p.skins.clear()
            val assets = assets(p.name)
            p.scene.entities.mapNotNull { it.skin }.distinct().forEach { s -> SkinLoader.load(File(assets, s))?.let { p.skins[s] = it } }
            audio.loadJson(o.optJSONArray("audio"), assets)
            true
        } catch (e: Exception) { false }
    }

    fun displayName(uri: Uri): String {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "asset"
    }

    /** Copies a picked local file into the project's assets folder so it survives offline. */
    fun importAsset(uri: Uri, projectName: String): File {
        val name = safe(displayName(uri).substringBeforeLast('.')) + "." + displayName(uri).substringAfterLast('.', "bin")
        val out = File(assets(projectName), name)
        ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        return out
    }
}
