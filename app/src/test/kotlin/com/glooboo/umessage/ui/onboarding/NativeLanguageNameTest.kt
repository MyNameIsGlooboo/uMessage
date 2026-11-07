package com.glooboo.umessage.ui.onboarding

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeLanguageNameTest {
    @Test
    fun `a language is named in itself`() {
        assertEquals("English", nativeLanguageName(Locale.forLanguageTag("en")))
        assertEquals("Deutsch", nativeLanguageName(Locale.forLanguageTag("de")))
        assertEquals("فارسی", nativeLanguageName(Locale.forLanguageTag("fa")))
    }

    @Test
    fun `the first letter is capitalised where the language writes lower case`() {
        assertEquals("Français", nativeLanguageName(Locale.forLanguageTag("fr-FR")))
        assertEquals("Español", nativeLanguageName(Locale.forLanguageTag("es")))
    }

    @Test
    fun `a region does not change the name and a locale without a language is empty`() {
        assertEquals("English", nativeLanguageName(Locale.forLanguageTag("en-GB")))
        assertEquals("", nativeLanguageName(Locale.ROOT))
    }
}
