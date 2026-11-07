package com.glooboo.umessage.ui.components

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.glooboo.umessage.R
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Which shape a timestamp takes in lists: Google Messages' compact style ("Now", "5 min", "6:33 AM", "Mon", "Oct 29"). */
internal enum class TimeBucket { NOW, MINUTES, TODAY, THIS_WEEK, THIS_YEAR, OLDER }

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val WEEK_DAYS = 7L

/** Pure bucketing so it can be unit-tested without Android; future timestamps (clock skew) count as today or older. */
internal fun timeBucket(millis: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): TimeBucket {
    val age = now - millis
    if (age in 0 until MINUTE_MS) return TimeBucket.NOW
    if (age in MINUTE_MS until HOUR_MS) return TimeBucket.MINUTES
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val daysAgo = ChronoUnit.DAYS.between(day, today)
    return when {
        daysAgo == 0L -> TimeBucket.TODAY
        daysAgo in 1 until WEEK_DAYS -> TimeBucket.THIS_WEEK
        day.year == today.year && daysAgo > 0 -> TimeBucket.THIS_YEAR
        else -> TimeBucket.OLDER
    }
}

/**
 * The one timestamp formatter for lists (conversations, search, blocked messages, decision log). The clock follows
 * the system 12/24-hour setting and the locale (no leading zero in 12-hour time).
 */
fun formatListTime(context: Context, millis: Long, now: Long = System.currentTimeMillis()): String =
    when (timeBucket(millis, now)) {
        TimeBucket.NOW -> context.getString(R.string.ui_time_now)
        TimeBucket.MINUTES -> {
            val minutes = ((now - millis) / MINUTE_MS).toInt()
            context.resources.getQuantityString(R.plurals.ui_time_minutes, minutes, minutes)
        }
        TimeBucket.TODAY -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
        TimeBucket.THIS_WEEK -> DateUtils.formatDateTime(
            context, millis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
        )
        TimeBucket.THIS_YEAR -> DateUtils.formatDateTime(
            context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
        )
        TimeBucket.OLDER -> DateUtils.formatDateTime(
            context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_NUMERIC_DATE,
        )
    }

/** Time of day alone, in the system 12/24-hour style. */
fun formatClockTime(context: Context, millis: Long): String =
    DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)

/** [formatListTimeStyled] for composables: Jalali dates for a user who chose the Jalali style (B13). */
@Composable
fun rememberTimeLabel(timestamp: Long): String {
    val context = LocalContext.current
    return remember(timestamp) { formatListTimeStyled(context, timestamp) }
}
