package com.glooboo.umessage.ui.components

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
}
