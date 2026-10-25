package br.com.treinogt.capture

import android.graphics.Bitmap
import br.com.treinogt.recognition.LumaGrid
import br.com.treinogt.recognition.PedalMath
import br.com.treinogt.recognition.PxRect

object LumaGridBuilder {
    const val COLS = 32
    const val ROWS = 16

    /** Recorta a ROI, reduz para COLS x ROWS (com filtro) e converte para luminância. */
    fun build(src: Bitmap, rect: PxRect): LumaGrid {
        val crop = Bitmap.createBitmap(src, rect.left, rect.top, rect.width, rect.height)
        val small = Bitmap.createScaledBitmap(crop, COLS, ROWS, true)
        val px = IntArray(COLS * ROWS)
        small.getPixels(px, 0, COLS, 0, 0, COLS, ROWS)
        val out = ByteArray(px.size) { PedalMath.luma(px[it]).toByte() }
        if (small !== crop) small.recycle()
        if (crop !== src) crop.recycle()
        return LumaGrid(COLS, ROWS, out)
    }
}
