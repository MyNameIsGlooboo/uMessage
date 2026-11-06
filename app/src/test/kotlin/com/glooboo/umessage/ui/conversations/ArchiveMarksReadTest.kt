package com.glooboo.umessage.ui.conversations

import com.glooboo.umessage.models.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test

/** CL-32: archiving marks the archived chats read, only the ones that were unread (Undo marks those unread again). */
class ArchiveMarksReadTest {

    private fun conversation(id: Long, read: Boolean) = Conversation(
        threadId = id, snippet = "", date = 0, read = read, title = "t$id", photoUri = "",
        isGroupConversation = false, phoneNumber = "+1$id",
    )

    @Test
    fun onlyUnreadArchivedThreadsAreMarked() {
        val all = listOf(conversation(1, read = false), conversation(2, read = true), conversation(3, read = false))
        assertEquals(listOf(1L), threadsToMarkRead(all, listOf(1, 2)))
        assertEquals(listOf(1L, 3L), threadsToMarkRead(all, listOf(3, 1)))
        assertEquals(emptyList<Long>(), threadsToMarkRead(all, listOf(2)))
        assertEquals(emptyList<Long>(), threadsToMarkRead(all, emptyList()))
    }
}
