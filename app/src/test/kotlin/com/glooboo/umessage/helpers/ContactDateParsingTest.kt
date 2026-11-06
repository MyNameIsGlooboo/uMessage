package com.glooboo.umessage.helpers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.extensions.getDateTimeFromDateString
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * CM-21: contact birthdays in a shared vCard were read with Joda-Time; java.time must read the same formats the
 * same way, so a contact attached to an MMS keeps its dates.
 */
@RunWith(AndroidJUnit4::class)
class ContactDateParsingTest {

    @Test
    fun fullDatesInEveryFormat() {
        listOf("1990-05-12", "19900512", "1990.05.12").forEach {
            assertThat(it.getDateTimeFromDateString(false)).isEqualTo(LocalDate.of(1990, 5, 12))
        }
    }

    @Test
    fun twoDigitYearsMeanNineteenFiftyToTwentyFortyNine() {
        assertThat("90-05-12".getDateTimeFromDateString(false)).isEqualTo(LocalDate.of(1990, 5, 12))
        assertThat("12/05/12".getDateTimeFromDateString(false)).isEqualTo(LocalDate.of(2012, 5, 12))
    }

    @Test
    fun datesWithoutAYearGetThisYear() {
        val thisYear = LocalDate.now().year
        assertThat("--05-12".getDateTimeFromDateString(false)).isEqualTo(LocalDate.of(thisYear, 5, 12))
        assertThat("05-12".getDateTimeFromDateString(false)).isEqualTo(LocalDate.of(thisYear, 5, 12))
    }

    @Test
    fun unreadableTextGivesToday() {
        assertThat("not a date".getDateTimeFromDateString(false)).isEqualTo(LocalDate.now())
    }
}
