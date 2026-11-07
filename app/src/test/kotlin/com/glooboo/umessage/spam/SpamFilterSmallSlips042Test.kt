package com.glooboo.umessage.spam

import com.glooboo.umessage.spam.engine.ContactNumberIndex
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.RegionBlock
import com.glooboo.umessage.spam.engine.RuleMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * I6 (Arabic letter forms), SP-lowA (B10 plus sign), SP-lowB (B12 timeouts) and F12 (country code after a region
 * change).
 */
class SpamFilterSmallSlips042Test {

    // ---- I6: hamza and teh marbuta folds ----

    @Test fun hamzaFormsOfAlefFoldToPlainAlef() {
        assertEquals("اعلان", TextNormalizer.fold("إعلان"))
        assertEquals("اعلان", TextNormalizer.fold("أعلان"))
        assertEquals("اب", TextNormalizer.fold("آب"))
        assertEquals("اعلان", TextNormalizer.fold("ٱعلان"))
    }

    @Test fun wawWithHamzaAndTehMarbutaFold() {
        assertEquals("مسوول", TextNormalizer.fold("مسؤول"))
        assertEquals("مدرسه", TextNormalizer.fold("مدرسة"))
    }

    @Test fun keywordRuleTypedOneWayMatchesTheOtherSpelling() {
        assertTrue(TextNormalizer.containsFolded("إعلان ویژه", "اعلان"))
        assertTrue(TextNormalizer.containsFolded("اعلان ویژه", "إعلان"))
        assertTrue(TextNormalizer.containsFolded("مدرسه شما", "مدرسة"))
    }

    // ---- SP-lowA (B10): direction marks, brackets and a full-width plus ----

    private val us = NumberNormalizer { "US" }

    @Test fun directionMarkBracketAndFullWidthPlusKeepTheirPlus() {
        val plain = us.normalize("+447700900123")
        assertEquals("+447700900123", plain)
        assertEquals(plain, us.normalize("\u200E+44 7700 900123"))
        assertEquals(plain, us.normalize("\u200F\u202A+44 7700 900123\u202C"))
        assertEquals(plain, us.normalize("(+44) 7700 900123"))
        assertEquals(plain, us.normalize("\uFF0B44 7700 900123"))
    }

    @Test fun regionBlockSeesForeignNumbersWrittenWithMarks() {
        assertTrue(RegionBlock.isForeign("\u200E+44 7700 900123", "US"))
        assertTrue(RegionBlock.isForeign("(+44) 7700 900123", "US"))
        assertTrue(RegionBlock.isForeign("\uFF0B44 7700 900123", "US"))
        assertFalse(RegionBlock.isForeign("\u200E+44 7700 900123", "GB"))
    }

    // ---- SP-lowB (B12): one slow search must not kill a rule ----

    private var clock = 0L

    private fun regexRule() = SpamRule(kind = ListKind.BLOCK, type = RuleType.REGEX, pattern = "abc\\d+")
    private val message = IncomingMessage(address = "+15550100", body = "abc12", subscriptionId = 1)

    private fun matcher(outcomes: ArrayDeque<RegexGuard.Outcome>) = RuleMatcher(
        normalizer = us,
        now = { clock },
        search = { _: Pattern, _: String -> outcomes.removeFirst() },
    )

    @Test fun twoTimeoutsInARowDoNotTurnTheRuleOff() {
        val outcomes = ArrayDeque(
            listOf(
                RegexGuard.Outcome.TIMED_OUT, RegexGuard.Outcome.TIMED_OUT,
                RegexGuard.Outcome.MATCH,
            ),
        )
        val m = matcher(outcomes)
        assertFalse(m.matches(regexRule(), message))
        assertFalse(m.matches(regexRule(), message))
        assertTrue(m.matches(regexRule(), message))
    }

    @Test fun aFinishedSearchStartsTheCountAgain() {
        val outcomes = ArrayDeque(
            listOf(
                RegexGuard.Outcome.TIMED_OUT, RegexGuard.Outcome.TIMED_OUT, RegexGuard.Outcome.NO_MATCH,
                RegexGuard.Outcome.TIMED_OUT, RegexGuard.Outcome.TIMED_OUT, RegexGuard.Outcome.MATCH,
            ),
        )
        val m = matcher(outcomes)
        repeat(5) { m.matches(regexRule(), message) }
        assertTrue(m.matches(regexRule(), message))
    }

    @Test fun threeTimeoutsInARowTurnTheRuleOffForTenMinutesOnly() {
        val outcomes = ArrayDeque(
            listOf(
                RegexGuard.Outcome.TIMED_OUT, RegexGuard.Outcome.TIMED_OUT, RegexGuard.Outcome.TIMED_OUT,
                RegexGuard.Outcome.MATCH, RegexGuard.Outcome.MATCH,
            ),
        )
        val m = matcher(outcomes)
        repeat(3) { assertFalse(m.matches(regexRule(), message)) }
        clock += 9 * 60_000L
        assertFalse("still off: the search is not even run", m.matches(regexRule(), message))
        assertEquals(2, outcomes.size)
        clock += 2 * 60_000L
        assertTrue(m.matches(regexRule(), message))
    }

    // ---- F12: a contact saved without a country code, after the default region changed ----

    private val germany = NumberNormalizer { "DE" }

    @Test fun contactSavedNationallyStillMatchesAfterTheRegionChanged() {
        // Saved while the phone was in the UK; the default region is Germany now, the SIM is still British.
        val book = ContactNumberIndex<String>(germany, listOf("GB")).apply { add("07712 345678", "Friend") }
        assertEquals("Friend", book.find("+447712345678"))
        assertEquals("Friend", book.find("07712 345678"))
    }

    @Test fun withoutOtherRegionsTheReadingStaysStrict() {
        val book = ContactNumberIndex<String>(germany).apply { add("07712 345678", "Friend") }
        assertNull(book.find("+447712345678"))
    }

    @Test fun strangerFromAnotherCountryIsStillNotTheContact() {
        val book = ContactNumberIndex<String>(germany, listOf("GB")).apply { add("07712 345678", "Friend") }
        assertNull(book.find("+917712345678"))
        assertNull(book.find("+1 771 234 5678"))
    }

    @Test fun contactWithAnExplicitCountryCodeIsNotReadInOtherRegions() {
        val book = ContactNumberIndex<String>(germany, listOf("GB", "FR")).apply { add("+44 7712 345678", "Friend") }
        assertEquals("Friend", book.find("+447712345678"))
        assertNull(book.find("+497712345678"))
        assertNull(book.find("+337712345678"))
    }
}
