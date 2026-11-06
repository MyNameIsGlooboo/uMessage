package com.glooboo.umessage.spam.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DL-24: a stranger whose number shares the last 9 digits with a contact must not be taken for that contact. */
class ContactNumberIndexTest {

    private val home = NumberNormalizer { "GB" }

    private fun index(vararg contacts: Pair<String, String>) = ContactNumberIndex<String>(home).apply {
        contacts.forEach { (number, name) -> add(number, name) }
    }

    @Test fun strangerFromAnotherCountryIsNotTheContact() {
        val book = index("+44 712 345 6789" to "Mom")
        for (stranger in listOf("+91 712 345 6789", "+17123456789", "00917123456789", "+33 712345678 9", "+61 7123456789")) {
            assertNull(stranger, book.find(stranger))
        }
    }

    @Test fun everyFormOfTheContactsNumberFindsTheContact() {
        val book = index("+44 712 345 6789" to "Mom")
        val forms = listOf(
            "+447123456789", "00447123456789", "447123456789", "07123456789", "0712-345-6789", "7123456789",
            "۰۷۱۲۳۴۵۶۷۸۹", "+۴۴ ۷۱۲ ۳۴۵ ۶۷۸۹",
        )
        for (form in forms) assertEquals(form, "Mom", book.find(form))
    }

    @Test fun contactSavedNationallyIsFoundByInternationalSender() {
        val book = index("0712 345 6789" to "Mom")
        assertEquals("Mom", book.find("+447123456789"))
        assertNull(book.find("+917123456789"))
    }

    @Test fun twoContactsWithTheSameLastDigitsStayApart() {
        val book = index("+44 712 345 6789" to "Mom", "+91 712 345 6789" to "Uncle in Delhi")
        assertEquals("Mom", book.find("07123456789"))
        assertEquals("Mom", book.find("+447123456789"))
        assertEquals("Uncle in Delhi", book.find("+917123456789"))
        assertEquals("Uncle in Delhi", book.find("0091 7123456789"))
    }

    @Test fun contactWithoutAKnownCountryStillMatchesByLastDigits() {
        // An odd saved value that fits no country: the old last-9-digits rule is all there is.
        val book = index("123456789012345" to "Odd")
        assertEquals("Odd", book.find("+44 789012345"))
    }

    @Test fun shortCodesAndSenderIds() {
        val book = index("30001234" to "Bank", "HOMETEL" to "Operator")
        assertEquals("Bank", book.find("30001234"))
        assertEquals("Bank", book.find("+44 3000 1234"))
        assertNull(book.find("30001235"))
        assertNull(book.find("+91 30001234"))
        assertEquals("Operator", book.find("HomeTel"))
        assertNull(book.find("MCI"))
        assertNull(book.find(""))
    }

    @Test fun replaceDecidesWhichValueWins() {
        val book = ContactNumberIndex<String>(home)
        book.add("+447123456789", "no photo")
        book.add("07123456789", "with photo") { it == "no photo" }
        book.add("0044 712 345 6789", "later") { false }
        assertEquals("with photo", book.find("+447123456789"))
    }
}
