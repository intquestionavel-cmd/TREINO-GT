package br.com.treinogt.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display

/**
 * Captura via MediaProjection (API oficial; sem root). Mantém um VirtualDisplay espelhando a tela real.
 * - Android 14+: registerCallback ANTES de createVirtualDisplay (exigido).
 * - Acompanha mudança de orientação/tamanho do display e redimensiona o VirtualDisplay.
 * - onStopped é chamado quando o usuário/sistema revoga a captura.
 */
class ScreenCapturer(
    context: Context,
    private val projection: MediaProjection,
    private val onStopped: () -> Unit,
) : FrameSource {
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var bmp: Bitmap? = null
    @Volatile
    var width = 0
        private set
    @Volatile
    var height = 0
        private set
    private var dpi = 0
    private var released = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            release()
            onStopped()
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) resizeIfNeeded()
        }
    }

    fun start() {
        projection.registerCallback(projectionCallback, handler)
        readRealMetrics()
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "treinogt-capture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler,
        )
        displayManager.registerDisplayListener(displayListener, handler)
    }

    @Suppress("DEPRECATION")
    private fun readRealMetrics() {
        val m = DisplayMetrics()
        displayManager.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        width = m.widthPixels; height = m.heightPixels; dpi = m.densityDpi
    }

    @Synchronized
    private fun resizeIfNeeded() {
        if (released) return
        val oldW = width; val oldH = height
        readRealMetrics()
        if (oldW == width && oldH == height) return
        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay?.resize(width, height, dpi)
        virtualDisplay?.surface = newReader.surface
        reader?.close()
        reader = newReader
        bmp?.recycle(); bmp = null
    }

    /** Devolve o frame mais recente, ou null se não houve frame novo desde a última chamada. */
    @Synchronized
    override fun acquire(): Frame? {
        if (released) return null
        val image = reader?.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val bw = plane.rowStride / plane.pixelStride
            var b = bmp
            if (b == null || b.width != bw || b.height != image.height) {
                b?.recycle()
                b = Bitmap.createBitmap(bw, image.height, Bitmap.Config.ARGB_8888)
                bmp = b
            }
            plane.buffer.rewind()
            b!!.copyPixelsFromBuffer(plane.buffer)
            return Frame(b, image.width, image.height)
        } finally {
            image.close()
        }
    }

    @Synchronized
    private fun release() {
        if (released) return
        released = true
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        bmp?.recycle(); bmp = null
        virtualDisplay = null; reader = null
    }

    fun stop() {
        release()
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { projection.stop() }
    }
}
