package com.glooboo.umessage.spam.engine

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.extensions.isNumberBlockedByPattern
import org.fossify.commons.extensions.trimToComparableNumber
import org.fossify.commons.models.BlockedNumber
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** CM-01 / T13: the vendored Fossify blocked-number matcher, which the filter's legacy stage calls. */
@RunWith(AndroidJUnit4::class)
class LegacyBlockedNumbersTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun entry(number: String, e164: String = number) =
        BlockedNumber(1, number, e164, e164.trimToComparableNumber())

    private fun list(vararg entries: BlockedNumber) = arrayListOf(*entries)

    @Test fun malformedPatternEntryDoesNotThrow() {
        val blocked = list(entry("*("), entry("+44*(71"), entry("[0-9*"))
        assertFalse(app.isNumberBlockedByPattern("+447121234567", blocked))
        assertFalse(app.isNumberBlocked("+447121234567", blocked))
    }

    @Test fun malformedEntryDoesNotHideAValidOne() {
        val blocked = list(entry("*("), entry("+44712*"))
        assertTrue(app.isNumberBlocked("+447121234567", blocked))
    }

    @Test fun patternCharactersOtherThanStarAreLiteral() {
        assertTrue(app.isNumberBlockedByPattern("(555) 123", list(entry("(555)*"))))
        assertFalse(app.isNumberBlockedByPattern("+15551234567", list(entry("+1.55*"))))
        assertTrue(app.isNumberBlockedByPattern("+1.55123", list(entry("+1.55*"))))
    }

    @Test fun internationalNumbersMustMatchInFull() {
        val blocked = list(entry("+447123456789"))
        assertFalse(app.isNumberBlocked("+917123456789", blocked))
        assertFalse(app.isNumberBlocked("00917123456789", blocked))
        assertTrue(app.isNumberBlocked("+447123456789", blocked))
        assertTrue(app.isNumberBlocked("+44 7123 456789", blocked))
        assertTrue(app.isNumberBlocked("00447123456789", blocked))
    }

    @Test fun nationalFormStillMatchesByLastDigits() {
        assertTrue(app.isNumberBlocked("07121234567", list(entry("+447121234567"))))
        assertTrue(app.isNumberBlocked("+447121234567", list(entry("07121234567"))))
    }
}
