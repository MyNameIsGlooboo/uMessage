package com.glooboo.umessage.helpers

import android.content.Context
import com.glooboo.umessage.extensions.conversationsDB

/**
 * Provider truth (section 5, U9): the readers outside Spam (search and its tiles, starred, notes, recycle bin, share
 * targets, the scheduled list, the export picker, clean-ups) leave every chat in Spam out. In search a Spam chat is
 * found only in the Spam section, and only while the app lock does not cover Spam (MainSearchViewModel). Nothing
 * changes while ProviderTruthFlag is off.
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
