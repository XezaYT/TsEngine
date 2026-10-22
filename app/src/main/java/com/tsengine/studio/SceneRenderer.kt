package com.tsengine.studio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.sin

/** Stateless-ish renderer shared by the live SurfaceView and the offline exporter. */
class SceneRenderer(private val scene: Scene, private val timeline: Timeline, private val skins: ConcurrentHashMap<String, Skin>) {
    @Volatile var panX = 0f
    @Volatile var panY = 0f
    @Volatile var zoom = 1f
    @Volatile var showGrid = true
    @Volatile var target = Target.LANDSCAPE
    @Volatile var viewW = 1
    @Volatile var viewH = 1

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val limb = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER }
    private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val m = Matrix()
    private val path = Path()
    private val tmpR = RectF()

    fun frameRect(w: Float, h: Float): RectF {
        val ar = target.aspect
        var fw = w; var fh = fw / ar
        if (fh > h) { fh = h; fw = fh * ar }
        val l = (w - fw) / 2f; val t = (h - fh) / 2f
        return RectF(l, t, l + fw, t + fh)
    }

    fun screenToWorld(sx: Float, sy: Float): Pair<Float, Float> =
        Pair((sx - viewW / 2f - panX) / zoom, (sy - viewH * 0.4f - panY) / zoom)

    fun hitTest(sx: Float, sy: Float): Entity? = synchronized(scene) {
        val (wx, wy) = screenToWorld(sx, sy)
        scene.entities.sortedByDescending { depth(it) }.firstOrNull { it.bounds.contains(wx, wy) }
    }

    private fun depth(e: Entity) = e.layer * 1000f + e.x + e.y + e.z * 0.5f

    fun draw(canvas: Canvas, w: Int, h: Int, editor: Boolean) {
        viewW = w; viewH = h
        synchronized(scene) {
            canvas.drawColor(if (editor) 0xFF0E1015.toInt() else Color.BLACK)
            val frame = frameRect(w.toFloat(), h.toFloat())
            canvas.save()
            if (!editor) canvas.clipRect(frame)
            drawScene(canvas, w, h, editor, frame)
            canvas.restore()
            if (editor) drawGuides(canvas, frame, w, h)
        }
    }

    /** Renders exactly the safe-frame region, scaled to outW x outH (no editor UI). */
    fun drawExport(canvas: Canvas, outW: Int, outH: Int) {
        synchronized(scene) {
            val w = viewW; val h = viewH
            val frame = frameRect(w.toFloat(), h.toFloat())
            canvas.drawColor(Color.BLACK)
            canvas.save()
            canvas.scale(outW / frame.width(), outH / frame.height())
            canvas.translate(-frame.left, -frame.top)
            canvas.clipRect(frame)
            drawScene(canvas, w, h, false, frame)
            canvas.restore()
        }
    }

    private fun drawScene(c: Canvas, w: Int, h: Int, editor: Boolean, frame: RectF) {
        val t = timeline.timeMs
        fill.shader = null; fill.style = Paint.Style.FILL; fill.color = 0xFF1B2030.toInt()
        c.drawRect(frame, fill)
        val shake = scene.globals["shake"] ?: 0f
        val shx = shake * 16f * (sin(t * 0.091) + sin(t * 0.173 + 1.7)).toFloat() * 0.5f
        val shy = shake * 16f * (sin(t * 0.113 + 0.6) + sin(t * 0.151 + 2.3)).toFloat() * 0.5f
        c.save()
        c.translate(w / 2f + panX + shx, h * 0.4f + panY + shy)
        c.scale(zoom, zoom)
        if (editor && showGrid) drawGrid(c)
        scene.entities.sortedBy { depth(it) }.forEach { drawEntity(c, it) }
        drawLighting(c)
        if (editor) drawGizmos(c)
        c.restore()
        drawFog(c, frame, t)
        drawVignette(c, frame)
    }

    private fun drawGrid(c: Canvas) {
        stroke.strokeWidth = 1.5f / zoom; stroke.pathEffect = null
        val n = 12
        for (i in -n..n) {
            stroke.color = if (i == 0) 0x66FF6B6B else 0x33FFFFFF
            c.drawLine(Iso.sx(i.toFloat(), -n.toFloat()), Iso.sy(i.toFloat(), -n.toFloat(), 0f), Iso.sx(i.toFloat(), n.toFloat()), Iso.sy(i.toFloat(), n.toFloat(), 0f), stroke)
            stroke.color = if (i == 0) 0x6651CF66 else 0x33FFFFFF
            c.drawLine(Iso.sx(-n.toFloat(), i.toFloat()), Iso.sy(-n.toFloat(), i.toFloat(), 0f), Iso.sx(n.toFloat(), i.toFloat()), Iso.sy(n.toFloat(), i.toFloat(), 0f), stroke)
        }
    }

    private fun shade(color: Int, f: Float): Int =
        Color.argb(Color.alpha(color), (Color.red(color) * f).toInt().coerceIn(0, 255), (Color.green(color) * f).toInt().coerceIn(0, 255), (Color.blue(color) * f).toInt().coerceIn(0, 255))

    private fun drawEntity(c: Canvas, e: Entity) {
        val ax = Iso.sx(e.x, e.y); val ay = Iso.sy(e.x, e.y, e.z)
        if (e.type == EType.LIGHT) { e.bounds.set(ax - 18f, ay - 18f, ax + 18f, ay + 18f); return }
        m.reset(); m.postScale(e.sx * (if (e.flipX) -1f else 1f), e.sy); m.postRotate(e.rot); m.postTranslate(ax, ay)
        // ground shadow (not transformed)
        if (e.z > 0.01f) {
            fill.shader = null; fill.color = 0x44000000
            c.drawOval(Iso.sx(e.x, e.y) - 30f, Iso.sy(e.x, e.y, 0f) - 12f, Iso.sx(e.x, e.y) + 30f, Iso.sy(e.x, e.y, 0f) + 12f, fill)
        }
        c.save(); c.concat(m)
        val local = when (e.type) {
            EType.BOX, EType.WALL -> drawBox(c, e)
            EType.PUPPET -> drawPuppet(c, e)
            else -> drawProp(c, e)
        }
        c.restore()
        m.mapRect(e.bounds, local)
    }

    private fun poly(c: Canvas, color: Int, vararg p: Float) {
        path.reset(); path.moveTo(p[0], p[1]); var i = 2
        while (i < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
        path.close(); fill.shader = null; fill.style = Paint.Style.FILL; fill.color = color; c.drawPath(path, fill)
        stroke.color = 0x55000000; stroke.strokeWidth = 1.5f; stroke.pathEffect = null; c.drawPath(path, stroke)
    }

    private fun drawBox(c: Canvas, e: Entity): RectF {
        val hw = e.w / 2f; val hd = e.d / 2f; val H = e.h
        fun px(dx: Float, dy: Float) = Iso.sx(dx, dy)
        fun py(dx: Float, dy: Float, dz: Float) = Iso.sy(dx, dy, dz)
        // left face (y = +hd), right face (x = +hw), top face
        poly(c, shade(e.color, 0.78f), px(-hw, hd), py(-hw, hd, 0f), px(hw, hd), py(hw, hd, 0f), px(hw, hd), py(hw, hd, H), px(-hw, hd), py(-hw, hd, H))
        poly(c, shade(e.color, 0.55f), px(hw, -hd), py(hw, -hd, 0f), px(hw, hd), py(hw, hd, 0f), px(hw, hd), py(hw, hd, H), px(hw, -hd), py(hw, -hd, H))
        poly(c, shade(e.color, 1.12f), px(-hw, -hd), py(-hw, -hd, H), px(hw, -hd), py(hw, -hd, H), px(hw, hd), py(hw, hd, H), px(-hw, hd), py(-hw, hd, H))
        return RectF(px(-hw, hd), py(-hw, -hd, H), px(hw, -hd), py(hw, hd, 0f)).also { it.sort() }
    }

    private fun limbLine(c: Canvas, px: Float, py: Float, deg: Float, len: Float, color: Int, width: Float) {
        limb.color = color; limb.strokeWidth = width
        c.save(); c.rotate(deg, px, py); c.drawLine(px, py, px, py + len, limb); c.restore()
    }

    private fun drawPuppet(c: Canvas, e: Entity): RectF {
        val sk = e.skin?.let { skins[it] }
        if (sk != null) return drawSkinPuppet(c, e, sk)
        val j = e.joints; val dark = shade(e.color, 0.7f)
        limbLine(c, -8f, -55f, j["legL"] ?: 0f, 55f, dark, 13f)
        limbLine(c, 8f, -55f, j["legR"] ?: 0f, 55f, dark, 13f)
        c.save(); c.rotate(j["torso"] ?: 0f, 0f, -55f)
        limb.color = e.color; limb.strokeWidth = 30f; c.drawLine(0f, -58f, 0f, -92f, limb)
        limbLine(c, -17f, -98f, j["armL"] ?: 0f, 44f, dark, 11f)
        limbLine(c, 17f, -98f, j["armR"] ?: 0f, 44f, dark, 11f)
        c.save(); c.rotate(j["head"] ?: 0f, 0f, -104f)
        fill.shader = null; fill.style = Paint.Style.FILL; fill.color = 0xFFFFE0BD.toInt(); c.drawCircle(0f, -122f, 17f, fill)
        fill.color = 0xFF222222.toInt(); c.drawCircle(-6f, -124f, 2.5f, fill); c.drawCircle(6f, -124f, 2.5f, fill)
        c.restore(); c.restore()
        return RectF(-36f, -144f, 36f, 4f)
    }

    private fun drawPart(c: Canvas, sk: Skin, name: String, deg: Float) {
        val list = sk.parts[name] ?: return
        val pv = sk.pivots[name] ?: return
        c.save(); c.rotate(deg, pv.x, pv.y)
        list.forEach { fill.shader = null; fill.style = Paint.Style.FILL; fill.color = it.fill; c.drawPath(it.path, fill) }
        c.restore()
    }

    private fun drawSkinPuppet(c: Canvas, e: Entity, sk: Skin): RectF {
        val s = 150f / sk.bounds.height().coerceAtLeast(1f)
        c.save(); c.scale(s, s); c.translate(-sk.bounds.centerX(), -sk.bounds.bottom)
        drawPart(c, sk, "body", 0f)
        drawPart(c, sk, "legL", e.joints["legL"] ?: 0f); drawPart(c, sk, "legR", e.joints["legR"] ?: 0f)
        val tp = sk.pivots["torso"]
        c.save(); if (tp != null) c.rotate(e.joints["torso"] ?: 0f, tp.x, tp.y)
        drawPart(c, sk, "torso", 0f); drawPart(c, sk, "armL", e.joints["armL"] ?: 0f)
        drawPart(c, sk, "armR", e.joints["armR"] ?: 0f); drawPart(c, sk, "head", e.joints["head"] ?: 0f)
        c.restore(); c.restore()
        val hw = sk.bounds.width() * s / 2f
        return RectF(-hw, -150f, hw, 0f)
    }

    private fun drawProp(c: Canvas, e: Entity): RectF {
        val sk = e.skin?.let { skins[it] }
        if (sk != null) {
            val s = 90f / maxOf(sk.bounds.width(), sk.bounds.height(), 1f)
            c.save(); c.scale(s, s); c.translate(-sk.bounds.centerX(), -sk.bounds.bottom)
            sk.parts.values.forEach { l -> l.forEach { fill.shader = null; fill.style = Paint.Style.FILL; fill.color = it.fill; c.drawPath(it.path, fill) } }
            c.restore()
            val hw = sk.bounds.width() * s / 2f
            return RectF(-hw, -sk.bounds.height() * s, hw, 0f)
        }
        fill.shader = null; fill.style = Paint.Style.FILL; fill.color = e.color
        c.drawRoundRect(-36f, -72f, 36f, 0f, 14f, 14f, fill)
        text.color = Color.WHITE; c.drawText(e.name.take(1).uppercase(), 0f, -28f, text)
        return RectF(-36f, -72f, 36f, 0f)
    }

    private fun drawLighting(c: Canvas) {
        val amb = scene.globals["ambient"] ?: 0f
        if (amb <= 0.01f) return
        c.saveLayer(-8000f, -8000f, 8000f, 8000f, null)
        c.drawColor(Color.argb((amb * 238).toInt(), 4, 7, 20))
        scene.entities.filter { it.type == EType.LIGHT && it.intensity > 0.01f }.forEach { l ->
            val cx = Iso.sx(l.x, l.y); val cy = Iso.sy(l.x, l.y, l.z)
            val r = l.radius * Iso.TW / 2f * l.sx
            val a = (l.intensity.coerceIn(0f, 1f) * 255).toInt()
            cut.shader = RadialGradient(cx, cy, r, intArrayOf(Color.argb(a, 255, 255, 255), Color.argb((a * 0.55f).toInt(), 255, 255, 255), 0), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            if (l.cone >= 359f) c.drawCircle(cx, cy, r, cut)
            else {
                path.reset(); path.moveTo(cx, cy)
                tmpR.set(cx - r, cy - r, cx + r, cy + r)
                path.arcTo(tmpR, l.aim - l.cone / 2f, l.cone); path.close()
                c.drawPath(path, cut)
            }
        }
        cut.shader = null
        c.restore()
    }

    private fun drawFog(c: Canvas, frame: RectF, t: Long) {
        val f = scene.globals["fog"] ?: 0f
        if (f <= 0.01f) return
        fill.shader = null; fill.style = Paint.Style.FILL
        fill.color = Color.argb((f * 85).toInt(), 190, 200, 215); c.drawRect(frame, fill)
        for (i in 0 until 40) {
            val fx = ((i * 0.618034f + t * 0.00002f * (1 + i % 5)) % 1f + 1f) % 1f
            val fy = (((i * 0.754878f) % 1f) + sin(t * 0.0007 + i).toFloat() * 0.02f + 1f) % 1f
            fill.color = Color.argb((f * 55).toInt(), 220, 225, 235)
            c.drawCircle(frame.left + fx * frame.width(), frame.top + fy * frame.height(), (18 + (i % 5) * 10) * frame.width() / 1280f * 1.6f, fill)
        }
    }

    private fun drawVignette(c: Canvas, frame: RectF) {
        val v = scene.globals["vignette"] ?: 0f
        if (v <= 0.01f) return
        val r = hypot(frame.width(), frame.height()) / 2f
        fill.style = Paint.Style.FILL
        fill.shader = RadialGradient(frame.centerX(), frame.centerY(), r, intArrayOf(0, 0, Color.argb((v * 255).toInt(), 0, 0, 0)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(frame, fill); fill.shader = null
    }

    private fun drawGizmos(c: Canvas) {
        val sel = scene.selected()
        scene.entities.filter { it.type == EType.LIGHT }.forEach { l ->
            val cx = Iso.sx(l.x, l.y); val cy = Iso.sy(l.x, l.y, l.z)
            fill.shader = null; fill.style = Paint.Style.FILL; fill.color = 0xFFFFE066.toInt(); c.drawCircle(cx, cy, 10f, fill)
            stroke.color = 0xFFFFE066.toInt(); stroke.strokeWidth = 2f / zoom; stroke.pathEffect = null
            val rad = Math.toRadians(l.aim.toDouble())
            c.drawLine(cx, cy, cx + Math.cos(rad).toFloat() * 40f, cy + Math.sin(rad).toFloat() * 40f, stroke)
        }
        if (sel != null) {
            stroke.color = 0xFF4DABF7.toInt(); stroke.strokeWidth = 2.5f / zoom; stroke.pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
            c.drawRect(sel.bounds, stroke)
            if (sel.z > 0.01f) c.drawLine(Iso.sx(sel.x, sel.y), Iso.sy(sel.x, sel.y, 0f), Iso.sx(sel.x, sel.y), Iso.sy(sel.x, sel.y, sel.z), stroke)
            stroke.pathEffect = null
        }
    }

    private fun drawGuides(c: Canvas, frame: RectF, w: Int, h: Int) {
        fill.shader = null; fill.style = Paint.Style.FILL; fill.color = 0x99000000.toInt()
        c.drawRect(0f, 0f, w.toFloat(), frame.top, fill); c.drawRect(0f, frame.bottom, w.toFloat(), h.toFloat(), fill)
        c.drawRect(0f, frame.top, frame.left, frame.bottom, fill); c.drawRect(frame.right, frame.top, w.toFloat(), frame.bottom, fill)
        stroke.pathEffect = null; stroke.color = 0xFFFF6B6B.toInt(); stroke.strokeWidth = 3f; c.drawRect(frame, stroke)
        stroke.color = 0x88FFFFFF.toInt(); stroke.strokeWidth = 1.5f; stroke.pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
        val sx = frame.width() * 0.05f; val sy = frame.height() * 0.05f
        c.drawRect(frame.left + sx, frame.top + sy, frame.right - sx, frame.bottom - sy, stroke)
        stroke.pathEffect = null
        c.drawLine(frame.centerX() - 12f, frame.centerY(), frame.centerX() + 12f, frame.centerY(), stroke)
        c.drawLine(frame.centerX(), frame.centerY() - 12f, frame.centerX(), frame.centerY() + 12f, stroke)
        text.textSize = 26f; text.color = 0xFFFF6B6B.toInt(); text.textAlign = Paint.Align.LEFT
        c.drawText("${target.label} safe frame", frame.left + 12f, frame.top + 30f, text)
        text.textAlign = Paint.Align.CENTER
    }
}
