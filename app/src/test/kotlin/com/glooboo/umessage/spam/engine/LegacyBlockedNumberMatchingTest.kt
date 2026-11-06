package com.glooboo.umessage.spam.engine

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.commons.extensions.blockedNumberE164
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.extensions.isNumberBlockedByPattern
import org.fossify.commons.extensions.trimToComparableNumber
import org.fossify.commons.models.BlockedNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** CM-02 / CM-03 / CM-04: the vendored Fossify blocked-number list with local and foreign numbers. */
@RunWith(AndroidJUnit4::class)
class LegacyBlockedNumberMatchingTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** An entry as getBlockedNumbers() builds it from the provider row. */
    private fun entry(number: String, e164: String = number) =
        BlockedNumber(1, number, e164, e164.trimToComparableNumber())

    private fun list(vararg entries: BlockedNumber) = arrayListOf(*entries)

    // --- CM-02: wildcard entries and separators ---

    @Test fun wildcardMatchesNumbersWithSpacesAndDashes() {
        val senders = listOf("+1 555 123 4567", "+1-555-123-4567", "+1 (555) 123-4567", "+15551234567", "001 555 1234567")
        for (sender in senders) assertTrue(sender, app.isNumberBlockedByPattern(sender, list(entry("+1555*")), "US"))
    }

    @Test fun wildcardTypedWithSpacesMatches() {
        assertTrue(app.isNumberBlockedByPattern("+447121234567", list(entry("+44 712*")), "GB"))
        assertTrue(app.isNumberBlockedByPattern("+44 712 123 4567", list(entry("+44-712-*")), "GB"))
    }

    @Test fun homeWildcardMatchesEveryForm() {
        val senders = listOf("+447121234567", "+44 712 123 4567", "00447121234567", "07121234567", "0712 123 4567", "۰۷۱۲۱۲۳۴۵۶۷")
        for (sender in senders) {
            assertTrue(sender, app.isNumberBlockedByPattern(sender, list(entry("+44712*")), "GB"))
            assertTrue(sender, app.isNumberBlockedByPattern(sender, list(entry("0712*")), "GB"))
        }
    }

    @Test fun wildcardDoesNotMatchOtherNumbers() {
        assertFalse(app.isNumberBlockedByPattern("+91 712 123 4567", list(entry("+44712*")), "GB"))
        assertFalse(app.isNumberBlockedByPattern("+44 713 123 4567", list(entry("+44712*")), "GB"))
        assertFalse(app.isNumberBlockedByPattern("+91 712 123 4567", list(entry("0712*")), "GB"))
        assertFalse(app.isNumberBlockedByPattern("+15551234567", list(entry("+1.55*")), "US"))
    }

    // --- CM-03: different people must not collide on the last 9 digits ---

    @Test fun nationalNumberIsReadInTheCountryBeforeComparing() {
        // An Indian user: 09876 543210 is +91 98765 43210, not the blocked +44 9876543210.
        assertFalse(app.isNumberBlocked("09876543210", list(entry("+449876543210")), "IN"))
        assertTrue(app.isNumberBlocked("09876543210", list(entry("+919876543210")), "IN"))
        // A home-country user: 0712… is the blocked +44 712….
        assertTrue(app.isNumberBlocked("07121234567", list(entry("+447121234567")), "GB"))
        assertTrue(app.isNumberBlocked("0712 123 4567", list(entry("+447121234567")), "GB"))
        assertFalse(app.isNumberBlocked("07121234567", list(entry("+917121234567")), "GB"))
    }

    @Test fun blockedNationalEntryIsReadInTheCountry() {
        assertTrue(app.isNumberBlocked("+447121234567", list(entry("07121234567")), "GB"))
        assertFalse(app.isNumberBlocked("+917121234567", list(entry("07121234567")), "GB"))
        assertFalse(app.isNumberBlocked("+917121234567", list(entry("07121234567", "+447121234567")), "GB"))
    }

    @Test fun differentCountriesWithSameLastDigitsNeverCollide() {
        val blocked = list(entry("+44 712 345 6789", "+447123456789"))
        for (sender in listOf("+917123456789", "+17123456789", "00917123456789", "+33 7 12 34 56 78 9")) {
            assertFalse(sender, app.isNumberBlocked(sender, blocked, "GB"))
        }
        for (sender in listOf("+447123456789", "00447123456789", "07123456789", "+44 712-345-6789")) {
            assertTrue(sender, app.isNumberBlocked(sender, blocked, "GB"))
        }
    }

    @Test fun unknownCountryFallsBackToLastDigits() {
        assertTrue(app.isNumberBlocked("07121234567", list(entry("+447121234567")), ""))
    }

    @Test fun shortCodesAndSenderIdsMatchExactly() {
        assertTrue(app.isNumberBlocked("30001234", list(entry("30001234")), "GB"))
        assertFalse(app.isNumberBlocked("30001235", list(entry("30001234")), "GB"))
        assertTrue(app.isNumberBlocked("HOMETEL", list(entry("HOMETEL")), "GB"))
        assertFalse(app.isNumberBlocked("HOMETE", list(entry("HOMETEL")), "GB"))
    }

    // --- CM-04: what is stored as the E.164 column ---

    @Test fun storedE164HasTheCountryCode() {
        assertEquals("+447123456789", blockedNumberE164("0712 345 6789", "GB"))
        assertEquals("+447123456789", blockedNumberE164("07123456789", "gb"))
        assertEquals("+447123456789", blockedNumberE164("۰۷۱۲۳۴۵۶۷۸۹", "GB"))
        assertEquals("+447123456789", blockedNumberE164("0044 712 345 6789", "GB"))
        assertEquals("+919876543210", blockedNumberE164("+91 98765 43210", "GB"))
        assertEquals("+919876543210", blockedNumberE164("09876 543210", "IN"))
    }

    @Test fun nothingStoredWhenNoE164Exists() {
        assertNull(blockedNumberE164("+44712*", "GB"))
        assertNull(blockedNumberE164("30001234", "GB"))
        assertNull(blockedNumberE164("HOMETEL", "GB"))
        assertNull(blockedNumberE164("07123456789", ""))
        assertNull(blockedNumberE164("", "GB"))
    }
}
