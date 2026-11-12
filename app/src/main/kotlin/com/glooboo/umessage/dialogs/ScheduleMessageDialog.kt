package com.glooboo.umessage.dialogs

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.DateStyle
import com.glooboo.umessage.helpers.w3cPrefs
import com.glooboo.umessage.messaging.RepeatRule
import com.glooboo.umessage.ui.components.ChoiceDialog
import com.glooboo.umessage.ui.thread.ScheduleCalendar
import com.glooboo.umessage.ui.thread.fullDateLabel
import com.glooboo.umessage.ui.components.showComposeDialog
import com.glooboo.umessage.ui.icons.Symbols
import org.fossify.commons.activities.BaseSimpleActivity
import com.glooboo.umessage.extensions.roundToClosestMultipleOf
import org.fossify.commons.extensions.toast
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import com.glooboo.umessage.ui.components.dialogScroll

private enum class Step { Date, Time, Repeat, Preview }

/**
 * Pick when a message is sent: date, then time, then a summary to confirm, where it can also be set to repeat. Opened
 * with an existing time ([initialMillis]) it goes straight to the summary. [callback] gets the picked time in epoch
 * milliseconds and how often it repeats ([initialRepeat] to start with). [forReminder] picks the time of a message
 * reminder instead: no repeat row and no word about sending.
 */
class ScheduleMessageDialog(
    private val activity: BaseSimpleActivity,
    initialMillis: Long? = null,
    initialRepeat: RepeatRule = RepeatRule.NONE,
    forReminder: Boolean = false,
    private val callback: (millis: Long?, repeat: RepeatRule) -> Unit,
) {
    init {
        activity.showComposeDialog { dismiss ->
            ScheduleFlow(
                activity = activity,
                initial = initialMillis?.let {
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                },
                initialRepeat = initialRepeat,
                forReminder = forReminder,
                onConfirm = { picked, repeat ->
                    callback(picked.toEpochMillis(), repeat)
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }
    }
}

/** This wall-clock time on the phone, in epoch milliseconds. */
private fun LocalDateTime.toEpochMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun nextHour(): Int = (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) + 1).coerceIn(0, LAST_HOUR)

private const val MINUTE_STEP = 5
private const val LAST_HOUR = 23
private const val LAST_MINUTE = 59

private fun nextMinute(): Int {
    val minute = Calendar.getInstance().get(Calendar.MINUTE) + MINUTE_STEP
    return minute.roundToClosestMultipleOf(MINUTE_STEP).coerceIn(0, LAST_MINUTE)
}

/** [picked] at the time of [current], or for a new message ([current] null) at the next hour and a round minute. */
private fun withPickedDate(picked: LocalDate, current: LocalDateTime?): LocalDateTime {
    val base = current ?: LocalDateTime.now().withHour(nextHour()).withMinute(nextMinute())
    return LocalDateTime.of(picked, base.toLocalTime().withSecond(0).withNano(0))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeStep(
    initial: LocalDateTime,
    isFuture: (LocalDateTime) -> Boolean,
    activity: BaseSimpleActivity,
    onCancel: () -> Unit,
    onConfirm: (LocalDateTime) -> Unit,
) {
    val futureError = stringResource(R.string.must_pick_time_in_the_future)
    val timeState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = DateFormat.is24HourFormat(activity),
    )
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.screens_schedule_pick_time)) },
        text = {
            Column(Modifier.fillMaxWidth().dialogScroll(), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = timeState)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val candidate = initial.withHour(timeState.hour).withMinute(timeState.minute)
                        .withSecond(0).withNano(0)
                    // Stay on the time picker, like before: the time must still be in the future.
                    if (isFuture(candidate)) onConfirm(candidate) else activity.toast(futureError)
                },
            ) { Text(stringResource(R.string.screens_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.screens_cancel)) }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewStep(
    dateTime: LocalDateTime,
    repeat: RepeatRule,
    isNew: Boolean,
    forReminder: Boolean,
    style: DateStyle,
    locale: Locale,
    activity: BaseSimpleActivity,
    isFuture: (LocalDateTime) -> Boolean,
    onPreset: (LocalDateTime) -> Unit,
    onOpen: (Step) -> Unit,
    onConfirm: (LocalDateTime, RepeatRule) -> Unit,
    onDismiss: () -> Unit,
) {
    val futureError = stringResource(R.string.must_pick_time_in_the_future)
    val dateText = fullDateLabel(
        dateTime.toLocalDate(),
        locale,
        jalali = style != DateStyle.GREGORIAN,
        both = style == DateStyle.BOTH,
    )
    val timeText = DateUtils.formatDateTime(activity, dateTime.toEpochMillis(), DateUtils.FORMAT_SHOW_TIME)
    val titleRes = if (forReminder) R.string.reminder_dialog_title else R.string.schedule_message
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(Modifier.dialogScroll(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isNew) {
                    val now = LocalDateTime.now()
                    val presets = listOf(
                        R.string.thread_review_schedule_in_hour to now.plusHours(1).withSecond(0).withNano(0),
                        R.string.thread_review_schedule_evening to now.toLocalDate().atTime(18, 0),
                        R.string.thread_review_schedule_tomorrow to now.toLocalDate().plusDays(1).atTime(9, 0),
                    ).filter { isFuture(it.second) }
                    // B5: the presets wrap as whole buttons; a label never breaks inside a word.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        presets.forEach { (label, value) ->
                            TextButton(onClick = { onPreset(value) }) {
                                Text(
                                    stringResource(label),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
                if (!forReminder) {
                    Text(
                        stringResource(R.string.schedule_send_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                PickerRow(Symbols.CalendarMonth, stringResource(R.string.screens_schedule_date), dateText) {
                    onOpen(Step.Date)
                }
                PickerRow(Symbols.Schedule, stringResource(R.string.screens_schedule_time), timeText) {
                    onOpen(Step.Time)
                }
                if (!forReminder) {
                    PickerRow(
                        Symbols.ScheduleSend,
                        stringResource(R.string.schedule_repeat_label),
                        stringResource(repeatChoiceRes(repeat)),
                    ) { onOpen(Step.Repeat) }
                }
                if (repeat != RepeatRule.NONE && style != DateStyle.GREGORIAN) {
                    Text(
                        stringResource(R.string.schedule_repeat_jalali_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isFuture(dateTime)) onConfirm(dateTime, repeat) else activity.toast(futureError)
                },
            ) { Text(stringResource(R.string.screens_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screens_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleFlow(
    activity: BaseSimpleActivity,
    initial: LocalDateTime?,
    initialRepeat: RepeatRule,
    forReminder: Boolean,
    onConfirm: (LocalDateTime, RepeatRule) -> Unit,
    onDismiss: () -> Unit,
) {
    val isNew = initial == null
    var repeat by remember { mutableStateOf(initialRepeat) }
    var dateTime by remember { mutableStateOf(initial ?: LocalDateTime.now().plusHours(1).withSecond(0).withNano(0)) }
    // TH-25: a new message starts at the summary with quick presets; the calendar and clock are one tap away.
    var step by remember { mutableStateOf(Step.Preview) }
    // A new message walks date -> time -> summary; once the date is set it is no longer "new" for the date picker.
    var walking by remember { mutableStateOf(false) }
    val futureError = stringResource(R.string.must_pick_time_in_the_future)
    val isFuture = { value: LocalDateTime -> value.toEpochMillis() > System.currentTimeMillis() }
    val style = remember { activity.w3cPrefs.dateStyle }
    val locale = LocalConfiguration.current.locales[0]

    when (step) {
        Step.Date -> DateStep(
            initialDate = dateTime.toLocalDate(),
            walking = walking,
            style = style,
            locale = locale,
            onCancel = { if (walking) onDismiss() else step = Step.Preview },
            onConfirm = { picked ->
                dateTime = withPickedDate(picked, dateTime.takeUnless { walking })
                if (walking) {
                    step = Step.Time
                } else {
                    if (!isFuture(dateTime)) activity.toast(futureError)
                    step = Step.Preview
                }
            },
        )

        Step.Time -> TimeStep(
            initial = dateTime,
            isFuture = isFuture,
            activity = activity,
            onCancel = { if (walking) onDismiss() else step = Step.Preview },
            onConfirm = {
                dateTime = it
                walking = false
                step = Step.Preview
            },
        )

        Step.Repeat -> RepeatStep(
            selected = repeat,
            onSelect = {
                repeat = it
                step = Step.Preview
            },
            onDismiss = { step = Step.Preview },
        )

        Step.Preview -> PreviewStep(
            dateTime = dateTime,
            repeat = repeat,
            isNew = isNew,
            forReminder = forReminder,
            style = style,
            locale = locale,
            activity = activity,
            isFuture = isFuture,
            onPreset = { dateTime = it },
            onOpen = { step = it },
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

/** The label of [rule] in the repeat choices. */
internal fun repeatChoiceRes(rule: RepeatRule): Int = when (rule) {
    RepeatRule.NONE -> R.string.schedule_repeat_never
    RepeatRule.DAILY -> R.string.schedule_repeat_daily
    RepeatRule.WEEKLY -> R.string.schedule_repeat_weekly
    RepeatRule.MONTHLY -> R.string.schedule_repeat_monthly
    RepeatRule.YEARLY -> R.string.schedule_repeat_yearly
}

/** Never (the default), every day, week, month or year. */
@Composable
private fun RepeatStep(selected: RepeatRule, onSelect: (RepeatRule) -> Unit, onDismiss: () -> Unit) {
    val rules = RepeatRule.entries
    ChoiceDialog(
        options = rules.map { stringResource(repeatChoiceRes(it)) },
        selectedIndex = rules.indexOf(selected),
        onSelect = { onSelect(rules[it]) },
        onDismiss = onDismiss,
        title = stringResource(R.string.schedule_repeat_label),
    )
}

/**
 * TH-24, TH-26: the app's own calendar, Jalali when the date style asks for it (the Gregorian date under the month
 * with "Both"), sized to the month, so no blank band. Past days are faded and cannot be picked.
 */
@Composable
private fun DateStep(
    initialDate: LocalDate,
    walking: Boolean,
    style: DateStyle,
    locale: Locale,
    onCancel: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val today = remember { LocalDate.now() }
    var picked by remember { mutableStateOf(maxOf(initialDate, today)) }
    val jalali = style != DateStyle.GREGORIAN
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.thread_r2_schedule_pick_date)) },
        text = {
            Column(Modifier.dialogScroll()) {
                ScheduleCalendar(
                    selected = picked,
                    minDate = today,
                    jalali = jalali,
                    locale = locale,
                    onSelect = { picked = it },
                    subtitle = fullDateLabel(picked, locale, jalali = true, both = true)
                        .takeIf { style == DateStyle.BOTH },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) {
                Text(stringResource(if (walking) R.string.screens_schedule_next else R.string.screens_ok))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.screens_cancel)) } },
    )
}

@Composable
private fun PickerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
