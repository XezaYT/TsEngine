package com.tsengine.studio

import android.media.MediaPlayer
import android.media.PlaybackParams
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

class AudioItem(val file: File, val name: String, var offsetMs: Long = 0, var volume: Float = 1f, var muted: Boolean = false) {
    var player: MediaPlayer? = null
    var durationMs = 0
    var appliedSpeed = 1f
}

/** One MediaPlayer per imported local file (MP3/WAV/AAC). All are slaved to the Timeline clock. */
class AudioEngine {
    val items = ArrayList<AudioItem>()

    @Synchronized fun add(file: File, name: String, offsetMs: Long = 0, volume: Float = 1f, muted: Boolean = false): AudioItem? {
        return try {
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath)
            p.prepare()
            p.setVolume(volume, volume)
            val it = AudioItem(file, name, offsetMs, volume, muted)
            it.player = p; it.durationMs = p.duration
            items.add(it); it
        } catch (e: Exception) { null }
    }

    @Synchronized fun remove(i: AudioItem) { try { i.player?.release() } catch (_: Exception) {}; items.remove(i) }
    @Synchronized fun clear() { items.toList().forEach { remove(it) } }

    @Synchronized fun applySpeed(speed: Float) {
        items.forEach { i ->
            val p = i.player ?: return@forEach
            if (p.isPlaying) try { p.playbackParams = PlaybackParams().setSpeed(speed); i.appliedSpeed = speed } catch (_: Exception) {}
        }
    }

    /** Core sync routine. Call whenever timeline time/play-state changes. */
    @Synchronized fun update(timeMs: Long, playing: Boolean, speed: Float, forceSeek: Boolean) {
        items.forEach { i ->
            val p = i.player ?: return@forEach
            try {
                val local = (timeMs - i.offsetMs).toInt()
                val inRange = local >= 0 && local < i.durationMs
                if (!playing || !inRange || i.muted) {
                    if (p.isPlaying) p.pause()
                    if (forceSeek && inRange) p.seekTo(local)
                    return@forEach
                }
                p.setVolume(i.volume, i.volume)
                if (!p.isPlaying) {
                    p.seekTo(local); p.start()
                    p.playbackParams = PlaybackParams().setSpeed(speed); i.appliedSpeed = speed
                } else if (abs(p.currentPosition - local) > 80 || forceSeek) {
                    p.seekTo(local)
                }
                if (i.appliedSpeed != speed) { p.playbackParams = PlaybackParams().setSpeed(speed); i.appliedSpeed = speed }
            } catch (_: Exception) {}
        }
    }

    @Synchronized fun release() { clear() }

    @Synchronized fun toJson(): JSONArray = JSONArray().also { a ->
        items.forEach { a.put(JSONObject().apply { put("file", it.file.name); put("name", it.name)
            put("offset", it.offsetMs); put("volume", it.volume.toDouble()); put("muted", it.muted) }) }
    }

    fun loadJson(a: JSONArray?, assetsDir: File) {
        clear()
        if (a == null) return
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            add(File(assetsDir, o.getString("file")), o.optString("name"), o.optLong("offset"), o.optDouble("volume", 1.0).toFloat(), o.optBoolean("muted"))
        }
    }
}
