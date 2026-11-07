package com.glooboo.umessage.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** I21: a person's number or a service line is decided by libphonenumber, not by one country's patterns. */
class ShortLine042Test {

    private fun withLocale(locale: Locale, block: () -> Unit) {
        val before = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            block()
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun mobileNumbersOfOtherCountriesAreNotServiceLines() = withLocale(Locale("en", "GB")) {
        assertFalse(MessageCategories.isServiceSender("07712345678"))
        assertFalse(MessageCategories.isServiceSender("+447712345678"))
        assertFalse(MessageCategories.isServiceSender("+14155550123"))
        assertFalse(MessageCategories.isServiceSender("+91 98765 43210"))
    }

    @Test fun nationalMobileOfTheHomeCountryIsAPerson() = withLocale(Locale("en", "US")) {
        assertFalse(MessageCategories.isServiceSender("4155550123"))
        assertFalse(MessageCategories.isServiceSender("(415) 555-0123"))
    }

    @Test fun shortCodesAreServiceLines() = withLocale(Locale("en", "US")) {
        assertTrue(MessageCategories.isServiceSender("5000123"))
        assertTrue(MessageCategories.isServiceSender("30001234"))
        assertTrue(MessageCategories.isServiceSender("+98 5000123"))
        assertTrue(MessageCategories.isServiceSender("BankX"))
    }

    @Test fun numbersWithALeadingZeroAndBlankAreNotServiceLines() = withLocale(Locale("en", "US")) {
        assertFalse(MessageCategories.isServiceSender("02112345678"))
        assertFalse(MessageCategories.isServiceSender(""))
    }

    @Test fun anUnknownRegionStillReadsShortCodesAsServices() = withLocale(Locale.ROOT) {
        assertTrue(MessageCategories.isServiceSender("5000123"))
        val category = MessageCategories.classify("07712345678", "Deposit 5 USD", isContact = false)
        assertEquals(MessageCategory.Other, category)
    }
}
