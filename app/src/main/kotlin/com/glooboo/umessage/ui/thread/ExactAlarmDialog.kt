package com.glooboo.umessage.ui.thread

import android.app.Activity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

fun Activity.showExactAlarmDialog(onOpenSettings: () -> Unit, onScheduleAnyway: () -> Unit) {
    showComposeDialog { dismiss ->
        ExactAlarmDialog(
            onOpenSettings = {
                dismiss()
                onOpenSettings()
            },
            onScheduleAnyway = {
                dismiss()
                onScheduleAnyway()
            },
            onDismiss = dismiss,
        )
    }
}
