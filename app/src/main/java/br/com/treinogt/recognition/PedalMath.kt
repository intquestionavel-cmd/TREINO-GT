package br.com.treinogt.recognition

/** Grade de luminância reduzida (ex.: 32x16) de uma ROI. Valores 0..255 guardados em bytes. */
class LumaGrid(val width: Int, val height: Int, val data: ByteArray) {
    init {
        require(data.size == width * height) { "data.size=${data.size} != ${width}x$height" }
    }

    fun at(i: Int): Int = data[i].toInt() and 0xFF
    val size: Int get() = data.size
}

object PedalMath {
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /**
     * Fração de células cuja luminância difere da base (pedal solto) por mais de [lumaThreshold].
     * Retorna null se não houver base ou se as grades tiverem tamanhos diferentes.
     */
    fun changedRatio(current: LumaGrid, baseline: LumaGrid?, lumaThreshold: Int = 40): Float? {
        if (baseline == null || baseline.width != current.width || baseline.height != current.height) return null
        var n = 0
        for (i in 0 until current.size) {
            if (Math.abs(current.at(i) - baseline.at(i)) > lumaThreshold) n++
        }
        return n.toFloat() / current.size
    }
}

/** Binariza com histerese (pressAt para acionar, releaseAt para soltar) para evitar oscilação. */
class PedalTracker(private val pressAt: Float = 0.12f, private val releaseAt: Float = 0.06f) {
    private var pressed = false

    fun reset() {
        pressed = false
    }

    fun update(ratio: Float?): PedalReading {
        if (ratio == null) return PedalReading.UNKNOWN
        pressed = if (pressed) ratio > releaseAt else ratio >= pressAt
        val mid = (pressAt + releaseAt) / 2f
        // "confiança" heurística: distância ao ponto médio da histerese. NÃO é uma probabilidade calibrada.
        val conf = (Math.abs(ratio - mid) / mid).coerceIn(0f, 1f)
        return PedalReading(if (pressed) PedalState.PRESSED else PedalState.RELEASED, ratio, conf)
    }
}
