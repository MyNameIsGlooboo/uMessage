package com.glooboo.umessage.ui.conversations

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.SelectionTopBar
import com.glooboo.umessage.ui.icons.Symbols

/**
 * Contextual action bar for multi-select on the main list. It is the shared [SelectionTopBar] (the same bar as
 * Archive, Recycle bin and the other selectable lists) with the list's actions; the bar applies the status bar
 * inset itself.
 */
@Composable
fun ConversationsSelectionBarRoute(viewModel: ConversationsViewModel, callbacks: ConversationsCallbacks) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ConversationsSelectionBar(selection = state.selection, callbacks = callbacks)
}

@Composable
fun ConversationsSelectionBar(
    selection: SelectionActions,
    callbacks: ConversationsCallbacks,
    modifier: Modifier = Modifier,
    /** I29: opens the label picker for the selected rows; no Labels entry while null. */
    onLabels: (() -> Unit)? = null,
    /** Select all of the rows on screen (a filter may hide some); the whole list by default. */
    onSelectAll: () -> Unit = callbacks.onSelectAll,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var blockDialog by rememberSaveable { mutableStateOf(false) }
    var renameDialog by rememberSaveable { mutableStateOf(false) }

    SelectionTopBar(count = selection.count, onClose = callbacks.onClearSelection, modifier = modifier) {
        // CL-22: Select all is on the bar itself, not buried in the menu (Pin moved there to make room).
        if (!selection.allSelected) {
            IconButton(onClick = onSelectAll) {
                Icon(Symbols.SelectAll, contentDescription = stringResource(R.string.convlist_select_all))
            }
        }
        if (selection.canArchive) {
            IconButton(onClick = callbacks.onArchiveSelected) {
                Icon(Symbols.Archive, contentDescription = stringResource(R.string.convlist_archive))
            }
        }
        // U35: the most used actions sit in the bar, Archive and Delete (undoable from the snackbar); Block is in the
        // menu, next to "Block and archive".
        IconButton(onClick = callbacks.onDeleteSelected) {
            Icon(Symbols.Delete, contentDescription = stringResource(R.string.convlist_delete))
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Symbols.MoreVert, contentDescription = stringResource(R.string.convlist_more_options))
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.widthIn(min = 200.dp, max = 280.dp),
            ) {
                // CL-18/19/21: grouped by purpose with dividers.
                val groups = selectionMenuGroups(
                    selection, callbacks, onRename = { renameDialog = true }, onBlock = { blockDialog = true },
                    onLabels,
                )
                groups.forEachIndexed { index, entries ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    entries.forEach { entry ->
                        val error = MaterialTheme.colorScheme.error
                        val tint = if (entry.destructive) error else MaterialTheme.colorScheme.onSurfaceVariant
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        stringResource(entry.label),
                                        color = if (entry.destructive) error else Color.Unspecified,
                                    )
                                    entry.hint?.let {
                                        Text(
                                            stringResource(it),
                                            modifier = Modifier.padding(top = 2.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            leadingIcon = { Icon(entry.icon, contentDescription = null, tint = tint) },
                            onClick = {
                                menuOpen = false
                                entry.action()
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                            // Owner note R3: a row with a hint line ("Block and archive") is two or three lines tall;
                            // the default padding is horizontal only, so its text touched the dividers above and below.
                            contentPadding = if (entry.hint != null) {
                                HintedItemPadding
                            } else {
                                MenuDefaults.DropdownMenuItemContentPadding
                            },
                        )
                    }
                }
            }
        }
    }

    if (blockDialog) {
        val numbers = selection.numbers.joinToString(", ")
        AlertDialog(
            onDismissRequest = { blockDialog = false },
            title = { Text(stringResource(R.string.convlist_block_title)) },
            text = { Text(stringResource(R.string.convlist_block_message, numbers)) },
            confirmButton = {
                TextButton(onClick = {
                    blockDialog = false
                    callbacks.onBlockSelected()
                }) { Text(stringResource(R.string.convlist_block_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { blockDialog = false }) { Text(stringResource(R.string.convlist_cancel)) }
            },
        )
    }
    if (renameDialog) {
        RenameDialog(
            initial = selection.renameInitial,
            onConfirm = {
                renameDialog = false
                callbacks.onRename(it)
            },
            onDismiss = { renameDialog = false },
        )
    }
}

/** The menu's own 12dp sides, plus room above and below a row whose label has a hint line under it. */
private val HintedItemPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)

/** The overflow menu of the selection bar, in groups (empty groups left out). */
private fun selectionMenuGroups(
    selection: SelectionActions,
    callbacks: ConversationsCallbacks,
    onRename: () -> Unit,
    onBlock: () -> Unit,
    onLabels: (() -> Unit)? = null,
): List<List<MenuEntry>> = listOf(
    buildList {
        if (onLabels != null) add(MenuEntry(R.string.i29_menu_labels, Symbols.Tag, onLabels))
        if (selection.canPin) {
            add(MenuEntry(R.string.convlist_pin, Symbols.Keep, { callbacks.onPin(true) }))
        } else if (selection.canUnpin) {
            add(MenuEntry(R.string.convlist_unpin, Symbols.KeepOff, { callbacks.onPin(false) }))
        }
        if (selection.canMarkRead) {
            add(MenuEntry(R.string.convlist_mark_read, Symbols.DoneAll, { callbacks.onMarkRead(true) }))
        }
        if (selection.canMarkUnread) {
            add(MenuEntry(R.string.convlist_mark_unread, Symbols.Sms, { callbacks.onMarkRead(false) }))
        }
    },
    buildList {
        if (selection.canBlock) add(MenuEntry(R.string.ux8a_selection_block, Symbols.Block, onBlock))
        if (selection.canReportSpam) {
            add(
                MenuEntry(
                    R.string.convx_block_archive, Symbols.Shield, callbacks.onReportSpamSelected,
                    hint = R.string.convx_block_archive_hint,
                ),
            )
        }
    },
    buildList {
        if (selection.canDial) add(MenuEntry(R.string.convlist_dial, Symbols.Call, callbacks.onDial))
        if (selection.canCopyNumber) {
            add(MenuEntry(R.string.convlist_copy_number, Symbols.ContentCopy, callbacks.onCopyNumber))
        }
        if (selection.canAddContact) {
            add(MenuEntry(R.string.convlist_add_contact, Symbols.PersonAdd, callbacks.onAddContact))
        }
        if (selection.canRename) add(MenuEntry(R.string.convlist_rename, Symbols.Edit, onRename))
        if (selection.canShowDetails) add(MenuEntry(R.string.convlist_details, Symbols.Info, callbacks.onDetails))
    },
).filter { it.isNotEmpty() }

/** One row of the overflow menu. */
private class MenuEntry(
    val label: Int,
    val icon: ImageVector,
    val action: () -> Unit,
    val hint: Int? = null,
    val destructive: Boolean = false,
)

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    RenameConversationAlert(
        title = stringResource(R.string.convlist_rename_title),
        initial = initial,
        placeholder = initial,
        hint = stringResource(R.string.rename_conversation_warning),
        onSave = onConfirm,
        onDismiss = onDismiss,
    )
}
