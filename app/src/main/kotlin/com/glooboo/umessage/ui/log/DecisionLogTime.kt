package com.glooboo.umessage.ui.log

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.formatClockTime
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.ui.components.uiLocale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * SE-37: rows show only the clock time; the day is in the header above them ([formatLogDay]). The clock follows the
 * system 12/24-hour setting and the locale, so "00:04" is never mistaken for a duration.
 */
fun formatLogTime(context: Context, millis: Long): String = formatClockTime(context, millis)

/** Which header a day gets: "Today", "Yesterday" or its date. Pure so it can be tested without Android. */
internal enum class LogDayKind { TODAY, YESTERDAY, OTHER }

internal fun logDayKind(day: LocalDate, today: LocalDate): LogDayKind = when (ChronoUnit.DAYS.between(day, today)) {
    0L -> LogDayKind.TODAY
    1L -> LogDayKind.YESTERDAY
    else -> LogDayKind.OTHER
}

/** "Today", "Yesterday", or "Sun, Nov 1" in the phone's locale and digits. */
fun formatLogDay(
    context: Context,
    day: LocalDate,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = when (logDayKind(day, today)) {
    LogDayKind.TODAY -> context.getString(R.string.declog_today)
    LogDayKind.YESTERDAY -> context.getString(R.string.declog_yesterday)
    LogDayKind.OTHER -> DateUtils.formatDateTime(
        context,
        day.atStartOfDay(zone).toInstant().toEpochMilli(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL,
    )
}

@Composable
internal fun rememberLogTime(timestamp: Long): String {
    val context = LocalContext.current
    val locale = uiLocale()
    return remember(timestamp, locale) { localizeDigits(formatLogTime(context, timestamp), locale) }
}

@Composable
internal fun rememberLogDay(day: LocalDate): String {
    val context = LocalContext.current
    val locale = uiLocale()
    val today = LocalDate.now()
    return remember(day, today, locale) { localizeDigits(formatLogDay(context, day, today), locale) }
}

/** Day of [millis] in the phone's zone. */
internal fun logDayOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

/** "SIM 1" with a no-break space, so a narrow row never leaves the number alone on the next line. */
internal fun unbreakable(text: String): String = text.replace(' ', ' ')
