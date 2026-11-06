package com.glooboo.umessage.services

import org.junit.Assert.assertEquals
import org.junit.Test

class RecipientParsingTest {

    @Test fun singleNumber() = assertEquals(listOf("+15551234567"), parseRecipients("+15551234567"))

    // mmsto:123 used to become "to:123" because "mms" was stripped before "mmsto:".
    @Test fun schemeIsNotPartOfInput() = assertEquals(listOf("123"), parseRecipients("123"))

    @Test fun dropsQuery() = assertEquals(listOf("5551234"), parseRecipients("5551234?body=hello"))

    @Test fun dropsHierarchicalSlashes() = assertEquals(listOf("5551234"), parseRecipients("//5551234"))

    @Test fun splitsMultipleRecipients() =
        assertEquals(listOf("111", "222", "333"), parseRecipients("111, 222;333"))

    @Test fun emptyAndNull() {
        assertEquals(emptyList<String>(), parseRecipients(null))
        assertEquals(emptyList<String>(), parseRecipients(""))
        assertEquals(emptyList<String>(), parseRecipients("?body=hi"))
    }
}
