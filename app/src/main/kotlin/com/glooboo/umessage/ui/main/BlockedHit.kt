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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.ui.components.rememberCodeHidden
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.spam.BlockedFilters
import com.glooboo.umessage.ui.theme.contentDirection

/** main-22: a sender of blocked (spam) messages with at least one message that holds the typed text. */
internal data class BlockedHit(
    /** The sender as the newest matching message wrote it; opens that sender's blocked thread. */
    val address: String,
    /** The sender as shown (a number in the phone's grouping). */
    val title: String,
    /** The newest matching message, cut around the match. */
    val snippet: String,
    val date: String,
    /** How many of the sender's blocked messages match. */
    val count: Int,
)

/**
 * One hit per sender from the held [messages] that answer [query] (text or sender), newest first. Messages of a sender
 * whose key is in [hiddenSenderKeys] are left out: a sender who has a chat hidden behind the app lock is not revealed
 * through the spam section either. [keyOf] says which addresses are one sender (see SenderKeys).
 */
internal fun buildBlockedHits(
    messages: List<BlockedMessage>,
    query: String,
    keyOf: (String) -> String,
    hiddenSenderKeys: Set<String>,
    title: (String) -> String,
    formatDate: (Long) -> String,
): List<BlockedHit> = messages
    .filter { BlockedFilters.matchesQuery(it, query) }
    .groupBy { keyOf(it.address) }
    .filterKeys { it !in hiddenSenderKeys }
    .values
    .map { list -> list.sortedByDescending { it.receivedAt } }
    .sortedByDescending { it.first().receivedAt }
    .map { list ->
        val newest = list.first()
        BlockedHit(
            address = newest.address,
            title = title(newest.address),
            snippet = if (newest.hasText) searchExcerpt(newest.body, query) else "",
            date = formatDate(newest.receivedAt),
            count = list.size,
        )
    }

/** The blocked hits the page lists: only while text is typed and no tile filter is on, like the notes. */
internal fun MainSearchState.shownBlockedHits(typed: Boolean): List<BlockedHit> =
    if (typed && filter == InboxFilter.All) blockedHits else emptyList()

/** The "Blocked messages" section: heading, then one card with a row per sender. */
internal fun LazyListScope.blockedSection(
    page: ResultPage<BlockedHit>,
    query: String,
    onSeeAll: () -> Unit,
    onOpen: (String) -> Unit,
    heading: String,
) {
    if (page.items.isEmpty()) return
    item(key = "h-blocked") { SectionHeading(heading, page.hidden > 0, onSeeAll) }
    items(page.items.size, key = { "b-" + page.items[it].address }) { index ->
        BlockedResultRow(page.items[index], query, onOpen, Modifier.sectionCardRow(index, page.items.size))
    }
}

/** A blocked sender: a shield, the sender with the match marked, the date, the matching text and how many matched. */
@Composable
internal fun BlockedResultRow(
    hit: BlockedHit,
    query: String,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val highlight = SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpen(hit.address) }
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Symbols.Warning, contentDescription = null, tint = colors.error, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    highlightedText(hit.title, query, highlight),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    hit.date,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (hit.snippet.isNotEmpty()) {
                Text(
                    highlightedText(rememberCodeHidden(hit.snippet), query, highlight),
                    style = MaterialTheme.typography.bodyMedium.contentDirection(),
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hit.count > 1) {
                Text(
                    pluralStringResource(R.plurals.w2b1_search_matches, hit.count, hit.count),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                )
            }
        }
    }
}
