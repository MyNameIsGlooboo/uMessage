package com.glooboo.umessage.messaging

import com.glooboo.umessage.ui.thread.JalaliDate
import com.glooboo.umessage.ui.thread.JalaliDates
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** B04: when a repeating scheduled message comes back. */
class ScheduleRepeatTest {

    private val utc = ZoneId.of("UTC")
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(date: LocalDate, zone: ZoneId = utc, hour: Int = 9): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun dateOf(millis: Long, zone: ZoneId = utc): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun next(
        from: LocalDate,
        rule: RepeatRule,
        calendar: RepeatCalendar = RepeatCalendar.GREGORIAN,
        anchor: LocalDate = from,
        now: Long = at(from),
        zone: ZoneId = utc,
    ): LocalDate {
        val repeat = newScheduledRepeat(1L, rule, calendar, at(anchor, zone), zone)
        return dateOf(
            nextOccurrence(at(from, zone), rule, calendar, repeat.anchorDay, repeat.anchorMonth, now, zone)!!,
            zone,
        )
    }

    private fun jalali(date: LocalDate) = JalaliDates.fromGregorian(date)

    @Test
    fun neverHasNoNextTime() {
        assertThat(nextOccurrence(0L, RepeatRule.NONE, RepeatCalendar.GREGORIAN, 1, 1, 0L, utc)).isNull()
    }

    @Test
    fun dailyAndWeekly() {
        assertThat(next(LocalDate.of(2026, 1, 31), RepeatRule.DAILY)).isEqualTo(LocalDate.of(2026, 2, 1))
        assertThat(next(LocalDate.of(2026, 1, 31), RepeatRule.WEEKLY)).isEqualTo(LocalDate.of(2026, 2, 7))
    }

    @Test
    fun monthKeepsTheAnchorDayAndClampsToTheLastDay() {
        val jan31 = LocalDate.of(2026, 1, 31)
        val feb28 = next(jan31, RepeatRule.MONTHLY)
        assertThat(feb28).isEqualTo(LocalDate.of(2026, 2, 28))
        // From the clamped day the next one is the 31st again.
        assertThat(next(feb28, RepeatRule.MONTHLY, anchor = jan31)).isEqualTo(LocalDate.of(2026, 3, 31))
        assertThat(next(LocalDate.of(2028, 1, 31), RepeatRule.MONTHLY)).isEqualTo(LocalDate.of(2028, 2, 29))
        assertThat(next(LocalDate.of(2026, 1, 30), RepeatRule.MONTHLY)).isEqualTo(LocalDate.of(2026, 2, 28))
        assertThat(next(LocalDate.of(2026, 3, 30), RepeatRule.MONTHLY)).isEqualTo(LocalDate.of(2026, 4, 30))
        assertThat(next(LocalDate.of(2026, 12, 15), RepeatRule.MONTHLY)).isEqualTo(LocalDate.of(2027, 1, 15))
    }

    @Test
    fun yearlyOnTheLeapDay() {
        val leapDay = LocalDate.of(2028, 2, 29)
        val common = next(leapDay, RepeatRule.YEARLY)
        assertThat(common).isEqualTo(LocalDate.of(2029, 2, 28))
        assertThat(next(LocalDate.of(2031, 2, 28), RepeatRule.YEARLY, anchor = leapDay))
            .isEqualTo(LocalDate.of(2032, 2, 29))
    }

    @Test
    fun theClockTimeStaysTheSameAcrossADaylightSavingChange() {
        val before = LocalDate.of(2026, 3, 28)
        val first = at(before, berlin)
        val result = nextOccurrence(first, RepeatRule.DAILY, RepeatCalendar.GREGORIAN, 28, 3, first, berlin)!!
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(result), berlin)
        assertThat(local).isEqualTo(LocalDateTime.of(2026, 3, 29, 9, 0))
        // The day is 23 hours long, but the message still goes out at 9:00.
        assertThat(result - first).isEqualTo(23 * 60 * 60_000L)
    }

    @Test
    fun jalaliYearKeepsTheFirstOfFarvardin() {
        val nowruz = JalaliDates.nowruz(1405)
        val result = next(nowruz, RepeatRule.YEARLY, RepeatCalendar.JALALI)
        assertThat(result).isEqualTo(JalaliDates.nowruz(1406))
        assertThat(jalali(result)).isEqualTo(JalaliDate(1406, 1, 1))
    }

    @Test
    fun jalaliThirtiethOfEsfandClampsInACommonYear() {
        val leapYear = (1400..1440).first { JalaliDates.isLeapYear(it) }
        val esfand30 = JalaliDates.toGregorian(JalaliDate(leapYear, 12, 30))
        val result = next(esfand30, RepeatRule.YEARLY, RepeatCalendar.JALALI)
        assertThat(jalali(result)).isEqualTo(JalaliDate(leapYear + 1, 12, 29))
    }

    @Test
    fun jalaliMonthsClampToTheirOwnLength() {
        val shahrivar31 = JalaliDates.toGregorian(JalaliDate(1405, 6, 31))
        val mehr = next(shahrivar31, RepeatRule.MONTHLY, RepeatCalendar.JALALI)
        assertThat(jalali(mehr)).isEqualTo(JalaliDate(1405, 7, 30))
        // The anchor stays the 31st, and Aban has 30 days too.
        val aban = next(mehr, RepeatRule.MONTHLY, RepeatCalendar.JALALI, anchor = shahrivar31)
        assertThat(jalali(aban)).isEqualTo(JalaliDate(1405, 8, 30))
        val farvardin = JalaliDates.toGregorian(JalaliDate(1405, 1, 31))
        assertThat(jalali(next(farvardin, RepeatRule.MONTHLY, RepeatCalendar.JALALI)))
            .isEqualTo(JalaliDate(1405, 2, 31))
    }

    @Test
    fun aPhoneThatWasOffSkipsTheMissedOnes() {
        val start = LocalDate.of(2026, 1, 1)
        val tenDaysLater = at(LocalDate.of(2026, 1, 10), hour = 12)
        assertThat(next(start, RepeatRule.DAILY, now = tenDaysLater)).isEqualTo(LocalDate.of(2026, 1, 11))
        assertThat(next(LocalDate.of(2026, 1, 15), RepeatRule.MONTHLY, now = at(LocalDate.of(2026, 3, 20))))
            .isEqualTo(LocalDate.of(2026, 4, 15))
        assertThat(next(start, RepeatRule.YEARLY, now = at(LocalDate.of(2029, 6, 1))))
            .isEqualTo(LocalDate.of(2030, 1, 1))
    }

    @Test
    fun theNextTimeIsAlwaysInTheFuture() {
        val now = at(LocalDate.of(2026, 6, 15))
        for (rule in RepeatRule.entries.filter { it != RepeatRule.NONE }) {
            for (calendar in RepeatCalendar.entries) {
                val result = nextOccurrence(at(LocalDate.of(2020, 2, 29)), rule, calendar, 29, 2, now, utc)!!
                assertThat(result).isGreaterThan(now)
            }
        }
    }

    @Test
    fun theAnchorIsTheDayInTheChosenCalendar() {
        val date = JalaliDates.toGregorian(JalaliDate(1405, 7, 5))
        val gregorian = newScheduledRepeat(1L, RepeatRule.YEARLY, RepeatCalendar.GREGORIAN, at(date), utc)
        assertThat(gregorian.anchorDay).isEqualTo(date.dayOfMonth)
        assertThat(gregorian.anchorMonth).isEqualTo(date.monthValue)
        val persian = newScheduledRepeat(1L, RepeatRule.YEARLY, RepeatCalendar.JALALI, at(date), utc)
        assertThat(persian.anchorDay).isEqualTo(5)
        assertThat(persian.anchorMonth).isEqualTo(7)
        assertThat(persian.rule).isEqualTo("YEARLY")
        assertThat(persian.calendar).isEqualTo("JALALI")
    }

    @Test
    fun namesRoundTripAndUnknownOnesAreSafe() {
        assertThat(RepeatRule.fromName("MONTHLY")).isEqualTo(RepeatRule.MONTHLY)
        assertThat(RepeatRule.fromName("nonsense")).isEqualTo(RepeatRule.NONE)
        assertThat(RepeatRule.fromName(null)).isEqualTo(RepeatRule.NONE)
        assertThat(RepeatCalendar.fromName("JALALI")).isEqualTo(RepeatCalendar.JALALI)
        assertThat(RepeatCalendar.fromName(null)).isEqualTo(RepeatCalendar.GREGORIAN)
    }
}
