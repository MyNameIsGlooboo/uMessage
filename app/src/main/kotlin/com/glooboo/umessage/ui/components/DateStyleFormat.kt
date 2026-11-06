package com.glooboo.umessage.ui.components

import android.content.Context
import com.glooboo.umessage.helpers.DateStyle
import com.glooboo.umessage.helpers.w3cPrefs
import com.glooboo.umessage.ui.thread.JalaliDates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * ID-31: [formatListTime] in the user's date style. Clock times, "Now", "5 min" and weekdays are the same in every
 * style; the day-and-month labels of older messages are written in the Jalali calendar for [DateStyle.JALALI]. Lists
 * are narrow, so [DateStyle.BOTH] keeps the short Gregorian label there (the thread separators show both).
 */
fun formatListTimeStyled(
    context: Context,
    millis: Long,
    now: Long = System.currentTimeMillis(),
    style: DateStyle = context.w3cPrefs.dateStyle,
): String {
    if (style != DateStyle.JALALI) return formatListTime(context, millis, now)
    val bucket = timeBucket(millis, now)
    if (bucket != TimeBucket.THIS_YEAR && bucket != TimeBucket.OLDER) return formatListTime(context, millis, now)
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val persian = context.resources.configuration.locales[0].language == "fa"
    return jalaliListDate(day, today, persian)
}

/** "7 Aban" within the current Jalali year, "7 Aban 1404" before it (Persian script and digits when [persian]). */
internal fun jalaliListDate(day: LocalDate, today: LocalDate, persian: Boolean): String =
    JalaliDates.format(
        day,
        withYear = JalaliDates.fromGregorian(day).year != JalaliDates.fromGregorian(today).year,
        persian = persian,
    )
