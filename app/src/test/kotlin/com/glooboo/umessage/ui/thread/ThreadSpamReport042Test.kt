package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.spam.RuleType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SP-lowA (B11): "already blocked" must not rest on a match of the number's tail alone. */
class ThreadSpamReport042Test {

    @Test fun aShortNumberDoesNotCoverALongNumberWithTheSameTail() {
        assertFalse(isSameSender("5551234", "+1 415 555 1234", RuleType.NUMBER))
    }

    @Test fun sameNumberInDifferentFormsIsTheSameSender() {
        assertTrue(isSameSender("+14155551234", "+1 415 555 1234", RuleType.NUMBER))
        assertTrue(isSameSender("+14155551234", "4155551234", RuleType.NUMBER))
    }

    @Test fun differentCountriesAreDifferentSenders() {
        assertFalse(isSameSender("+14155551234", "+44 4155551234", RuleType.NUMBER))
    }
}
