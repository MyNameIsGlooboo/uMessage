package com.glooboo.umessage.dialogs

import org.junit.Assert.assertEquals
import org.junit.Test

/** CL-10: the sender row of Message details shows the number in the display form, isolated for right-to-left text. */
class MessageDetailsSenderTest {

    private val display = { text: String -> if (text == "5550100") "۵۵۵-۰۱۰۰" else text }

    @Test
    fun aNamedSenderShowsTheFormattedNumber() {
        assertEquals("Sara Ahmadi (⁦۵۵۵-۰۱۰۰⁩)", formatContactInfo("Sara Ahmadi", "5550100", display))
    }

    @Test
    fun anUnnamedSenderShowsTheNumberOnce() {
        assertEquals("⁦۵۵۵-۰۱۰۰⁩", formatContactInfo("5550100", "5550100", display))
        assertEquals("AX-AMAZN", formatContactInfo("AX-AMAZN", "AX-AMAZN", display))
    }
}
