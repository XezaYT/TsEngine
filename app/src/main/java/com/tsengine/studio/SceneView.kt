package com.tsengine.studio

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.Looper
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.ConcurrentHashMap

enum class DragMode { MOVE_XY, ELEVATION }

class SceneView(ctx: Context, val scene: Scene, val timeline: Timeline, val skins: ConcurrentHashMap<String, Skin>) : SurfaceView(ctx), SurfaceHolder.Callback {
    val renderer = SceneRenderer(scene, timeline, skins)
    var dragMode = DragMode.MOVE_XY
    var snap = true
    @Volatile var clean = false
    var onSelectionChanged: ((Entity?) -> Unit)? = null
    var onEntityDragged: ((Entity) -> Unit)? = null
    var onCleanTap: (() -> Unit)? = null
    @Volatile var exporting = false
    private var thread: RenderThread? = null
    private var dragTarget: Entity? = null

    init { holder.addCallback(this) }

    private inner class RenderThread : Thread("ts-render") {
        @Volatile var running = true
        private var looper: Looper? = null
        override fun run() {
            Looper.prepare(); looper = Looper.myLooper()
            val ch = Choreographer.getInstance()
            val cb = object : Choreographer.FrameCallback {
                override fun doFrame(nanos: Long) {
                    if (!running) { looper?.quit(); return }
                    renderFrame(nanos)
                    ch.postFrameCallback(this)
                }
            }
            ch.postFrameCallback(cb)
            Looper.loop()
        }
    }

    private fun renderFrame(nanos: Long) {
        if (exporting) return
        timeline.tick(nanos)
        var canvas: Canvas? = null
        try {
            canvas = try { holder.lockHardwareCanvas() } catch (e: Exception) { holder.lockCanvas() }
            if (canvas != null) renderer.draw(canvas, canvas.width, canvas.height, !clean)
        } catch (_: Exception) {
        } finally { if (canvas != null) try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {} }
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        if (Build.VERSION.SDK_INT >= 30) try { h.surface.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) } catch (_: Exception) {}
        thread = RenderThread().also { it.start() }
    }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) {
        thread?.running = false
        try { thread?.join(800) } catch (_: InterruptedException) {}
        thread = null
    }

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val old = renderer.zoom
            val nz = (old * d.scaleFactor).coerceIn(0.2f, 6f)
            // zoom around the pinch focus
            val fx = d.focusX - renderer.viewW / 2f; val fy = d.focusY - renderer.viewH * 0.4f
            renderer.panX = fx - (fx - renderer.panX) * (nz / old)
            renderer.panY = fy - (fy - renderer.panY) * (nz / old)
            renderer.zoom = nz
            return true
        }
    })

    private val gest = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (clean) return true
            val hit = renderer.hitTest(e.x, e.y)
            dragTarget = hit
            if (hit != null && scene.selectedId != hit.id) { scene.selectedId = hit.id; onSelectionChanged?.invoke(hit) }
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (clean) { onCleanTap?.invoke(); return true }
            if (renderer.hitTest(e.x, e.y) == null) { scene.selectedId = null; onSelectionChanged?.invoke(null) }
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDet.isInProgress) return false
            val t = dragTarget
            if (t != null && e2.pointerCount == 1) {
                val ddx = -dx / renderer.zoom; val ddy = -dy / renderer.zoom
                synchronized(scene) {
                    if (dragMode == DragMode.MOVE_XY) {
                        val a = ddx / (Iso.TW / 2f); val b = ddy / (Iso.TH / 2f)
                        t.x += (a + b) / 2f; t.y += (b - a) / 2f
                    } else t.z = (t.z - ddy / Iso.ZH).coerceAtLeast(0f)
                }
                onEntityDragged?.invoke(t)
            } else { renderer.panX -= dx; renderer.panY -= dy }
            return true
        }
    })

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!clean) scaleDet.onTouchEvent(ev) else if (ev.pointerCount > 1) scaleDet.onTouchEvent(ev)
        gest.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            val t = dragTarget
            if (t != null && snap) synchronized(scene) {
                if (dragMode == DragMode.MOVE_XY) { t.x = Math.round(t.x * 4f) / 4f.toFloat(); t.y = Math.round(t.y * 4f) / 4f.toFloat() }
                else t.z = Math.round(t.z * 4f) / 4f.toFloat()
                onEntityDragged?.invoke(t)
            }
            dragTarget = null
        }
        return true
    }
}
