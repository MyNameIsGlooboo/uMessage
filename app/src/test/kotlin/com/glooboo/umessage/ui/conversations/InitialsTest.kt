package com.glooboo.umessage.ui.conversations

import org.junit.Assert.assertEquals
import org.junit.Test

class InitialsTest {
    @Test fun twoWords() = assertEquals("MR", initialsOf("Maya Rivera"))
    @Test fun oneWord() = assertEquals("B", initialsOf("bank"))
    @Test fun numberOnlyGetsNoLetters() = assertEquals("", initialsOf("+1 555 123 0004"))
    @Test fun shortCodeGetsNoLetters() = assertEquals("", initialsOf("50505"))
    @Test fun skipsLeadingSymbols() = assertEquals("JA", initialsOf("(John) Appleseed"))
}
