package com.glooboo.umessage.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLinksTest {
    private fun numbers(text: String) = findPhoneNumbers(text).map { it.number }

    @Test
    fun internationalAndLocalNumbersAreFound() {
        assertEquals(listOf("+14155550123"), numbers("Call +1 (415) 555-0123 today"))
        assertEquals(listOf("09121234567"), numbers("زنگ بزن 09121234567 لطفا"))
        assertEquals(listOf("+989121234567"), numbers("00989121234567"))
        assertEquals(listOf("02112345678"), numbers("021-1234 5678"))
        assertEquals(listOf("5551234567"), numbers("call 5551234567."))
    }

    @Test
    fun persianDigitsAreFound() {
        assertEquals(listOf("09121234567"), numbers("شماره ۰۹۱۲۱۲۳۴۵۶۷ است"))
    }

    @Test
    fun rangeCoversTheWrittenNumber() {
        val text = "Ring +44 20 7946 0958 now"
        val match = findPhoneNumbers(text).single()
        assertEquals("+44 20 7946 0958", text.substring(match.range))
        assertEquals("+442079460958", match.number)
    }

    @Test
    fun verificationCodesAreNotNumbers() {
        assertTrue(numbers("Your code is 123456").isEmpty())
        assertTrue(numbers("Code: 1234567 expires soon").isEmpty())
        assertTrue(numbers("code 12345678").isEmpty())
        assertTrue(numbers("G-123456 is your code").isEmpty())
        assertTrue(numbers("123 456").isEmpty())
    }

    @Test
    fun pricesAreNotNumbers() {
        assertTrue(numbers("Total $1234567 due").isEmpty())
        assertTrue(numbers("Paid 1.234.567 today").isEmpty())
        assertTrue(numbers("Sum 1234567.50 EUR").isEmpty())
        assertTrue(numbers("Sum 1,234,567 IRR").isEmpty())
        assertTrue(numbers("50% off 12345678%").isEmpty())
    }

    @Test
    fun datesAndTimesAreNotNumbers() {
        assertTrue(numbers("On 2026-11-11 at 12:30").isEmpty())
        assertTrue(numbers("Due 11.11.2026").isEmpty())
        assertTrue(numbers("Due 11/11/2026").isEmpty())
        assertTrue(numbers("2026/11/11 10:20:30").isEmpty())
    }

    @Test
    fun cardsIdsAndPartsOfWordsAreNotNumbers() {
        assertTrue(numbers("Card 6037 9912 3456 7890").isEmpty())
        assertTrue(numbers("Order AB12345678 shipped").isEmpty())
        assertTrue(numbers("ref #12345678").isEmpty())
        assertTrue(numbers("see example.com/12345678901").isEmpty())
        assertTrue(numbers("me@12345678901.test").isEmpty())
        assertTrue(numbers("version 10.0.0.1234567").isEmpty())
    }

    @Test
    fun severalNumbersAreAllFound() {
        assertEquals(listOf("09121234567", "+14155550123"), numbers("09121234567 or +1-415-555-0123"))
    }

    @Test
    fun phoneLinkRoundTrip() {
        assertEquals("tel:+14155550123", phoneLink("+14155550123"))
        assertEquals("+14155550123", phoneOf("tel:+14155550123"))
        assertNull(phoneOf("https://example.com"))
        assertEquals("+14155550123", linkCopyText("tel:+14155550123"))
    }
}
