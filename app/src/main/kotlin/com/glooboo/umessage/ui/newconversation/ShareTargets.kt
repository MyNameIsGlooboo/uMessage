package com.glooboo.umessage.ui.newconversation

import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.TextNormalizer

/*
 * The "Recent chats" section of the picker when something is shared into the app: which chats are offered and how the
 * search field narrows them. Free of Android, so unit tests run it on the JVM.
 */

/** Most chats offered in the "Recent chats" section. */
internal const val RECENT_SHARE_LIMIT = 8

/**
 * A chat can be a share target when it is a real, listed chat with an address to send to. Archived chats stay out (as
 * in the main list), and so do the placeholder chats of scheduled messages, which have no thread of their own yet.
 */
private fun Conversation.canBeShareTarget(): Boolean =
    !isArchived && !isScheduled && phoneNumber.isNotBlank()

/** Newest first; chats with the same date keep a stable order (the higher thread id first). */
private val NEWEST_FIRST = compareByDescending<Conversation> { it.date }.thenByDescending { it.threadId }

/** The [limit] most recent chats that can be shared to, newest first. */
internal fun recentShareTargets(
    conversations: List<Conversation>,
    limit: Int = RECENT_SHARE_LIMIT,
): List<Conversation> = conversations.filter { it.canBeShareTarget() }.sortedWith(NEWEST_FIRST).take(limit)

/**
 * The share targets matching [query], newest first and at most [limit]. A blank query gives [recentShareTargets]. A
 * chat matches by title, or by number however it is typed (MC-09: spaces, Persian digits, 0555... or +1555...). The
 * title is also compared with Arabic letter variants and digits folded, so "علي" finds "علی".
 */
internal fun filterShareTargets(
    conversations: List<Conversation>,
    query: String,
    limit: Int = RECENT_SHARE_LIMIT,
): List<Conversation> {
    if (query.isBlank()) return recentShareTargets(conversations, limit)
    val text = query.trim()
    val foldedText = TextNormalizer.lower(text)
    val numberForms = numberSearchForms(text)
    return conversations
        .filter { it.canBeShareTarget() && matchesShareQuery(it, text, foldedText, numberForms) }
        .sortedWith(NEWEST_FIRST)
        .take(limit)
}

private fun matchesShareQuery(
    chat: Conversation,
    text: String,
    foldedText: String,
    numberForms: List<String>?,
): Boolean {
    if (chat.title.contains(text, ignoreCase = true)) return true
    if (TextNormalizer.lower(chat.title).contains(foldedText)) return true
    val number = chat.phoneNumber
    if (number.contains(text, ignoreCase = true)) return true
    return numberForms != null && numberMatches(number, numberForms)
}
