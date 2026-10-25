package br.com.treinogt.recognition

import org.junit.Assert.*
import org.junit.Test

class ValidatorAndPedalTest {
    private fun sp(v: Int) = SpeedReading(v, "$v", 0.9f, Validity.VALID)
    private fun ns(sec: Double) = (sec * 1e9).toLong()

    @Test fun rejectsImpossibleJumpButKeepsRawValue() {
        val v = SpeedPlausibilityValidator()
        assertTrue(v.validate(sp(100), ns(0.0)).isValid)
        val r = v.validate(sp(400), ns(0.3))
        assertEquals(Validity.IMPLAUSIBLE_JUMP, r.validity); assertEquals(400, r.value)
    }
    @Test fun acceptsPlausibleChange() {
        val v = SpeedPlausibilityValidator()
        v.validate(sp(100), ns(0.0))
        assertTrue(v.validate(sp(112), ns(0.3)).isValid)
    }
    @Test fun resyncsWhenTwoRejectedReadingsAgree() {
        val v = SpeedPlausibilityValidator()
        v.validate(sp(100), ns(0.0))
        assertFalse(v.validate(sp(400), ns(0.3)).isValid)
        assertTrue(v.validate(sp(402), ns(0.6)).isValid)
        assertTrue(v.validate(sp(405), ns(0.9)).isValid)
    }
    @Test fun invalidInputPassesThroughUntouched() {
        val bad = SpeedReading(null, "x", null, Validity.NON_NUMERIC)
        assertSame(bad, SpeedPlausibilityValidator().validate(bad, 0))
    }

    private fun grid(fill: Int, litCells: Int = 0, lit: Int = 255): LumaGrid {
        val d = ByteArray(32 * 16) { fill.toByte() }
        for (i in 0 until litCells) d[i] = lit.toByte()
        return LumaGrid(32, 16, d)
    }
    @Test fun changedRatioCountsCellsAboveThreshold() {
        val base = grid(20)
        assertEquals(0f, PedalMath.changedRatio(grid(20), base)!!, 1e-6f)
        assertEquals(0.25f, PedalMath.changedRatio(grid(20, litCells = 128), base)!!, 1e-6f)
        assertEquals(0f, PedalMath.changedRatio(grid(50), base)!!, 1e-6f)   // diferença 30 < limiar 40
    }
    @Test fun noBaselineOrSizeMismatchMeansUnknown() {
        assertNull(PedalMath.changedRatio(grid(20), null))
        assertNull(PedalMath.changedRatio(grid(20), LumaGrid(16, 8, ByteArray(128))))
        assertEquals(PedalState.UNKNOWN, PedalTracker().update(null).state)
    }
    @Test fun trackerHasHysteresisAndNeverReportsIntensity() {
        val t = PedalTracker(pressAt = 0.12f, releaseAt = 0.06f)
        assertEquals(PedalState.RELEASED, t.update(0.05f).state)
        assertEquals(PedalState.RELEASED, t.update(0.10f).state)
        val pressed = t.update(0.30f)
        assertEquals(PedalState.PRESSED, pressed.state); assertNull(pressed.intensityEstimate)
        assertEquals(PedalState.PRESSED, t.update(0.08f).state)   // ainda acima do limiar de soltar
        assertEquals(PedalState.RELEASED, t.update(0.04f).state)
    }
    @Test fun roiAdaptsToResolutionAndDetectsOrientationMismatch() {
        val roi = Roi(0.5f, 0.5f, 0.75f, 1.0f)
        assertEquals(PxRect(1000, 500, 1500, 1000), roi.toPixelRect(2000, 1000))
        assertEquals(PxRect(500, 250, 750, 500), roi.toPixelRect(1000, 500))
        val cfg = RoiConfig.placeholder()
        assertTrue(cfg.isCompatible(2712, 1220)); assertFalse(cfg.isCompatible(1220, 2712))
        assertFalse(cfg.calibrated)
    }
    @Test fun profileJsonRoundTripKeepsBaselinesAndGears() {
        val p = RecognitionParams(allowedGears = GearParser.DIGIT_GEARS + "N", throttleBaseline = grid(30, 10))
        val back = ProfileCodec.paramsFromJson(ProfileCodec.paramsToJson(p))
        assertTrue("N" in back.allowedGears)
        assertArrayEquals(p.throttleBaseline!!.data, back.throttleBaseline!!.data)
        val roi = RoiConfig.placeholder()
        assertEquals(roi, ProfileCodec.roiFromJson(ProfileCodec.roiToJson(roi)))
    }
}
