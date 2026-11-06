package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.helpers.DateStyle
import com.glooboo.umessage.ui.thread.JalaliDates
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Locale

/**
 * When a held message arrived, for "Why blocked" (round 4). The Gregorian day is written the locale's own way
 * ("Nov 2, 2026" in en-US, "2 Nov 2026" in en-GB) instead of the fixed day-month-year Message details uses, which read
 * oddly in en-US. [DateStyle.JALALI] writes the day in the Jalali calendar ("11 Aban 1405"), [DateStyle.BOTH] the
 * Gregorian day with the Jalali one in brackets, as the app's other dates do. The time follows the phone's 12/24-hour
 * setting ([use24Hour]); Persian gets its own digits and comma.
 */
internal fun blockedReceivedTime(
    epochMillis: Long,
    zone: ZoneId,
    use24Hour: Boolean,
    locale: Locale,
    style: DateStyle,
): String {
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val day = moment.toLocalDate()
    val persian = locale.language == "fa"
    val digits = DecimalStyle.of(locale)
    val gregorian = day.format(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withDecimalStyle(digits),
    )
    val dayText = when (style) {
        DateStyle.GREGORIAN -> gregorian
        DateStyle.JALALI -> JalaliDates.format(day, withYear = true, persian = persian)
        DateStyle.BOTH -> "$gregorian (${JalaliDates.format(day, withYear = true, persian = persian)})"
    }
    val time = moment.format(
        DateTimeFormatter.ofPattern(if (use24Hour) "H:mm" else "h:mm a", locale).withDecimalStyle(digits),
    )
    return dayText + (if (persian) "، " else ", ") + time
}
