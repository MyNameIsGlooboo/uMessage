package com.glooboo.umessage.ui.main

import com.glooboo.umessage.helpers.MessageCategory
import com.glooboo.umessage.helpers.OtpCodeExtractor
import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.ui.conversations.ConversationRow

/**
 * N11: the inbox filters are picked with the tiles on the search page ([SearchFilterTile]); the inbox filter button and
 * its chip row are gone. One filter is on at a time. Leaving search keeps it, and the inbox then shows a single
 * removable chip so a filtered list never passes for a short inbox; with no filter nothing extra is shown.
 */
internal object InboxFilterPolicy {

    private val MESSAGE_FILTERS = setOf(InboxFilter.Starred, InboxFilter.Images, InboxFilter.Links)
    private val LOOKUP_FILTERS = MESSAGE_FILTERS + InboxFilter.Unknown

    /** I28: the filters that need a lookup beyond the rows ([ShortcutHits]); the others read the row itself. */
    fun needsLookup(filter: InboxFilter): Boolean = filter in LOOKUP_FILTERS

    /** I28: the filters whose messages the search page lists, not only the conversations holding them. */
    fun listsMessages(filter: InboxFilter): Boolean = filter in MESSAGE_FILTERS

    /**
     * Whether [row] belongs to [filter]. The first five read what the row already holds; Unknown, Starred, Images and
     * Links look the conversation up in [threads], found by [SearchShortcuts]; Label and Sim
     * (one SIM, see [SimFilter]) are given theirs.
     */
    fun matches(filter: InboxFilter, row: ConversationRow, threads: Set<Long> = emptySet()): Boolean = when (filter) {
        InboxFilter.All -> true
        InboxFilter.Unread -> row.unread
        InboxFilter.Codes -> row.otpCode != null || OtpCodeExtractor.extract(row.snippet) != null
        InboxFilter.Transactions -> row.category == MessageCategory.Transactions
        InboxFilter.Offers -> row.category == MessageCategory.Offers
        InboxFilter.Unknown, InboxFilter.Starred, InboxFilter.Images, InboxFilter.Links, InboxFilter.Label,
        InboxFilter.Sim -> row.threadId in threads
    }

    fun filterRows(
        rows: List<ConversationRow>,
        filter: InboxFilter,
        threads: Set<Long> = emptySet(),
    ): List<ConversationRow> =
        if (filter == InboxFilter.All) rows else rows.filter { matches(filter, it, threads) }

    /** Tapping a tile turns its filter on; tapping the tile that is on turns it off. */
    fun afterTap(current: InboxFilter, tapped: InboxFilter): InboxFilter =
        if (current == tapped) InboxFilter.All else tapped

    /** The chip under the top bar shows while a filter is on, except while rows are selected. */
    fun chipVisible(filter: InboxFilter, selecting: Boolean): Boolean = filter != InboxFilter.All && !selecting

    /** Typed search results narrowed to the conversations of [matching]; no filter keeps every result. */
    fun narrow(results: List<SearchResult>, filter: InboxFilter, matching: List<ConversationRow>): List<SearchResult> {
        if (filter == InboxFilter.All) return results
        val threads = matching.mapTo(HashSet()) { it.threadId }
        return results.filter { it.threadId in threads }
    }
}
