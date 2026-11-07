package com.glooboo.umessage.spam

import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.RuleMatcher
import com.glooboo.umessage.ui.spam.RuleError
import com.glooboo.umessage.ui.spam.RuleTest
import com.glooboo.umessage.ui.spam.RuleTestResult
import com.glooboo.umessage.ui.spam.RuleValidation
import com.glooboo.umessage.ui.spam.RuleValidator
import com.glooboo.umessage.ui.spam.RuleWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** I7: a keyword in double quotes only matches as a whole word; every other keyword stays a plain substring. */
class KeywordWholeWord042Test {

    private val matcher = RuleMatcher(NumberNormalizer { "GB" })

    private fun hit(kind: ListKind, pattern: String, body: String) = matcher.matches(
        SpamRule(kind = kind, type = RuleType.KEYWORD, pattern = pattern),
        IncomingMessage(address = "+447700900123", body = body, subscriptionId = 1),
    )

    @Test fun plainKeywordStillMatchesInsideWords() {
        assertTrue(hit(ListKind.BLOCK, "win", "Open the window"))
        assertTrue(hit(ListKind.BLOCK, "sex", "Visit Essex"))
    }

    @Test fun wholeWordKeywordSkipsLongerWords() {
        assertFalse(hit(ListKind.BLOCK, "\"win\"", "Open the window"))
        assertFalse(hit(ListKind.BLOCK, "\"win\"", "Charles Darwin"))
        assertFalse(hit(ListKind.BLOCK, "\"sex\"", "Visit Essex"))
        assertTrue(hit(ListKind.BLOCK, "\"win\"", "You WIN a prize"))
        assertTrue(hit(ListKind.BLOCK, "\"win\"", "win, win."))
        assertTrue(hit(ListKind.ALLOW, "\"invoice\"", "Your invoice: 12"))
    }

    @Test fun wholeWordWorksInPersianAndWithArabicLetterForms() {
        assertTrue(hit(ListKind.BLOCK, "\"اعلان\"", "این یک إعلان است"))
        assertFalse(hit(ListKind.BLOCK, "\"کد\"", "کدام یک"))
        assertTrue(hit(ListKind.BLOCK, "\"کد\"", "کد شما ۱۲۳"))
    }

    @Test fun blockKeywordStillSeesThroughSpacedOutLettersAsAWholeWord() {
        assertTrue(hit(ListKind.BLOCK, "\"free\"", "F R E E gift"))
        assertTrue(hit(ListKind.BLOCK, "\"free\"", "fr33 money"))
        assertFalse(hit(ListKind.ALLOW, "\"free\"", "F R E E gift"))
        assertFalse(hit(ListKind.BLOCK, "\"free\"", "freedom"))
    }

    @Test fun quotesAreOnlyWholeWordWhenAPairWithAWordInside() {
        assertFalse(KeywordMatch.isWholeWord("\""))
        assertFalse(KeywordMatch.isWholeWord("\"\""))
        assertFalse(KeywordMatch.isWholeWord("\"win"))
        assertTrue(KeywordMatch.isWholeWord("\"win\""))
        assertEquals("win", KeywordMatch.bare("\"win\""))
        assertEquals("win", KeywordMatch.bare("win"))
    }

    @Test fun theSwitchTogglesTheQuotes() {
        assertEquals("\"win\"", KeywordMatch.toggled("win"))
        assertEquals("win", KeywordMatch.toggled("\"win\""))
        assertEquals("", KeywordMatch.toggled(""))
    }

    @Test fun validationCountsTheWordNotTheQuotes() {
        val validated = RuleValidator.validate(ListKind.BLOCK, RuleType.KEYWORD, "\"win\"")
        assertEquals(RuleValidation.Valid("\"win\""), validated)
        assertEquals(
            RuleValidation.Invalid(RuleError.KEYWORD_SHORT),
            RuleValidator.validate(ListKind.BLOCK, RuleType.KEYWORD, "\"a\""),
        )
        assertEquals(
            RuleValidation.Invalid(RuleError.ALLOW_KEYWORD_SHORT),
            RuleValidator.validate(ListKind.ALLOW, RuleType.KEYWORD, "\"ab\""),
        )
    }

    @Test fun aWholeWordShortKeywordIsNotBroad() {
        val broad = RuleValidator.warnings(ListKind.BLOCK, RuleType.KEYWORD, "win", emptyList(), null)
        val whole = RuleValidator.warnings(ListKind.BLOCK, RuleType.KEYWORD, "\"win\"", emptyList(), null)
        assertTrue(RuleWarning.BROAD in broad)
        assertFalse(RuleWarning.BROAD in whole)
    }

    @Test fun theTestBoxUsesTheSameMatching() {
        val inWindow = RuleTest.run(ListKind.BLOCK, RuleType.KEYWORD, "\"win\"", "Open the window")
        assertEquals(RuleTestResult.NO_MATCH, inWindow)
        assertEquals(RuleTestResult.MATCH, RuleTest.run(ListKind.BLOCK, RuleType.KEYWORD, "\"win\"", "You win"))
        assertEquals(RuleTestResult.MATCH, RuleTest.run(ListKind.BLOCK, RuleType.KEYWORD, "win", "Open the window"))
    }
}
