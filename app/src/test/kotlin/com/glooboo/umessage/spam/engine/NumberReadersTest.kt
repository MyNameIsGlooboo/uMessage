package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.google.i18n.phonenumbers.PhoneNumberUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R6, S8, M-T3: one fixed region list (every SIM's country, then the default region) reads a sender the same way in
 * every path and whatever SIM a text arrived on. Includes the block for a second region (GB): a mobile number written
 * with 0, with +44 and with 0044, and a service line. Every number is made up (the UK drama range 07700 900xxx).
 */
class NumberReadersTest {

    private fun readers(default: String, vararg sims: String) =
        NumberReaders(NumberNormalizer { default }) { sims.toList() }

    @Test fun theListIsEverySimThenTheDefault() {
        val list = readers("US", "gb", "DE", "GB").readers()
        assertEquals(3, list.size)
    }

    @Test fun oneMobileNumberInThreeShapesIsOneSender() {
        listOf(readers("US", "GB"), readers("GB"), readers("US", "GB", "DE")).forEach { r ->
            val keys = listOf(REGION2_NATIONAL, REGION2_PLUS, REGION2_00).map(r::senderKey)
            assertEquals(listOf(REGION2_PLUS, REGION2_PLUS, REGION2_PLUS), keys)
            assertTrue(r.same(REGION2_NATIONAL, REGION2_PLUS))
            assertTrue(r.same(REGION2_00, REGION2_PLUS))
        }
    }

    /** The receiving SIM is not part of the reading: a SIM order in which GB is second still finds the member. */
    @Test fun aMemberStoredUnderOneShapeIsFoundUnderEveryShapeOnTwoSimRegions() {
        listOf(readers("US", "GB", "DE"), readers("US", "DE", "GB")).forEach { r ->
            val stored = r.senderKey(REGION2_PLUS)
            listOf(REGION2_NATIONAL, REGION2_PLUS, REGION2_00).forEach { shape ->
                assertTrue("$shape in ${r.senderKeys(shape)}", stored in r.senderKeys(shape))
            }
        }
    }

    @Test fun aServiceLineIsStableAndNotAMobile() {
        val r = readers("US", "GB")
        val key = r.senderKey(SERVICE)
        assertTrue(key.isNotEmpty())
        assertEquals(key, r.senderKey(SERVICE))
        assertTrue(r.same(SERVICE, SERVICE))
        assertFalse(r.same(SERVICE, REGION2_PLUS))
        assertNotEquals(key, r.senderKey(REGION2_PLUS))
    }

    @Test fun aSenderNameIsItsLowerCaseName() {
        val r = readers("US", "GB")
        assertEquals("mybank", r.senderKey(" MyBank "))
        assertEquals(setOf("mybank"), r.senderKeys("MYBANK"))
        assertEquals("", r.senderKey("  "))
        assertTrue(r.senderKeys("").isEmpty())
    }

    @Test fun usNumbersInTheirShapes() {
        val r = readers("US")
        assertEquals("+15550001234", r.senderKey("(555) 000-1234"))
        assertEquals("+15550001234", r.senderKey("+1 555 000 1234"))
        // A rule's pattern is the number as the filter has always stored it (normalize needs a valid national number).
        assertEquals("+15550001234", r.rulePattern("+1 555-000-1234"))
        assertEquals("MYBANK", r.rulePattern("MyBank"))
    }

    /** RuleMatcher reads numbers with the one list, so a rule matches on both SIMs alike. */
    @Test fun ruleMatcherUsesTheOneList() {
        val matcher = RuleMatcher(
            NumberNormalizer { "US" },
            regionForSubscription = { sub -> if (sub == 2) "GB" else null },
            simRegions = { listOf("GB") },
        )
        val rule = SpamRule(id = 1, kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = REGION2_PLUS)
        val onSim1 = IncomingMessage(REGION2_NATIONAL, "hi", 1)
        val onSim2 = IncomingMessage(REGION2_NATIONAL, "hi", 2)

        assertTrue(matcher.matches(rule, onSim1))
        assertTrue(matcher.matches(rule, onSim2))
    }

    /** Every made-up second-region number of the provider-truth tests is unassigned (invalid) for libphonenumber. */
    @Test fun theDummySecondRegionNumbersAreInvalid() {
        val util = PhoneNumberUtil.getInstance()
        listOf(REGION2_NATIONAL, "07700900000", REGION2_PLUS, REGION2_00).forEach { raw ->
            val number = util.parse(if (raw.startsWith("00")) "+" + raw.drop(2) else raw, "GB")
            assertFalse("$raw must stay unassigned", util.isValidNumber(number))
        }
    }

    private companion object {
        const val REGION2_NATIONAL = "07700900123"
        const val REGION2_PLUS = "+447700900123"
        const val REGION2_00 = "00447700900123"
        const val SERVICE = "60123"
    }
}
