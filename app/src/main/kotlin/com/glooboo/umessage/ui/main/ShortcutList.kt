package com.glooboo.umessage.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.StarredMessages
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.SearchResult
import kotlinx.coroutines.launch

/* I28, I13: the page of the Starred, Images and Links tiles: the messages they found, newest first. */

/** What the page shows for a message tile, and what a tap on one of its rows does. */
@Stable
internal class ShortcutList(val shown: ShortcutHits, val open: (SearchResult) -> Unit)

/**
 * A tap opens the chat at the message. A star whose chat is gone opens a dialog with the saved text instead, with
 * Unstar; the star then leaves the list at once and the next lookup agrees.
 */
@Composable
internal fun rememberShortcutList(
    filter: InboxFilter,
    shortcut: ShortcutHits,
    onOpen: (SearchResult) -> Unit,
): ShortcutList {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var unstarred by remember { mutableStateOf(emptySet<MessageKey>()) }
    var snapshot by remember { mutableStateOf<SearchResult?>(null) }
    snapshot?.let { hit ->
        StarredSnapshotDialog(
            hit,
            onUnstar = {
                val key = MessageKey(hit.messageId, hit.isMMS)
                unstarred = unstarred + key
                scope.launch { StarredMessages.unstar(context, listOf(key)) }
            },
            onDismiss = { snapshot = null },
        )
    }
    val shown = remember(shortcut, unstarred) {
        shortcut.copy(hits = shortcut.hits.filterNot { MessageKey(it.messageId, it.isMMS) in unstarred })
    }
    val open = { result: SearchResult ->
        val gone = filter == InboxFilter.Starred && result.threadId in shortcut.goneThreads
        if (gone) snapshot = result else onOpen(result)
    }
    return ShortcutList(shown, open)
}

internal fun LazyListScope.shortcutItems(
    filter: InboxFilter,
    shortcut: ShortcutHits,
    onOpen: (SearchResult) -> Unit,
) {
    when {
        !shortcut.loaded -> Unit
        shortcut.hits.isEmpty() -> item(key = "empty") { SearchFilterEmpty(filter) }
        else -> {
            items(shortcut.hits, key = { "s-${it.messageId}-${it.isMMS}" }) { hit ->
                SearchResultRow(hit, 1, "", onOpen)
            }
            if (filter == InboxFilter.Starred) item(key = "starred-note") { StarredNote() }
        }
    }
}

/** Says that a star keeps a copy of the text, so deleting a chat does not remove it. */
@Composable
private fun StarredNote() {
    Text(
        stringResource(R.string.i13_starred_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
    )
}

/** The saved text of a starred message whose chat was deleted, read only. */
@Composable
internal fun StarredSnapshotDialog(hit: SearchResult, onUnstar: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(hit.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(hit.snippet)
                Text(
                    stringResource(R.string.i13_snapshot_gone),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.i13_snapshot_close)) } },
        dismissButton = {
            TextButton(onClick = { onUnstar(); onDismiss() }) { Text(stringResource(R.string.i13_unstar)) }
        },
    )
}
