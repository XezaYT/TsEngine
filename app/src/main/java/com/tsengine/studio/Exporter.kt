package com.tsengine.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream

object Exporter {
    fun snapshot(r: SceneRenderer, w: Int, h: Int, out: File) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        r.drawExport(Canvas(bmp), w, h)
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    /** Deterministic offline render: steps the timeline frame by frame and encodes H.264 into MP4. (Video track only.) */
    fun video(r: SceneRenderer, t: Timeline, out: File, w: Int, h: Int, onProgress: (Float) -> Unit) {
        val fps = t.fps
        val total = (t.durationMs * fps / 1000f).toInt().coerceAtLeast(1)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var started = false
        val info = MediaCodec.BufferInfo()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val canvas = Canvas(bmp)
        val px = IntArray(w * h)
        val oldTime = t.timeMs; val wasPlaying = t.playing
        t.pause()

        fun drain(eos: Boolean) {
            while (true) {
                val oi = codec.dequeueOutputBuffer(info, if (eos) 10_000L else 0L)
                when {
                    oi == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
                    oi >= 0 -> {
                        val buf = codec.getOutputBuffer(oi)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) { buf.position(info.offset); buf.limit(info.offset + info.size); muxer.writeSampleData(track, buf, info) }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            for (i in 0 until total) {
                t.seek((i * 1000f / fps).toLong())
                bmp.eraseColor(Color.BLACK)
                r.drawExport(canvas, w, h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                var idx = -1
                while (idx < 0) { idx = codec.dequeueInputBuffer(10_000L); if (idx < 0) drain(false) }
                fill(codec.getInputImage(idx)!!, px, w, h)
                codec.queueInputBuffer(idx, 0, w * h * 3 / 2, i * 1_000_000L / fps, 0)
                drain(false)
                onProgress((i + 1) / total.toFloat())
            }
            var idx = -1
            while (idx < 0) { idx = codec.dequeueInputBuffer(10_000L); if (idx < 0) drain(false) }
            codec.queueInputBuffer(idx, 0, 0, total * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            try { if (started) muxer.stop() } catch (_: Exception) {}
            muxer.release(); bmp.recycle()
            t.seek(oldTime); if (wasPlaying) t.play()
        }
    }

    private fun fill(img: Image, px: IntArray, w: Int, h: Int) {
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = px[row + x]; val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                yb.put(y * yP.rowStride + x * yP.pixelStride, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte())
                if (y % 2 == 0 && x % 2 == 0) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    ub.put((y / 2) * uP.rowStride + (x / 2) * uP.pixelStride, u)
                    vb.put((y / 2) * vP.rowStride + (x / 2) * vP.pixelStride, v)
                }
            }
        }
    }
}
