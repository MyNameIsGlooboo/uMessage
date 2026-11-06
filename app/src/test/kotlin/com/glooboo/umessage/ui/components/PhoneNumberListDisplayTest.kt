package com.glooboo.umessage.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** N2, CL-10: group titles show each number in the display form, joined with the language's comma. */
class PhoneNumberListDisplayTest {

    private val english = Locale.ENGLISH
    private val persian = Locale.forLanguageTag("fa")
    private val sevenDigits = { number: String -> number.substring(0, 3) + "-" + number.substring(3) }
    private fun display(locale: Locale) = { text: String -> displayNumber(text, locale, sevenDigits) }

    @Test
    fun eachNumberOfAGroupTitleIsFormatted() {
        assertEquals("555-9876, 555-1234", displayTitle("5559876, 5551234", english, display(english)))
        assertEquals("555-9876, Sara", displayTitle("5559876, Sara", english, display(english)))
    }

    @Test
    fun persianGroupTitlesUsePersianDigitsAndTheArabicComma() {
        assertEquals("۵۵۵-۹۸۷۶، ۵۵۵-۱۲۳۴", displayTitle("5559876, 5551234", persian, display(persian)))
        assertEquals("۵۵۵-۹۸۷۶، Sara، Ali +۲", displayTitle("5559876, Sara, Ali +2", persian, display(persian)))
    }

    @Test
    fun singleTitlesAndNameOnlyListsAreLeftAsTheyWere() {
        assertEquals("555-9876", displayTitle("5559876", english, display(english)))
        assertEquals("Sara, Ali", displayTitle("Sara, Ali", persian, display(persian)))
        assertEquals("Sara Ahmadi", displayTitle("Sara Ahmadi", persian, display(persian)))
    }

    @Test
    fun theSeparatorFollowsTheLanguage() {
        assertEquals(", ", listSeparator(english))
        assertEquals("، ", listSeparator(persian))
        assertEquals("، ", listSeparator(Locale.forLanguageTag("ar")))
    }
}
