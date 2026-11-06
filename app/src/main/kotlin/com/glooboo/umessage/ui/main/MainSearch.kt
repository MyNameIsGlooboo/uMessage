package com.glooboo.umessage.ui.main

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.spam.TextNormalizer
import com.glooboo.umessage.ui.components.asciiDigits
import com.glooboo.umessage.ui.components.isBareNumber

/** Searching starts with this many characters; shorter input shows a hint instead of results. */
const val MIN_SEARCH_LENGTH = 2

fun isSearchable(query: String): Boolean = query.length >= MIN_SEARCH_LENGTH

/** MC-17: typing waits this long for the next key before searching, so "hello" runs one search, not five. */
const val SEARCH_DEBOUNCE_MS = 250L

/** Characters a shown phone number is grouped with; skipped when matching a digits-only query. */
private const val NUMBER_SEPARATORS = " -().⁦⁩"

/**
 * Every case-insensitive occurrence of [query] in [text], as index ranges (end inclusive). Empty query matches nothing.
 * Both are read through [TextNormalizer.fold], as the search itself is, so "علي" highlights "علی". A query of digits
 * only skips number separators in [text], so "9850001" still highlights the shown "985-0001" (CL-10).
 */
fun highlightRanges(text: String, query: String): List<IntRange> {
    val wanted = TextNormalizer.fold(query)
    if (wanted.isEmpty() || text.isEmpty()) return emptyList()
    val digitsOnly = wanted.all { it in '0'..'9' }
    // fold() only replaces or drops characters, so each folded character comes from exactly one original index.
    val origin = IntArray(text.length)
    val folded = StringBuilder(text.length)
    for ((i, c) in text.withIndex()) {
        val f = if (digitsOnly && c in NUMBER_SEPARATORS) "" else TextNormalizer.fold(c.toString())
        if (f.isEmpty()) continue
        origin[folded.length] = i
        folded.append(f)
    }
    val ranges = ArrayList<IntRange>()
    var from = 0
    while (from <= folded.length - wanted.length) {
        val at = folded.indexOf(wanted, from, ignoreCase = true)
        if (at < 0) break
        ranges.add(origin[at]..origin[at + wanted.length - 1])
        from = at + wanted.length
    }
    return ranges
}

/** Characters kept before the first match when a long message is cut down to an excerpt. */
private const val EXCERPT_LEAD = 24

/**
 * CL-15: the part of [body] to show for a hit: one line, starting a little before the first match (with an ellipsis)
 * so the match is visible in a one- or two-line preview instead of lying beyond the cut.
 */
fun searchExcerpt(body: String, query: String): String {
    val oneLine = body.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
    val first = highlightRanges(oneLine, query).firstOrNull() ?: return oneLine
    if (first.first <= EXCERPT_LEAD) return oneLine
    // Cut at a word edge when there is one close by, so a word is not started mid-way.
    var start = first.first - EXCERPT_LEAD
    val space = oneLine.indexOf(' ', start)
    if (space in start until first.first) start = space + 1
    return "…" + oneLine.substring(start)
}

/** True when the first match of [query] in [text] is a whole word, not the inside of a longer one. */
fun isWholeWordHit(text: String, query: String): Boolean = highlightRanges(text, query).any { range ->
    val before = text.getOrNull(range.first - 1)
    val after = text.getOrNull(range.last + 1)
    (before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())
}

/** Fewer digits than this in a query is not looked up in phone numbers ("12" would match nearly every chat). */
private const val MIN_NUMBER_QUERY_DIGITS = 3

/**
 * CL-15: the conversations whose phone number holds the digits of [query], however either is written (spaces, dashes,
 * Persian digits). A query with fewer than [MIN_NUMBER_QUERY_DIGITS] digits, or with letters, matches none.
 */
fun conversationsByNumber(conversations: List<Conversation>, query: String): List<Conversation> {
    if (query.any { it.isLetter() }) return emptyList()
    val wanted = asciiDigits(query)
    if (wanted.length < MIN_NUMBER_QUERY_DIGITS) return emptyList()
    return conversations.filter { !it.isGroupConversation && asciiDigits(it.phoneNumber).contains(wanted) }
}

/** [byTitle] then the [byNumber] hits it does not already hold, one entry per thread. */
fun mergeConversationHits(byTitle: List<Conversation>, byNumber: List<Conversation>): List<Conversation> {
    val seen = HashSet<Long>()
    return (byTitle + byNumber).filter { seen.add(it.threadId) }
}

/** N5: true when the shown [number] only repeats a [title] that is itself a number ([rawTitle] before display). */
private fun sameNumber(rawTitle: String, title: String, number: String): Boolean =
    number == title || (isBareNumber(rawTitle) && asciiDigits(title) == asciiDigits(number))

/**
 * Search rows for the main screen: matching conversations first (title, number), then matching messages,
 * newest first by date (MC-17: SMS and MMS ids are separate counters, so an id says nothing about age).
 * [formatDate] turns a Unix-seconds timestamp into the label shown on the row; [displayName] gives a name or number
 * its on-screen form (CL-10: the same grouping and digits as the conversation list).
 */
fun buildSearchResults(
    conversations: List<Conversation>,
    messages: List<Message>,
    formatDate: (Int) -> String,
    query: String = "",
    displayName: (String) -> String = { it },
): List<SearchResult> {
    val results = ArrayList<SearchResult>(conversations.size + messages.size)
    // CL-15: with a query, whole-word hits come before hits inside longer words; each group keeps its order.
    val orderedConversations = if (query.isEmpty()) {
        conversations
    } else {
        conversations.sortedBy { !(isWholeWordHit(it.title, query) || isWholeWordHit(it.phoneNumber, query)) }
    }
    orderedConversations.forEach { conversation ->
        val title = displayName(conversation.title)
        val number = displayName(conversation.phoneNumber)
        results.add(
            SearchResult(
                messageId = -1,
                title = title,
                // N5: an unsaved number is already the title; it is not shown a second time under it.
                snippet = if (sameNumber(conversation.title, title, number)) "" else number,
                date = formatDate(conversation.date),
                threadId = conversation.threadId,
                photoUri = conversation.photoUri,
            ),
        )
    }
    val newestFirst = messages.sortedWith(compareByDescending<Message> { it.date }.thenByDescending { it.id })
    val orderedMessages = if (query.isEmpty()) newestFirst else newestFirst.sortedBy { !isWholeWordHit(it.body, query) }
    orderedMessages.forEach { message ->
        var recipient = displayName(message.senderName)
        if (recipient.isEmpty() && message.participants.isNotEmpty()) {
            recipient = message.participants.joinToString(", ") { displayName(it.name) }
        }
        results.add(
            SearchResult(
                messageId = message.id,
                title = recipient,
                snippet = if (query.isEmpty()) message.body else searchExcerpt(message.body, query),
                date = formatDate(message.date),
                threadId = message.threadId,
                photoUri = message.senderPhotoUri,
                isMMS = message.isMMS,
            ),
        )
    }
    return results
}

/** One line of the search results list: a section header, or a hit standing for a whole thread. */
sealed interface SearchListItem {
    val key: String

    /** [conversations] true for the "Conversations" header, false for "Messages". */
    data class Header(val conversations: Boolean) : SearchListItem {
        override val key: String get() = if (conversations) "header-conversations" else "header-messages"
    }

    /**
     * [result] is the best hit of its thread; [count] is how many messages of the thread matched (1 for a
     * conversation hit).
     */
    data class Hit(val result: SearchResult, val count: Int) : SearchListItem {
        override val key: String
            get() = (if (result.messageId == CONVERSATION_HIT) "c-" else "m-") + result.threadId
    }
}

/** [SearchResult.messageId] of a hit on the conversation itself (its title or number), not on a message. */
const val CONVERSATION_HIT = -1L

/** [MainSearchState.labelId] while no label is picked. */
const val NO_LABEL = -1L

/** [MainSearchState.simId] while no SIM is picked. */
const val NO_SIM = -1

/**
 * CL-15: sections "Conversations" and "Messages", and one row per thread in each: the hits of a thread merge into the
 * first (best ranked) one, with the number of matches. A section without hits has no header. [SearchResult] itself is
 * unchanged, so the row opens the thread the way a single hit does.
 */
fun groupSearchResults(results: List<SearchResult>): List<SearchListItem> {
    val conversations = results.filter { it.messageId == CONVERSATION_HIT }
    val messages = LinkedHashMap<Long, Pair<SearchResult, Int>>()
    results.filter { it.messageId != CONVERSATION_HIT }.forEach { hit ->
        val seen = messages[hit.threadId]
        messages[hit.threadId] = if (seen == null) hit to 1 else seen.first to (seen.second + 1)
    }
    return buildList {
        if (conversations.isNotEmpty()) {
            add(SearchListItem.Header(conversations = true))
            conversations.forEach { add(SearchListItem.Hit(it, 1)) }
        }
        if (messages.isNotEmpty()) {
            add(SearchListItem.Header(conversations = false))
            messages.values.forEach { (hit, count) -> add(SearchListItem.Hit(hit, count)) }
        }
    }
}

/** Compose-observable state of the main screen's search field and its results. */
@Stable
class MainSearchState {
    /** True from the moment the field is focused until the user leaves search. */
    var active by mutableStateOf(false)
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SearchResult>>(emptyList())

    /** The private notes that hold [query]; the page lists them under "Notes". */
    internal var noteHits by mutableStateOf<List<NoteHit>>(emptyList())

    /** N11: the filter picked with a tile. It outlives the search: the inbox keeps showing it. */
    var filter by mutableStateOf(InboxFilter.All)

    /** I29: the label of the filter while [filter] is [InboxFilter.Label]. */
    var labelId by mutableLongStateOf(NO_LABEL)

    /** The SIM (subscription id) of the filter while [filter] is [InboxFilter.Sim]. */
    var simId by mutableIntStateOf(NO_SIM)

    fun close() {
        active = false
        query = ""
        results = emptyList()
        noteHits = emptyList()
    }
}
