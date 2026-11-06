package com.glooboo.umessage.ui.thread

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDirectionsTest {
    @Test fun persianIsRtl() = assertTrue(startsRightToLeft("سلام"))
    @Test fun arabicIsRtl() = assertTrue(startsRightToLeft("مرحبا"))
    @Test fun hebrewIsRtl() = assertTrue(startsRightToLeft("שלום"))
    @Test fun englishIsLtr() = assertFalse(startsRightToLeft("hello"))
    @Test fun emptyIsLtr() = assertFalse(startsRightToLeft(""))
    @Test fun digitsFirstThenPersianIsRtl() = assertTrue(startsRightToLeft("123 سلام"))
    @Test fun digitsFirstThenEnglishIsLtr() = assertFalse(startsRightToLeft("123 hello"))
    @Test fun emojiFirstThenPersianIsRtl() = assertTrue(startsRightToLeft("😀 سلام"))
}
