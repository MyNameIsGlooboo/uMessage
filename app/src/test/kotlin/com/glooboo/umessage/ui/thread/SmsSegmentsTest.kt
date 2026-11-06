package com.glooboo.umessage.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RM-46: Persian text splits at 70 characters (67 per part), plain English at 160 (153 per part). */
class SmsSegmentsTest {

    @Test
    fun shortEnglishIsOneGsmPart() {
        val s = SmsSegments.of("On my way")
        assertEquals(SmsSegments(parts = 1, remaining = 151, unicode = false), s)
    }

    @Test
    fun englishSplitsAt160Then153() {
        assertEquals(1, SmsSegments.of("a".repeat(160)).parts)
        val two = SmsSegments.of("a".repeat(161))
        assertEquals(2, two.parts)
        assertEquals(2 * 153 - 161, two.remaining)
    }

    @Test
    fun extensionCharactersCountTwice() {
        // 159 letters and a euro sign: 161 septets, so two parts.
        assertEquals(2, SmsSegments.of("a".repeat(159) + "€").parts)
    }

    @Test
    fun persianIsAlwaysUnicode() {
        val s = SmsSegments.of("سلام")
        assertTrue(s.unicode)
        assertEquals(SmsSegments.UCS2_SINGLE - 4, s.remaining)
    }

    @Test
    fun eightyPersianCharactersAreTwoParts() {
        val s = SmsSegments.of("س".repeat(80))
        assertEquals(2, s.parts)
        assertEquals(2 * 67 - 80, s.remaining)
        assertTrue(s.isMultipart)
        assertEquals(67, s.perPart)
    }

    @Test
    fun seventyPersianCharactersStillFitOne() {
        val s = SmsSegments.of("س".repeat(70))
        assertEquals(1, s.parts)
        assertEquals(0, s.remaining)
        assertFalse(s.isMultipart)
    }

    @Test
    fun oneLetterOutsideGsmMakesTheWholeMessageUnicode() {
        val s = SmsSegments.of("a".repeat(100) + "ش")
        assertTrue(s.unicode)
        assertEquals(2, s.parts)
    }
}
