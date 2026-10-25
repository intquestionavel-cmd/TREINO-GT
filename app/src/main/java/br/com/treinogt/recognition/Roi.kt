package br.com.treinogt.recognition

import kotlin.math.roundToInt

enum class RoiKind(val label: String) {
    SPEED("Velocidade"), GEAR("Marcha"), THROTTLE("Acelerador"), BRAKE("Freio"), MINIMAP("Minimap")
}

data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Região de interesse NORMALIZADA (0..1) em relação ao frame — adapta-se a qualquer resolução. */
data class Roi(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun isValid(): Boolean =
        left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f && right > left && bottom > top

    fun toPixelRect(w: Int, h: Int): PxRect {
        val l = (left * w).roundToInt().coerceIn(0, w - 1)
        val t = (top * h).roundToInt().coerceIn(0, h - 1)
        val r = (right * w).roundToInt().coerceIn(l + 1, w)
        val b = (bottom * h).roundToInt().coerceIn(t + 1, h)
        return PxRect(l, t, r, b)
    }
}

/**
 * calibrated=false => as ROIs são apenas PALPITES iniciais. O pipeline NÃO produz amostras até o usuário calibrar.
 * A orientação de referência é guardada: ROIs calibradas em paisagem não valem para retrato (e vice-versa).
 */
data class RoiConfig(
    val version: Int,
    val refWidth: Int,
    val refHeight: Int,
    val calibrated: Boolean,
    val rois: Map<RoiKind, Roi>,
) {
    fun roi(kind: RoiKind): Roi? = rois[kind]
    fun isCompatible(w: Int, h: Int): Boolean = (w >= h) == (refWidth >= refHeight)

    companion object {
        /** Palpites a partir da descrição do documento (sem a captura de referência). Exigem calibração. */
        fun placeholder(): RoiConfig = RoiConfig(
            version = 1, refWidth = 2712, refHeight = 1220, calibrated = false,
            rois = mapOf(
                RoiKind.SPEED to Roi(0.43f, 0.84f, 0.55f, 0.96f),
                RoiKind.GEAR to Roi(0.56f, 0.86f, 0.60f, 0.96f),
                RoiKind.BRAKE to Roi(0.28f, 0.84f, 0.40f, 0.97f),
                RoiKind.THROTTLE to Roi(0.62f, 0.84f, 0.74f, 0.97f),
                RoiKind.MINIMAP to Roi(0.00f, 0.00f, 0.20f, 0.30f),
            ),
        )
    }
}
