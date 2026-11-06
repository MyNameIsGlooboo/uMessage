package com.glooboo.umessage.ui.newconversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactDisplayTest {

    @Test
    fun digitLedAndSymbolNamesShareTheHashSection() {
        assertEquals("#", sectionLetter("7123456789"))
        assertEquals("#", sectionLetter("+44 712"))
        assertEquals("#", sectionLetter(""))
        assertEquals("A", sectionLetter("Anna"))
        assertEquals("S", sectionLetter("  Sara"))
    }

    @Test
    fun numberThatOnlyRepeatsTheNameIsHidden() {
        assertEquals(emptyList<String>(), supportingNumbers("0712 345 6789", listOf("+447123456789")))
        assertEquals(listOf("+15550101"), supportingNumbers("Sara", listOf("+15550101")))
        assertEquals(listOf("+15550101"), supportingNumbers("0712 345 6789", listOf("+447123456789", "+15550101")))
    }
}
