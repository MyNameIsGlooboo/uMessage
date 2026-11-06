package com.glooboo.umessage.ui.thread

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadDatesTest {
    private val zone = ZoneId.of("UTC")
    private val words = RelativeDateWords("Today", "Yesterday")
    private val now = ZonedDateTime.of(2026, 10, 30, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun label(year: Int, month: Int, day: Int, hour: Int = 21, minute: Int = 34, use24: Boolean = true) =
        relativeDateLabel(
            ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli(),
            now, zone, use24, words, Locale.ENGLISH,
        )

    @Test fun today() = assertEquals("Today · 09:05", label(2026, 10, 30, 9, 5))
    @Test fun yesterday() = assertEquals("Yesterday · 21:34", label(2026, 10, 29))
    @Test fun weekday() = assertEquals("Wednesday · 21:34", label(2026, 10, 28))
    @Test fun sixDaysAgoIsWeekday() = assertEquals("Saturday · 21:34", label(2026, 10, 24))
    @Test fun sevenDaysAgoIsDate() = assertEquals("23 Oct · 21:34", label(2026, 10, 23))
    @Test fun earlierThisYear() = assertEquals("8 Apr · 17:13", label(2026, 4, 8, 17, 13))
    @Test fun previousYear() = assertEquals("29 Dec 2025 · 08:00", label(2025, 12, 29, 8, 0))
    @Test fun twelveHourClock() = assertEquals("Yesterday · 9:34 PM", label(2026, 10, 29, use24 = false))
}
