package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownSenderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3 / SE-10 / DL-24: how NUMBER and PREFIX rules and contact numbers compare, for the ways national (and other)
 * numbers are written. Each row is checked through NumberNormalizer and, for rules, through DefaultSpamFilter.
 */
class NumberMatchingTest {

    private val home = NumberNormalizer { "GB" }
    private val india = NumberNormalizer { "IN" }
    private val noRegion = NumberNormalizer { "" }

    /** The same home-country mobile, every way a phone, an operator or a person writes it. */
    private val mobileForms = listOf(
        "+447123456789",
        "+44 712 345 6789",
        "+44-712-345-6789",
        "00447123456789",
        "0044 712 345 6789",
        "447123456789",
        "07123456789",
        "0712 345 6789",
        "0712-345-6789",
        "(0712) 345 6789",
        "7123456789",
        "۰۷۱۲۳۴۵۶۷۸۹",
        "+۴۴ ۷۱۲۳۴۵۶۷۸۹",
        "٠٧١٢٣٤٥٦٧٨٩",
        "۰۰۴۴ ۷۱۲ ۳۴۵ ۶۷۸۹",
    )

    // --- sameAddress / NUMBER rules ---------------------------------------------------------------

    @Test fun everyFormOfAHomeMobileIsTheSameNumber() {
        for (a in mobileForms) for (b in mobileForms) {
            assertTrue("$a vs $b", home.sameAddress(a, b))
        }
    }

    @Test fun normalizeGivesE164ForEveryForm() {
        for (form in mobileForms) assertEquals(form, "+447123456789", home.normalize(form))
    }

    @Test fun differentNumbersNeverMatch() {
        val pairs = listOf(
            "+447123456789" to "+447123456788",
            "07123456789" to "07123456788",
            "+447123456789" to "+917123456789", // same last 10 digits, other country
            "+447123456789" to "+17123456789",
            "00447123456789" to "00917123456789",
            "+447700900123" to "+917700900123",
            "07123456789" to "+917123456789", // national home number vs Indian
            "30001234" to "30001235",
            "30001234" to "+9130001234",
            "VM-HDFCBK" to "+447123456789",
            "MCI" to "MTN",
            "" to "",
            "  " to "+447123456789",
        )
        for ((a, b) in pairs) {
            assertTrue("$a vs $b should differ", !home.sameAddress(a, b))
            assertTrue("$b vs $a should differ", !home.sameAddress(b, a))
        }
    }

    @Test fun foreignNumbersMatchTheirOwnForms() {
        assertTrue(home.sameAddress("+91 98765 43210", "0091-98765-43210"))
        assertTrue(india.sameAddress("+91 98765 43210", "098765 43210"))
        assertTrue(home.sameAddress("+1 (415) 555-2671", "+14155552671"))
        assertTrue(!home.sameAddress("+91 98765 43210", "098765 43210")) // national read as a home number
    }

    @Test fun shortCodesMatchOnlyTheirOwnDigits() {
        val codes = listOf("3000", "30001234", "10001234", "2000123", "50001234567", "1000", "5830001234", "121")
        for (code in codes) {
            assertTrue(code, home.sameAddress(code, code))
            assertTrue(code, home.sameAddress(code, " $code "))
            assertEquals("normalize keeps short code $code", code, home.normalize(code))
        }
        assertTrue(!home.sameAddress("3000", "30001234"))
        assertTrue(!home.sameAddress("121", "+44121"))
    }

    @Test fun shortCodeWrittenWithCountryCodeMatchesTheBareCode() {
        assertTrue(home.sameAddress("30001234", "+4430001234"))
        assertTrue(home.sameAddress("+4430001234", "30001234"))
        assertTrue(home.sameAddress("۳۰۰۰۱۲۳۴", "+4430001234"))
    }

    @Test fun senderIdsCompareCaseInsensitively() {
        assertTrue(home.sameAddress("Local-Net One", "LOCAL-NET ONE"))
        assertTrue(home.sameAddress(" hometel ", "HOMETEL"))
        assertTrue(!home.sameAddress("HOMETEL", "HOMETE"))
    }

    @Test fun withoutARegionNationalDigitsStillMatchTheInternationalForm() {
        assertTrue(noRegion.sameAddress("07123456789", "+447123456789"))
        assertTrue(noRegion.sameAddress("+447123456789", "۰۷۱۲۳۴۵۶۷۸۹"))
        assertTrue(!noRegion.sameAddress("07123456789", "+447123456788"))
        assertTrue(!noRegion.sameAddress("+917123456789", "+447123456789"))
    }

    // --- comparableKey ----------------------------------------------------------------------------

    @Test fun comparableKeyIsOneValuePerNumber() {
        val keys = mobileForms.map(home::comparableKey).toSet()
        assertEquals(setOf("+447123456789"), keys)
        assertEquals("+917123456789", home.comparableKey("+91 712 345 6789"))
        assertEquals("+4430001234", home.comparableKey("30001234"))
        assertEquals("+4430001234", home.comparableKey("+44 3000 1234"))
        assertEquals("HOMETEL", home.comparableKey("HomeTel"))
        assertEquals("", home.comparableKey(" "))
        assertTrue(home.hasCountryCode("07123456789"))
        assertTrue(!home.hasCountryCode("MCI"))
        assertTrue(!noRegion.hasCountryCode("07123456789"))
    }

    // --- PREFIX rules -----------------------------------------------------------------------------

    private fun prefixMatches(reader: NumberNormalizer, prefix: String, address: String): Boolean {
        val clean = reader.cleanPrefix(prefix)
        return clean.isNotEmpty() && reader.prefixCandidates(address).any { it.startsWith(clean) }
    }

    @Test fun nationalPrefixMatchesEveryFormOfTheSender() {
        val prefixes = listOf("0712", "0712 ", "0 712", "۰۷۱۲", "+44712", "+44 712", "0044712", "44712", "712", "+۴۴ ۷۱۲")
        for (prefix in prefixes) for (address in mobileForms) {
            assertTrue("prefix $prefix vs $address", prefixMatches(home, prefix, address))
        }
    }

    @Test fun prefixesThatDenoteAnotherNumberDoNotMatch() {
        val misses = listOf(
            "0713" to "+447123456789",
            "+44713" to "07123456789",
            "0712" to "+917123456789", // home 0712 is not an Indian number
            "+44912" to "+447123456789",
            "0091" to "+447123456789",
            "0712" to "MCI",
            "3000" to "+9130001234",
        )
        for ((prefix, address) in misses) assertTrue("$prefix vs $address", !prefixMatches(home, prefix, address))
    }

    @Test fun foreignReaderDoesNotTreatHomePrefixAsLocal() {
        assertTrue(!prefixMatches(india, "0712", "+447123456789"))
        assertTrue(prefixMatches(india, "09876", "+919876543210"))
        assertTrue(prefixMatches(india, "+919876", "098765 43210"))
    }

    @Test fun shortCodePrefixes() {
        assertTrue(prefixMatches(home, "3000", "30001234"))
        assertTrue(prefixMatches(home, "3000", "+4430001234"))
        assertTrue(prefixMatches(home, "۳۰۰۰", "30001234"))
        assertTrue(prefixMatches(home, "1000", "10001234"))
        assertTrue(prefixMatches(home, "5830", "5830001234"))
        assertTrue(!prefixMatches(home, "3000", "10001234"))
    }

    // --- through the filter -----------------------------------------------------------------------

    private fun filter(rule: SpamRule, default: String = "US", sim: String? = "GB") = DefaultSpamFilter(
        { listOf(rule) },
        { SpamSettings(mode = UnknownSenderMode.OFF) },
        NoContacts,
        NumberNormalizer { default },
        regionForSubscription = { sim },
    )

    private object NoContacts : ContactChecker {
        override fun isContact(address: String) = false
        override fun canReadContacts() = true
    }

    private fun blocked(rule: SpamRule, address: String, default: String = "US", sim: String? = "GB") =
        filter(rule, default, sim).evaluate(IncomingMessage(address, "Cheap loans!", 1)) is FilterDecision.Block

    private fun block(type: RuleType, pattern: String) =
        SpamRule(id = 1, kind = ListKind.BLOCK, type = type, pattern = pattern)

    @Test fun numberRuleTypedNationallyBlocksEveryForm() {
        for (pattern in listOf("07123456789", "0712 345 6789", "۰۷۱۲۳۴۵۶۷۸۹", "+44 712 345 6789", "00447123456789")) {
            for (address in mobileForms) {
                assertTrue("$pattern vs $address", blocked(block(RuleType.NUMBER, pattern), address))
            }
        }
    }

    @Test fun numberRuleDoesNotBlockAnotherCountry() {
        assertTrue(!blocked(block(RuleType.NUMBER, "07123456789"), "+917123456789"))
        assertTrue(!blocked(block(RuleType.NUMBER, "+447123456789"), "+917123456789"))
        assertTrue(!blocked(block(RuleType.NUMBER, "07123456789"), "+917123456789", default = "GB", sim = null))
    }

    @Test fun prefixRuleTypedNationallyBlocksInternationalSenders() {
        for (pattern in listOf("0712", "+44712", "0044712", "۰۷۱۲", "0712-")) {
            for (address in mobileForms) {
                assertTrue("$pattern vs $address", blocked(block(RuleType.PREFIX, pattern), address))
            }
        }
        assertTrue(blocked(block(RuleType.PREFIX, "0712"), "+447123456789", default = "GB", sim = null))
    }

    @Test fun prefixRuleDoesNotBlockAnotherCountry() {
        assertTrue(!blocked(block(RuleType.PREFIX, "0712"), "+917123456789"))
        assertTrue(!blocked(block(RuleType.PREFIX, "0712"), "+17123456789"))
    }
}
