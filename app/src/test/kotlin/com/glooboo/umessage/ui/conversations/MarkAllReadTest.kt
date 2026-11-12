package com.glooboo.umessage.ui.conversations

import com.glooboo.umessage.models.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test

/** main-21: "Mark all read" changes exactly the unread chats of the list, so Undo can restore exactly those. */
class MarkAllReadTest {

    private fun conversation(id: Long, read: Boolean) = Conversation(
        threadId = id, snippet = "", date = 0, read = read, title = "t$id", photoUri = "",
        isGroupConversation = false, phoneNumber = "+1$id",
    )

    private val all = listOf(
        conversation(1, read = false), conversation(2, read = true),
        conversation(3, read = false), conversation(4, read = false),
    )

    @Test
    fun onlyUnreadChatsAreChanged() {
        assertEquals(listOf(1L, 3L, 4L), unreadThreadsToMarkRead(all, emptySet()))
    }

    @Test
    fun hiddenAndPendingChatsAreLeftAlone() {
        // 3 is behind the app lock, 4 waits for its archive or delete to finish: neither is touched nor restored.
        assertEquals(listOf(1L), unreadThreadsToMarkRead(all, setOf(3L, 4L)))
    }

    @Test
    fun nothingUnreadChangesNothing() {
        assertEquals(emptyList<Long>(), unreadThreadsToMarkRead(listOf(conversation(2, read = true)), emptySet()))
    }
}
