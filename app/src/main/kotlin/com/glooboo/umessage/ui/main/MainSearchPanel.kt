package com.glooboo.umessage.ui.main

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.models.SearchResult
import org.fossify.commons.extensions.toast
import com.glooboo.umessage.ui.conversations.ContactAvatar
import com.glooboo.umessage.ui.conversations.ConversationRow
import com.glooboo.umessage.ui.conversations.RowLabel
import com.glooboo.umessage.ui.conversations.rememberRowLabels
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.contentDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The page under the open search bar. Empty: the tiles, one row of labels and a few recent searches. With a tile on or
 * text typed: one row per conversation, at once and without Enter.
 */

@Composable
internal fun SearchResultsPanel(
    search: MainSearchState,
    rows: List<ConversationRow>,
    shortcut: ShortcutHits,
    listState: LazyListState,
    recent: List<String>,
    simTiles: List<SimTile>,
    actions: SearchPanelActions,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val filter = search.filter
        val tileList = rememberShortcutList(filter, shortcut, actions.onOpen)
        val matching = remember(rows, filter, shortcut) {
            InboxFilterPolicy.filterRows(rows, filter, shortcut.threads)
        }
        val hits = remember(search.results, filter, matching) {
            InboxFilterPolicy.narrow(search.results, filter, matching)
        }
        val bottomPadding = PaddingValues(
            bottom = maxOf(
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                WindowInsets.ime.asPaddingValues().calculateBottomPadding(),
            ),
        )
        val typed = isSearchable(search.query)
        // Typed text with a tile on narrows that tile's list; a tile alone lists all of its conversations.
        val listed = remember(hits, matching, rows, typed) {
            if (typed) conversationHits(hits, rows) else conversationRows(matching)
        }
        SearchPanelBody(search, typed, listed, tileList, shortcut, listState, recent, simTiles, bottomPadding, actions)
    }
}

/**
 * The tiles, or the tile's messages, the conversations or what is missing; a tile that is on is a chip in the field.
 */
@Composable
private fun SearchPanelBody(
    search: MainSearchState,
    typed: Boolean,
    listed: List<ConversationHitRow>,
    tileList: ShortcutList,
    shortcut: ShortcutHits,
    listState: LazyListState,
    recent: List<String>,
    simTiles: List<SimTile>,
    bottomPadding: PaddingValues,
    actions: SearchPanelActions,
) {
    val filter = search.filter
    val query = search.query
    val labels = rememberRowLabels()
    val tileOnly = filter != InboxFilter.All && !typed
    val contacts = rememberContactHits(query, filter, typed)
    // Notes are searched in all messages, so a tile that narrows the list leaves them out, like the contacts.
    val notes = search.shownNoteHits(typed)
    Column(Modifier.fillMaxSize()) {
        when {
            filter == InboxFilter.All && !typed -> SearchTilesPage(search, simTiles, recent, bottomPadding, actions)
            tileOnly && InboxFilterPolicy.listsMessages(filter) ->
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = bottomPadding) {
                    shortcutItems(filter, tileList.shown, tileList.open)
                }
            listed.isEmpty() && tileOnly -> if (filter != InboxFilter.Sim || shortcut.loaded) {
                SearchFilterEmpty(filter)
            }
            noHits(listed, contacts, notes) -> SearchHint(stringResource(R.string.main_search_no_results))
            else -> ConversationResults(
                all = listed,
                contacts = contacts,
                notes = notes,
                query = query,
                labels = labels,
                listState = listState,
                bottomPadding = bottomPadding,
                resetKey = listOf(filter, search.labelId, search.simId, query),
                onOpen = actions.onOpen,
            )
        }
    }
}

/** The contacts that match [query]; only while text is typed and no tile filter is on. */
@Composable
private fun rememberContactHits(query: String, filter: InboxFilter, typed: Boolean): List<ContactHit> {
    val context = LocalContext.current
    val allContacts by rememberSearchContacts()
    return remember(allContacts, query, filter, typed) {
        if (typed && filter == InboxFilter.All) {
            matchContacts(allContacts, query) { type, label -> phoneTypeLabel(context, type, label) }
        } else {
            emptyList()
        }
    }
}

/** The empty page: the tile grid, one row of labels when there are any, and the last few searches. */
@Composable
private fun SearchTilesPage(
    search: MainSearchState,
    simTiles: List<SimTile>,
    recent: List<String>,
    bottomPadding: PaddingValues,
    actions: SearchPanelActions,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomPadding) {
        item(key = "tiles") {
            SearchFilterGrid(
                selected = search.filter,
                simTiles = simTiles,
                selectedSim = search.simId,
                onTap = { actions.onFilter(InboxFilterPolicy.afterTap(search.filter, it)) },
                onTapSim = actions.onSim,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item(key = "labels") { SearchLabelRow(search) }
        val shown = shownRecent(recent)
        if (shown.isNotEmpty()) recentItems(shown, actions.onPickRecent, actions.onClearRecent)
    }
}

/**
 * The results as sections, each a heading with "See all" over one rounded card: the conversations, then the contacts.
 * Each shows [SECTION_PREVIEW] rows until its "See all" is tapped. [resetKey] resets that.
 */
@Composable
private fun ConversationResults(
    all: List<ConversationHitRow>,
    contacts: List<ContactHit>,
    notes: List<NoteHit>,
    query: String,
    labels: Map<Long, List<RowLabel>>,
    listState: LazyListState,
    bottomPadding: PaddingValues,
    resetKey: Any,
    onOpen: (SearchResult) -> Unit,
) {
    var conversationsShown by remember(resetKey) { mutableIntStateOf(SECTION_PREVIEW) }
    var contactsShown by remember(resetKey) { mutableIntStateOf(SECTION_PREVIEW) }
    var notesShown by remember(resetKey) { mutableIntStateOf(SECTION_PREVIEW) }
    val conversationPage = remember(all, conversationsShown) { resultPage(all, conversationsShown) }
    val contactPage = remember(contacts, contactsShown) { resultPage(contacts, contactsShown) }
    val notePage = remember(notes, notesShown) { resultPage(notes, notesShown) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openContact = { hit: ContactHit ->
        scope.launch {
            // getOrCreateThreadId is a Telephony provider round trip: keep it off the main thread.
            val address = contactThreadAddress(hit.number)
            val threadId = withContext(Dispatchers.IO) {
                runCatching { context.getThreadId(address) }
                    .onFailure { Log.w("MainSearch", "Thread for a contact not made", it) }
                    .getOrDefault(0L)
            }
            if (threadId > 0L) {
                onOpen(contactHitResult(hit, threadId))
            } else {
                // Never a silent tap: the person is told, as in the new-conversation picker.
                context.toast(R.string.new_conversation_invalid_number, length = Toast.LENGTH_LONG)
            }
        }
        Unit
    }
    val conversationsTitle = stringResource(R.string.search_section_conversations)
    val contactsTitle = stringResource(R.string.search_section_contacts)
    val notesTitle = stringResource(R.string.notes_section)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = bottomPadding) {
        conversationSection(
            conversationPage, query, labels, { conversationsShown = Int.MAX_VALUE }, onOpen, conversationsTitle,
        )
        contactSection(contactPage, query, { contactsShown = Int.MAX_VALUE }, openContact, contactsTitle)
        noteSection(notePage, query, { notesShown = Int.MAX_VALUE }, onOpen, notesTitle)
    }
}

/** What the page says while a filter is on and no conversation matches it. */
@Composable
internal fun SearchFilterEmpty(filter: InboxFilter) {
    val tile = filterTile(filter)
    val titleRes = tile?.emptyTitleRes ?: when (filter) {
        InboxFilter.Label -> R.string.i29_empty_title
        InboxFilter.Sim -> R.string.search_page_sim_empty_title
        else -> return
    }
    val bodyRes = tile?.emptyBodyRes
        ?: if (filter == InboxFilter.Sim) R.string.search_page_sim_empty_body else R.string.i29_empty_body
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SearchHint(text: String) {
    Box(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** CL-14: the last few searches, newest first, to tap; "Clear" forgets them. */
private fun LazyListScope.recentItems(recent: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    item(key = "recent-title") {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.w2b1_search_recent),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            val clearDescription = stringResource(R.string.w2b1_search_recent_clear_description)
            TextButton(onClick = onClear, modifier = Modifier.semantics { contentDescription = clearDescription }) {
                Text(stringResource(R.string.w2b1_search_recent_clear))
            }
        }
    }
    items(recent, key = { "recent-$it" }) { query ->
        Row(
            Modifier.fillMaxWidth().clickable { onPick(query) }.heightIn(min = 48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Symbols.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(query, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One row per thread; [count] above 1 says how many of its messages matched (CL-15). */
@Composable
internal fun SearchResultRow(result: SearchResult, count: Int, query: String, onOpen: (SearchResult) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val highlight = SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(result) }
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ContactAvatar(title = result.title, photoUri = result.photoUri)
        Column(Modifier.weight(1f)) {
            // The time sits on the name line, as in the conversation list.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    highlightedText(result.title, query, highlight),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    result.date,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (result.snippet.isNotEmpty()) {
                Text(
                    highlightedText(result.snippet, query, highlight),
                    style = MaterialTheme.typography.bodyMedium.contentDirection(),
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (count > 1) {
                Text(
                    pluralStringResource(R.plurals.w2b1_search_matches, count, count),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                )
            }
        }
    }
}
