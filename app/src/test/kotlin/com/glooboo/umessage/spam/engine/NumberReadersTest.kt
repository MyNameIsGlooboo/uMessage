package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * R6, S8, M-T3: one fixed region list (every SIM's country, then the default region) reads a sender the same way in
 * every path and whatever SIM a text arrived on. Includes the block for region IR: a mobile number written with 0,
 * with +98 and with 0098, and a service line. Every number is made up.
 */
class NumberReadersTest {

    @get:Rule val flag = ProviderTruthFlagRule(enabled = false)

    private fun readers(default: String, vararg sims: String) =
        NumberReaders(NumberNormalizer { default }) { sims.toList() }

    @Test fun theListIsEverySimThenTheDefault() {
        val list = readers("US", "ir", "GB", "IR").readers()
        assertEquals(3, list.size)
    }

    @Test fun oneMobileNumberInThreeShapesIsOneSender() {
        listOf(readers("US", "IR"), readers("IR"), readers("US", "IR", "GB")).forEach { r ->
            val keys = listOf(IR_NATIONAL, IR_PLUS, IR_00).map(r::senderKey)
            assertEquals(listOf(IR_PLUS, IR_PLUS, IR_PLUS), keys)
            assertTrue(r.same(IR_NATIONAL, IR_PLUS))
            assertTrue(r.same(IR_00, IR_PLUS))
        }
    }

    /** The receiving SIM is not part of the reading: a SIM order in which IR is second still finds the member. */
    @Test fun aMemberStoredUnderOneShapeIsFoundUnderEveryShapeOnTwoSimRegions() {
        listOf(readers("US", "IR", "GB"), readers("US", "GB", "IR")).forEach { r ->
            val stored = r.senderKey(IR_PLUS)
            listOf(IR_NATIONAL, IR_PLUS, IR_00).forEach { shape ->
                assertTrue("$shape in ${r.senderKeys(shape)}", stored in r.senderKeys(shape))
            }
        }
    }

    @Test fun aServiceLineIsStableAndNotAMobile() {
        val r = readers("US", "IR")
        val key = r.senderKey(IR_SERVICE)
        assertTrue(key.isNotEmpty())
        assertEquals(key, r.senderKey(IR_SERVICE))
        assertTrue(r.same(IR_SERVICE, IR_SERVICE))
        assertFalse(r.same(IR_SERVICE, IR_PLUS))
        assertNotEquals(key, r.senderKey(IR_PLUS))
    }

    @Test fun aSenderNameIsItsLowerCaseName() {
        val r = readers("US", "IR")
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

    /** With the flag on, RuleMatcher reads numbers with the one list, so a rule matches on both SIMs alike. */
    @Test fun ruleMatcherUsesTheOneListOnlyWithTheFlagOn() {
        val matcher = RuleMatcher(
            NumberNormalizer { "US" },
            regionForSubscription = { sub -> if (sub == 2) "IR" else null },
            simRegions = { listOf("IR") },
        )
        val rule = SpamRule(id = 1, kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = IR_PLUS)
        val onSim1 = IncomingMessage(IR_NATIONAL, "hi", 1)
        val onSim2 = IncomingMessage(IR_NATIONAL, "hi", 2)

        ProviderTruthFlag.enabled = false
        assertFalse("0.4.14: SIM 1 reads 0906… in the default region", matcher.matches(rule, onSim1))
        assertTrue(matcher.matches(rule, onSim2))

        ProviderTruthFlag.enabled = true
        assertTrue(matcher.matches(rule, onSim1))
        assertTrue(matcher.matches(rule, onSim2))
    }

    private companion object {
        const val IR_NATIONAL = "09061234567"
        const val IR_PLUS = "+989061234567"
        const val IR_00 = "00989061234567"
        const val IR_SERVICE = "30001234"
    }
}
