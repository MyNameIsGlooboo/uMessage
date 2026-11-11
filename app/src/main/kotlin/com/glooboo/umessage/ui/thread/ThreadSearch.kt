package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.models.MessageKey

/**
 * "Search in conversation" (TH-20). Matching ignores case, reads Persian and Arabic digits as 0-9 and Arabic ي/ك as
 * the Persian letters, so "۴۸۲" finds "482913" and the other way round. Every character maps to exactly one, so a
 * position in the folded text is the same position in the original.
 */
internal fun foldForSearch(text: String): String = buildString(text.length) {
    text.forEach { c ->
        append(
            when (c) {
                in '۰'..'۹' -> '0' + (c - '۰')
                in '٠'..'٩' -> '0' + (c - '٠')
                'ي' -> 'ی'
                'ك' -> 'ک'
                else -> c.lowercaseChar()
            },
        )
    }
}

/** Where [query] occurs in [text] (after [foldForSearch]), without overlaps; empty for a blank query. */
internal fun searchRanges(text: String, query: String): List<IntRange> {
    val needle = foldForSearch(query.trim())
    if (needle.isEmpty()) return emptyList()
    val haystack = foldForSearch(text)
    val ranges = ArrayList<IntRange>()
    var from = 0
    while (true) {
        val at = haystack.indexOf(needle, from)
        if (at < 0) break
        ranges += at until at + needle.length
        from = at + needle.length
    }
    return ranges
}

internal fun matchesSearch(text: String, query: String): Boolean = searchRanges(text, query).isNotEmpty()

/** "Previous" from match [current] of [count], newest first: the next older match, wrapping to the newest. */
internal fun olderMatch(current: Int, count: Int): Int = if (count == 0) 0 else (current + 1) % count

/** "Next": the next newer match, wrapping to the oldest. */
internal fun newerMatch(current: Int, count: Int): Int = if (count == 0) 0 else (current - 1 + count) % count

/** A message that matches a search, with its date in seconds, so matches from different sources sort together. */
data class SearchHit(val key: MessageKey, val date: Int)

/**
 * The matches of an in-conversation search, newest first: those among the loaded messages ([loaded], newest first)
 * plus those [older] found in the database that are not loaded yet. A message in both counts once.
 */
internal fun mergeSearchHits(loaded: List<SearchHit>, older: List<SearchHit>): List<MessageKey> {
    if (older.isEmpty()) return loaded.map { it.key }
    val seen = HashSet<MessageKey>(loaded.size + older.size)
    val all = ArrayList<SearchHit>(loaded.size + older.size)
    for (hit in loaded) if (seen.add(hit.key)) all += hit
    for (hit in older) if (seen.add(hit.key)) all += hit
    return all.sortedByDescending { it.date }.map { it.key }
}
