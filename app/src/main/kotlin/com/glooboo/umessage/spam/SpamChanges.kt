package com.glooboo.umessage.spam

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * J19: the one source of "the Spam table changed" ([SpamSorting.changes] shows it). A sort that moved a chat bumps it,
 * and so does every write outside a sort: a spam text or MMS received, its unread count cleared, a chat forgotten. The
 * Messages tab number and the Spam list read the table again then.
 */
object SpamChanges {
    private val count = MutableStateFlow(0L)

    /** Grows by one each time the Spam table changed. */
    val changes: StateFlow<Long> = count.asStateFlow()

    fun noteChanged() {
        count.update { it + 1 }
    }
}
