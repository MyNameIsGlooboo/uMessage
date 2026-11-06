package com.glooboo.umessage.ui.thread

import java.time.LocalDate
import java.time.Month

/** A date in the Jalali (Solar Hijri) calendar. */
data class JalaliDate(val year: Int, val month: Int, val day: Int)

/**
 * Jalali dates for the thread's date separators, the schedule summary and the schedule date picker (TH-03, TH-24).
 * Pure arithmetic on [LocalDate], no library: the 33-year cycle rule, exact for the years people use (checked against
 * known Nowruz days in the unit test). Display only; nothing stored is converted.
 */
object JalaliDates {

    const val MONTHS_IN_YEAR = 12

    /** Farvardin to Shahrivar have 31 days, Mehr to Bahman 30, Esfand 29 (30 in a leap year). */
    private const val LONG_MONTH_DAYS = 31
    private const val SHORT_MONTH_DAYS = 30
    private const val LONG_MONTHS = 6
    private const val LONG_HALF_DAYS = LONG_MONTHS * LONG_MONTH_DAYS
    private const val ESFAND_DAYS = 29

    // The day-count constants of the 33-year cycle conversion.
    private const val DAY_OFFSET = 355_666
    private const val DAYS_IN_YEAR = 365
    private const val LEAP_EVERY = 4
    private const val CENTURY = 100
    private const val FOUR_CENTURIES = 400
    private const val YEAR_OFFSET = -1595
    private const val CYCLE_YEARS = 33
    private const val CYCLE_DAYS = 12_053
    private const val FOUR_YEAR_DAYS = 1461
    private const val MARCH = 3
    private const val NOWRUZ_EARLIEST_DAY = 19
    private const val NOWRUZ_LATEST_DAY = 22
    private const val GREGORIAN_YEAR_OFFSET = 621

    private val PERSIAN_MONTHS = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
    )

    private val LATIN_MONTHS = arrayOf(
        "Farvardin", "Ordibehesht", "Khordad", "Tir", "Mordad", "Shahrivar",
        "Mehr", "Aban", "Azar", "Dey", "Bahman", "Esfand",
    )

    fun fromGregorian(date: LocalDate): JalaliDate {
        val gy = date.year
        val gm = date.monthValue
        val gd = date.dayOfMonth
        val gy2 = if (gm > 2) gy + 1 else gy
        val leapDays = (gy2 + LEAP_EVERY - 1) / LEAP_EVERY - (gy2 + CENTURY - 1) / CENTURY +
            (gy2 + FOUR_CENTURIES - 1) / FOUR_CENTURIES
        var days = DAY_OFFSET + DAYS_IN_YEAR * gy + leapDays + gd + daysBeforeMonth(gm)
        var jy = YEAR_OFFSET + CYCLE_YEARS * (days / CYCLE_DAYS)
        days %= CYCLE_DAYS
        jy += LEAP_EVERY * (days / FOUR_YEAR_DAYS)
        days %= FOUR_YEAR_DAYS
        if (days > DAYS_IN_YEAR) {
            jy += (days - 1) / DAYS_IN_YEAR
            days = (days - 1) % DAYS_IN_YEAR
        }
        return if (days < LONG_HALF_DAYS) {
            JalaliDate(jy, 1 + days / LONG_MONTH_DAYS, 1 + days % LONG_MONTH_DAYS)
        } else {
            val rest = days - LONG_HALF_DAYS
            JalaliDate(jy, LONG_MONTHS + 1 + rest / SHORT_MONTH_DAYS, 1 + rest % SHORT_MONTH_DAYS)
        }
    }

    /** Days in a common Gregorian year before [month]; the formula adds the leap days itself. */
    private fun daysBeforeMonth(month: Int): Int = Month.of(month).firstDayOfYear(false) - 1

    /** The Gregorian day of 1 Farvardin (Nowruz) of Jalali [year]: 19 to 22 March of [year] + 621. */
    fun nowruz(year: Int): LocalDate {
        val gregorianYear = year + GREGORIAN_YEAR_OFFSET
        return (NOWRUZ_EARLIEST_DAY..NOWRUZ_LATEST_DAY)
            .map { LocalDate.of(gregorianYear, MARCH, it) }
            .first { fromGregorian(it).let { j -> j.year == year && j.month == 1 && j.day == 1 } }
    }

    /** Whether Esfand of Jalali [year] has 30 days. */
    fun isLeapYear(year: Int): Boolean = fromGregorian(nowruz(year + 1).minusDays(1)).day == SHORT_MONTH_DAYS

    /** Days in Jalali [month] (1..12) of [year]. */
    fun monthLength(year: Int, month: Int): Int = when {
        month <= LONG_MONTHS -> LONG_MONTH_DAYS
        month < MONTHS_IN_YEAR -> SHORT_MONTH_DAYS
        isLeapYear(year) -> SHORT_MONTH_DAYS
        else -> ESFAND_DAYS
    }

    /** The Gregorian day of a Jalali date; the inverse of [fromGregorian]. */
    fun toGregorian(date: JalaliDate): LocalDate {
        val daysBeforeMonth = if (date.month <= LONG_MONTHS) {
            (date.month - 1) * LONG_MONTH_DAYS
        } else {
            LONG_HALF_DAYS + (date.month - LONG_MONTHS - 1) * SHORT_MONTH_DAYS
        }
        return nowruz(date.year).plusDays((daysBeforeMonth + date.day - 1).toLong())
    }

    /** The first day of the Jalali month that is [months] after (or before, when negative) the one of [date]. */
    fun addMonths(date: JalaliDate, months: Int): JalaliDate {
        val index = date.year * MONTHS_IN_YEAR + (date.month - 1) + months
        return JalaliDate(Math.floorDiv(index, MONTHS_IN_YEAR), Math.floorMod(index, MONTHS_IN_YEAR) + 1, 1)
    }

    /** Month name, 1..12: Persian script when [persian], else the usual Latin spelling. */
    fun monthName(month: Int, persian: Boolean): String =
        (if (persian) PERSIAN_MONTHS else LATIN_MONTHS)[(month - 1).coerceIn(0, MONTHS_IN_YEAR - 1)]

    /** "11 Aban" or "11 Aban 1405" (Persian script and digits when [persian]). */
    fun format(date: LocalDate, withYear: Boolean, persian: Boolean): String {
        val j = fromGregorian(date)
        val text = buildString {
            append(j.day).append(' ').append(monthName(j.month, persian))
            if (withYear) append(' ').append(j.year)
        }
        return if (persian) toPersianDigits(text) else text
    }

    fun toPersianDigits(text: String): String =
        text.map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")
}
