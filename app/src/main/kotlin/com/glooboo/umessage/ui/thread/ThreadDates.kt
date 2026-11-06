package com.glooboo.umessage.ui.thread

import java.time.format.DecimalStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Words the date separator needs; the screen fills them from string resources. */
/** [separator] joins the day and the time: " · " in English, "، " in Persian (R.string.ui_separator). */
data class RelativeDateWords(val today: String, val yesterday: String, val separator: String = " · ")

/**
 * Date separator text as Google Messages writes it: "Today", "Yesterday", the weekday within the last week,
 * then "29 Oct" this year and "29 Oct 2025" before that, followed by the time of the first message of the run.
 */
fun relativeDateLabel(
    epochMillis: Long,
    nowMillis: Long,
    zone: ZoneId,
    use24Hour: Boolean,
    words: RelativeDateWords,
    locale: Locale = Locale.getDefault(),
    jalali: Boolean = false,
    /** With [jalali]: older days read "29 Oct (7 Aban)", both calendars. */
    both: Boolean = false,
): String {
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val day = moment.toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val daysAgo = ChronoUnit.DAYS.between(day, today)
    val dayText = when {
        daysAgo == 0L -> words.today
        daysAgo == 1L -> words.yesterday
        daysAgo in 2..6 -> day.format(pattern("EEEE", locale))
        // TH-03: the Jalali calendar when the setting is on; the year shows only when it is not the current one.
        jalali -> {
            val jalaliText = JalaliDates.format(
                day,
                withYear = JalaliDates.fromGregorian(day).year != JalaliDates.fromGregorian(today).year,
                persian = locale.language == "fa",
            )
            if (both) {
                val gregorian = day.format(pattern(if (day.year == today.year) "d MMM" else "d MMM yyyy", locale))
                "$gregorian ($jalaliText)"
            } else {
                jalaliText
            }
        }
        day.year == today.year -> day.format(pattern("d MMM", locale))
        else -> day.format(pattern("d MMM yyyy", locale))
    }
    val time = moment.format(pattern(if (use24Hour) "HH:mm" else "h:mm a", locale))
    return dayText + words.separator + time
}

/** A formatter that also writes the digits of [locale] (۰-۹ in Persian); java.time keeps 0-9 otherwise. */
private fun pattern(pattern: String, locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern(pattern, locale).withDecimalStyle(DecimalStyle.of(locale))

/** Just the time of day, for a separator that sits under a day label already shown (TH-01). */
fun timeOnlyLabel(epochMillis: Long, zone: ZoneId, use24Hour: Boolean, locale: Locale = Locale.getDefault()): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(pattern(if (use24Hour) "HH:mm" else "h:mm a", locale))

/**
 * The full time of one message for Message details (B4, TH-14): weekday, day, month, a four-digit year and the time
 * with seconds, "Mon, 2 Nov 2026, 12:12:50". [jalali] writes the day in the Jalali calendar ("Mon, 11 Aban 1405,
 * ..."), and with [both] too the Gregorian day comes first with the Jalali one in brackets. Persian digits in Persian.
 */
fun messageDetailsTime(
    epochMillis: Long,
    zone: ZoneId,
    use24Hour: Boolean,
    locale: Locale = Locale.getDefault(),
    jalali: Boolean = false,
    both: Boolean = false,
): String {
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val time = moment.format(pattern(if (use24Hour) "HH:mm:ss" else "h:mm:ss a", locale))
    val comma = if (locale.language == "fa") "، " else ", "
    return fullDateLabel(moment.toLocalDate(), locale, jalali, both) + comma + time
}

/**
 * "Mon, 2 Nov 2026", "Mon, 11 Aban 1405" ([jalali]) or "Mon, 2 Nov 2026 (11 Aban 1405)" ([both]): a day with its
 * weekday and four-digit year, for Message details and the schedule dialog. Persian script and digits in Persian.
 */
fun fullDateLabel(
    day: LocalDate,
    locale: Locale = Locale.getDefault(),
    jalali: Boolean = false,
    both: Boolean = false,
): String {
    val persian = locale.language == "fa"
    val weekday = day.format(pattern("EEE", locale))
    val gregorian = day.format(pattern("d MMM yyyy", locale))
    val dayText = when {
        !jalali -> gregorian
        both -> "$gregorian (${JalaliDates.format(day, withYear = true, persian = persian)})"
        else -> JalaliDates.format(day, withYear = true, persian = persian)
    }
    // Persian separates the parts with its own comma.
    return weekday + (if (persian) "، " else ", ") + dayText
}
