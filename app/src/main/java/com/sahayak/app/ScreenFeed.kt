package com.sahayak.app

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Screen ki tasveer JPEG banakar bhejta hai: badlav par har second tak,
 * aur screen na badle to har 5 second mein ek baar.
 */
class ScreenFeed(
    private val projection: MediaProjection,
    private val onChanged: () -> Unit,
    private val onFrame: (ByteArray) -> Unit
) {
    private val thread = HandlerThread("sahayak-screen")
    private lateinit var handler: Handler
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var lastJpeg: ByteArray? = null
    private var lastSentAt = 0L
    private var signature: IntArray? = null
    @Volatile private var running = false

    fun start() {
        val metrics = Resources.getSystem().displayMetrics
        val scale = min(1f, MAX_WIDTH.toFloat() / metrics.widthPixels)
        val width = (metrics.widthPixels * scale).toInt() and 1.inv()
        val height = (metrics.heightPixels * scale).toInt() and 1.inv()
        val density = (metrics.densityDpi * scale).toInt()

        thread.start()
        handler = Handler(thread.looper)
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        display = projection.createVirtualDisplay(
            "sahayak", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, handler
        )
        running = true
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                capture()
            } catch (e: RuntimeException) {
                // ek frame chhootne se session nahi rukna chahiye
            }
            handler.postDelayed(this, FRAME_MS)
        }
    }

    private fun capture() {
        val image = reader?.acquireLatestImage()
        var jpeg: ByteArray? = null
        if (image != null) {
            image.use {
                val plane = it.planes[0]
                val rowWidth = plane.rowStride / plane.pixelStride
                val full = Bitmap.createBitmap(rowWidth, it.height, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(full, 0, 0, it.width, it.height)
                val out = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                jpeg = out.toByteArray()
                checkChange(cropped)
                if (cropped !== full) cropped.recycle()
                full.recycle()
            }
            lastJpeg = jpeg
        }
        val now = System.currentTimeMillis()
        val frame = jpeg ?: lastJpeg?.takeIf { now - lastSentAt >= HEARTBEAT_MS }
        if (frame != null) {
            lastSentAt = now
            onFrame(frame)
        }
    }

    /** Chhoti tasveer se dekhta hai ki screen sach mein badli ya nahi. */
    private fun checkChange(bitmap: Bitmap) {
        val small = Bitmap.createScaledBitmap(bitmap, SIG_WIDTH, SIG_HEIGHT, true)
        val pixels = IntArray(SIG_WIDTH * SIG_HEIGHT)
        small.getPixels(pixels, 0, SIG_WIDTH, 0, 0, SIG_WIDTH, SIG_HEIGHT)
        if (small !== bitmap) small.recycle()
        val next = IntArray(pixels.size) { i ->
            val p = pixels[i]
            ((p shr 16 and 255) + (p shr 8 and 255) + (p and 255)) / 3
        }
        val old = signature
        signature = next
        if (old == null) return
        val moved = next.indices.count { kotlin.math.abs(next[it] - old[it]) > CELL_DIFF }
        if (moved >= next.size * CHANGE_SHARE) onChanged()
    }

    fun stop() {
        running = false
        if (thread.isAlive) {
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
        display?.release()
        reader?.close()
        display = null
        reader = null
    }

    companion object {
        private const val MAX_WIDTH = 640
        private const val JPEG_QUALITY = 55
        private const val FRAME_MS = 1_000L
        private const val HEARTBEAT_MS = 5_000L
        private const val SIG_WIDTH = 24
        private const val SIG_HEIGHT = 42
        private const val CELL_DIFF = 24
        private const val CHANGE_SHARE = 0.03
    }
}
