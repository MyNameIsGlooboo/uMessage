package com.glooboo.umessage.messaging

import com.glooboo.umessage.models.ScheduledRepeat
import com.glooboo.umessage.ui.thread.JalaliDate
import com.glooboo.umessage.ui.thread.JalaliDates
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** How often a scheduled message comes back. The names are what [ScheduledRepeat.rule] stores. */
enum class RepeatRule {
    NONE, DAILY, WEEKLY, MONTHLY, YEARLY;

    companion object {
        fun fromName(name: String?): RepeatRule = entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** The calendar a repeat counts months and years in. The names are what [ScheduledRepeat.calendar] stores. */
enum class RepeatCalendar {
    GREGORIAN, JALALI;

    companion object {
        fun fromName(name: String?): RepeatCalendar = entries.firstOrNull { it.name == name } ?: GREGORIAN
    }
}

/** Gives up after this many steps, so a bad rule can never spin: a daily repeat covers about eleven years. */
private const val MAX_REPEAT_STEPS = 4000

private const val DAYS_IN_WEEK = 7L

/**
 * The next time after [nowMillis] that a message first due at [previousMillis] repeats, or null for [RepeatRule.NONE].
 * It counts from the intended time, never from when the last one was really sent, and skips every occurrence already
 * in the past, so a phone that was off sends one late message and books the next one in the future. The month and
 * year rules keep [anchorDay] (and [anchorMonth]) and clamp to the last day of a short month, so 31 January gives
 * 28 February and then 31 March again. With [RepeatCalendar.JALALI] the days and months are Jalali ones. The time of
 * day stays the same on the clock, also across a daylight saving change.
 */
fun nextOccurrence(
    previousMillis: Long,
    rule: RepeatRule,
    calendar: RepeatCalendar,
    anchorDay: Int,
    anchorMonth: Int,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Long? {
    if (rule == RepeatRule.NONE) return null
    val previous = Instant.ofEpochMilli(previousMillis).atZone(zone)
    val date = previous.toLocalDate()
    val time = previous.toLocalTime()
    for (step in 1..MAX_REPEAT_STEPS) {
        val day = occurrenceDate(date, rule, calendar, anchorDay, anchorMonth, step)
        val millis = day.atTime(time).atZone(zone).toInstant().toEpochMilli()
        if (millis > nowMillis) return millis
    }
    return null
}

private fun occurrenceDate(
    date: LocalDate,
    rule: RepeatRule,
    calendar: RepeatCalendar,
    anchorDay: Int,
    anchorMonth: Int,
    step: Int,
): LocalDate = when (rule) {
    RepeatRule.DAILY -> date.plusDays(step.toLong())
    RepeatRule.WEEKLY -> date.plusDays(step * DAYS_IN_WEEK)
    RepeatRule.MONTHLY, RepeatRule.YEARLY -> {
        val months = if (rule == RepeatRule.MONTHLY) step else step * JalaliDates.MONTHS_IN_YEAR
        if (calendar == RepeatCalendar.JALALI) {
            jalaliMonthDay(date, rule, anchorDay, anchorMonth, months)
        } else {
            gregorianMonthDay(date, rule, anchorDay, anchorMonth, months)
        }
    }
    RepeatRule.NONE -> date
}

private fun gregorianMonthDay(date: LocalDate, rule: RepeatRule, day: Int, month: Int, months: Int): LocalDate {
    val base = if (rule == RepeatRule.YEARLY) YearMonth.of(date.year, month) else YearMonth.from(date)
    val target = base.plusMonths(months.toLong())
    return target.atDay(minOf(day, target.lengthOfMonth()))
}

private fun jalaliMonthDay(date: LocalDate, rule: RepeatRule, day: Int, month: Int, months: Int): LocalDate {
    val from = JalaliDates.fromGregorian(date)
    val baseMonth = if (rule == RepeatRule.YEARLY) month else from.month
    val index = from.year * JalaliDates.MONTHS_IN_YEAR + (baseMonth - 1) + months
    val year = Math.floorDiv(index, JalaliDates.MONTHS_IN_YEAR)
    val targetMonth = Math.floorMod(index, JalaliDates.MONTHS_IN_YEAR) + 1
    val clamped = minOf(day, JalaliDates.monthLength(year, targetMonth))
    return JalaliDates.toGregorian(JalaliDate(year, targetMonth, clamped))
}

/** The repeat to save for scheduled message [messageId] first due at [firstMillis]; its anchor is that day. */
fun newScheduledRepeat(
    messageId: Long,
    rule: RepeatRule,
    calendar: RepeatCalendar,
    firstMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): ScheduledRepeat {
    val date = Instant.ofEpochMilli(firstMillis).atZone(zone).toLocalDate()
    val (day, month) = if (calendar == RepeatCalendar.JALALI) {
        JalaliDates.fromGregorian(date).let { it.day to it.month }
    } else {
        date.dayOfMonth to date.monthValue
    }
    return ScheduledRepeat(messageId, rule.name, calendar.name, day, month)
}
