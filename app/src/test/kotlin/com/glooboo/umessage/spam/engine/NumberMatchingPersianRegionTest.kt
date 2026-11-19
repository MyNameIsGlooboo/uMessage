package com.glooboo.umessage.spam.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Number matching with region IR: 09…, +98, 0098 and Persian digits, landlines, service lines, prefixes. */
class NumberMatchingPersianRegionTest {

    private val home = NumberNormalizer { "IR" }

    private val mobileForms = listOf(
        "09000001111",
        "0900 000 1111",
        "0900-000-1111",
        "+989000001111",
        "+98 900 000 1111",
        "00989000001111",
        "0098 900 000 1111",
        "989000001111",
        "9000001111",
        "۰۹۰۰۰۰۰۱۱۱۱",
        "+۹۸ ۹۰۰ ۰۰۰ ۱۱۱۱",
        "۰۰۹۸۹۰۰۰۰۰۱۱۱۱",
    )

    private fun prefixMatches(prefix: String, address: String): Boolean {
        val clean = home.cleanPrefix(prefix)
        return clean.isNotEmpty() && home.prefixCandidates(address).any { it.startsWith(clean) }
    }

    @Test fun everyFormOfAMobileIsOneNumber() {
        for (a in mobileForms) for (b in mobileForms) assertTrue("$a vs $b", home.sameAddress(a, b))
        for (form in mobileForms) assertEquals(form, "+989000001111", home.normalize(form))
    }

    @Test fun differentMobilesNeverMatch() {
        assertFalse(home.sameAddress("09000001111", "09000001112"))
        assertFalse(home.sameAddress("+989000001111", "+919000001111"))
        assertFalse(home.sameAddress("09000001111", "+449000001111"))
    }

    @Test fun landlineKeepsItsAreaCode() {
        assertEquals("+982100001111", home.comparableKey("02100001111"))
        assertTrue(home.sameAddress("02100001111", "+98 21 0000 1111"))
        assertTrue(home.sameAddress("0098 21 0000 1111", "02100001111"))
        assertFalse(home.sameAddress("02100001111", "02500001111"))
    }

    @Test fun serviceLinesAndSenderIdsStayApart() {
        assertEquals("3000", home.normalize("3000"))
        assertFalse(home.sameAddress("3000", "09000001111"))
        assertEquals("MYBANK", home.comparableKey("MyBank"))
        assertFalse(home.sameAddress("MyBank", "09000001111"))
        assertTrue(home.isSenderId("MyBank"))
    }

    @Test fun prefixRulesMatchEveryFormOfTheSender() {
        val prefixes = listOf("0900", "0 900", "۰۹۰۰", "+98900", "+98 900", "0098900", "98900", "900")
        for (prefix in prefixes) for (address in mobileForms) {
            assertTrue("prefix $prefix vs $address", prefixMatches(prefix, address))
        }
    }

    @Test fun prefixRulesDoNotMatchOtherNumbers() {
        assertFalse(prefixMatches("0901", "+989000001111"))
        assertFalse(prefixMatches("+98901", "09000001111"))
        assertFalse(prefixMatches("0900", "+919000001111"))
        assertFalse(prefixMatches("0900", "MyBank"))
    }
}
