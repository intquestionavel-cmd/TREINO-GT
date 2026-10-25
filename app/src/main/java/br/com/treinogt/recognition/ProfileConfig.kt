package br.com.treinogt.recognition

import org.json.JSONObject
import java.util.Base64

data class RecognitionParams(
    val minConfidence: Float = 0.5f,
    val maxSpeedKmh: Int = 450,
    val maxDeltaKmhPerSec: Float = 300f,
    val allowedGears: Set<String> = GearParser.DIGIT_GEARS,
    val throttleBaseline: LumaGrid? = null,
    val brakeBaseline: LumaGrid? = null,
)

data class ProfileConfig(
    val id: Long = 0,
    val name: String? = null,
    val roi: RoiConfig = RoiConfig.placeholder(),
    val params: RecognitionParams = RecognitionParams(),
    val version: Int = 1,
)

/** Serialização JSON (org.json) das ROIs e parâmetros — armazenadas em TrackProfileEntity. */
object ProfileCodec {
    fun roiToJson(c: RoiConfig): String {
        val o = JSONObject()
        o.put("version", c.version).put("refWidth", c.refWidth).put("refHeight", c.refHeight)
            .put("calibrated", c.calibrated)
        val r = JSONObject()
        for ((k, v) in c.rois) {
            r.put(k.name, JSONObject().put("l", v.left.toDouble()).put("t", v.top.toDouble())
                .put("r", v.right.toDouble()).put("b", v.bottom.toDouble()))
        }
        o.put("rois", r)
        return o.toString()
    }

    fun roiFromJson(s: String): RoiConfig {
        val o = JSONObject(s)
        val r = o.getJSONObject("rois")
        val map = LinkedHashMap<RoiKind, Roi>()
        for (k in RoiKind.values()) {
            val j = r.optJSONObject(k.name) ?: continue
            map[k] = Roi(j.getDouble("l").toFloat(), j.getDouble("t").toFloat(),
                j.getDouble("r").toFloat(), j.getDouble("b").toFloat())
        }
        return RoiConfig(o.optInt("version", 1), o.getInt("refWidth"), o.getInt("refHeight"),
            o.optBoolean("calibrated", false), map)
    }

    private fun gridToJson(g: LumaGrid): JSONObject =
        JSONObject().put("w", g.width).put("h", g.height)
            .put("d", Base64.getEncoder().encodeToString(g.data))

    private fun gridFromJson(j: JSONObject): LumaGrid =
        LumaGrid(j.getInt("w"), j.getInt("h"), Base64.getDecoder().decode(j.getString("d")))

    fun paramsToJson(p: RecognitionParams): String {
        val o = JSONObject()
        o.put("minConfidence", p.minConfidence.toDouble()).put("maxSpeedKmh", p.maxSpeedKmh)
            .put("maxDeltaKmhPerSec", p.maxDeltaKmhPerSec.toDouble())
            .put("allowedGears", p.allowedGears.sorted().joinToString(","))
        p.throttleBaseline?.let { o.put("throttleBaseline", gridToJson(it)) }
        p.brakeBaseline?.let { o.put("brakeBaseline", gridToJson(it)) }
        return o.toString()
    }

    fun paramsFromJson(s: String): RecognitionParams {
        val o = JSONObject(s)
        val gears = o.optString("allowedGears", "").split(",").filter { it.isNotBlank() }.toSet()
        return RecognitionParams(
            minConfidence = o.optDouble("minConfidence", 0.5).toFloat(),
            maxSpeedKmh = o.optInt("maxSpeedKmh", 450),
            maxDeltaKmhPerSec = o.optDouble("maxDeltaKmhPerSec", 300.0).toFloat(),
            allowedGears = if (gears.isEmpty()) GearParser.DIGIT_GEARS else gears,
            throttleBaseline = o.optJSONObject("throttleBaseline")?.let(::gridFromJson),
            brakeBaseline = o.optJSONObject("brakeBaseline")?.let(::gridFromJson),
        )
    }
}
