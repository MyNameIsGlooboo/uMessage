package com.glooboo.umessage.ui.spam

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.ui.components.SelectionTopBar
import com.glooboo.umessage.ui.icons.Symbols

/** The chosen ids after a tap on [id]: in when it was out, out when it was in. */
internal fun toggleMessageSelection(selected: Set<Long>, id: Long): Set<Long> =
    if (id in selected) selected - id else selected + id

/** Of the [selected] ids only those still in [messages]: a message that left (moved, deleted, purged) drops out. */
internal fun presentSelection(messages: List<BlockedMessage>, selected: Set<Long>): Set<Long> {
    if (selected.isEmpty()) return selected
    return messages.mapTo(mutableSetOf()) { it.id }.intersect(selected)
}

/** The chosen messages that "Move to inbox" can restore (an empty picture message has nothing to restore). */
internal fun releasableSelection(messages: List<BlockedMessage>, selected: Set<Long>): Set<Long> =
    messages.filter { it.id in selected && it.canRestoreContent }.mapTo(mutableSetOf()) { it.id }

/** The top bar while messages are chosen: Select all, Move to inbox and Delete for the chosen ones. */
@Composable
internal fun BlockedSelectionBar(
    messages: List<BlockedMessage>,
    selection: Set<Long>,
    actions: BlockedThreadActions,
    onChosenChange: (Set<Long>) -> Unit,
) {
    val releasable = releasableSelection(messages, selection)
    SelectionTopBar(count = selection.size, onClose = { onChosenChange(emptySet()) }) {
        if (selection.size < messages.size) {
            IconButton(onClick = { onChosenChange(messages.mapTo(mutableSetOf()) { it.id }) }) {
                Icon(
                    Symbols.SelectAll,
                    contentDescription = stringResource(R.string.spam_action_select_all)
                )
            }
        }
        if (releasable.isNotEmpty()) {
            IconButton(onClick = { actions.onReleaseMessages(releasable); onChosenChange(emptySet()) }) {
                Icon(Symbols.Inbox, contentDescription = stringResource(R.string.blocked_action_move_inbox))
            }
        }
        IconButton(onClick = { actions.onDeleteMessages(selection); onChosenChange(emptySet()) }) {
            Icon(Symbols.Delete, contentDescription = stringResource(R.string.spam_action_delete))
        }
    }
}
