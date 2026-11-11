package com.glooboo.umessage.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerObfuscationTest {

    @Test fun invisibleMarksAreDropped() {
        assertEquals("free", TextNormalizer.deobfuscate("f‎r‪e⁢e͏️"))
    }

    @Test fun fancyLettersMatchKeyword() {
        assertTrue(TextNormalizer.containsObfuscated("ＦＲＥＥ gift", "free")) // fullwidth
        assertTrue(TextNormalizer.containsObfuscated("𝐟𝐫𝐞𝐞 prize", "free")) // math bold
        assertTrue(TextNormalizer.containsObfuscated("f̶r̶e̶e̶ prize", "free")) // strike-through
        assertTrue(TextNormalizer.containsObfuscated("f́ree", "free")) // accent
    }

    @Test fun mathBoldDigitsAreAscii() {
        assertEquals("1234", TextNormalizer.deobfuscate("𝟏𝟐𝟑𝟒"))
    }

    @Test fun plainTextIsUnchanged() {
        assertFalse(TextNormalizer.containsObfuscated("Your parcel is on the way", "free"))
        assertEquals("hello world 2025", TextNormalizer.deobfuscate("Hello World 2025"))
    }
}
