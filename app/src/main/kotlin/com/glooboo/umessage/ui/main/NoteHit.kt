package com.glooboo.umessage.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.MessageNote
import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.ui.conversations.ContactAvatar
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.contentDirection

/** A private note found by the search: the note, the chat it belongs to and the date of its message. */
internal data class NoteHit(
    val key: String,
    val threadId: Long,
    val title: String,
    /** The note text, cut around the match like a message excerpt. */
    val text: String,
    val date: String,
    val photoUri: String,
) {
    /** What a tap opens: the chat of the note (the note's message has no provider id to scroll to). */
    fun open() = SearchResult(CONVERSATION_HIT, title, "", date, threadId, photoUri)
}

/**
 * The note hits of [notes] (newest edit first, as given), each with the title and photo of its chat from
 * [conversations]. A note whose chat is not known (a restored note not bound yet) or no longer exists is left out,
 * because a tap could not open anything.
 */
internal fun buildNoteHits(
    notes: List<MessageNote>,
    conversations: Map<Long, Conversation>,
    query: String,
    formatDate: (Int) -> String,
    displayName: (String) -> String = { it },
): List<NoteHit> = notes.mapNotNull { note ->
    val chat = conversations[note.threadId]
    if (note.threadId == 0L || chat == null) {
        null
    } else {
        NoteHit(
            key = note.key,
            threadId = note.threadId,
            title = displayName(chat.title),
            text = searchExcerpt(note.text, query),
            date = formatDate(note.messageDate),
            photoUri = chat.photoUri,
        )
    }
}

/** The note hits the page lists: only while text is typed and no tile filter is on, like the contacts. */
internal fun MainSearchState.shownNoteHits(typed: Boolean): List<NoteHit> =
    if (typed && filter == InboxFilter.All) noteHits else emptyList()

/** True when no conversation, contact or note matched. */
internal fun noHits(listed: List<ConversationHitRow>, contacts: List<ContactHit>, notes: List<NoteHit>) =
    listed.isEmpty() && contacts.isEmpty() && notes.isEmpty()

/** The "Notes" section: heading, then one card with a row per matching note. */
internal fun LazyListScope.noteSection(
    page: ResultPage<NoteHit>,
    query: String,
    onSeeAll: () -> Unit,
    onOpen: (SearchResult) -> Unit,
    heading: String,
) {
    if (page.items.isEmpty()) return
    item(key = "h-notes") { SectionHeading(heading, page.hidden > 0, onSeeAll) }
    items(page.items.size, key = { "n-" + page.items[it].key }) { index ->
        NoteResultRow(page.items[index], query, onOpen, Modifier.sectionCardRow(index, page.items.size))
    }
}

/** A note: the chat's avatar, the note text with the match marked, the chat title and the message date below. */
@Composable
internal fun NoteResultRow(hit: NoteHit, query: String, onOpen: (SearchResult) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val highlight = SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpen(hit.open()) }
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ContactAvatar(title = hit.title, photoUri = hit.photoUri)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Symbols.Description,
                    contentDescription = stringResource(R.string.note),
                    tint = colors.tertiary,
                    modifier = Modifier.padding(top = 2.dp).size(16.dp),
                )
                Text(
                    highlightedText(hit.text, query, highlight),
                    style = MaterialTheme.typography.bodyLarge.contentDirection(),
                    color = colors.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    hit.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    hit.date,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
