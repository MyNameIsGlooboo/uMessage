package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/** Days in a week, and the alpha of a day that can no longer be picked (TH-26: faded but legible). */
private const val DAYS_IN_WEEK = 7
private const val DISABLED_ALPHA = 0.38f

/**
 * One month of the schedule date picker (TH-24, ID-32): Gregorian, or Jalali when the date style asks for it.
 * [first] is its first day (as a Gregorian date) and [length] its number of days.
 */
data class CalendarMonth(val jalali: Boolean, val year: Int, val month: Int, val first: LocalDate, val length: Int) {
    val last: LocalDate get() = first.plusDays(length - 1L)

    /** The month after ([months] = 1) or before (-1) this one, in the same calendar. */
    fun plus(months: Int): CalendarMonth =
        if (jalali) {
            JalaliDates.addMonths(JalaliDate(year, month, 1), months).let { of(it.year, it.month, jalali = true) }
        } else {
            YearMonth.of(year, month).plusMonths(months.toLong()).let { of(it.year, it.monthValue, jalali = false) }
        }

    /** The day number of [date] in this month, as the grid writes it. */
    fun dayNumber(date: LocalDate): Int = ChronoUnit.DAYS.between(first, date).toInt() + 1

    companion object {
        fun of(year: Int, month: Int, jalali: Boolean): CalendarMonth =
            if (jalali) {
                val first = JalaliDates.toGregorian(JalaliDate(year, month, 1))
                CalendarMonth(true, year, month, first, JalaliDates.monthLength(year, month))
            } else {
                val ym = YearMonth.of(year, month)
                CalendarMonth(false, year, month, ym.atDay(1), ym.lengthOfMonth())
            }

        /** The month that holds [date]. */
        fun containing(date: LocalDate, jalali: Boolean): CalendarMonth =
            if (jalali) {
                JalaliDates.fromGregorian(date).let { of(it.year, it.month, jalali = true) }
            } else {
                of(date.year, date.monthValue, jalali = false)
            }
    }
}

/** The week starts on Saturday in the Jalali calendar and in Persian, else where the [locale] starts it. */
fun firstDayOfWeek(jalali: Boolean, locale: Locale): DayOfWeek =
    if (jalali || locale.language == "fa") DayOfWeek.SATURDAY else WeekFields.of(locale).firstDayOfWeek

/**
 * The month as weeks of seven cells, null for the blank cells before the first day and after the last. Only as many
 * weeks as the month needs, so the picker has no empty band under it (TH-26).
 */
fun monthWeeks(month: CalendarMonth, weekStart: DayOfWeek): List<List<LocalDate?>> {
    val lead = Math.floorMod(month.first.dayOfWeek.value - weekStart.value, DAYS_IN_WEEK)
    val cells = List(lead) { null } + List(month.length) { month.first.plusDays(it.toLong()) }
    return cells.chunked(DAYS_IN_WEEK).map { week -> week + List(DAYS_IN_WEEK - week.size) { null } }
}

/** "Aban 1405" or "November 2026", in Persian script and digits for Persian. */
fun monthTitle(month: CalendarMonth, locale: Locale): String {
    val persian = locale.language == "fa"
    return if (month.jalali) {
        val text = "${JalaliDates.monthName(month.month, persian)} ${month.year}"
        if (persian) JalaliDates.toPersianDigits(text) else text
    } else {
        month.first.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale).withDecimalStyle(DecimalStyle.of(locale)))
    }
}

/**
 * The calendar of the schedule dialog: month title with previous and next buttons, the weekday letters and the days.
 * Days before [minDate] are faded and cannot be picked; Friday (the region's weekend) is in the error colour when the
 * week starts on Saturday. [subtitle] gives a line under the title for the picked day (the other calendar's date).
 */
@Composable
fun ScheduleCalendar(
    selected: LocalDate,
    minDate: LocalDate,
    jalali: Boolean,
    locale: Locale,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    var month by remember(jalali) { mutableStateOf(CalendarMonth.containing(selected, jalali)) }
    val weekStart = firstDayOfWeek(jalali, locale)
    val persian = locale.language == "fa"
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(monthTitle(month, locale), style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            IconButton(onClick = { month = month.plus(-1) }, enabled = month.first > minDate) {
                Icon(Symbols.ArrowBack, contentDescription = stringResource(R.string.thread_r2_calendar_previous))
            }
            IconButton(onClick = { month = month.plus(1) }) {
                Icon(Symbols.ArrowForward, contentDescription = stringResource(R.string.thread_r2_calendar_next))
            }
        }
        Row(Modifier.padding(top = 8.dp)) {
            List(DAYS_IN_WEEK) { weekStart.plus(it.toLong()) }.forEach { day ->
                Text(
                    day.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isWeekend(day, weekStart)) colors.error else colors.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        monthWeeks(month, weekStart).forEach { week ->
            Row {
                week.forEach { date ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (date != null) {
                            val number = month.dayNumber(date).toString()
                            DayCell(
                                text = if (persian) JalaliDates.toPersianDigits(number) else number,
                                selected = date == selected,
                                enabled = date >= minDate,
                                weekend = isWeekend(date.dayOfWeek, weekStart),
                                onClick = { onSelect(date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Friday is the weekend in a week that starts on Saturday (the regional week). */
private fun isWeekend(day: DayOfWeek, weekStart: DayOfWeek): Boolean =
    day == DayOfWeek.FRIDAY && weekStart == DayOfWeek.SATURDAY

@Composable
private fun DayCell(text: String, selected: Boolean, enabled: Boolean, weekend: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val textColor = when {
        selected -> colors.onPrimary
        weekend -> colors.error
        else -> colors.onSurface
    }
    Box(
        modifier = Modifier
            .padding(2.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(if (selected) colors.primary else Color.Transparent)
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (enabled) textColor else textColor.copy(alpha = DISABLED_ALPHA),
        )
    }
}
