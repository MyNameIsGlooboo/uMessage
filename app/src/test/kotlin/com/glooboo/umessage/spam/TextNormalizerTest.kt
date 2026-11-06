package com.glooboo.umessage.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2 / SE-12 / SE-15: one way of reading Persian, Arabic and obfuscated text. */
class TextNormalizerTest {

    @Test fun arabicLetterFormsBecomePersian() {
        assertEquals("علی", TextNormalizer.fold("علي"))
        assertEquals("کیک", TextNormalizer.fold("كيك"))
        assertEquals("موسی", TextNormalizer.fold("موسى"))
    }

    @Test fun digitsBecomeAscii() {
        assertEquals("12345 and 6789", TextNormalizer.fold("۱۲۳۴۵ and ٦٧٨٩"))
    }

    @Test fun invisibleMarksAreDropped() {
        assertEquals("میخواهم", TextNormalizer.fold("می\u200Cخواهم"))
        assertEquals("رمز", TextNormalizer.fold("رمـــز"))
        assertEquals("رمز", TextNormalizer.fold("رَمْز"))
        assertEquals("free", TextNormalizer.fold("fr\u200Bee\u00AD"))
        assertEquals("abc", TextNormalizer.fold("a\u200Db\uFEFFc\u2060"))
    }

    @Test fun plainTextIsUnchanged() {
        val text = "Hello, world 42"
        assertTrue(text === TextNormalizer.fold(text))
    }

    @Test fun lowerIgnoresTheDeviceLocale() {
        assertEquals("бесплатно", TextNormalizer.lower("БЕСПЛАТНО"))
        assertEquals("title", TextNormalizer.lower("TITLE"))
    }

    @Test fun foldedKeywordMatchIgnoresCaseInEveryScript() {
        assertTrue(TextNormalizer.containsFolded("БЕСПЛАТНО приз!", "бесплатно"))
        assertTrue(TextNormalizer.containsFolded("ΔΩΡΟ για σας", "δωρο"))
        assertTrue(TextNormalizer.containsFolded("وام فوري بدون ضامن", "وام فوری"))
        assertTrue(TextNormalizer.containsFolded("خريد اقساطي", "اقساطی"))
        assertTrue(TextNormalizer.containsFolded("ثبت\u200Cنام رایگان", "ثبتنام"))
        assertTrue(TextNormalizer.containsFolded("کد ۱۲۳", "123"))
        assertFalse(TextNormalizer.containsFolded("anything", ""))
        assertFalse(TextNormalizer.containsFolded("anything", "\u200C"))
    }

    @Test fun spacedOutLettersAreJoined() {
        assertTrue(TextNormalizer.containsObfuscated("F R E E gift!", "free"))
        assertTrue(TextNormalizer.containsObfuscated("get it f.r.e.e now", "free"))
        assertTrue(TextNormalizer.containsObfuscated("W-I-N a prize", "win"))
        assertTrue(TextNormalizer.containsObfuscated("ر ا ی گ ا ن", "رایگان"))
    }

    @Test fun leetspeakIsReadAsLetters() {
        assertTrue(TextNormalizer.containsObfuscated("fr33 prize inside", "free"))
        assertTrue(TextNormalizer.containsObfuscated("CA\$H now", "cash"))
        assertTrue(TextNormalizer.containsObfuscated("w1nner!", "winner"))
        assertTrue(TextNormalizer.containsObfuscated("l0an approved", "loan"))
        // A keyword written in leetspeak still matches itself.
        assertTrue(TextNormalizer.containsObfuscated("fr33 prize", "fr33"))
    }

    @Test fun obfuscationDoesNotInventWords() {
        // Numbers alone are not leetspeak, so prices and codes stay as they are.
        assertFalse(TextNormalizer.containsObfuscated("Pay 500 by 2025", "soo"))
        assertFalse(TextNormalizer.containsObfuscated("Room 101 at 5", "ioi"))
        // Words with a space between them are not "spaced-out letters".
        assertFalse(TextNormalizer.containsObfuscated("of reeds", "free"))
        assertFalse(TextNormalizer.containsObfuscated("free!", "freei"))
        assertFalse(TextNormalizer.containsObfuscated("I am in", "iamin"))
        assertFalse(TextNormalizer.containsObfuscated("من و تو", "منوتو"))
    }

    @Test fun needsFoldOnlyForNonAsciiOrDigits() {
        assertFalse(TextNormalizer.needsFold("hello"))
        assertTrue(TextNormalizer.needsFold("علي"))
        assertTrue(TextNormalizer.needsFold("1234"))
    }
}
