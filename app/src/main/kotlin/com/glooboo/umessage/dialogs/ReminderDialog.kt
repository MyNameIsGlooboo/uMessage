package com.glooboo.umessage.dialogs

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.Oem
import com.glooboo.umessage.helpers.OemHelper
import com.glooboo.umessage.messaging.ReminderChoice
import com.glooboo.umessage.messaging.addMessageReminder
import com.glooboo.umessage.messaging.reminderTime
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.ui.components.showComposeDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * I51, "Remind me": asks when, then saves the reminder and sets its alarm. Without exact-alarm access the alarm is
 * inexact and the toast says the reminder may be a little late. Nothing is sent.
 */
fun askForReminder(activity: BaseSimpleActivity, message: Message) {
    if (message.isScheduled) return
    activity.showComposeDialog { dismiss ->
        ReminderChoices(
            onChoose = { choice ->
                dismiss()
                saveReminder(activity, message, reminderTime(choice, System.currentTimeMillis()))
            },
            onPickTime = {
                dismiss()
                ScheduleMessageDialog(activity, forReminder = true) { millis, _ ->
                    if (millis != null) saveReminder(activity, message, millis)
                }
            },
            onOpenBatterySettings = {
                OemHelper.steps(activity).firstOrNull()?.let { OemHelper.launch(activity, it) }
            },
            onDismiss = dismiss,
        )
    }
}

private fun saveReminder(activity: BaseSimpleActivity, message: Message, remindAt: Long) {
    ensureBackgroundThread {
        val (_, exact) = activity.addMessageReminder(message, remindAt)
        val time = DateUtils.formatDateTime(
            activity, remindAt, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL
        )
        activity.runOnUiThread {
            val text = if (exact) {
                activity.getString(R.string.reminder_set, time)
            } else {
                activity.getString(R.string.reminder_set_approximate, time)
            }
            activity.toast(text, Toast.LENGTH_LONG)
        }
    }
}

@Composable
private fun ReminderChoices(
    onChoose: (ReminderChoice) -> Unit,
    onPickTime: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reminder_dialog_title)) },
        text = {
            Column {
                ChoiceRow(R.string.reminder_in_3_hours) { onChoose(ReminderChoice.IN_THREE_HOURS) }
                ChoiceRow(R.string.reminder_tomorrow_morning) { onChoose(ReminderChoice.TOMORROW_MORNING) }
                ChoiceRow(R.string.reminder_pick_time, onPickTime)
                // Some phones stop apps in the background and delay alarms; the guide step opens the right setting.
                if (OemHelper.detect() != Oem.OTHER) {
                    Text(
                        stringResource(R.string.reminder_battery_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    TextButton(onClick = onOpenBatterySettings) {
                        Text(stringResource(R.string.reminder_battery_open))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screens_cancel)) } },
    )
}

@Composable
private fun ChoiceRow(label: Int, onClick: () -> Unit) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}
