package com.glooboo.umessage.ui.main

import android.content.Context
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.SearchResult

/**
 * Provider truth (section 5, U9): the readers outside Spam (search and its tiles, starred, notes, recycle bin, share
 * targets, the export picker, clean-ups) leave every chat in Spam out. In search a Spam chat is found only in the Spam
 * section, and only while the app lock does not cover Spam (see [MainSearchViewModel.searchSpamChats]). Nothing changes
 * while ProviderTruthFlag is off.
 */
internal object SpamReaders {

    /**
     * The chats shown in Spam (S9: a row counts only while its chat still has that sender); empty while the flag is
     * off. Throws when the table cannot be read, so a caller can show nothing rather than a Spam chat.
     */
    fun threadIds(context: Context): Set<Long> =
        if (!ProviderTruthFlag.enabled) {
            emptySet()
        } else {
            context.conversationsDB.getValidSpamThreads().mapTo(HashSet()) { it.threadId }
        }

    /** [items] without those whose chat is in [spam]. */
    fun <T> without(items: List<T>, spam: Set<Long>, threadOf: (T) -> Long): List<T> =
        if (spam.isEmpty()) items else items.filterNot { threadOf(it) in spam }
}

/** One row of the Spam section: what the row shows, and the chat a tap opens (behind its own lock gate, U1). */
internal data class SpamChatHit(val row: BlockedHit, val open: SearchResult)

/**
 * The Spam section's rows: one per Spam chat that answers the query, newest first. [chats] are the Spam chats whose
 * title or number match, [messages] the matching messages of Spam chats, [chatOf] every Spam chat by its thread. A
 * message hit shows the newest matching text and how many matched; a chat found by its title or number only shows no
 * text. [BlockedHit.address] carries the thread id, so two chats with the same number are still two rows.
 */
internal fun buildSpamChatHits(
    chats: List<Conversation>,
    messages: List<Message>,
    chatOf: Map<Long, Conversation>,
    query: String,
    title: (String) -> String,
    formatDate: (Int) -> String,
): List<SpamChatHit> {
    val byThread = messages.filter { it.threadId in chatOf }.groupBy { it.threadId }
    val threads = LinkedHashSet<Long>().apply {
        addAll(byThread.keys)
        chats.forEach { if (it.threadId in chatOf) add(it.threadId) }
    }
    return threads.map { threadId ->
        val chat = chatOf.getValue(threadId)
        val newest = byThread[threadId]?.maxWithOrNull(compareBy<Message> { it.date }.thenBy { it.id })
        val date = newest?.date ?: chat.date
        val shownTitle = title(chat.title.ifBlank { chat.phoneNumber })
        val row = BlockedHit(
            address = threadId.toString(),
            title = shownTitle,
            snippet = newest?.let { searchExcerpt(it.body, query) }.orEmpty(),
            date = formatDate(date),
            count = byThread[threadId]?.size ?: 1,
        )
        val open = SearchResult(
            messageId = newest?.id ?: CONVERSATION_HIT,
            title = shownTitle,
            snippet = "",
            date = "",
            threadId = threadId,
            photoUri = chat.photoUri,
            isMMS = newest?.isMMS == true,
        )
        date to SpamChatHit(row, open)
    }.sortedByDescending { it.first }.map { it.second }
}
