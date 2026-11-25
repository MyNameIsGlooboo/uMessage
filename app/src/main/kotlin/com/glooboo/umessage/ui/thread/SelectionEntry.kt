package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols

/** One row of the selection bar's menu. */
data class SelectionEntry(val label: Int, val action: SelectionAction)

/** The leading icon of a selection menu row. */
internal fun selectionEntryIcon(action: SelectionAction): ImageVector = when (action) {
    SelectionAction.Copy -> Symbols.ContentCopy
    SelectionAction.Share -> Symbols.Upload
    SelectionAction.SaveAs -> Symbols.Download
    SelectionAction.Forward -> Symbols.Forward
    SelectionAction.Details -> Symbols.Info
    SelectionAction.Delete -> Symbols.Delete
    SelectionAction.Restore -> Symbols.RestoreFromTrash
    SelectionAction.ReadAloud -> ThreadIcons.VolumeUp
    SelectionAction.Star -> Symbols.Keep
    SelectionAction.Remind -> Symbols.Notifications
    SelectionAction.SaveText -> Symbols.Keep
    SelectionAction.Note -> Symbols.Edit
}

/**
 * The selection menu's rows: Share, Details, the save rows, Note, Restore (recycle bin), Read aloud and Remind me.
 * [noteLabel] is the note row's label (see [noteMenuLabel]), null when the row is not offered.
 */
fun selectionEntries(
    hasText: Boolean,
    single: Boolean,
    canSave: Boolean,
    readAloudOk: Boolean,
    readAloudLabel: Int,
    isRecycleBin: Boolean,
    noteLabel: Int? = null,
): List<SelectionEntry> {
    fun pick(vararg entries: Triple<Boolean, Int, SelectionAction>) =
        entries.filter { it.first }.map { SelectionEntry(it.second, it.third) }
    return pick(
        Triple(hasText, R.string.thread_action_share, SelectionAction.Share),
        Triple(single, R.string.thread_action_details, SelectionAction.Details),
        Triple(canSave, R.string.thread_action_save_as, SelectionAction.SaveAs),
        Triple(single && hasText && !isRecycleBin, R.string.n06_save_from_message, SelectionAction.SaveText),
        Triple(noteLabel != null, noteLabel ?: R.string.add_note, SelectionAction.Note),
        Triple(isRecycleBin, R.string.thread_action_restore, SelectionAction.Restore),
        Triple(readAloudOk, readAloudLabel, SelectionAction.ReadAloud),
        Triple(single && !isRecycleBin, R.string.reminder_action, SelectionAction.Remind),
    )
}

/** The message rows of the selection bar's menu, then Select all. */
@Composable
internal fun SelectionMenuRows(
    entries: List<SelectionEntry>,
    actions: ThreadActions,
    onSelectAll: () -> Unit,
    close: () -> Unit,
) {
    val row = @Composable { entry: SelectionEntry ->
        DropdownMenuItem(
            text = { Text(stringResource(entry.label)) },
            leadingIcon = { Icon(selectionEntryIcon(entry.action), contentDescription = null) },
            onClick = {
                close()
                actions.onSelectionAction(entry.action)
            },
        )
    }
    Column {
        entries.forEach { row(it) }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.thread_action_select_all)) },
            leadingIcon = { Icon(Symbols.SelectAll, contentDescription = null) },
            onClick = {
                close()
                onSelectAll()
            },
        )
    }
}
