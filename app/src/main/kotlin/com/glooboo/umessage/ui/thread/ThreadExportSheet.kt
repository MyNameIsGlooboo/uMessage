package com.glooboo.umessage.ui.thread

import android.app.Activity
import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.dialogs.ExportChoiceRow
import com.glooboo.umessage.export.ConversationFileFormat
import com.glooboo.umessage.export.ExportRange
import com.glooboo.umessage.ui.components.showComposeDialog
import java.time.LocalDate

/** What the user asked for in the export sheet. */
data class ThreadExportOptions(val format: ConversationFileFormat, val range: ExportRange)

private enum class DateChoice { ALL, LAST_30_DAYS, PICK }

private const val RECENT_DAYS = 30

/**
 * The one question before a conversation is exported: PDF or plain text, and which dates. "Pick dates" then asks
 * for the first and the last day with the system date picker. [onChosen] gets the answer.
 */
class ThreadExportSheet(private val activity: Activity, private val onChosen: (ThreadExportOptions) -> Unit) {
    init {
        activity.showComposeDialog { dismiss ->
            var format by remember { mutableStateOf(ConversationFileFormat.PDF) }
            var dates by remember { mutableStateOf(DateChoice.ALL) }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(stringResource(R.string.export_conv_title)) },
                text = { SheetBody(format, { format = it }, dates, { dates = it }) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dismiss()
                            choose(format, dates)
                        },
                    ) { Text(stringResource(R.string.export_conv_action)) }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.screens_cancel)) } },
            )
        }
    }

    @Composable
    private fun SheetBody(
        format: ConversationFileFormat,
        onFormat: (ConversationFileFormat) -> Unit,
        dates: DateChoice,
        onDates: (DateChoice) -> Unit,
    ) {
        Column {
            Column(Modifier.selectableGroup()) {
                ExportChoiceRow(
                    stringResource(R.string.export_conv_format_pdf), format == ConversationFileFormat.PDF,
                    { onFormat(ConversationFileFormat.PDF) },
                )
                ExportChoiceRow(
                    stringResource(R.string.export_conv_format_text), format == ConversationFileFormat.TEXT,
                    { onFormat(ConversationFileFormat.TEXT) },
                )
            }
            Column(Modifier.selectableGroup().padding(top = 8.dp)) {
                ExportChoiceRow(
                    stringResource(R.string.export_conv_dates_all), dates == DateChoice.ALL,
                    { onDates(DateChoice.ALL) },
                )
                ExportChoiceRow(
                    stringResource(R.string.export_conv_dates_30), dates == DateChoice.LAST_30_DAYS,
                    { onDates(DateChoice.LAST_30_DAYS) },
                )
                ExportChoiceRow(
                    stringResource(R.string.export_conv_dates_pick), dates == DateChoice.PICK,
                    { onDates(DateChoice.PICK) },
                )
            }
            Text(
                text = stringResource(R.string.share_text_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }

    private fun choose(format: ConversationFileFormat, dates: DateChoice) {
        when (dates) {
            DateChoice.ALL -> onChosen(ThreadExportOptions(format, ExportRange.All))
            DateChoice.LAST_30_DAYS -> onChosen(ThreadExportOptions(format, ExportRange.LastDays(RECENT_DAYS)))
            DateChoice.PICK -> pickRange { from, to ->
                onChosen(ThreadExportOptions(format, ExportRange.Between(from, to)))
            }
        }
    }

    private fun pickRange(onPicked: (from: LocalDate, to: LocalDate) -> Unit) {
        val today = LocalDate.now()
        pickDay(R.string.export_conv_pick_from, today.minusDays(RECENT_DAYS.toLong())) { from ->
            pickDay(R.string.export_conv_pick_to, today) { to -> onPicked(from, to) }
        }
    }

    private fun pickDay(titleRes: Int, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
        val dialog = DatePickerDialog(
            activity,
            { _, year, month, day -> onPicked(LocalDate.of(year, month + 1, day)) },
            initial.year, initial.monthValue - 1, initial.dayOfMonth,
        )
        dialog.setTitle(titleRes)
        dialog.show()
    }
}
