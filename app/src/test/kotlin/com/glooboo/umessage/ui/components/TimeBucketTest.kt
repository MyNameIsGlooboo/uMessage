package com.glooboo.umessage.ui.components

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeBucketTest {
    private val zone = ZoneId.of("UTC")
    private val now = at(2026, 10, 30, 12, 0)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0) =
        ZonedDateTime.of(year, month, day, hour, minute, second, 0, zone).toInstant().toEpochMilli()

    private fun bucket(millis: Long) = timeBucket(millis, now, zone)

    @Test fun justNow() = assertEquals(TimeBucket.NOW, bucket(at(2026, 10, 30, 11, 59, 30)))
    @Test fun minutesAgo() = assertEquals(TimeBucket.MINUTES, bucket(at(2026, 10, 30, 11, 5)))
    @Test fun earlierToday() = assertEquals(TimeBucket.TODAY, bucket(at(2026, 10, 30, 6, 33)))
    @Test fun lastMinuteOfYesterdayIsThisWeek() = assertEquals(TimeBucket.THIS_WEEK, bucket(at(2026, 10, 29, 23, 59)))
    @Test fun sixDaysAgo() = assertEquals(TimeBucket.THIS_WEEK, bucket(at(2026, 10, 24, 9, 0)))
    @Test fun sevenDaysAgoIsDate() = assertEquals(TimeBucket.THIS_YEAR, bucket(at(2026, 10, 23, 9, 0)))
    @Test fun previousYear() = assertEquals(TimeBucket.OLDER, bucket(at(2025, 12, 31, 9, 0)))
    @Test fun futureTodayIsTime() = assertEquals(TimeBucket.TODAY, bucket(at(2026, 10, 30, 18, 0)))
    @Test fun futureDayIsFullDate() = assertEquals(TimeBucket.OLDER, bucket(at(2026, 11, 2, 9, 0)))
}
