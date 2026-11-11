package com.glooboo.umessage.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.ui.conversations.ConversationRow

/**
 * I28: what a tile that needs more than the loaded rows found, for [filter]. [threads] are the conversations the inbox
 * keeps; [hits] are the messages the search page lists (empty for Unknown, which lists conversations). A star whose
 * chat is gone is in [goneThreads]: only its saved text is left. [loaded] is false until the lookup answered.
 */
@Immutable
data class ShortcutHits(
    val filter: InboxFilter = InboxFilter.All,
    val threads: Set<Long> = emptySet(),
    val hits: List<SearchResult> = emptyList(),
    val goneThreads: Set<Long> = emptySet(),
    val loaded: Boolean = false,
)

/** Looks the tile of [filter] up again when it or the rows change, and when the app comes back to the front. */
@Composable
internal fun rememberShortcutHits(filter: InboxFilter, rows: List<ConversationRow>): ShortcutHits {
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    // Pin, mark-read and other row edits do not change what the lookups find: key on what they do read.
    val signature = remember(rows) { rows.map { Triple(it.threadId, it.snippet, it.dateLabel) } }
    val state = produceState(ShortcutHits(), filter, signature, resumes) {
        value = if (InboxFilterPolicy.needsLookup(filter)) {
            SearchShortcuts.load(context, filter, rows)
        } else {
            ShortcutHits(filter, loaded = true)
        }
    }
    return state.value.takeIf { it.filter == filter } ?: ShortcutHits(filter)
}
