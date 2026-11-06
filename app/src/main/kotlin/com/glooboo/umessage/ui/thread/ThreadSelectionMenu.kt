package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    SelectionAction.SelectText -> Symbols.TextFields
    SelectionAction.Details -> Symbols.Info
    SelectionAction.Delete -> Symbols.Delete
    SelectionAction.Restore -> Symbols.RestoreFromTrash
    SelectionAction.ReadAloud -> ThreadIcons.VolumeUp
    SelectionAction.Star -> Symbols.Keep
    SelectionAction.Remind -> Symbols.Notifications
    SelectionAction.SaveText -> Symbols.Keep
}

/** The leading icon of a sender-decision row in the selection bar's menu. */
internal fun selectionMenuIcon(action: ThreadMenuAction): ImageVector = when (action) {
    ThreadMenuAction.AllowSender -> Symbols.Shield
    ThreadMenuAction.ReportSpam -> Symbols.Warning
    ThreadMenuAction.Block -> Symbols.Block
    ThreadMenuAction.AlwaysForward -> Symbols.ArrowForward
    ThreadMenuAction.WhyDecided -> Symbols.Rule
    else -> Symbols.Info
}

/** What the selection menu shows for the selected messages: the short list and what "More…" opens (U37). */
data class SelectionEntries(val main: List<SelectionEntry>, val more: List<SelectionEntry>)

/**
 * U37: Share, Select text, Message details and the save rows stay in the list; Read aloud and Remind me fold into
 * "More…". In the recycle bin Restore joins the list.
 */
fun selectionEntries(
    hasText: Boolean,
    single: Boolean,
    canSave: Boolean,
    readAloudOk: Boolean,
    readAloudLabel: Int,
    isRecycleBin: Boolean,
): SelectionEntries {
    fun pick(vararg entries: Triple<Boolean, Int, SelectionAction>) =
        entries.filter { it.first }.map { SelectionEntry(it.second, it.third) }
    return SelectionEntries(
        main = pick(
            Triple(hasText, R.string.thread_action_share, SelectionAction.Share),
            Triple(single && hasText, R.string.thread_action_select_text, SelectionAction.SelectText),
            Triple(single, R.string.thread_action_details, SelectionAction.Details),
            Triple(canSave, R.string.thread_action_save_as, SelectionAction.SaveAs),
            Triple(single && hasText && !isRecycleBin, R.string.n06_save_from_message, SelectionAction.SaveText),
            Triple(isRecycleBin, R.string.thread_action_restore, SelectionAction.Restore),
        ),
        more = pick(
            Triple(readAloudOk, readAloudLabel, SelectionAction.ReadAloud),
            Triple(single && !isRecycleBin, R.string.reminder_action, SelectionAction.Remind),
        ),
    )
}

/** The message rows of the selection bar's menu, then "More…" (when it has rows) and Select all. */
@Composable
internal fun SelectionMenuRows(
    entries: SelectionEntries,
    actions: ThreadActions,
    onSelectAll: () -> Unit,
    close: () -> Unit,
) {
    var showMore by remember { mutableStateOf(false) }
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
        entries.main.forEach { row(it) }
        if (showMore) entries.more.forEach { row(it) }
        if (entries.more.isNotEmpty() && !showMore) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.thread_menu_more)) },
                leadingIcon = { Icon(Symbols.MoreVert, contentDescription = null) },
                onClick = { showMore = true },
            )
        }
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
