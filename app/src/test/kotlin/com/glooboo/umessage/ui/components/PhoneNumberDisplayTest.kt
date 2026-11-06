package com.glooboo.umessage.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** CL-10, CL-33, TH-29, VS-38 and B1: the shared number display form and number isolation. */
class PhoneNumberDisplayTest {

    private val english = Locale.ENGLISH
    private val persian = Locale.forLanguageTag("fa")
    private val dashes = { number: String -> number.replace(" ", "").chunked(3).joinToString("-") }

    @Test
    fun bareNumbersAreGroupedInLatinDigitsInEnglish() {
        assertEquals("071-234-567-89", displayNumber("07123456789", english, dashes))
        assertEquals("+44-712-345-678-9", displayNumber("+44 712 345 6789", english, dashes))
    }

    @Test
    fun bareNumbersUsePersianDigitsInThePersianUi() {
        assertEquals("۹۸۵-۰۰۰۱", displayNumber("9850001", persian) { "985-0001" })
        assertEquals("+۴۴ ۷۱۲ ۳۴۵ ۶۷۸۹", displayNumber("+447123456789", persian) { "+44 712 345 6789" })
        // A short code is never regrouped, but its digits still follow the language.
        assertEquals("۳۰۰۰۱۲", displayNumber("300012", persian) { "grouped" })
        assertEquals("300012", displayNumber("300012", english) { "grouped" })
    }

    @Test
    fun namesAndSenderIdsAreLeftAlone() {
        val never = { _: String -> "formatted" }
        assertEquals("Sara Ahmadi", displayNumber("Sara Ahmadi", persian, never))
        assertEquals("علی رضایی", displayNumber("علی رضایی", persian, never))
        assertEquals("VM-HDFCBK", displayNumber("VM-HDFCBK", persian, never))
        assertEquals("Mom 2", displayNumber("Mom 2", persian, never))
        assertEquals("", displayNumber("", persian, never))
    }

    @Test
    fun aFormatterThatGivesNothingKeepsTheNumber() {
        assertEquals("07123456789", displayNumber("07123456789", english) { null })
        assertEquals("07123456789", displayNumber("07123456789", english) { " " })
        assertEquals("۰۷۱۲۳۴۵۶۷۸۹", displayNumber("07123456789", persian) { null })
    }

    @Test
    fun bareNumberRecognition() {
        assertTrue(isBareNumber("+44 712 345 6789"))
        assertTrue(isBareNumber("(020) 8888-1234"))
        assertTrue(isBareNumber("30007"))
        assertFalse(isBareNumber("BANK123"))
        assertFalse(isBareNumber("Sara"))
        assertFalse(isBareNumber(""))
    }

    @Test
    fun asciiDigitsDropsSeparatorsAndFoldsScripts() {
        assertEquals("071234", asciiDigits("۰۷۱۲-۳۴"))
        assertEquals("9850001", asciiDigits("985-0001"))
        assertEquals("", asciiDigits("Sara"))
    }

    @Test
    fun persianAmountStaysInOneIsolate() {
        // B1: each digit group used to get its own isolate, and a right-to-left line showed "۰۰۰,۲۵۰,۱".
        assertEquals("مبلغ: ⁦۱,۲۵۰,۰۰۰⁩ ریال", isolateNumbers("مبلغ: ۱,۲۵۰,۰۰۰ ریال"))
        assertEquals(
            "رمز پویا: ⁦۵۲۸۱۴۷۳۶⁩ مبلغ: ⁦۱,۲۵۰,۰۰۰⁩ ریال بانک ملت",
            isolateNumbers("رمز پویا: ۵۲۸۱۴۷۳۶ مبلغ: ۱,۲۵۰,۰۰۰ ریال بانک ملت"),
        )
        assertEquals("مانده ⁦۱٬۲۵۰٬۰۰۰⁩ ریال", isolateNumbers("مانده ۱٬۲۵۰٬۰۰۰ ریال"))
        assertEquals("Balance ⁦1,250,000.50⁩ Rial", isolateNumbers("Balance 1,250,000.50 Rial"))
    }

    @Test
    fun aSeparatorNotFollowedByADigitEndsTheRun() {
        assertEquals("Call ⁦0712345⁩, then text", isolateNumbers("Call 0712345, then text"))
        assertEquals("⁦123⁩, ⁦456⁩", isolateNumbers("123, 456"))
    }
}
