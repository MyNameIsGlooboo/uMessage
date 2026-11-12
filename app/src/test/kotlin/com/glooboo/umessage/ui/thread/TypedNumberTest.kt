package com.glooboo.umessage.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Thread-22: only phone numbers typed in the people editor become recipients. */
class TypedNumberTest {

    @Test
    fun numbersPass() {
        assertEquals("+98 912 345 6789", ThreadRecipients.typedNumberOrNull(" +98 912 345 6789 "))
        assertEquals("5550101", ThreadRecipients.typedNumberOrNull("5550101"))
    }

    @Test
    fun persianDigitsBecomeAscii() {
        assertEquals("0912", ThreadRecipients.typedNumberOrNull("\u06F0\u06F9\u06F1\u06F2"))
    }

    @Test
    fun lettersAreRejected() {
        assertNull(ThreadRecipients.typedNumberOrNull("abc"))
        assertNull(ThreadRecipients.typedNumberOrNull("Bank1234"))
        assertNull(ThreadRecipients.typedNumberOrNull(""))
    }
}
