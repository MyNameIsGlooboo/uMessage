package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.helpers.DateStyle
import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Test

/** Round 4 emulator-check fixes on the Blocked screens: "Why blocked" dates and the "Always allow?" wording. */
class BlockedRound4Test {

    private val zone = ZoneId.of("UTC")
    private val millis = ZonedDateTime.of(2026, 11, 2, 21, 55, 27, 0, zone).toInstant().toEpochMilli()

    @Test
    fun receivedDateFollowsTheLocale() {
        assertThat(blockedReceivedTime(millis, zone, false, Locale.US, DateStyle.GREGORIAN))
            .isEqualTo("Nov 2, 2026, 9:55 PM")
        assertThat(blockedReceivedTime(millis, zone, true, Locale.UK, DateStyle.GREGORIAN))
            .isEqualTo("2 Nov 2026, 21:55")
    }

    @Test
    fun receivedDateKeepsTheJalaliStyles() {
        assertThat(blockedReceivedTime(millis, zone, true, Locale.US, DateStyle.JALALI))
            .isEqualTo("11 Aban 1405, 21:55")
        assertThat(blockedReceivedTime(millis, zone, true, Locale.US, DateStyle.BOTH))
            .isEqualTo("Nov 2, 2026 (11 Aban 1405), 21:55")
        val persian = blockedReceivedTime(millis, zone, true, Locale.forLanguageTag("fa"), DateStyle.JALALI)
        assertThat(persian).isEqualTo("۱۱ آبان ۱۴۰۵، ۲۱:۵۵")
    }
}
