package com.tsengine.studio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

class TimelineView(ctx: Context, private val tl: Timeline, private val audio: AudioEngine) : View(ctx) {
    @Volatile var targets: Set<String> = setOf("g")
    var onScrub: (() -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0B8C4.toInt(); textSize = 22f }
    private val dia = Path()
    private val padL = 16f; private val padR = 16f

    private fun xOf(t: Long) = padL + (width - padL - padR) * t / tl.durationMs.toFloat()

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF161A22.toInt())
        p.style = Paint.Style.FILL; p.strokeWidth = 1.5f
        val secs = (tl.durationMs / 1000).toInt()
        val stepS = when { secs > 120 -> 10; secs > 40 -> 5; else -> 1 }
        var s = 0
        while (s <= secs) {
            val x = xOf(s * 1000L); p.color = 0x44FFFFFF
            c.drawLine(x, 0f, x, height.toFloat(), p); c.drawText("${s}s", x + 4f, 20f, txt); s += stepS
        }
        var row = 0
        synchronized(audio) {
            audio.items.forEach { a ->
                val y = 30f + row * 22f
                p.color = if (a.muted) 0x44888888 else 0xAA3BC9DB.toInt()
                c.drawRect(xOf(a.offsetMs), y, xOf(a.offsetMs + a.durationMs), y + 18f, p)
                p.color = Color_white; c.drawText(a.name.take(24), xOf(a.offsetMs) + 6f, y + 15f, txt.also { it.textSize = 18f })
                txt.textSize = 22f; row++
            }
        }
        val ky = height - 30f
        p.color = 0xFFFFD43B.toInt()
        tl.keyTimes(targets).forEach { t ->
            val x = xOf(t); dia.reset(); dia.moveTo(x, ky - 11f); dia.lineTo(x + 11f, ky); dia.lineTo(x, ky + 11f); dia.lineTo(x - 11f, ky); dia.close()
            c.drawPath(dia, p)
        }
        val px = xOf(tl.timeMs); p.color = 0xFFFF5252.toInt(); p.strokeWidth = 3f
        c.drawLine(px, 0f, px, height.toFloat(), p)
        dia.reset(); dia.moveTo(px - 9f, 0f); dia.lineTo(px + 9f, 0f); dia.lineTo(px, 14f); dia.close(); c.drawPath(dia, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val f = ((e.x - padL) / (width - padL - padR)).coerceIn(0f, 1f)
            val idx = Math.round(f * tl.durationMs * tl.fps / 1000f)
            tl.seekFrame(idx)
            invalidate(); onScrub?.invoke()
        }
        return true
    }

    companion object { private const val Color_white = 0xFFFFFFFF.toInt() }
}
