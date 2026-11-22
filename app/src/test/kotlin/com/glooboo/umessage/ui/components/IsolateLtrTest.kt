package com.glooboo.umessage.ui.components

import com.glooboo.umessage.spam.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class IsolateLtrTest {
    private val english = Locale.ENGLISH
    private val persian = Locale.forLanguageTag("fa")

    @Test
    fun aShortCountryPrefixIsIsolated() {
        // N-1 (step I Fable review 2): isolateNumbers leaves runs under three digits alone, so "+98" showed as "98+".
        assertEquals("\u2066+98\u2069", isolateLtr("+98"))
        assertEquals("\u2066+1\u2069", isolateLtr("+1"))
    }

    @Test
    fun emptyAndIsolatedTextIsLeftAlone() {
        assertEquals("", isolateLtr(""))
        assertEquals("\u2066+98\u2069", isolateLtr("\u2066+98\u2069"))
    }

    @Test
    fun aRuleValueIsIsolatedByItsType() {
        // P-1 to P-5: a prefix whole however short, a number by its digit runs, a word as it is.
        assertEquals("\u2066+1\u2069", ruleValueText(RuleType.PREFIX, "+1", english))
        assertEquals("\u2066+1555\u2069", ruleValueText(RuleType.PREFIX, "+1555", english))
        assertEquals(
            "\u2066+1 555-000-1234\u2069",
            ruleValueText(RuleType.NUMBER, "+15550001234", english, number = "+1 555-000-1234"),
        )
        assertEquals(isolateNumbers("+15550001234"), ruleValueText(RuleType.NUMBER, "+15550001234", english))
        assertEquals("free 100", ruleValueText(RuleType.KEYWORD, "free 100", english))
        assertEquals("BANK", ruleValueText(RuleType.SENDER_ID, "BANK", english))
    }

    @Test
    fun aPrefixTakesTheLanguagesDigitsButIsNeverGrouped() {
        // J6 fix round: a number rule shows local digits, so a prefix does too; a long one stays one run.
        assertEquals("\u2066+\u06F1\u06F5\u06F5\u06F5\u2069", ruleValueText(RuleType.PREFIX, "+1555", persian))
        assertEquals("\u2066+15550001\u2069", ruleValueText(RuleType.PREFIX, "+15550001", english))
    }

    @Test
    fun aSenderNameWithDigitsIsNotIsolated() {
        // F-4: isolating the digits of "AX-650012" laid it out as "650012-AX" in a right-to-left line.
        assertEquals("AX-650012", ruleValueText(RuleType.SENDER_ID, "AX-650012", english))
        assertEquals("win 5000", ruleLabelText("win 5000", RuleType.KEYWORD, english))
    }

    @Test
    fun aLiveRuleLabelFollowsItsType() {
        assertEquals("\u2066+1\u2069", ruleLabelText("+1", RuleType.PREFIX, english))
        assertEquals(
            "\u2066+1 555-000-1234\u2069",
            ruleLabelText("+15550001234", RuleType.NUMBER, english, number = "+1 555-000-1234"),
        )
    }

    @Test
    fun aDeletedRulesBareNumberLabelLooksLikeTheLivePrefix() {
        // F-6: the same prefix reads the same before and after its rule is deleted.
        assertEquals(ruleLabelText("+1", RuleType.PREFIX, persian), ruleLabelText("+1", null, persian))
        assertEquals("\u2066+15550001234\u2069", ruleLabelText("+15550001234", null, english))
    }

    @Test
    fun aDeletedRulesOtherLabelIsShownAsItIs() {
        assertEquals("#12", ruleLabelText("#12", null, english))
        assertEquals("AX-650012", ruleLabelText("AX-650012", null, english))
    }

    @Test
    fun onlyNumberAndPrefixRulesReadLeftToRight() {
        assertTrue(RuleType.NUMBER.readsLeftToRight)
        assertTrue(RuleType.PREFIX.readsLeftToRight)
        assertFalse(RuleType.SENDER_ID.readsLeftToRight)
        assertFalse(RuleType.KEYWORD.readsLeftToRight)
    }
}
