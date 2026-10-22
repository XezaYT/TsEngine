package com.tsengine.studio

import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class EType { PUPPET, PROP, BOX, WALL, LIGHT }
enum class Target(val aspect: Float, val label: String) { LANDSCAPE(16f / 9f, "16:9"), PORTRAIT(9f / 16f, "9:16") }

enum class Ease(val label: String) {
    LINEAR("Linear"), EASE_IN_OUT("Ease-In-Out"), BACK_OUT("Back-Out"), BOUNCE_OUT("Bounce-Out");

    fun apply(t0: Float): Float {
        val t = t0.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> t
            EASE_IN_OUT -> if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) / 2f
            BACK_OUT -> { val c1 = 1.70158f; val c3 = c1 + 1f; val u = t - 1f; 1f + c3 * u * u * u + c1 * u * u }
            BOUNCE_OUT -> {
                val n1 = 7.5625f; val d1 = 2.75f
                when {
                    t < 1f / d1 -> n1 * t * t
                    t < 2f / d1 -> { val u = t - 1.5f / d1; n1 * u * u + 0.75f }
                    t < 2.5f / d1 -> { val u = t - 2.25f / d1; n1 * u * u + 0.9375f }
                    else -> { val u = t - 2.625f / d1; n1 * u * u + 0.984375f }
                }
            }
        }
    }
}

object Iso {
    const val TW = 96f   // tile width in world px
    const val TH = 48f   // tile height
    const val ZH = 48f   // pixels per unit of elevation
    fun sx(x: Float, y: Float) = (x - y) * TW / 2f
    fun sy(x: Float, y: Float, z: Float) = (x + y) * TH / 2f - z * ZH
}

object Joints { val ALL = listOf("head", "torso", "armL", "armR", "legL", "legR") }

class Entity(val id: String = UUID.randomUUID().toString().take(8), var type: EType, var name: String) {
    var x = 0f; var y = 0f; var z = 0f
    var rot = 0f; var sx = 1f; var sy = 1f
    var flipX = false; var layer = 0
    var w = 1f; var d = 1f; var h = 1f
    var color = 0xFF5C7CFA.toInt()
    var skin: String? = null
    var intensity = 1f; var radius = 6f; var cone = 70f; var aim = 90f
    val joints = LinkedHashMap<String, Float>().apply { Joints.ALL.forEach { put(it, 0f) } }
    val bounds = RectF()

    fun propNames(): List<String> {
        val l = arrayListOf("x", "y", "z", "rot", "sx", "sy")
        when (type) {
            EType.PUPPET -> Joints.ALL.forEach { l.add("j.$it") }
            EType.BOX, EType.WALL -> { l.add("w"); l.add("d"); l.add("h") }
            EType.LIGHT -> { l.add("intensity"); l.add("radius"); l.add("cone"); l.add("aim") }
            else -> {}
        }
        return l
    }

    fun get(p: String): Float = if (p.startsWith("j.")) joints[p.substring(2)] ?: 0f else when (p) {
        "x" -> x; "y" -> y; "z" -> z; "rot" -> rot; "sx" -> sx; "sy" -> sy
        "w" -> w; "d" -> d; "h" -> h
        "intensity" -> intensity; "radius" -> radius; "cone" -> cone; "aim" -> aim
        else -> 0f
    }

    fun set(p: String, v: Float) {
        if (p.startsWith("j.")) { joints[p.substring(2)] = v; return }
        when (p) {
            "x" -> x = v; "y" -> y = v; "z" -> z = v; "rot" -> rot = v; "sx" -> sx = v; "sy" -> sy = v
            "w" -> w = v; "d" -> d = v; "h" -> h = v
            "intensity" -> intensity = v; "radius" -> radius = v; "cone" -> cone = v; "aim" -> aim = v
        }
    }

    fun duplicate(): Entity {
        val c = Entity(type = type, name = "$name copy")
        c.x = x + 0.5f; c.y = y + 0.5f; c.z = z; c.rot = rot; c.sx = sx; c.sy = sy; c.flipX = flipX
        c.layer = layer; c.w = w; c.d = d; c.h = h; c.color = color; c.skin = skin
        c.intensity = intensity; c.radius = radius; c.cone = cone; c.aim = aim
        joints.forEach { (k, v) -> c.joints[k] = v }
        return c
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type.name); put("name", name)
        put("x", x.toDouble()); put("y", y.toDouble()); put("z", z.toDouble())
        put("rot", rot.toDouble()); put("sx", sx.toDouble()); put("sy", sy.toDouble())
        put("flipX", flipX); put("layer", layer)
        put("w", w.toDouble()); put("d", d.toDouble()); put("h", h.toDouble())
        put("color", color); put("skin", skin ?: JSONObject.NULL)
        put("intensity", intensity.toDouble()); put("radius", radius.toDouble())
        put("cone", cone.toDouble()); put("aim", aim.toDouble())
        put("joints", JSONObject().also { j -> joints.forEach { (k, v) -> j.put(k, v.toDouble()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Entity {
            val e = Entity(o.getString("id"), EType.valueOf(o.getString("type")), o.optString("name", "Entity"))
            e.x = o.optDouble("x", 0.0).toFloat(); e.y = o.optDouble("y", 0.0).toFloat(); e.z = o.optDouble("z", 0.0).toFloat()
            e.rot = o.optDouble("rot", 0.0).toFloat(); e.sx = o.optDouble("sx", 1.0).toFloat(); e.sy = o.optDouble("sy", 1.0).toFloat()
            e.flipX = o.optBoolean("flipX", false); e.layer = o.optInt("layer", 0)
            e.w = o.optDouble("w", 1.0).toFloat(); e.d = o.optDouble("d", 1.0).toFloat(); e.h = o.optDouble("h", 1.0).toFloat()
            e.color = o.optInt("color", e.color)
            e.skin = if (o.isNull("skin")) null else o.optString("skin")
            e.intensity = o.optDouble("intensity", 1.0).toFloat(); e.radius = o.optDouble("radius", 6.0).toFloat()
            e.cone = o.optDouble("cone", 70.0).toFloat(); e.aim = o.optDouble("aim", 90.0).toFloat()
            o.optJSONObject("joints")?.let { j -> Joints.ALL.forEach { k -> e.joints[k] = j.optDouble(k, 0.0).toFloat() } }
            return e
        }
    }
}

class Scene {
    val entities = ArrayList<Entity>()
    val globals = linkedMapOf("ambient" to 0f, "fog" to 0f, "vignette" to 0.25f, "shake" to 0f)
    var selectedId: String? = null
    private val counters = HashMap<EType, Int>()

    fun selected(): Entity? = selectedId?.let { id -> entities.firstOrNull { it.id == id } }
    fun find(id: String): Entity? = entities.firstOrNull { it.id == id }

    fun create(type: EType, gx: Float, gy: Float): Entity {
        val n = (counters[type] ?: 0) + 1; counters[type] = n
        val e = Entity(type = type, name = "${type.name.lowercase().replaceFirstChar { it.uppercase() }} $n")
        e.x = gx; e.y = gy
        when (type) {
            EType.PUPPET -> e.color = 0xFFFFB86B.toInt()
            EType.PROP -> e.color = 0xFF63E6BE.toInt()
            EType.BOX -> { e.color = 0xFF74C0FC.toInt() }
            EType.WALL -> { e.w = 4f; e.d = 0.25f; e.h = 2.5f; e.color = 0xFFADB5BD.toInt() }
            EType.LIGHT -> { e.z = 2.5f; e.radius = 6f; e.cone = 70f; e.aim = 90f }
        }
        entities.add(e); selectedId = e.id
        return e
    }

    fun remove(e: Entity) { entities.remove(e); if (selectedId == e.id) selectedId = null }

    fun getProp(target: String, prop: String): Float =
        if (target == "g") globals[prop] ?: 0f else find(target)?.get(prop) ?: 0f

    fun setProp(target: String, prop: String, v: Float) {
        if (target == "g") { if (globals.containsKey(prop)) globals[prop] = v } else find(target)?.set(prop, v)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("entities", JSONArray().also { a -> entities.forEach { a.put(it.toJson()) } })
        put("globals", JSONObject().also { g -> globals.forEach { (k, v) -> g.put(k, v.toDouble()) } })
    }

    fun loadJson(o: JSONObject) {
        entities.clear(); selectedId = null
        val a = o.optJSONArray("entities") ?: JSONArray()
        for (i in 0 until a.length()) entities.add(Entity.fromJson(a.getJSONObject(i)))
        o.optJSONObject("globals")?.let { g -> globals.keys.toList().forEach { k -> globals[k] = g.optDouble(k, globals[k]!!.toDouble()).toFloat() } }
    }
}
