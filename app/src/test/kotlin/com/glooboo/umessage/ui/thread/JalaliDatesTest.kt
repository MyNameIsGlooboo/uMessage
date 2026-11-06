package com.glooboo.umessage.ui.thread

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class JalaliDatesTest {
    private fun j(y: Int, m: Int, d: Int) = JalaliDates.fromGregorian(LocalDate.of(y, m, d))

    @Test fun nowruzDays() {
        assertEquals(JalaliDate(1405, 1, 1), j(2026, 3, 21))
        assertEquals(JalaliDate(1404, 1, 1), j(2025, 3, 21))
        assertEquals(JalaliDate(1403, 1, 1), j(2024, 3, 20))
    }

    @Test fun leapYearEndsOnThe30thOfEsfand() = assertEquals(JalaliDate(1403, 12, 30), j(2025, 3, 20))

    @Test fun knownDates() {
        assertEquals(JalaliDate(1378, 10, 11), j(2000, 1, 1))
        assertEquals(JalaliDate(1357, 11, 22), j(1979, 2, 11))
        assertEquals(JalaliDate(1405, 8, 11), j(2026, 11, 2))
    }

    @Test fun toGregorianIsTheInverse() {
        var day = LocalDate.of(2023, 1, 1)
        while (day.year < 2028) {
            assertEquals(day, JalaliDates.toGregorian(JalaliDates.fromGregorian(day)))
            day = day.plusDays(1)
        }
    }

    @Test fun monthLengthsAndLeapYears() {
        assertEquals(31, JalaliDates.monthLength(1405, 1))
        assertEquals(30, JalaliDates.monthLength(1405, 7))
        assertEquals(30, JalaliDates.monthLength(1403, 12))
        assertEquals(29, JalaliDates.monthLength(1404, 12))
        assertEquals(LocalDate.of(2026, 3, 21), JalaliDates.nowruz(1405))
    }

    @Test fun addMonthsCrossesYears() {
        assertEquals(JalaliDate(1406, 1, 1), JalaliDates.addMonths(JalaliDate(1405, 12, 10), 1))
        assertEquals(JalaliDate(1404, 12, 1), JalaliDates.addMonths(JalaliDate(1405, 1, 5), -1))
    }

    @Test fun messageDetailsTimeHasAFourDigitYearAndFollowsTheDateStyle() {
        val zone = ZoneId.of("UTC")
        val millis = ZonedDateTime.of(2026, 11, 2, 12, 12, 50, 0, zone).toInstant().toEpochMilli()
        assertEquals("Mon, 2 Nov 2026, 12:12:50", messageDetailsTime(millis, zone, true, Locale.ENGLISH))
        assertEquals("Mon, 2 Nov 2026, 12:12:50 PM", messageDetailsTime(millis, zone, false, Locale.ENGLISH))
        assertEquals(
            "Mon, 11 Aban 1405, 12:12:50",
            messageDetailsTime(millis, zone, true, Locale.ENGLISH, jalali = true),
        )
        assertEquals(
            "Mon, 2 Nov 2026 (11 Aban 1405), 12:12:50",
            messageDetailsTime(millis, zone, true, Locale.ENGLISH, jalali = true, both = true),
        )
        val persian = messageDetailsTime(millis, zone, true, Locale.forLanguageTag("fa"), jalali = true)
        assertEquals(true, persian.contains("۱۱ آبان ۱۴۰۵"))
        assertEquals(true, persian.endsWith("۱۲:۱۲:۵۰"))
    }

    @Test fun formatsLatinAndPersian() {
        val date = LocalDate.of(2026, 11, 2)
        assertEquals("11 Aban", JalaliDates.format(date, withYear = false, persian = false))
        assertEquals("11 Aban 1405", JalaliDates.format(date, withYear = true, persian = false))
        assertEquals("۱۱ آبان ۱۴۰۵", JalaliDates.format(date, withYear = true, persian = true))
    }

    @Test fun separatorUsesJalaliAfterAWeekAndShowsOtherYears() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 11, 2, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        fun at(y: Int, m: Int, d: Int) = ZonedDateTime.of(y, m, d, 9, 5, 0, 0, zone).toInstant().toEpochMilli()
        val words = RelativeDateWords("Today", "Yesterday")
        assertEquals(
            "11 Mehr · 09:05",
            relativeDateLabel(at(2026, 10, 3), now, zone, true, words, Locale.ENGLISH, jalali = true),
        )
        assertEquals(
            "1 Dey 1404 · 09:05",
            relativeDateLabel(at(2025, 12, 22), now, zone, true, words, Locale.ENGLISH, jalali = true),
        )
        assertEquals(
            "Today · 09:05",
            relativeDateLabel(at(2026, 11, 2), now, zone, true, words, Locale.ENGLISH, jalali = true),
        )
    }
}
