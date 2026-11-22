package com.glooboo.umessage.ui.components

import com.glooboo.umessage.spam.RuleType
import org.junit.Assert.assertEquals
import org.junit.Test

class IsolateLtrTest {
    @Test
    fun aShortCountryPrefixIsIsolated() {
        // N-1 (step I Fable review 2): isolateNumbers leaves runs under three digits alone, so "+98" showed as "98+".
        assertEquals("⁦+98⁩", isolateLtr("+98"))
        assertEquals("⁦+1⁩", isolateLtr("+1"))
    }

    @Test
    fun emptyAndIsolatedTextIsLeftAlone() {
        assertEquals("", isolateLtr(""))
        assertEquals("⁦+98⁩", isolateLtr("⁦+98⁩"))
    }

    @Test
    fun aRuleValueIsIsolatedByItsType() {
        // P-1 to P-5: a prefix whole however short, a number by its digit runs, a word as it is.
        assertEquals("⁦+1⁩", ruleValueText(RuleType.PREFIX, "+1"))
        assertEquals("⁦+1555⁩", ruleValueText(RuleType.PREFIX, "+1555"))
        assertEquals(
            "⁦+1 555-000-1234⁩",
            ruleValueText(RuleType.NUMBER, "+15550001234", number = "+1 555-000-1234"),
        )
        assertEquals(isolateNumbers("+15550001234"), ruleValueText(RuleType.NUMBER, "+15550001234"))
        assertEquals("free 100", ruleValueText(RuleType.KEYWORD, "free 100"))
        assertEquals("BANK", ruleValueText(RuleType.SENDER_ID, "BANK"))
    }
}
