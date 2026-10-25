package br.com.treinogt.recognition

import org.junit.Assert.*
import org.junit.Test

class ParsersTest {
    @Test fun parsesPlainAndUnitSuffixedSpeed() {
        assertEquals(187, SpeedParser.parse("187", 0.9f).value)
        assertEquals(187, SpeedParser.parse(" 187 km/h ", 0.9f).value)
        assertTrue(SpeedParser.parse("187", 0.9f).isValid)
    }
    @Test fun appliesOcrConfusionsOnlyInsideNumericText() {
        assertEquals(100, SpeedParser.parse("1OO", 0.9f).value)
        assertEquals(Validity.NON_NUMERIC, SpeedParser.parse("abc", 0.9f).validity)
    }
    @Test fun rejectsEmptyOutOfRangeAndLowConfidenceButKeepsRawValue() {
        assertEquals(Validity.EMPTY, SpeedParser.parse("  ", null).validity)
        val oor = SpeedParser.parse("999", 0.9f)
        assertEquals(Validity.OUT_OF_RANGE, oor.validity); assertEquals(999, oor.value); assertFalse(oor.isValid)
        val low = SpeedParser.parse("120", 0.2f)
        assertEquals(Validity.LOW_CONFIDENCE, low.validity); assertEquals("120", low.raw)
    }
    @Test fun unknownConfidenceIsAcceptedButRecordedAsNull() {
        val r = SpeedParser.parse("80", null)
        assertTrue(r.isValid); assertNull(r.confidence)
    }
    @Test fun gearOnlyAcceptsConfiguredStates() {
        assertEquals("3", GearParser.parse("3", 0.9f).value)
        assertEquals(Validity.NOT_ALLOWED, GearParser.parse("N", 0.9f).validity)   // N não é permitido por padrão
        assertEquals("N", GearParser.parse("N", 0.9f, GearParser.DIGIT_GEARS + "N").value)
        assertNull(GearParser.parse("X", 0.9f).value)                              // não inventa marcha
        assertEquals("1", GearParser.parse("I", 0.9f).value)
    }
    @Test fun gearLowConfidenceIsInvalid() {
        assertFalse(GearParser.parse("4", 0.1f).isValid)
    }
}
