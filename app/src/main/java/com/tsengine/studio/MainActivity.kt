package com.tsengine.studio

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var store: ProjectStore
    private val scene = Scene()
    private val timeline = Timeline(scene)
    private val project = Project(scene, timeline)
    private val audio = AudioEngine()
    private lateinit var sv: SceneView
    private lateinit var tl: TimelineView
    private lateinit var inspector: LinearLayout
    private lateinit var topBar: View
    private lateinit var leftBar: View
    private lateinit var rightPanel: View
    private lateinit var bottomPanel: View
    private lateinit var exitClean: Button
    private lateinit var timeLabel: TextView
    private lateinit var status: TextView
    private lateinit var playBtn: Button
    private val ui = Handler(Looper.getMainLooper())
    private val syncers = ArrayList<() -> Unit>()
    private var suppress = false
    private var autoKey = false
    private var ease = Ease.EASE_IN_OUT
    private var clean = false

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) importAudio(u) }
    private val pickSkin = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) importSkin(u) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        store = ProjectStore(this)
        timeline.audio = audio
        sv = SceneView(this, scene, timeline, project.skins)
        tl = TimelineView(this, timeline, audio)
        buildUi()
        sv.onSelectionChanged = { runOnUiThread { onSelection() } }
        sv.onEntityDragged = { e -> if (autoKey) timeline.recordEntity(e, ease, listOf("x", "y", "z")) }
        sv.onCleanTap = { timeline.toggle() }
        timeline.onPlayStateChanged = { runOnUiThread { playBtn.text = if (timeline.playing) "⏸" else "▶" } }
        tl.onScrub = { refreshNow() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (clean) setClean(false) else finish() }
        })
        if (store.list().contains("autosave")) store.load("autosave", project, audio)
        if (scene.entities.isEmpty()) scene.create(EType.PUPPET, 0f, 0f)
        sv.renderer.target = project.target
        onSelection()
        ui.post(refresher)
    }

    private val refresher = object : Runnable {
        override fun run() { refreshNow(); ui.postDelayed(this, 100) }
    }

    private fun refreshNow() {
        val f = timeline.frameIndex(); val ms = timeline.timeMs
        timeLabel.text = String.format(Locale.US, "%02d:%02d.%02d  f%d/%d", ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10, f, (timeline.durationMs * timeline.fps / 1000).toInt())
        tl.invalidate()
        if (timeline.playing) syncers.forEach { it() }
    }

    override fun onPause() {
        super.onPause(); timeline.pause()
        try { val n = project.name; project.name = "autosave"; store.save(project, audio); project.name = n } catch (_: Exception) {}
    }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); audio.release(); super.onDestroy() }

    // ------------------------------------------------------------------ UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(t: String, w: Int = ViewGroup.LayoutParams.WRAP_CONTENT, f: () -> Unit) = Button(this).apply {
        text = t; textSize = 11f; isAllCaps = false; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(8), dp(6), dp(8), dp(6)); setTextColor(Color.WHITE); setBackgroundColor(0xFF2B3140.toInt())
        layoutParams = LinearLayout.LayoutParams(w, dp(36)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
        setOnClickListener { f() }
    }

    private fun label(t: String, size: Float = 12f) = TextView(this).apply { text = t; textSize = size; setTextColor(0xFFC9D1DC.toInt()); setPadding(dp(6), dp(4), dp(6), dp(2)) }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF0B0D12.toInt()) }
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        status = label("ready").apply { gravity = Gravity.CENTER_VERTICAL }
        val tb = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xFF171B24.toInt()); gravity = Gravity.CENTER_VERTICAL }
        tb.addView(TextView(this).apply { text = "TS ENGINE STUDIO"; setTextColor(0xFF4DABF7.toInt()); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(10), 0, dp(10), 0) })
        tb.addView(btn("Name") { prompt("Project name", project.name) { project.name = it; toast("Project: $it") } })
        tb.addView(btn("New") { newProject() })
        tb.addView(btn("Save") { store.save(project, audio); toast("Saved → ${store.dir(project.name).absolutePath}") })
        tb.addView(btn("Load") { loadDialog() })
        tb.addView(btn("PNG") { snapshot() })
        tb.addView(btn("MP4") { exportVideo() })
        tb.addView(btn("Grid") { sv.renderer.showGrid = !sv.renderer.showGrid })
        tb.addView(btn("16:9/9:16") { project.target = if (project.target == Target.LANDSCAPE) Target.PORTRAIT else Target.LANDSCAPE; sv.renderer.target = project.target })
        tb.addView(btn("Clean View") { setClean(true) })
        tb.addView(status)
        topBar = HorizontalScrollWrap(tb)
        main.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // left tool column
        val lc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun add(t: String, f: () -> Unit) = lc.addView(btn(t, ViewGroup.LayoutParams.MATCH_PARENT, f))
        add("+ Puppet") { addEntity(EType.PUPPET) }; add("+ Prop") { addEntity(EType.PROP) }
        add("+ Box") { addEntity(EType.BOX) }; add("+ Wall") { addEntity(EType.WALL) }; add("+ Light") { addEntity(EType.LIGHT) }
        add("Delete") { scene.selected()?.let { e -> synchronized(scene) { scene.remove(e) }; timeline.removeTarget(e.id); onSelection() } }
        add("Duplicate") { scene.selected()?.let { e -> val c = e.duplicate(); synchronized(scene) { scene.entities.add(c); scene.selectedId = c.id }; onSelection() } }
        add("Flip") { mutate { it.flipX = !it.flipX } }
        add("Layer ▲") { mutate { it.layer += 1 } }; add("Layer ▼") { mutate { it.layer -= 1 } }
        add("Rot −15°") { mutate { it.rot -= 15f } }; add("Rot +15°") { mutate { it.rot += 15f } }
        add("Scale −") { mutate { it.sx *= 0.9f; it.sy *= 0.9f } }; add("Scale +") { mutate { it.sx *= 1.1f; it.sy *= 1.1f } }
        val dm = btn("Drag: XY", ViewGroup.LayoutParams.MATCH_PARENT) {}
        dm.setOnClickListener { sv.dragMode = if (sv.dragMode == DragMode.MOVE_XY) DragMode.ELEVATION else DragMode.MOVE_XY; dm.text = if (sv.dragMode == DragMode.MOVE_XY) "Drag: XY" else "Drag: Z" }
        lc.addView(dm)
        val sn = btn("Snap: on", ViewGroup.LayoutParams.MATCH_PARENT) {}
        sn.setOnClickListener { sv.snap = !sv.snap; sn.text = if (sv.snap) "Snap: on" else "Snap: off" }
        lc.addView(sn)
        add("Skin SVG/JSON") { if (scene.selected() == null) toast("Select an entity first") else pickSkin.launch(arrayOf("*/*")) }
        add("Clear skin") { mutate { it.skin = null } }
        val ls = ScrollView(this).apply { addView(lc); setBackgroundColor(0xFF12151C.toInt()) }
        leftBar = ls
        mid.addView(ls, LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.MATCH_PARENT))

        // centre: scene + timeline panel
        val centre = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        centre.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        centre.addView(buildTimelinePanel().also { bottomPanel = it }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
        mid.addView(centre, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        inspector = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rs = ScrollView(this).apply { addView(inspector); setBackgroundColor(0xFF12151C.toInt()) }
        rightPanel = rs
        mid.addView(rs, LinearLayout.LayoutParams(dp(270), ViewGroup.LayoutParams.MATCH_PARENT))
        main.addView(mid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(main, FrameLayout.LayoutParams(-1, -1))

        exitClean = Button(this).apply { text = "✕"; alpha = 0.12f; visibility = View.GONE; setOnClickListener { setClean(false) } }
        root.addView(exitClean, FrameLayout.LayoutParams(dp(44), dp(36)).apply { gravity = Gravity.TOP or Gravity.START; setMargins(dp(4), dp(4), 0, 0) })
        setContentView(root)
    }

    private fun HorizontalScrollWrap(v: View) = android.widget.HorizontalScrollView(this).apply { addView(v); isHorizontalScrollBarEnabled = false }

    private fun buildTimelinePanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF12151C.toInt()) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        playBtn = btn("▶") { timeline.toggle() }
        row.addView(btn("⏮") { timeline.pause(); timeline.seek(0) })
        row.addView(btn("−1f") { timeline.step(-1); refreshNow() })
        row.addView(playBtn)
        row.addView(btn("+1f") { timeline.step(1); refreshNow() })
        val sp = btn("1x") {}
        sp.setOnClickListener {
            val speeds = floatArrayOf(0.25f, 0.5f, 1f, 2f); val i = (speeds.indexOfFirst { it == timeline.speed } + 1) % speeds.size
            timeline.setSpeed(speeds[i]); sp.text = "${speeds[i]}x".replace(".0x", "x")
        }
        row.addView(sp)
        val lp = btn("Loop: on") {}
        lp.setOnClickListener { timeline.loop = !timeline.loop; lp.text = if (timeline.loop) "Loop: on" else "Loop: off" }
        row.addView(lp)
        timeLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.MONOSPACE; setPadding(dp(8), 0, dp(8), 0) }
        row.addView(timeLabel)
        row.addView(btn("Dur") { prompt("Duration (seconds)", (timeline.durationMs / 1000).toString()) { s -> s.toLongOrNull()?.let { timeline.durationMs = (it.coerceIn(1, 3600)) * 1000 } } })
        val es = btn("Ease: ${ease.label}") {}
        es.setOnClickListener { ease = Ease.values()[(ease.ordinal + 1) % Ease.values().size]; es.text = "Ease: ${ease.label}" }
        row.addView(es)
        row.addView(btn("◆ Key") { scene.selected()?.let { timeline.recordEntity(it, ease) }; timeline.recordGlobals(ease); tl.invalidate() })
        row.addView(btn("◇ Del Key") { timeline.deleteKeysAt(tl.targets); tl.invalidate() })
        val ak = btn("Auto-key: off") {}
        ak.setOnClickListener { autoKey = !autoKey; ak.text = if (autoKey) "Auto-key: ON" else "Auto-key: off" }
        row.addView(ak)
        row.addView(btn("♪ Audio +") { pickAudio.launch(arrayOf("audio/*")) })
        row.addView(btn("♪ Tracks") { audioDialog() })
        col.addView(android.widget.HorizontalScrollView(this).apply { addView(row); isHorizontalScrollBarEnabled = false }, LinearLayout.LayoutParams(-1, dp(44)))
        col.addView(tl, LinearLayout.LayoutParams(-1, 0, 1f))
        return col
    }

    // ------------------------------------------------------------------ Inspector
    private fun onSelection() {
        val e = scene.selected()
        tl.targets = if (e != null) setOf(e.id, "g") else setOf("g")
        inspector.removeAllViews(); syncers.clear()
        if (e == null) inspector.addView(label("Nothing selected — tap an entity", 13f))
        else {
            inspector.addView(label("${e.name}  [${e.type}]" + (e.skin?.let { "  skin: $it" } ?: ""), 13f).apply { setTypeface(null, Typeface.BOLD) })
            inspector.addView(btn("Rename") { prompt("Name", e.name) { n -> e.name = n; onSelection() } })
            addSlider("X", -20f, 20f, e, "x"); addSlider("Y", -20f, 20f, e, "y"); addSlider("Elevation Z", 0f, 10f, e, "z")
            addSlider("Rotation", -180f, 180f, e, "rot"); addSlider("Scale X", 0.1f, 5f, e, "sx"); addSlider("Scale Y", 0.1f, 5f, e, "sy")
            when (e.type) {
                EType.PUPPET -> {
                    inspector.addView(label("RIG — JOINTS", 12f))
                    addSlider("Head", -60f, 60f, e, "j.head"); addSlider("Torso", -45f, 45f, e, "j.torso")
                    addSlider("Arm L", -180f, 180f, e, "j.armL"); addSlider("Arm R", -180f, 180f, e, "j.armR")
                    addSlider("Leg L", -90f, 90f, e, "j.legL"); addSlider("Leg R", -90f, 90f, e, "j.legR")
                }
                EType.BOX, EType.WALL -> { addSlider("Width", 0.1f, 12f, e, "w"); addSlider("Depth", 0.1f, 12f, e, "d"); addSlider("Height", 0.1f, 10f, e, "h") }
                EType.LIGHT -> { addSlider("Intensity", 0f, 1f, e, "intensity"); addSlider("Radius", 1f, 16f, e, "radius"); addSlider("Cone °(360=omni)", 10f, 360f, e, "cone"); addSlider("Aim °", -180f, 180f, e, "aim") }
                else -> {}
            }
            if (e.type != EType.LIGHT) inspector.addView(btn("Cycle colour") {
                val pal = intArrayOf(0xFF5C7CFA.toInt(), 0xFFFFB86B.toInt(), 0xFF63E6BE.toInt(), 0xFFFF6B6B.toInt(), 0xFFADB5BD.toInt(), 0xFFDA77F2.toInt(), 0xFFFFE066.toInt())
                synchronized(scene) { e.color = pal[(pal.indexOf(e.color) + 1) % pal.size] }
            })
        }
        inspector.addView(label("SCENE GFX / POST-FX", 12f).apply { setPadding(dp(6), dp(14), dp(6), dp(2)) })
        listOf(Triple("Ambient darkness", "ambient", 1f), Triple("Fog / particles", "fog", 1f), Triple("Vignette", "vignette", 1f), Triple("Camera shake", "shake", 1f))
            .forEach { (n, k, mx) -> addGlobalSlider(n, k, mx) }
    }

    private fun slider(name: String, min: Float, max: Float, get: () -> Float, set: (Float) -> Unit, onUser: () -> Unit) {
        val tv = label(""); val sb = SeekBar(this).apply { this.max = 1000 }
        fun upd(v: Float) { tv.text = String.format(Locale.US, "%s   %.2f", name, v) }
        val sync = {
            val v = get(); suppress = true; sb.progress = (((v - min) / (max - min)) * 1000f).toInt().coerceIn(0, 1000); suppress = false; upd(v)
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser || suppress) return
                val v = min + (max - min) * p / 1000f
                synchronized(scene) { set(v) }; upd(v); onUser()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        syncers.add(sync); sync()
        inspector.addView(tv); inspector.addView(sb)
    }

    private fun addSlider(name: String, min: Float, max: Float, e: Entity, prop: String) =
        slider(name, min, max, { e.get(prop) }, { e.set(prop, it) }) { if (autoKey) timeline.recordEntity(e, ease, listOf(prop)); tl.invalidate() }

    private fun addGlobalSlider(name: String, key: String, max: Float) =
        slider(name, 0f, max, { scene.globals[key] ?: 0f }, { scene.globals[key] = it }) { if (autoKey) timeline.recordGlobals(ease, listOf(key)); tl.invalidate() }

    private fun mutate(f: (Entity) -> Unit) {
        val e = scene.selected() ?: return
        synchronized(scene) { f(e) }
        if (autoKey) timeline.recordEntity(e, ease)
        syncers.forEach { it() }
    }

    private fun addEntity(t: EType) {
        synchronized(scene) { scene.create(t, 0f, 0f) }
        onSelection()
    }

    // ------------------------------------------------------------------ IO / dialogs
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun prompt(title: String, def: String, ok: (String) -> Unit) {
        val et = EditText(this).apply { setText(def) }
        AlertDialog.Builder(this).setTitle(title).setView(et).setPositiveButton("OK") { _, _ -> ok(et.text.toString()) }.setNegativeButton("Cancel", null).show()
    }

    private fun importSkin(u: Uri) {
        val e = scene.selected() ?: return
        try {
            val f = store.importAsset(u, project.name)
            val skin = SkinLoader.load(f)
            if (skin == null) { toast("Could not parse skin (need SVG <path>/<rect>/<circle> or Figma JSON)"); return }
            project.skins[f.name] = skin
            synchronized(scene) { e.skin = f.name }
            onSelection(); toast("Skin bound: ${f.name}")
        } catch (ex: Exception) { toast("Skin import failed: ${ex.message}") }
    }

    private fun importAudio(u: Uri) {
        try {
            val f = store.importAsset(u, project.name)
            val item = audio.add(f, store.displayName(u))
            if (item == null) toast("Unsupported audio file") else { toast("Audio added (${item.durationMs / 1000}s)"); if (item.durationMs > timeline.durationMs) timeline.durationMs = item.durationMs.toLong() }
            tl.invalidate()
        } catch (ex: Exception) { toast("Audio import failed: ${ex.message}") }
    }

    private fun audioDialog() {
        val items = audio.items.toList()
        if (items.isEmpty()) { toast("No audio tracks. Use ♪ Audio +"); return }
        val names = items.map { "${it.name}  (offset ${it.offsetMs}ms${if (it.muted) ", muted" else ""})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Audio tracks").setItems(names) { _, i ->
            val a = items[i]
            AlertDialog.Builder(this).setTitle(a.name).setItems(arrayOf("Toggle mute", "Offset −100ms", "Offset +100ms", "Offset = playhead", "Volume 100%/50%", "Remove")) { _, k ->
                when (k) {
                    0 -> a.muted = !a.muted; 1 -> a.offsetMs -= 100; 2 -> a.offsetMs += 100; 3 -> a.offsetMs = timeline.timeMs
                    4 -> a.volume = if (a.volume > 0.75f) 0.5f else 1f
                    5 -> audio.remove(a)
                }
                timeline.seek(timeline.timeMs)
            }.show()
        }.show()
    }

    private fun newProject() {
        timeline.pause()
        synchronized(scene) { scene.entities.clear(); scene.selectedId = null; timeline.tracks.clear(); scene.globals.keys.toList().forEach { scene.globals[it] = if (it == "vignette") 0.25f else 0f } }
        audio.clear(); project.skins.clear(); project.name = "project_" + SimpleDateFormat("MMdd_HHmm", Locale.US).format(Date())
        timeline.seek(0); onSelection()
    }

    private fun loadDialog() {
        val names = store.list().toTypedArray()
        if (names.isEmpty()) { toast("No saved projects yet"); return }
        AlertDialog.Builder(this).setTitle("Load project").setItems(names) { _, i ->
            if (store.load(names[i], project, audio)) { sv.renderer.target = project.target; timeline.seek(0); onSelection(); toast("Loaded ${names[i]}") } else toast("Load failed")
        }.show()
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun snapshot() {
        val (w, h) = if (project.target == Target.LANDSCAPE) 1920 to 1080 else 1080 to 1920
        val f = File(store.exportsDir, "${project.name}_${stamp()}.png")
        try { Exporter.snapshot(sv.renderer, w, h, f); toast("Saved ${f.absolutePath}") } catch (e: Exception) { toast("Snapshot failed: ${e.message}") }
    }

    private fun exportVideo() {
        val (w, h) = if (project.target == Target.LANDSCAPE) 1280 to 720 else 720 to 1280
        val f = File(store.exportsDir, "${project.name}_${stamp()}.mp4")
        sv.exporting = true; status.text = "Exporting 0%"
        Thread {
            try {
                Exporter.video(sv.renderer, timeline, f, w, h) { p -> runOnUiThread { status.text = "Exporting ${(p * 100).toInt()}%" } }
                runOnUiThread { toast("Saved ${f.absolutePath}"); status.text = "ready" }
            } catch (e: Exception) { runOnUiThread { toast("Export failed: ${e.message}"); status.text = "ready" } }
            finally { sv.exporting = false }
        }.start()
    }

    private fun setClean(on: Boolean) {
        clean = on; sv.clean = on
        val vis = if (on) View.GONE else View.VISIBLE
        topBar.visibility = vis; leftBar.visibility = vis; rightPanel.visibility = vis; bottomPanel.visibility = vis
        exitClean.visibility = if (on) View.VISIBLE else View.GONE
        if (on) toast("Clean view: tap scene = play/pause · Back = exit")
    }
}
