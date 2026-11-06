package com.glooboo.umessage.ui.main

import androidx.compose.runtime.Immutable
import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.ui.conversations.ConversationRow

/** The results list shows this many conversations first; "Show more" adds the rest. */
internal const val RESULTS_FIRST = 20

/** The recent searches on the empty search page. */
internal const val RECENT_SHOWN = 5

/** One row of the results: a conversation, with the best match inside it as [snippet]. */
@Immutable
internal data class ConversationHitRow(
    val threadId: Long,
    val title: String,
    val snippet: String,
    val date: String,
    val photoUri: String,
    /** What a tap opens: the best message hit of the thread, else the conversation itself. */
    val open: SearchResult,
    /** The inbox row of the conversation (unread count, group icon); null for an archived one. */
    val row: ConversationRow?,
)

/**
 * One row per conversation from the ranked [results]: the hits of a thread merge into the first one, but a message hit
 * wins over a hit on the title or number, because its excerpt says more. Order follows the ranking.
 */
internal fun conversationHits(results: List<SearchResult>, rows: List<ConversationRow>): List<ConversationHitRow> {
    val byThread = rows.associateBy { it.threadId }
    val best = LinkedHashMap<Long, SearchResult>()
    results.forEach { hit ->
        val seen = best[hit.threadId]
        if (seen == null || (seen.messageId == CONVERSATION_HIT && hit.messageId != CONVERSATION_HIT)) {
            best[hit.threadId] = hit
        }
    }
    return best.values.map { hit ->
        val row = byThread[hit.threadId]
        ConversationHitRow(
            threadId = hit.threadId,
            title = row?.title ?: hit.title,
            snippet = hit.snippet,
            date = hit.date,
            photoUri = row?.photoUri ?: hit.photoUri,
            open = hit,
            row = row,
        )
    }
}

/** The conversations of a tile that is on and has no text typed yet: each with its newest message as the snippet. */
internal fun conversationRows(rows: List<ConversationRow>): List<ConversationHitRow> = rows.map { row ->
    ConversationHitRow(row.threadId, row.title, row.snippet, row.dateLabel, row.photoUri, conversationHit(row), row)
}

/** What a page of results holds: the first [shown] items, and how many more "Show more" would add. */
internal class ResultPage<T>(val items: List<T>, val hidden: Int)

internal fun <T> resultPage(all: List<T>, shown: Int = RESULTS_FIRST): ResultPage<T> =
    ResultPage(all.take(shown), (all.size - shown).coerceAtLeast(0))

/** True while the page lists conversations (a tile is on or text is typed) instead of the tiles. */
internal fun MainSearchState.showsResults(): Boolean = filter != InboxFilter.All || isSearchable(query)

/** True while a tile (or a SIM) is on: the first Back clears it, and only a query alone closes search at once. */
internal fun MainSearchState.hasTileOn(): Boolean = filter != InboxFilter.All

/** Back from the results: the tile and the text go, and the tile grid shows again. */
internal fun MainSearchState.backToTiles(onQueryChange: (String) -> Unit) {
    filter = InboxFilter.All
    onQueryChange("")
}

/** The recent searches the empty page lists. */
internal fun shownRecent(recent: List<String>): List<String> = recent.take(RECENT_SHOWN)
