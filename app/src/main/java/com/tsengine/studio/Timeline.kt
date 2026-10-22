package com.tsengine.studio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

class Key(var t: Long, var v: Float, var ease: Ease)

class Track(val target: String, val prop: String) {
    val keys = ArrayList<Key>()
    val id get() = "$target|$prop"

    fun put(t: Long, v: Float, e: Ease) {
        keys.firstOrNull { it.t == t }?.let { it.v = v; it.ease = e; return }
        keys.add(Key(t, v, e)); keys.sortBy { it.t }
    }

    fun eval(t: Long): Float {
        if (keys.isEmpty()) return 0f
        if (t <= keys.first().t) return keys.first().v
        if (t >= keys.last().t) return keys.last().v
        for (i in 0 until keys.size - 1) {
            val a = keys[i]; val b = keys[i + 1]
            if (t >= a.t && t <= b.t) {
                val f = (t - a.t).toFloat() / (b.t - a.t).toFloat()
                return a.v + (b.v - a.v) * a.ease.apply(f)
            }
        }
        return keys.last().v
    }
}

/** Master clock. Audio is slaved to it (hard-locked: any drift >80ms triggers a re-seek). */
class Timeline(private val scene: Scene) {
    var durationMs = 10_000L
    var fps = 30
    @Volatile var loop = true
    @Volatile var speed = 1f
        set(s) {
            field = s
            audio?.applySpeed(s)
        }
    @Volatile var timeMs = 0L
    @Volatile var playing = false
    val tracks = LinkedHashMap<String, Track>()
    var audio: AudioEngine? = null
    var onPlayStateChanged: (() -> Unit)? = null
    private var lastNanos = 0L
    private var clock = 0.0

    fun frameIndex(): Int = Math.round(timeMs * fps / 1000f)
    private fun snappedNow(): Long = (frameIndex() * 1000f / fps).roundToLong()

    fun play() {
        synchronized(scene) {
            if (timeMs >= durationMs) timeMs = 0
            clock = timeMs.toDouble(); lastNanos = 0L; playing = true
            audio?.update(timeMs, true, speed, true)
        }
        onPlayStateChanged?.invoke()
    }

    fun pause() {
        synchronized(scene) { playing = false; audio?.update(timeMs, false, speed, false) }
        onPlayStateChanged?.invoke()
    }

    fun toggle() { if (playing) pause() else play() }

    fun setSpeed(s: Float) { speed = s }

    fun seek(ms: Long) {
        synchronized(scene) {
            timeMs = ms.coerceIn(0L, durationMs); clock = timeMs.toDouble()
            applyAt(timeMs)
            audio?.update(timeMs, playing, speed, true)
        }
    }

    fun seekFrame(i: Int) = seek((i.coerceAtLeast(0) * 1000f / fps).roundToLong())
    fun step(delta: Int) { pause(); seekFrame(frameIndex() + delta) }

    /** Called once per vsync from the render thread. */
    fun tick(nowNanos: Long) {
        if (!playing) return
        synchronized(scene) {
            if (lastNanos == 0L) { lastNanos = nowNanos; return }
            val dtMs = (nowNanos - lastNanos) / 1_000_000.0
            lastNanos = nowNanos
            clock += dtMs * speed
            var ended = false
            if (clock >= durationMs) {
                if (loop) clock %= durationMs.toDouble() else { clock = durationMs.toDouble(); ended = true }
            }
            timeMs = clock.toLong()
            applyAt(timeMs)
            if (ended) { playing = false; audio?.update(timeMs, false, speed, false); onPlayStateChanged?.invoke() }
            else audio?.update(timeMs, true, speed, false)
        }
    }

    fun applyAt(t: Long) { tracks.values.forEach { scene.setProp(it.target, it.prop, it.eval(t)) } }

    private fun track(target: String, prop: String) = tracks.getOrPut("$target|$prop") { Track(target, prop) }

    fun recordEntity(e: Entity, ease: Ease, props: List<String> = e.propNames()) {
        synchronized(scene) { val t = snappedNow(); props.forEach { track(e.id, it).put(t, e.get(it), ease) } }
    }

    fun recordGlobals(ease: Ease, props: List<String> = scene.globals.keys.toList()) {
        synchronized(scene) { val t = snappedNow(); props.forEach { track("g", it).put(t, scene.globals[it] ?: 0f, ease) } }
    }

    fun deleteKeysAt(targets: Set<String>) {
        synchronized(scene) {
            val t = snappedNow()
            tracks.values.filter { it.target in targets }.forEach { tr -> tr.keys.removeAll { it.t == t } }
            tracks.entries.removeAll { it.value.keys.isEmpty() }
        }
    }

    fun keyTimes(targets: Set<String>): List<Long> = synchronized(scene) {
        tracks.values.filter { it.target in targets }.flatMap { tr -> tr.keys.map { it.t } }.distinct().sorted()
    }

    fun removeTarget(id: String) { synchronized(scene) { tracks.entries.removeAll { it.value.target == id } } }

    fun toJson(): JSONObject = JSONObject().apply {
        put("durationMs", durationMs); put("fps", fps); put("loop", loop)
        put("tracks", JSONArray().also { arr ->
            tracks.values.forEach { tr ->
                arr.put(JSONObject().apply {
                    put("target", tr.target); put("prop", tr.prop)
                    put("keys", JSONArray().also { ka -> tr.keys.forEach { k ->
                        ka.put(JSONObject().apply { put("t", k.t); put("v", k.v.toDouble()); put("e", k.ease.name) }) } })
                })
            }
        })
    }

    fun loadJson(o: JSONObject) {
        durationMs = o.optLong("durationMs", 10_000L); fps = o.optInt("fps", 30); loop = o.optBoolean("loop", true)
        tracks.clear(); timeMs = 0; playing = false
        val arr = o.optJSONArray("tracks") ?: return
        for (i in 0 until arr.length()) {
            val to = arr.getJSONObject(i)
            val tr = Track(to.getString("target"), to.getString("prop"))
            val ka = to.getJSONArray("keys")
            for (k in 0 until ka.length()) {
                val ko = ka.getJSONObject(k)
                tr.keys.add(Key(ko.getLong("t"), ko.getDouble("v").toFloat(), Ease.valueOf(ko.optString("e", "LINEAR"))))
            }
            tracks[tr.id] = tr
        }
    }
}
