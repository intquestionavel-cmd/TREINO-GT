package br.com.treinogt.capture

import android.graphics.Bitmap

/**
 * `bitmap` é REUTILIZADO pelo capturador (evita alocar ~13 MB por frame) e pode ser mais largo que `width`
 * (padding de rowStride). Sempre recortar usando `width`/`height`. Válido somente até o próximo acquire().
 */
class Frame(val bitmap: Bitmap, val width: Int, val height: Int)

interface FrameSource {
    fun acquire(): Frame?
}
