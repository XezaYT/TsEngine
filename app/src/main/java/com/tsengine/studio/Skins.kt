package com.tsengine.studio

import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import androidx.core.graphics.PathParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SkinPath(val path: Path, val fill: Int)

/** A hot-swappable vector skin. Parts named head/torso/armL/armR/legL/legR become rigged joints; everything else is static "body". */
class Skin(val parts: LinkedHashMap<String, MutableList<SkinPath>>) {
    val bounds = RectF()
    val pivots = HashMap<String, PointF>()

    init {
        val tmp = RectF(); var first = true
        parts.forEach { (name, list) ->
            val pb = RectF(); var pf = true
            list.forEach { sp ->
                sp.path.computeBounds(tmp, true)
                if (pf) { pb.set(tmp); pf = false } else pb.union(tmp)
            }
            if (!pf) {
                if (first) { bounds.set(pb); first = false } else bounds.union(pb)
                pivots[name] = when (name) {
                    "head", "torso" -> PointF(pb.centerX(), pb.bottom)
                    else -> PointF(pb.centerX(), pb.top)
                }
            }
        }
        if (first) bounds.set(0f, 0f, 1f, 1f)
    }
}

object SkinLoader {
    fun load(file: File): Skin? = try {
        val text = file.readText()
        if (file.extension.equals("svg", true) || text.trimStart().startsWith("<")) fromSvg(text) else fromJson(text)
    } catch (e: Exception) { null }

    private fun partOf(vararg cands: String?): String {
        for (c in cands) { if (c == null) continue; Joints.ALL.firstOrNull { it.equals(c.trim(), true) }?.let { return it } }
        return "body"
    }

    private fun color(s: String?): Int? {
        if (s == null) return 0xFF9AA5B1.toInt()
        val v = s.trim()
        if (v.equals("none", true)) return null
        return try {
            if (v.matches(Regex("#[0-9a-fA-F]{3}"))) Color.parseColor("#" + v.substring(1).map { "$it$it" }.joinToString(""))
            else Color.parseColor(v)
        } catch (e: Exception) { 0xFF9AA5B1.toInt() }
    }

    private fun attr(s: String, name: String): String? =
        Regex("""(?<![\w-])$name\s*=\s*["']([^"']*)["']""").find(s)?.groupValues?.get(1)

    private fun styleFill(s: String): String? {
        val st = attr(s, "style") ?: return attr(s, "fill")
        return Regex("""fill\s*:\s*([^;]+)""").find(st)?.groupValues?.get(1)?.trim() ?: attr(s, "fill")
    }

    fun fromSvg(text: String): Skin? {
        val parts = LinkedHashMap<String, MutableList<SkinPath>>()
        val tagRe = Regex("""<(/?)(g|path|rect|circle)\b([^>]*?)(/?)>""", RegexOption.DOT_MATCHES_ALL)
        val stack = ArrayList<Pair<String?, String?>>()
        for (m in tagRe.findAll(text)) {
            val closing = m.groupValues[1] == "/"; val tag = m.groupValues[2]; val a = m.groupValues[3]; val selfClose = m.groupValues[4] == "/"
            val id = attr(a, "id") ?: attr(a, "inkscape:label") ?: attr(a, "data-part")
            if (tag == "g") {
                if (closing) { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) }
                else if (!selfClose) stack.add(Pair(id ?: stack.lastOrNull()?.first, styleFill(a) ?: stack.lastOrNull()?.second))
                continue
            }
            if (closing) continue
            val fill = color(styleFill(a) ?: stack.lastOrNull()?.second) ?: continue
            val path: Path = try {
                when (tag) {
                    "path" -> PathParser.createPathFromPathData(attr(a, "d") ?: continue)
                    "rect" -> Path().apply {
                        val x = attr(a, "x")?.toFloat() ?: 0f; val y = attr(a, "y")?.toFloat() ?: 0f
                        addRect(x, y, x + (attr(a, "width")?.toFloat() ?: 0f), y + (attr(a, "height")?.toFloat() ?: 0f), Path.Direction.CW)
                    }
                    else -> Path().apply { addCircle(attr(a, "cx")?.toFloat() ?: 0f, attr(a, "cy")?.toFloat() ?: 0f, attr(a, "r")?.toFloat() ?: 0f, Path.Direction.CW) }
                }
            } catch (e: Exception) { continue }
            val part = partOf(id, stack.lastOrNull()?.first)
            parts.getOrPut(part) { ArrayList() }.add(SkinPath(path, fill))
        }
        return if (parts.isEmpty()) null else Skin(parts)
    }

    /** Supports: {"layers":[{"name","path","fill"}]}, {"parts":{"head":{"path","fill"}}}, and raw Figma REST node trees (fillGeometry + absoluteBoundingBox). */
    fun fromJson(text: String): Skin? {
        val root = JSONObject(text)
        val parts = LinkedHashMap<String, MutableList<SkinPath>>()
        fun add(name: String?, ancestor: String?, d: String, fill: Int, dx: Float, dy: Float) {
            val p = PathParser.createPathFromPathData(d); if (dx != 0f || dy != 0f) p.offset(dx, dy)
            parts.getOrPut(partOf(name, ancestor)) { ArrayList() }.add(SkinPath(p, fill))
        }
        root.optJSONArray("layers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(o.optString("name"), null, o.optString("path", o.optString("d")), color(o.optString("fill", "#9AA5B1")) ?: continue, 0f, 0f)
            }
        }
        root.optJSONObject("parts")?.let { po ->
            po.keys().forEach { k ->
                val o = po.getJSONObject(k)
                add(k, null, o.optString("path", o.optString("d")), color(o.optString("fill", "#9AA5B1")) ?: return@forEach, 0f, 0f)
            }
        }
        fun walk(n: JSONObject, ancestor: String?) {
            val name = n.optString("name")
            val geo: JSONArray? = n.optJSONArray("fillGeometry")
            if (geo != null) {
                var c = 0xFF9AA5B1.toInt()
                n.optJSONArray("fills")?.optJSONObject(0)?.optJSONObject("color")?.let { col ->
                    c = Color.argb((col.optDouble("a", 1.0) * 255).toInt(), (col.optDouble("r") * 255).toInt(), (col.optDouble("g") * 255).toInt(), (col.optDouble("b") * 255).toInt())
                }
                val bb = n.optJSONObject("absoluteBoundingBox")
                val dx = bb?.optDouble("x", 0.0)?.toFloat() ?: 0f; val dy = bb?.optDouble("y", 0.0)?.toFloat() ?: 0f
                for (i in 0 until geo.length()) add(name, ancestor, geo.getJSONObject(i).optString("path"), c, dx, dy)
            }
            val kids = n.optJSONArray("children") ?: return
            val anc = if (partOf(name) != "body") name else ancestor
            for (i in 0 until kids.length()) walk(kids.getJSONObject(i), anc)
        }
        walk(root, null)
        root.optJSONObject("document")?.let { walk(it, null) }
        return if (parts.isEmpty()) null else Skin(parts)
    }
}
