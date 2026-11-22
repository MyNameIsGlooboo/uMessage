package com.glooboo.umessage.ui.thread

import android.app.Activity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.showComposeDialog

/**
 * Shown before scheduling when "Alarms & reminders" is off (the default on Android 14+). It never blocks
 * scheduling: [onOpenSettings] opens the system setting, [onScheduleAnyway] schedules with an inexact alarm.
 * Dismissing the dialog does neither, like a cancel.
 */
@Composable
fun ExactAlarmDialog(onOpenSettings: () -> Unit, onScheduleAnyway: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_exact_alarm_title)) },
        text = { Text(stringResource(R.string.schedule_exact_alarm_text)) },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.schedule_exact_alarm_open_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onScheduleAnyway) { Text(stringResource(R.string.schedule_exact_alarm_anyway)) }
        },
    )
}

/**
 * [onClosedUnanswered] runs when the dialog goes without the user's answer: the chat closed it (K2-6, left under the
 * app lock or another chat loaded in place), so the offer can be made again next time.
 */
fun Activity.showExactAlarmDialog(
    onOpenSettings: () -> Unit,
    onScheduleAnyway: () -> Unit,
    onClosedUnanswered: () -> Unit = {},
) {
    showComposeDialog { dismiss ->
        val answered = remember { booleanArrayOf(false) }
        DisposableEffect(Unit) { onDispose { if (!answered[0]) onClosedUnanswered() } }
        ExactAlarmDialog(
            onOpenSettings = {
                answered[0] = true
                dismiss()
                onOpenSettings()
            },
            onScheduleAnyway = {
                answered[0] = true
                dismiss()
                onScheduleAnyway()
            },
            onDismiss = {
                answered[0] = true
                dismiss()
            },
        )
    }
}
